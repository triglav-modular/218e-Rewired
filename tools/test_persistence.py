#!/usr/bin/env python3
"""Build/emulate persistence variants and fault injection. Never flash hardware.

    python3 tools/test_persistence.py
    python3 tools/test_persistence.py --mode seq-clock --quick
    python3 tools/test_persistence.py --mode seq --no-persist --quick

The images are built one at a time and then emulated together; --jobs sets how
many emulations run at once, and --jobs 1 puts the whole run back in a line.

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
import tomllib
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "tools"))
import options  # noqa: E402

METADATA = ("VERSION", "build.properties", "patch_manifest.txt", "tables.txt")
# Ghidra ends every run with the JVM banner and its Unsafe warnings on stderr,
# so a plain tail of the captured output never reaches the failure.
NOISE = re.compile(r"^(WARNING: |openjdk version|OpenJDK |Picked up |WARN  Uninitialized memory read)")


def excerpt(output: str) -> str:
    lines = output.splitlines()
    for index, line in enumerate(lines):
        if "SCRIPT ERROR" in line:
            return "\n".join(lines[index:index + 25])
    return "\n".join(line for line in lines if not NOISE.match(line))[-5000:]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("presets", "seq", "clock", "seq-clock", "all"), default="all")
    parser.add_argument("--quick", action="store_true", help="skip the clock frequency/duty sweep")
    parser.add_argument("--no-persist", action="store_true", help="test volatile sequencer edits/transport (requires --mode seq or seq-clock)")
    parser.add_argument("--ghidra", type=Path)
    parser.add_argument("--jobs", type=int, default=0,
                        help="emulations to run at once (default: one per mode, capped at 8)")
    args = parser.parse_args()
    if args.no_persist and args.mode not in ("seq", "seq-clock"):
        parser.error("--no-persist requires --mode seq or seq-clock")
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
    saved = {name: (build / name).read_bytes() if (build / name).exists() else None for name in METADATA}
    work = Path(tempfile.mkdtemp(prefix="persistence-regression-", dir=build))
    print(f"Artifacts: {work}", flush=True)
    modes = ("presets", "seq", "clock", "seq-clock") if args.mode == "all" else (args.mode,)
    # Built one at a time - every build writes the same fixed paths under
    # build/ - then emulated together, since an emulation reads one image and
    # writes one log and shares nothing with its neighbours.
    jobs = args.jobs or min(len(modes), 8)
    planned: list[tuple[str, list[str]]] = []
    try:
        for mode in modes:
            text = base
            for option, value in (("persist", not args.no_persist), ("sequencer", "seq" in mode), ("clock_divide", "clock" in mode)):
                text, n = re.subn(rf"^{option} = (?:true|false)$", f"{option} = {str(value).lower()}", text, flags=re.M)
                if n != 1:
                    raise SystemExit(f"Cannot set {option} in regression config")
            image = work / f"{mode}.hex"
            text, n = re.subn(r'^output_hex\s*=\s*"[^"]*"', f'output_hex = "{image}"', text, flags=re.M)
            if n != 1:
                raise SystemExit("Cannot redirect regression image")
            text, n = re.subn(r'^updaters?\s*=\s*(?:"[^"]*"|\[[^\]]*\])\n', "", text, flags=re.M)
            if n != 1 or any(k in tomllib.loads(text)["firmware"] for k in ("updater", "updaters")):
                raise SystemExit("Refusing a regression build that could rewrite the flashers")
            config = work / f"{mode}.toml"
            config.write_text(text)
            # options.py refuses a non-persistent config; this harness is one
            # of the few places allowed to characterise one.
            env = dict(os.environ)
            if args.no_persist:
                env[options.VOLATILE_ENV] = "1"
            result = subprocess.run(
                [sys.executable, "tools/build.py", "--no-ghidra",
                 "--config", str(config)],
                env=env, cwd=REPO, text=True, capture_output=True)
            (work / f"{mode}-build.log").write_text(result.stdout + result.stderr)
            if result.returncode:
                raise SystemExit(result.stdout + result.stderr)
            # Its own Ghidra project per mode: a shared one would serialise the
            # modes again on the project lock.
            command = [str(headless), str(work), f"persistence-{mode}", "-import", str(image),
                       "-processor", "avr32:BE:32:default", "-noanalysis", "-scriptPath", str(REPO / "src")]
            if not args.no_persist:
                command += ["-postScript", "PersistenceRegression.java", mode]
            if "clock" in mode and not args.no_persist:
                command += ["-postScript", "PersistenceClockRegression.java", "seq" if "seq" in mode else "arp"]
                if args.quick:
                    command.append("quick")
            if "seq" in mode:
                command += ["-postScript", "SequenceTransportRegression.java", mode]
                if args.quick:
                    command.append("quick")
                command += ["-postScript", "SequenceEditRegression.java", mode,
                            "volatile" if args.no_persist else "persist"]
            planned.append((mode, command))

        def emulate(mode: str, command: list[str]) -> str:
            result = subprocess.run(command, cwd=REPO, text=True, capture_output=True)
            output = result.stdout + result.stderr
            log = work / f"{mode}-emulation.log"
            log.write_text(output)
            expected = []
            if not args.no_persist:
                expected.append("PERSISTENCE REGRESSION PASS:")
                if "clock" in mode:
                    expected.append("CLOCK REGRESSION PASS:")
            if "seq" in mode:
                expected += ["SEQUENCE TRANSPORT PASS:", "SEQUENCE EDIT PASS:"]
            missing = [marker.rstrip(":") for marker in expected if marker not in output]
            if result.returncode or "ERROR REPORT SCRIPT ERROR" in output or missing:
                why = "no " + ", ".join(missing) if missing else "script error"
                return f"Persistence regression failed: {mode}, {why}; see {log}\n{excerpt(output)}"
            return ""

        print(f"Emulating {len(planned)} firmware image(s), {jobs} at a time...", flush=True)
        failures = []
        with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
            pending = [(mode, pool.submit(emulate, mode, command)) for mode, command in planned]
            # Reported in the order the modes were asked for, not the order
            # they finish, so the run reads the same however it was scheduled.
            for mode, future in pending:
                failure = future.result()
                print(f"--- {mode}", flush=True)
                for line in (work / f"{mode}-emulation.log").read_text().splitlines():
                    if "Regression.java>" in line:
                        print(line.split("Regression.java>", 1)[1].replace("(GhidraScript)", "").strip(), flush=True)
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
