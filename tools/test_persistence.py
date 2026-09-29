#!/usr/bin/env python3
"""Emulate persistence, fault injection and the sequencer on one image. Never flash hardware.

    python3 tools/test_persistence.py
    python3 tools/test_persistence.py --mode seq-clock --quick

One image, the shipped configuration, runs every script.  The modes are
configurations of it: seq-clock is the image's own defaults and lays
nothing, and seq (the divider off) and clock (the sequencer off) are laid
as settings records the way a send leaves one (src/SettingsRecord.java).  Each script runs
under the modes in SCRIPTS below, each script and mode its own emulation,
and the settings equivalence check boots each mode built in, sent over MIDI
and laid, side by side (src/SettingsEquivalence.java).  --trace prints what
each check reads of what a mode sets, which SCRIPTS is argued from.
The records are built one at a time and then emulated together; --jobs sets
how many emulations run at once, and --jobs 1 puts the whole run back in a
line.

Requires Ghidra's AVR32 language. All images, configs, logs and private
Ghidra projects stay under build/persistence-regression-*. Shared build
metadata is restored on exit; updaters and the shipped image are untouched.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "tools"))
import profiles  # noqa: E402
import test_clock  # noqa: E402

METADATA = ("VERSION", "build.properties", "patch_manifest.txt", "tables.txt", "settings.bin")
# Ghidra ends every run with the JVM banner and its Unsafe warnings on stderr,
# so a plain tail of the captured output never reaches the failure.
NOISE = re.compile(r"^(WARNING: |openjdk version|OpenJDK |Picked up |WARN  Uninitialized memory read)")

# The modes, as option values over config/218e.toml.  A fourth, presets
# (the sequencer and the divider both off), ran PersistenceRegression alone,
# and every check of it reads nothing either byte decides (--trace): it is
# the seq-clock run again.
MODES: dict[str, dict] = {
    "seq": {"clock_divide": False},
    "clock": {"sequencer": False},
    "seq-clock": {},
}
# PersistenceRegression's checks and the modes each runs under.  --trace
# shows every one but playbackSave reading nothing the modes set once the
# boot has loaded them: they run the same under all four, so under the
# image's own.  playbackSave plays a take with the divider on and holds the
# arp with it off, so it runs under both modes the divider is on in; with
# it off the check returns at once.  gestures returns at once with the
# sequencer off and reads nothing of the divider.
PERSISTENCE_CHECKS: dict[str, tuple[str, ...]] = {
    "basic": ("seq-clock",), "polySettingsMigration": ("seq-clock",), "relativeSteps": ("seq-clock",),
    "latchState": ("seq-clock",), "tuningSlot": ("seq-clock",), "stepDegrees": ("seq-clock",),
    "takeReference": ("seq-clock",), "retries": ("seq-clock",), "powerCuts": ("seq-clock",),
    "corruption": ("seq-clock",), "gesturePolicy": ("seq-clock",), "presets": ("seq-clock",),
    "gestures": ("seq-clock",), "playbackSave": ("clock", "seq-clock"),
}
# Each script, the marker it ends a passing run with, and the modes it runs
# under: see docs/BUILD.md, "One image", for why each row is what it is.
SCRIPTS: tuple[tuple[str, str, tuple[str, ...]], ...] = (
    ("PersistenceRegression.java", "PERSISTENCE REGRESSION PASS:",
     tuple(m for m in MODES if any(m in modes for modes in PERSISTENCE_CHECKS.values()))),
    # The image held to its own defaults and the records it makes: its
    # sequencer and divider checks set both bytes themselves.
    ("SettingsRegression.java", "SETTINGS REGRESSION PASS:", ("seq-clock",)),
    ("PersistenceClockRegression.java", "CLOCK REGRESSION PASS:", ("clock", "seq-clock")),
    ("SequenceTransportRegression.java", "SEQUENCE TRANSPORT PASS:", ("seq", "seq-clock")),
    ("SequenceEditRegression.java", "SEQUENCE EDIT PASS:", ("seq", "seq-clock")),
    # The keyboard over a running take: a sequencer, a divider and the
    # persisted record in one configuration, which its bench() asserts.
    # Until it was wired in here nothing executed it at all, while
    # docs/PLAN-2.0.md told the next reader it pinned the behaviour.
    ("PolyMidiProbe.java", "POLY MIDI PROBE PASS:", ("seq-clock",)),
)


def excerpt(output: str) -> str:
    lines = output.splitlines()
    for index, line in enumerate(lines):
        if "SCRIPT ERROR" in line:
            return "\n".join(lines[index:index + 25])
    return "\n".join(line for line in lines if not NOISE.match(line))[-5000:]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=(*MODES, "all"), default="all")
    parser.add_argument("--quick", action="store_true", help="skip the clock frequency/duty sweep")
    parser.add_argument("--trace", action="store_true", help="print what each check reads of the settings")
    parser.add_argument("--ghidra", type=Path)
    parser.add_argument("--jobs", type=int, default=0,
                        help="emulations to run at once (default: one per emulation, capped at 8)")
    args = parser.parse_args()
    base = (REPO / "config/218e.toml").read_text()
    headless = args.ghidra / "support/analyzeHeadless" if args.ghidra else profiles.headless(base)
    if not headless.is_file():
        raise SystemExit("Set GHIDRA_HOME, config/local.toml [tools].ghidra_home, or --ghidra.")
    build = REPO / "build"
    build.mkdir(exist_ok=True)
    saved = {name: (build / name).read_bytes() if (build / name).exists() else None for name in METADATA}
    work = Path(tempfile.mkdtemp(prefix="persistence-regression-", dir=build))
    print(f"Artifacts: {work}", flush=True)
    modes = tuple(MODES) if args.mode == "all" else (args.mode,)
    plan = [(script, marker, mode) for script, marker, owed in SCRIPTS for mode in modes if mode in owed]
    modes = tuple(m for m in modes if any(mode == m for _s, _m, mode in plan))
    jobs = args.jobs or min(len(plan) + 1, 8)
    try:
        # Built one at a time - every build writes the same fixed paths under
        # build/ - then emulated together, since an emulation reads one image
        # and writes one log and shares nothing with its neighbours.
        image = profiles.build("image", base, work)
        built = {mode: profiles.build(mode, profiles.configure(base, MODES[mode]), work) for mode in modes}
        static = [f"{mode}: {problem}" for mode, b in built.items()
                  for problem in profiles.static_differences(b, image["image"])]
        if static:
            raise SystemExit("A mode here is not data on the image under test:\n" + "\n".join(static))

        def ghidra(project: str, *script: str) -> list[str]:
            # Its own Ghidra project per emulation: a shared one would
            # serialise them again on the project lock.
            return [str(headless), str(work), project, "-import", str(image["image"]),
                    "-processor", "avr32:BE:32:default", "-noanalysis",
                    "-scriptPath", str(REPO / "src"), "-postScript", *script]

        def arguments(script: str, mode: str) -> list[str]:
            quick = ["quick"] if args.quick else []
            if script == "PersistenceRegression.java":
                owed = [c for c, modes in PERSISTENCE_CHECKS.items() if mode in modes]
                return [mode] if len(owed) == len(PERSISTENCE_CHECKS) else [mode, "checks=" + ",".join(owed)]
            if script == "SettingsRegression.java":
                # This image's own properties and record, which the settings
                # regression compares the mirror a boot fills against.
                return [mode, str(image["properties"]), str(image["record"])]
            if script == "PersistenceClockRegression.java":
                # What this configuration builds, which the clock suite holds
                # the image to: see test_clock.expectations.
                return ["seq" if "seq" in mode else "arp", *quick,
                        *test_clock.expectations(built[mode]["text"])]
            if script == "SequenceTransportRegression.java":
                return [mode, *quick]
            if script == "SequenceEditRegression.java":
                return [mode, "persist"]
            return []

        emulations = [("equivalence", "SETTINGS EQUIVALENCE PASS:", None,
                       ghidra("equivalence", "SettingsEquivalence.java",
                              *profiles.equivalence_arguments(built)))]
        for script, marker, mode in plan:
            name = f"{script.removesuffix('.java')}-{mode}"
            # seq-clock is the image's own; every other mode is laid.
            record = None if mode == "seq-clock" else built[mode]["record"]
            emulations.append((name, marker, record, ghidra(name, script, *arguments(script, mode))))

        def emulate(name: str, marker: str, record: Path | None, command: list[str]) -> str:
            env = dict(os.environ)
            env.pop("REWIRED_SETTINGS_RECORD", None)
            env.pop("REWIRED_READ_TRACE", None)
            if record:
                env["REWIRED_SETTINGS_RECORD"] = str(record)
            if args.trace:
                env["REWIRED_READ_TRACE"] = "1"
            result = subprocess.run(command, cwd=REPO, text=True, capture_output=True, env=env)
            output = result.stdout + result.stderr
            log = work / f"{name}-emulation.log"
            log.write_text(output)
            if result.returncode or "ERROR REPORT SCRIPT ERROR" in output or marker not in output:
                why = f"no {marker.rstrip(':')}" if marker not in output else "script error"
                return f"Persistence regression failed: {name}, {why}; see {log}\n{excerpt(output)}"
            return ""

        print(f"Emulating {len(emulations)} job(s) on one image, {jobs} at a time...", flush=True)
        failures = []
        with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
            pending = [(name, pool.submit(emulate, name, marker, record, command))
                       for name, marker, record, command in emulations]
            # Reported in the order they were planned, not the order they
            # finish, so the run reads the same however it was scheduled.
            for name, future in pending:
                failure = future.result()
                print(f"--- {name}", flush=True)
                for line in (work / f"{name}-emulation.log").read_text().splitlines():
                    for script in ("Regression.java>", "PolyMidiProbe.java>", "SettingsEquivalence.java>"):
                        if script in line:
                            print(line.split(script, 1)[1].replace("(GhidraScript)", "").strip(), flush=True)
                            break
                if failure:
                    failures.append(failure)
        if failures:
            raise SystemExit("\n\n".join(failures))
    finally:
        for name, data in saved.items():
            path = build / name
            if data is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(data)
    print("All requested persistence firmware regressions passed.", flush=True)


if __name__ == "__main__":
    main()
