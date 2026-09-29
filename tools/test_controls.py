#!/usr/bin/env python3
"""Execute knob/gesture regressions in emitted firmware; never flash hardware.

    python3 tools/test_controls.py
    python3 tools/test_controls.py --profile jack --check jackTransposer
    python3 tools/test_controls.py --image build/218eV3_v369_Rewired_DFU.hex

One image, the shipped configuration, runs every check.  Each check runs
under the configurations in its row of CHECKS below, and each configuration
reaches the image the way a send reaches the instrument: tools/build.py
serializes its settings record, which src/SettingsRecord.java lays in a
settings slot before every boot.  "default" lays nothing and boots the
image's own defaults.  Every option that can ship changes only the record
and the tables it carries, so this is the image built with those options,
and the settings equivalence check proves it for every configuration here:
statically against an image built with them (tools/profiles.py), and by
booting that image, a MIDI send and a laid record side by side
(src/SettingsEquivalence.java).
--image checks an existing image instead of building one; it must come from
this tree, whose builder makes the records it is run under.
--trace prints what each check reads of what a configuration sets, which is
what CHECKS is argued from; --every runs each check under every
configuration it can run under, to argue it again.
The images and records are built one at a time and then emulated together;
--jobs sets how many emulations run at once, and --jobs 1 puts the whole
run back in a line.  Temporary images/logs/projects stay in build/.
Shared metadata is restored.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import math
import os
import re
import subprocess
import tempfile
from pathlib import Path

from test_persistence import METADATA, REPO

import profiles  # noqa: E402

# The configurations, as option values over config/218e.toml.  default is
# the shipped one: knob 1 order, knob 2 spacing, knob 3 octaves, knob 4
# vibrato, the quantiser on, every option on, the jack transposing, the
# factory temperament.
TRN = {"knob1": '"orders"', "knob4": '"trn"'}
PROFILES: dict[str, dict] = {
    "default": {},
    # Knob 2 on quantized randomness beside six orders and trn.
    "roles": {**TRN, "knob2": '"quantized"', "quantize_presets": False},
    # An installed 12-TET slot and a measured correction, so the remap is
    # not a straight line and key-exact DAC values mean something.
    "tuned": {**TRN, "alternate_tunings": '["tunings/12TET.scl"]',
              "pitch_correction": '"calibration/218e-pitch-calibration.csv"'},
    # The factory arp: no latch, sequencer or divider, the pressure path
    # and its portamento off, the jack on portamento.
    "lean": {**TRN, "quantize_presets": False, "latching_arp": False, "sequencer": False,
             "clock_divide": False, "pressure_fix": False, "pressure_portamento": False,
             "portamento_in": '"portamento"'},
    # The jack transposer over an unequal scale: the one configuration
    # where a shift by degrees is not a shift by a constant.
    "jack": {**TRN, "alternate_tunings": '["tunings/5-Limit JI with Septimal 7th.scl"]'},
    "swing": {**TRN, "knob2": '"swing"', "quantize_presets": False},
    # Three patterns of different lengths, so a gate that wrapped at the
    # mask's width instead of the pattern's own would show.
    "patterns": {**TRN, "knob2": '"patterns"', "quantize_presets": False,
                 "arp_patterns": '["x...x...x...x...", "x.x.x.x.", ["xx..", 4]]'},
    # A map whose period is not twelve keys: 24 quarter tones to the octave,
    # one per key (audit 038711a, F13).
    "kbm": {"alternate_tunings": '[["tunings/24TET.scl", "tunings/24TET-full.kbm"]]'},
    # The 208c's pitch curve: the bottom key on the 0 V pitch.
    "offset-off": {"pitch_offset": False},
}

# Which configurations each check runs under, argued in docs/BUILD.md, "One
# image": test_controls.py --every --trace runs every check under every
# configuration it can run under and prints what each read of what the
# configurations set.  A configuration is left out of a row when it and one
# kept in the row boot machines that differ only in bytes the check never
# read under either - the run would be the same run - or when the check
# skips itself under it.  kbm and offset-off are in every row they change
# and pass, except where a check assumes twelve keys to the period or a
# pitch offset (docs/BUILD.md lists those).
CHECKS: dict[str, tuple[str, ...]] = {
    "midiPeriod": ('default', 'roles', 'tuned', 'lean', 'jack', 'kbm', 'offset-off'),
    "presetOwnership": ('default', 'roles', 'tuned', 'lean', 'jack', 'kbm'),
    "quickTapGate": ('default', 'roles', 'tuned', 'lean', 'jack', 'kbm', 'offset-off'),
    "bendUnderTheBottomKey": ('default', 'roles', 'tuned', 'lean', 'jack', 'kbm'),
    "presetQuantize": ('default', 'roles', 'tuned', 'lean', 'jack', 'offset-off'),
    "presetSequencer": ('default', 'tuned', 'jack', 'kbm', 'offset-off'),
    "transposeOutput": ('roles', 'tuned', 'lean', 'jack'),
    "knob4Zones": ('roles', 'tuned', 'lean', 'jack'),
    "noteOrders": ('roles', 'tuned', 'lean', 'jack', 'swing'),
    "releasedOrders": ('roles', 'tuned', 'lean', 'jack', 'swing'),
    "latchedOrders": ('roles', 'tuned', 'lean', 'jack', 'swing'),
    "latchExitHold": ('default', 'roles', 'tuned', 'jack', 'kbm', 'offset-off'),
    "latchTransposeState": ('default', 'roles', 'tuned', 'jack', 'swing', 'patterns', 'kbm', 'offset-off'),
    "latchStackMidi": ('default', 'roles', 'tuned', 'jack', 'swing', 'patterns', 'kbm', 'offset-off'),
    "latchHoldMidi": ('default', 'roles', 'tuned', 'jack', 'swing', 'patterns', 'kbm', 'offset-off'),
    "latchAfterMidi": ('default', 'roles', 'tuned', 'jack', 'swing', 'patterns', 'kbm', 'offset-off'),
    "latchPresetMidi": ('default', 'roles', 'tuned', 'jack', 'swing', 'patterns', 'kbm', 'offset-off'),
    "velocityFloor": ('default', 'roles', 'lean', 'kbm'),
    "keyOverTakeMidi": ('default', 'roles', 'tuned', 'jack', 'kbm', 'offset-off'),
    "midiOneSum": ('default', 'roles', 'tuned', 'lean', 'jack', 'kbm'),
    "midiClampPaths": ('default', 'roles', 'tuned', 'lean', 'jack', 'swing', 'patterns', 'kbm', 'offset-off'),
    "jackTransposer": ('jack',),
    "stripCarry": ('default', 'roles', 'tuned', 'jack', 'swing', 'patterns', 'kbm', 'offset-off'),
    "latchRecording": ('default', 'kbm', 'offset-off'),
    "recordedOctaves": ('default', 'kbm', 'offset-off'),
    "capacityAudition": ('default', 'kbm', 'offset-off'),
    "pressureOwnership": ('default', 'kbm', 'offset-off'),
    "staleAnchor": ('default', 'roles', 'tuned', 'jack', 'kbm', 'offset-off'),
    "previewBoundaries": ('default',),
    "recordedBounds": ('roles', 'tuned', 'jack'),
    "playbackPressure": ('default', 'roles', 'tuned', 'jack', 'kbm', 'offset-off'),
    "heldPresetEdit": ('default', 'roles', 'tuned', 'jack', 'kbm'),
    "retainedStartup": ('lean',),
    "quantizedRhythm": ('roles',),
    "swingRhythm": ('swing',),
    "patternGate": ('patterns',),
    "periodCell": ('default', 'offset-off'),
    "residue": ('default', 'kbm', 'offset-off'),
    "calibrationCommand": ('default', 'roles', 'tuned', 'lean', 'jack', 'kbm', 'offset-off'),
    "calibrationPitch": ('default', 'roles', 'tuned', 'lean', 'jack', 'offset-off'),
    "calibrationSilence": ('default', 'roles', 'tuned', 'lean', 'jack', 'swing', 'patterns', 'kbm', 'offset-off'),
    "calibrationExits": ('default', 'roles', 'tuned', 'lean', 'jack', 'kbm', 'offset-off'),
    "calibrationLiveEdit": ('default', 'roles', 'tuned', 'lean', 'jack', 'kbm', 'offset-off'),
    "exactPitch": ('default', 'roles', 'tuned', 'lean', 'jack', 'kbm', 'offset-off'),
    "tunedNotes": ('default', 'roles', 'tuned', 'lean', 'jack', 'kbm', 'offset-off'),
    "midiNoteOffs": ('default', 'roles', 'tuned', 'lean', 'jack', 'kbm', 'offset-off'),
    "blendSign": ('default', 'roles', 'lean'),
    "latchedUnderThePeriod": ('default', 'roles', 'tuned', 'jack', 'kbm', 'offset-off'),
    "anchoredBottomKey": ('default', 'roles', 'tuned', 'lean', 'jack', 'kbm', 'offset-off'),
    "glideUnderZero": ('default', 'roles', 'tuned', 'lean', 'jack', 'kbm', 'offset-off'),
    "reloadedRotation": ('default', 'roles', 'tuned', 'lean', 'jack', 'offset-off'),
    "gainPairOff": ('default', 'roles', 'lean'),
    "randomOctaveFloor": ('default', 'roles', 'tuned', 'lean', 'jack', 'swing', 'patterns', 'kbm', 'offset-off'),
    "blendClampOnce": ('default', 'roles', 'tuned', 'jack', 'kbm', 'offset-off'),
    "blendCarriedOffset": ('default', 'roles'),
    "rebaseSentinel": ('default', 'roles', 'tuned', 'jack', 'kbm', 'offset-off'),
    "midiUnderTheFloor": ('default', 'roles', 'tuned', 'jack', 'offset-off'),
    "auditionPin": ('default', 'roles', 'tuned', 'jack', 'kbm', 'offset-off'),
    "presetDegreesRounded": ('default', 'roles', 'tuned', 'lean', 'jack', 'kbm'),
    "glideCeiling": ('default', 'roles', 'tuned', 'lean', 'jack', 'kbm', 'offset-off'),
    "padFlipLands": ('default', 'roles', 'tuned', 'jack', 'kbm', 'offset-off'),
    "floorAnchorHandover": ('default', 'roles', 'tuned', 'jack', 'kbm', 'offset-off'),
    "transposedFloorAnchor": ('default', 'roles', 'tuned', 'jack', 'kbm', 'offset-off'),
}

# Each check's emulation time under one configuration, in seconds, the most
# any configuration took (the TIMES line every job prints), so that a
# configuration with many checks is cut into jobs and no job sets the run's
# length.  Rough is fine: it only decides where the cuts go.
SECONDS: dict[str, float] = {
    "midiPeriod": 6.0,
    "presetOwnership": 3.0,
    "quickTapGate": 1.0,
    "bendUnderTheBottomKey": 2.0,
    "presetQuantize": 4.0,
    "presetSequencer": 11.0,
    "transposeOutput": 3.0,
    "knob4Zones": 1.0,
    "noteOrders": 9.0,
    "releasedOrders": 5.0,
    "latchedOrders": 7.0,
    "latchExitHold": 4.0,
    "latchTransposeState": 14.0,
    "latchStackMidi": 1.0,
    "latchHoldMidi": 4.0,
    "latchAfterMidi": 2.0,
    "latchPresetMidi": 15.0,
    "velocityFloor": 8.0,
    "keyOverTakeMidi": 1.0,
    "midiOneSum": 79.0,
    "midiClampPaths": 9.0,
    "jackTransposer": 12.0,
    "stripCarry": 12.0,
    "latchRecording": 3.0,
    "recordedOctaves": 6.0,
    "capacityAudition": 1.0,
    "pressureOwnership": 5.0,
    "staleAnchor": 9.0,
    "previewBoundaries": 1.0,
    "recordedBounds": 2.0,
    "playbackPressure": 3.0,
    "heldPresetEdit": 4.0,
    "retainedStartup": 1.0,
    "quantizedRhythm": 12.0,
    "swingRhythm": 1.0,
    "patternGate": 2.0,
    "periodCell": 1.0,
    "residue": 6.0,
    "calibrationCommand": 1.0,
    "calibrationPitch": 5.0,
    "calibrationSilence": 6.0,
    "calibrationExits": 3.0,
    "calibrationLiveEdit": 1.0,
    "exactPitch": 104.0,
    "tunedNotes": 43.0,
    "midiNoteOffs": 53.0,
    "blendSign": 1.0,
    "latchedUnderThePeriod": 2.0,
    "anchoredBottomKey": 3.0,
    "glideUnderZero": 1.0,
    "reloadedRotation": 1.0,
    "gainPairOff": 1.0,
    "randomOctaveFloor": 36.0,
    "blendClampOnce": 11.0,
    "blendCarriedOffset": 4.0,
    "rebaseSentinel": 6.0,
    "midiUnderTheFloor": 5.0,
    "auditionPin": 2.0,
    "presetDegreesRounded": 40.0,
    "glideCeiling": 20.0,
    "padFlipLands": 29.0,
    "floorAnchorHandover": 5.0,
    "transposedFloorAnchor": 38.0,
}
JOB_SECONDS = 120.0


def split(owed: list[str]) -> list[list[str]]:
    """A configuration's checks in as many jobs as JOB_SECONDS asks for, each
    check to the lightest job so far, longest first."""
    count = max(1, math.ceil(sum(SECONDS[c] for c in owed) / JOB_SECONDS))
    chunks: list[list[str]] = [[] for _ in range(min(count, len(owed)))]
    load = [0.0] * len(chunks)
    for check in sorted(owed, key=lambda c: -SECONDS[c]):
        i = min(range(len(chunks)), key=load.__getitem__)
        chunks[i].append(check); load[i] += SECONDS[check]
    order = {c: i for i, c in enumerate(CHECKS)}
    return [sorted(c, key=order.__getitem__) for c in chunks if c]


def slot_of(built: dict, name: str) -> tuple[int, int, list[int]]:
    """Slot 0's key table, keys per period and period, off one build."""
    tables = built["tables"].read_text()

    def table(key: str) -> list[int]:
        match = re.search(rf"^{key} \(\d+\):\n\s*(.*)$", tables, flags=re.M)
        if not match:
            raise SystemExit(f"No {key} in the {name} build's tables")
        return [int(v) for v in match.group(1).split(",")]
    keys, entries = table("tuning_period_keys")[0], table("tuning_slot0")
    period = re.search(r"^number\.octave_units=(\d+)$", built["properties"].read_text(), flags=re.M)
    if not period or len(entries) != 32:
        raise SystemExit(f"The {name} build did not give 32 entries and a period")
    return keys, int(period.group(1)), entries


def bohlen_pierce(base: str, work: Path) -> str:
    """The Bohlen-Pierce slot as tools/build.py makes it, for ControlRegression.

    tunedNotes and midiOneSum lay a record with this table in the settings
    mirror, as a record sent over MIDI lays it, so a scale that repeats at
    the 3/1 is played through the image.  Built, not emulated: the table,
    its keys per period and its period are the builder's own, read back off
    its tables.txt and build.properties.
    """
    slot = '["tunings/BohlenPierce.scl", "tunings/BohlenPierce.kbm"]'
    built = profiles.build("bohlen-pierce", profiles.configure(base, {"alternate_tunings": f"[{slot}, {slot}, {slot}]"}), work)
    keys, period, entries = slot_of(built, "Bohlen-Pierce")
    if keys != 13:
        raise SystemExit("The Bohlen-Pierce build did not give 13 keys")
    return f"{keys}:{period}:" + ",".join(str(v) for v in entries)


def preset_tables(base: str, work: Path) -> str:
    """ControlRegression.presetDegreesRounded's tables (the clamp-chain scan,
    2026-09-28, host finding 2).  Two twelve-step scales whose period is not
    a whole number of units - 1201.5 and 1199 cents, the host scan's probe
    scales, written here - which preset_degrees read a period low at 30 of
    the knob's 1024 positions, and every bundled tuning, whose answers must
    not move.  Each table is tools/build.py's own."""
    probes = []
    for name, period in (("stretched", 1201.5), ("compressed", 1199.0)):
        lines = [f"! 12 equal steps of a {period}-cent period", name, " 12"]
        lines += [f" {period * k / 12:.10f}" for k in range(1, 13)]
        scale = work / f"_{name}.scl"
        scale.write_text("\n".join(lines) + "\n")
        probes.append((name, f'"{scale.relative_to(REPO)}"', 1))
    bundled = [("12TET", '"tunings/12TET.scl"', 0),
               ("SabatII-C", '"tunings/Sabat II (C-rooted).scl"', 0),
               ("SabatII", '"tunings/Sabat II.scl"', 0),
               ("5-limit", '"tunings/5-Limit JI with Septimal 7th.scl"', 0),
               ("24TET-full", '["tunings/24TET.scl", "tunings/24TET-full.kbm"]', 0),
               ("24TET-neutral", '["tunings/24TET.scl", "tunings/24TET-neutral.kbm"]', 0),
               ("diatonic7", '["tunings/diatonic7.scl", "tunings/diatonic7.kbm"]', 0),
               ("BohlenPierce", '["tunings/BohlenPierce.scl", "tunings/BohlenPierce.kbm"]', 0)]
    out = []
    for name, slot, probe in probes + bundled:
        built = profiles.build(f"preset-{name}", profiles.configure(
            base, {"alternate_tunings": f"[{slot}, {slot}, {slot}]"}), work)
        keys, period, entries = slot_of(built, name)
        out.append(f"{name}|{keys}|{period}|{probe}|" + ",".join(str(v) for v in entries))
    return ";".join(out)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", action="append", choices=tuple(PROFILES),
                        help="run only these configurations (repeatable)")
    parser.add_argument("--check", action="append", help="run only these checks (repeatable)")
    parser.add_argument("--image", type=Path, help="the image under test (default: build config/218e.toml)")
    parser.add_argument("--trace", action="store_true", help="print what each check reads of the settings")
    parser.add_argument("--every", action="store_true",
                        help="each check under every configuration it can run under, not only its row")
    parser.add_argument("--ghidra", type=Path)
    parser.add_argument("--jobs", type=int, default=0,
                        help="emulations to run at once (default: one per job, capped at 8)")
    args = parser.parse_args()
    base = (REPO / "config/218e.toml").read_text()
    headless = args.ghidra / "support/analyzeHeadless" if args.ghidra else profiles.headless(base)
    if not headless.is_file():
        raise SystemExit("Set GHIDRA_HOME, config/local.toml [tools].ghidra_home, or --ghidra.")
    unknown = set(args.check or ()) - set(CHECKS)
    if unknown:
        raise SystemExit(f"No such check: {', '.join(sorted(unknown))}")
    wanted = args.profile or list(PROFILES)
    checks = [c for c in CHECKS if not args.check or c in args.check]
    # Each job is one configuration and checks it owes: its own Ghidra
    # project and process, the configuration's record named in its
    # environment.
    plan: list[tuple[str, str, list[str]]] = []
    for name in wanted:
        owed = [c for c in checks if args.every or name in CHECKS[c]]
        for part, chunk in enumerate(split(owed)):
            plan.append((f"{name}-{part}" if part else name, name, chunk))
    if not plan:
        raise SystemExit("Nothing to run: no check owes those configurations")
    used = sorted({name for _job, name, _chunk in plan}, key=list(PROFILES).index)
    build = REPO / "build"
    build.mkdir(exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="control-regression-", dir=build))
    print(f"Artifacts: {work}", flush=True)
    saved = {
        name: (build / name).read_bytes() if (build / name).exists() else None
        for name in METADATA
    }
    jobs = args.jobs or min(len(plan) + 1, 8)
    failures = []
    try:
        # Built one at a time - every build writes the same fixed paths under
        # build/ - then emulated together.
        image = args.image.resolve() if args.image else profiles.build("image", base, work)["image"]
        built = {name: profiles.build(name, profiles.configure(base, PROFILES[name]), work) for name in used}
        static = [f"{name}: {problem}" for name, b in built.items()
                  for problem in profiles.static_differences(b, image)]
        if static:
            raise SystemExit("A configuration here is not data on the image under test:\n" + "\n".join(static))
        print(f"Settings equivalence, flash side: {len(built)} configuration(s) are the image under test's"
              " code, each with its own record as data", flush=True)
        bp = bohlen_pierce(base, work)
        pd = preset_tables(base, work)

        def ghidra(project: str, *script: str) -> list[str]:
            return [str(headless), str(work), project, "-import", str(image),
                    "-processor", "avr32:BE:32:default", "-noanalysis",
                    "-scriptPath", str(REPO / "src"), "-postScript", *script]

        emulations = [("equivalence", "SETTINGS EQUIVALENCE PASS:", None,
                       ghidra("equivalence", "SettingsEquivalence.java",
                              *profiles.equivalence_arguments(built)))]
        cost = {"equivalence": 5.0 * len(built)}
        for job, name, chunk in plan:
            cost[job] = sum(SECONDS[c] for c in chunk)
            command = ghidra(f"controls-{job}", "ControlRegression.java", f"profile={name}",
                             "checks=" + ",".join(chunk), f"bp={bp}", f"pd={pd}")
            if args.every:
                command.append("every=1")
            record = None if name == "default" else built[name]["record"]
            emulations.append((job, "CONTROL REGRESSION PASS:", record, command))

        def emulate(job: str, marker: str, record: Path | None, command: list[str]) -> str:
            env = dict(os.environ)
            env.pop("REWIRED_SETTINGS_RECORD", None)
            env.pop("REWIRED_READ_TRACE", None)
            if record:
                env["REWIRED_SETTINGS_RECORD"] = str(record)
            if args.trace:
                env["REWIRED_READ_TRACE"] = "1"
            result = subprocess.run(command, cwd=REPO, capture_output=True, text=True, env=env)
            output = result.stdout + result.stderr
            log = work / f"{job}-emulation.log"
            log.write_text(output)
            if result.returncode or "ERROR REPORT SCRIPT ERROR" in output or marker not in output:
                return str(log)
            return ""

        print(f"Emulating {len(emulations)} job(s) on one image, {jobs} at a time...", flush=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
            # Started longest first, so the last to start are the short ones.
            started = {job: pool.submit(emulate, job, marker, record, command)
                       for job, marker, record, command in sorted(emulations, key=lambda e: -cost[e[0]])}
            pending = [(job, started[job]) for job, _marker, _record, _command in emulations]
            # Reported in the order they were planned, not the order they
            # finish, so the run reads the same however it was scheduled.
            for job, future in pending:
                failure = future.result()
                print(f"--- {job}", flush=True)
                for line in (work / f"{job}-emulation.log").read_text().splitlines():
                    for script in ("ControlRegression.java>", "SettingsEquivalence.java>"):
                        if script in line:
                            print(line.split(script, 1)[1].replace("(GhidraScript)", "").strip(), flush=True)
                if failure:
                    failures.append(failure)
    finally:
        for name, data in saved.items():
            path = build / name
            if data is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(data)
    if failures:
        raise SystemExit("Control regressions failed; see:\n" + "\n".join(failures))
    print("All requested control firmware regressions passed.", flush=True)


if __name__ == "__main__":
    main()
