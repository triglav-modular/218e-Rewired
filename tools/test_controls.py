#!/usr/bin/env python3
"""Execute knob/gesture regressions in emitted firmware; never flash hardware.

    python3 tools/test_controls.py
    python3 tools/test_controls.py --variant roles --persist off
    python3 tools/test_controls.py --variant default --persist off --image build/218eV3_v369_Rewired_DFU.hex

The default, six-order/transpose, tuned transpose, and lean (factory arp,
no sequencer/divider) builds run with and without persistence.  The kbm
build carries a 24-key map (24TET.scl with 24TET-full.kbm) and runs only the
MIDI checks of the octave pads, the latch and a key played over a take, with
persistence.
--image checks an existing image without rebuilding it;
its variant and persistence settings must be specified correctly by the caller.
The images are built one at a time and then emulated together; --jobs sets how
many emulations run at once, and --jobs 1 puts the whole run back in a line.
Temporary images/logs/projects stay in build/. Shared metadata is restored.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import os
import re
import subprocess
import sys
import tempfile
import tomllib
from pathlib import Path

from test_persistence import METADATA, REPO

import options  # noqa: E402


def bohlen_pierce(base: str, work: Path) -> str:
    """The Bohlen-Pierce slot as tools/build.py makes it, for ControlRegression.

    Every variant's MIDI-note checks lay a record with this table in the
    settings mirror, as a record sent over MIDI lays it, so a scale that
    repeats at the 3/1 is played through each image.  Built once here, not
    emulated: the table, its keys per period and its period are the
    builder's own, read back off build/tables.txt and build.properties.
    """
    slot = '["tunings/BohlenPierce.scl", "tunings/BohlenPierce.kbm"]'
    text, count = re.subn(r'^alternate_tunings = false$',
                          f"alternate_tunings = [{slot}, {slot}, {slot}]", base, flags=re.M)
    if count != 1:
        raise SystemExit("Cannot set the Bohlen-Pierce tuning in regression config")
    text, count = re.subn(r'^output_hex\s*=\s*"[^"]*"',
                          f'output_hex = "{work / "bohlen-pierce.hex"}"', text, flags=re.M)
    if count != 1:
        raise SystemExit("Cannot redirect the Bohlen-Pierce image")
    text, count = re.subn(r'^updaters?\s*=\s*(?:"[^"]*"|\[[^\]]*\])\n', "", text, flags=re.M)
    if count != 1 or any(k in tomllib.loads(text)["firmware"] for k in ("updater", "updaters")):
        raise SystemExit("Refusing a regression build that could rewrite flashers")
    config = work / "bohlen-pierce.toml"
    config.write_text(text)
    result = subprocess.run([sys.executable, "tools/build.py", "--no-ghidra", "--config", str(config)],
                            cwd=REPO, capture_output=True, text=True)
    (work / "bohlen-pierce-build.log").write_text(result.stdout + result.stderr)
    if result.returncode:
        raise SystemExit(result.stdout + result.stderr)
    tables = (REPO / "build/tables.txt").read_text()
    def table(name: str) -> list[int]:
        match = re.search(rf"^{name} \(\d+\):\n\s*(.*)$", tables, flags=re.M)
        if not match:
            raise SystemExit(f"No {name} in the Bohlen-Pierce build's tables")
        return [int(v) for v in match.group(1).split(",")]
    keys, entries = table("tuning_period_keys")[0], table("tuning_slot0")
    period = re.search(r"^number\.octave_units=(\d+)$",
                       (REPO / "build/build.properties").read_text(), flags=re.M)
    if not period or len(entries) != 32 or keys != 13:
        raise SystemExit("The Bohlen-Pierce build did not give 32 entries, 13 keys and a period")
    return f"bp:{keys}:{period.group(1)}:" + ",".join(str(v) for v in entries)


def slot_table(base: str, work: Path, slot: str, name: str) -> tuple[int, int, list[int]]:
    """One tuning slot's key table, keys per period and period, as
    tools/build.py makes them: a build with the slot in all three places,
    read back off build/tables.txt and build.properties."""
    text, count = re.subn(r'^alternate_tunings = false$',
                          f"alternate_tunings = [{slot}, {slot}, {slot}]", base, flags=re.M)
    if count != 1:
        raise SystemExit(f"Cannot set the {name} tuning in regression config")
    text, count = re.subn(r'^output_hex\s*=\s*"[^"]*"',
                          f'output_hex = "{work / (name + ".hex")}"', text, flags=re.M)
    if count != 1:
        raise SystemExit(f"Cannot redirect the {name} image")
    text, count = re.subn(r'^updaters?\s*=\s*(?:"[^"]*"|\[[^\]]*\])\n', "", text, flags=re.M)
    if count != 1 or any(k in tomllib.loads(text)["firmware"] for k in ("updater", "updaters")):
        raise SystemExit("Refusing a regression build that could rewrite flashers")
    config = work / f"{name}.toml"
    config.write_text(text)
    result = subprocess.run([sys.executable, "tools/build.py", "--no-ghidra", "--config", str(config)],
                            cwd=REPO, capture_output=True, text=True)
    (work / f"{name}-build.log").write_text(result.stdout + result.stderr)
    if result.returncode:
        raise SystemExit(result.stdout + result.stderr)
    tables = (REPO / "build/tables.txt").read_text()
    def table(key: str) -> list[int]:
        match = re.search(rf"^{key} \(\d+\):\n\s*(.*)$", tables, flags=re.M)
        if not match:
            raise SystemExit(f"No {key} in the {name} build's tables")
        return [int(v) for v in match.group(1).split(",")]
    keys, entries = table("tuning_period_keys")[0], table("tuning_slot0")
    period = re.search(r"^number\.octave_units=(\d+)$",
                       (REPO / "build/build.properties").read_text(), flags=re.M)
    if not period or len(entries) != 32:
        raise SystemExit(f"The {name} build did not give 32 entries and a period")
    return keys, int(period.group(1)), entries


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
        keys, period, entries = slot_table(base, work, slot, f"preset-{name}")
        out.append(f"{name}|{keys}|{period}|{probe}|" + ",".join(str(v) for v in entries))
    return "pd:" + ";".join(out)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant",
                        choices=("default", "roles", "tuned", "lean", "jack",
                                 "swing", "patterns", "kbm", "all"),
                        default="all")
    parser.add_argument("--persist", choices=("on", "off", "both"), default="both")
    parser.add_argument("--image", type=Path)
    parser.add_argument("--ghidra", type=Path)
    parser.add_argument("--jobs", type=int, default=0,
                        help="emulations to run at once (default: one per variant, capped at 8)")
    args = parser.parse_args()
    if args.image and (args.variant == "all" or args.persist == "both"):
        parser.error("--image requires one --variant and --persist on/off")
    base = (REPO / "config/218e.toml").read_text()
    settings = tomllib.loads(base).get("tools", {})
    local = REPO / "config/local.toml"
    if local.exists():
        settings.update(tomllib.loads(local.read_text()).get("tools", {}))
    ghidra = args.ghidra or Path(os.environ.get("GHIDRA_HOME") or settings.get("ghidra_home", ""))
    headless = ghidra / "support/analyzeHeadless"
    if not headless.is_file():
        raise SystemExit("Set GHIDRA_HOME, config/local.toml [tools].ghidra_home, or --ghidra.")
    build = REPO / "build"
    build.mkdir(exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="control-regression-", dir=build))
    print(f"Artifacts: {work}", flush=True)
    # Saved with --image too: the Bohlen-Pierce table is built either way.
    saved = {
        name: (REPO / "build" / name).read_bytes() if (REPO / "build" / name).exists() else None
        for name in METADATA
    }
    variants = (("default", "roles", "tuned", "lean", "jack", "swing", "patterns", "kbm")
                if args.variant == "all" else (args.variant,))
    persists = (False, True) if args.persist == "both" else (args.persist == "on",)
    failures = []
    # Built one at a time - every build writes the same fixed paths under
    # build/ - then emulated together, since an emulation reads one image and
    # writes one log and shares nothing with its neighbours.
    jobs = args.jobs or min(len(variants) * len(persists), 8)
    planned: list[tuple[str, list[str]]] = []
    try:
        bp = bohlen_pierce(base, work)
        pd = preset_tables(base, work)
        for variant in variants:
            # Knob 2's other two roles. They are shipped in every image
            # built with them and were executed by nothing: the pattern
            # gate's mask walk and its wrap, and swing's alternating pair,
            # had no emulation at all. Neither has anything to do with
            # persistence, so they build once rather than twice, and nor
            # does the 24-key map's MIDI.
            for persist in ((True,) if variant in ("swing", "patterns", "kbm") else persists):
                name = f"{variant}-{'persist' if persist else 'volatile'}"
                image = args.image.resolve() if args.image else work / f"{name}.hex"
                if not args.image:
                    text = base
                    # The preset rotation rides on default, tuned and jack:
                    # the factory key table, an installed 12-TET scale, and -
                    # the case the whole feature is about - the 5-limit JI on
                    # the jack variant, where an unequal scale is the only
                    # thing that can tell a rotation from the constant-interval
                    # add it replaced.  The jack variant also puts BOTH inputs
                    # to the rotation in one image, which nothing else does.
                    # lean and roles prove the free add is untouched.
                    quantize = variant in ("default", "tuned", "jack", "kbm")
                    # Lean also runs the pressure path off (stage 2 phase F):
                    # the dispatchers back to the factory's curve and knobs,
                    # the interpolator's pass-through and the blend's route
                    # word all through the real scans.
                    for key, value in (("persist", persist), ("sequencer", variant != "lean"),
                                       ("clock_divide", variant != "lean"), ("latching_arp", variant != "lean"),
                                       ("quantize_presets", quantize),
                                       ("pressure_fix", variant != "lean"), ("pressure_portamento", variant != "lean")):
                        text, count = re.subn(rf"^{key} = (?:true|false)$",
                                             f"{key} = {str(value).lower()}", text, flags=re.M)
                        if count != 1:
                            raise SystemExit(f"Cannot set {key} in regression config")
                    # Replace explicit role choices as well as handling the
                    # shipped config, which leaves both at their defaults.
                    text = re.sub(r'^knob[124]\s*=.*\n', "", text, flags=re.M)
                    # The roles variant also puts knob 2 on quantized randomness,
                    # so its cave is exercised against the other roles.
                    role = ('knob1 = "order"\nknob2 = "spacing"\nknob4 = "vibrato"\n' if variant in ("default", "kbm")
                            else 'knob1 = "orders"\nknob2 = "quantized"\nknob4 = "trn"\n' if variant == "roles"
                            else 'knob1 = "orders"\nknob2 = "swing"\nknob4 = "trn"\n' if variant == "swing"
                            # Three patterns of different lengths, so the
                            # wrap is a different number per entry and a
                            # gate that wrapped at the mask's width instead
                            # of the pattern's own would show.
                            else 'knob1 = "orders"\nknob2 = "patterns"\nknob4 = "trn"\n'
                                 'arp_patterns = ["x...x...x...x...", "x.x.x.x.", ["xx..", 4]]\n'
                            if variant == "patterns"
                            else 'knob1 = "orders"\nknob4 = "trn"\n')
                    text = text.replace("[firmware]", role + "\n[firmware]", 1)
                    if variant == "lean":
                        # Stage 2 phase E: the jack's caves are in every image
                        # and its live byte decides.  Lean runs them with the
                        # byte off - the glide-rate addend is the factory's
                        # load again and the transposer reads no jack - so the
                        # off paths go through the real pitch chain here.
                        text, count = re.subn(r'^portamento_in = .*$',
                            'portamento_in = "portamento"', text, flags=re.M)
                        if count != 1:
                            raise SystemExit("Cannot put the jack on portamento in regression config")
                    if variant == "jack":
                        # The jack transposer over an unequal scale: the one
                        # configuration where a shift by degrees is not a
                        # shift by a constant, so a borrowed latch slot's
                        # interval and its key's differ.
                        # Name the option, not the value it happens to hold:
                        # pinning the old default here made the suite die with
                        # "Cannot enable the jack transposer" the moment
                        # config/218e.toml was aligned with the builder page.
                        text, count = re.subn(r'^portamento_in = .*$',
                            'portamento_in = "transpose"', text, flags=re.M)
                        if count != 1:
                            raise SystemExit("Cannot enable the jack transposer in regression config")
                        text, count = re.subn(r'^alternate_tunings = false$',
                            'alternate_tunings = ["tunings/5-Limit JI with Septimal 7th.scl"]',
                            text, flags=re.M)
                        if count != 1:
                            raise SystemExit("Cannot enable tuning in regression config")
                    if variant == "kbm":
                        # A map whose period is not twelve keys: 24 quarter
                        # tones to the octave, one per key, so a pad and the
                        # jack each move the MIDI note 24 per period (audit
                        # 038711a, F13).
                        text, count = re.subn(r'^alternate_tunings = false$',
                            'alternate_tunings = [["tunings/24TET.scl", "tunings/24TET-full.kbm"]]',
                            text, flags=re.M)
                        if count != 1:
                            raise SystemExit("Cannot enable the 24-key map in regression config")
                    if variant == "tuned":
                        text, count = re.subn(r'^alternate_tunings = false$',
                            'alternate_tunings = ["tunings/12TET.scl"]', text, flags=re.M)
                        if count != 1:
                            raise SystemExit("Cannot enable tuning in regression config")
                        # A measured correction, so the remap is not a straight
                        # line and key-exact DAC values mean something.
                        text, count = re.subn(r'^pitch_correction = false$',
                            'pitch_correction = "calibration/218e-pitch-calibration.csv"', text, flags=re.M)
                        if count != 1:
                            raise SystemExit("Cannot enable pitch correction in regression config")
                    text, count = re.subn(r'^output_hex\s*=\s*"[^"]*"',
                                         f'output_hex = "{image}"', text, flags=re.M)
                    if count != 1:
                        raise SystemExit("Cannot redirect regression image")
                    text, count = re.subn(r'^updaters?\s*=\s*(?:"[^"]*"|\[[^\]]*\])\n', "", text, flags=re.M)
                    if count != 1 or any(k in tomllib.loads(text)["firmware"] for k in ("updater", "updaters")):
                        raise SystemExit("Refusing a regression build that could rewrite flashers")
                    config = work / f"{name}.toml"
                    config.write_text(text)
                    # options.py refuses a non-persistent config; the volatile
                    # half of this matrix is one of the few places allowed to
                    # build one.
                    env = dict(os.environ)
                    if not persist:
                        env[options.VOLATILE_ENV] = "1"
                    result = subprocess.run([sys.executable, "tools/build.py", "--no-ghidra", "--config", str(config)],
                                            cwd=REPO, capture_output=True, env=env, text=True)
                    (work / f"{name}-build.log").write_text(result.stdout + result.stderr)
                    if result.returncode:
                        raise SystemExit(result.stdout + result.stderr)
                planned.append((name, [
                    str(headless), str(work), name, "-import", str(image),
                    "-processor", "avr32:BE:32:default", "-noanalysis", "-scriptPath", str(REPO / "src"),
                    "-postScript", "ControlRegression.java", "vibrato" if variant == "default" else "trn",
                    "order" if variant == "default" else "orders", "persist" if persist else "volatile",
                    "9", "lean" if variant == "lean" else "full",
                    "quantized" if variant in ("default", "tuned", "jack") else "free",
                    {"roles": "quantized", "swing": "swing",
                     "patterns": "patterns"}.get(variant, "spacing"),
                    "jack" if variant == "jack" else "knob", bp,
                    "kbm" if variant == "kbm" else "full", pd]))

        def emulate(name: str, command: list[str]) -> str:
            result = subprocess.run(command, cwd=REPO, capture_output=True, text=True)
            output = result.stdout + result.stderr
            log = work / f"{name}-emulation.log"
            log.write_text(output)
            if result.returncode or "ERROR REPORT SCRIPT ERROR" in output or "CONTROL REGRESSION PASS:" not in output:
                return str(log)
            return ""

        print(f"Emulating {len(planned)} firmware image(s), {jobs} at a time...", flush=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
            pending = [(name, pool.submit(emulate, name, command)) for name, command in planned]
            # Reported in the order the variants were asked for, not the order
            # they finish, so the run reads the same however it was scheduled.
            for name, future in pending:
                failure = future.result()
                print(f"--- {name}", flush=True)
                for line in (work / f"{name}-emulation.log").read_text().splitlines():
                    if "ControlRegression.java>" in line:
                        print(line.split("ControlRegression.java>", 1)[1].strip(), flush=True)
                if failure:
                    failures.append(failure)
    finally:
        for name, data in saved.items():
            path = REPO / "build" / name
            if data is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(data)
    if failures:
        raise SystemExit("Control regressions failed; see:\n" + "\n".join(failures))
    print("All requested control firmware regressions passed.", flush=True)


if __name__ == "__main__":
    main()
