#!/usr/bin/env python3
"""Build and emulate clock-only and clock+sequencer firmware; never flash.

    python3 tools/test_clock.py
    python3 tools/test_clock.py --mode seq --quick
    python3 tools/test_clock.py --mode pressure-off

Requires the AVR32 Ghidra language used by the reference assembler. Logs,
test configurations, images and a private Ghidra project stay in build/.
"""
from __future__ import annotations

import argparse
import contextlib
import shutil
import os
import re
import subprocess
import sys
import tempfile
import tomllib
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent


OPTIONS_PY = REPO / "tools" / "options.py"


@contextlib.contextmanager
def settle_constant(key: str, value: int):
    """Build with one of the two settle constants changed, then put it back.

    Neither settle is a build option - both are constants in tools/options.py,
    fixed at the value the firmware ships with.  The firmware still branches
    on them, so the trigger has to meet its bound at every value they can
    take, and the only way to build that is to edit the constant.  Restored in
    a finally, so an exception mid-run cannot leave the tree modified.
    """
    original = OPTIONS_PY.read_text()
    patched, n = re.subn(rf"(['\"]){key}\1: \d+", f"\\g<1>{key}\\g<1>: {value}", original)
    if n != 1:
        raise SystemExit(f"Cannot find the {key} constant in tools/options.py")

    def rewrite(text: str) -> None:
        # A single digit changes, so the file keeps its length; write it back
        # inside the same second and CPython keeps the cached bytecode, whose
        # invalidation is (mtime, size).  Every later build in the run then
        # silently uses the wrong constant - which reads as a firmware defect,
        # not a test one.  Drop the cache with the file, every time.
        OPTIONS_PY.write_text(text)
        shutil.rmtree(OPTIONS_PY.parent / "__pycache__", ignore_errors=True)

    try:
        rewrite(patched)
        yield
    finally:
        rewrite(original)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode",
                        choices=("seq", "arp", "pressure-off", "settle-scans",
                                 "no-gate-settle", "all", "both"),
                        default="all")
    parser.add_argument("--quick", action="store_true", help="skip the frequency/duty sweep")
    parser.add_argument("--ghidra", type=Path)
    args = parser.parse_args()
    base = (REPO / "config/218e.toml").read_text()
    settings = tomllib.loads(base).get("tools", {})
    local = REPO / "config/local.toml"
    if local.exists():
        settings.update(tomllib.loads(local.read_text()).get("tools", {}))
    ghidra = args.ghidra or Path(os.environ.get("GHIDRA_HOME") or settings.get("ghidra_home", ""))
    headless = ghidra / "support/analyzeHeadless"
    if not headless.is_file():
        raise SystemExit("Set GHIDRA_HOME, config/local.toml [tools].ghidra_home, or --ghidra.")
    (REPO / "build").mkdir(exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="clock-regression-", dir=REPO / "build"))
    print(f"Artifacts: {work}", flush=True)
    if args.mode == "all":
        modes = ("seq", "arp", "pressure-off", "settle-scans", "no-gate-settle")
    elif args.mode == "both":
        modes = ("seq", "arp")
    else:
        modes = (args.mode,)
    for mode in modes:
        text = base
        # pressure-off is the same clock as arp, built the way `pressure_fix
        # = false` builds it. The trigger's rise shares the event-17 wrapper
        # with the pressure interpolator, and that build turns smoothing off
        # while leaving clock division on - so it is the configuration where
        # the wrapper can go missing under the fix and take it with it.
        options = [("clock_divide", "true"),
                   ("sequencer", str(mode == "seq").lower())]
        if mode == "pressure-off":
            options += [("pressure_fix", "false"), ("pressure_portamento", "false")]
        for option, value in options:
            text, n = re.subn(rf"^{option} = (?:true|false)$", f"{option} = {value}", text, flags=re.M)
            if n != 1:
                raise SystemExit(f"Cannot set {option} in regression config")
        # The two settle settings used to decide whether the trigger rode the
        # 1 kHz flush at all: a nonzero clock_settle_scans handed the external
        # step back to the 5 ms scan, and a zero gate_settle_scans left the
        # internal beat unclaimed. Both now keep the flush, so both are built
        # and held to the same 1 ms bound as the defaults.
        # Neither settle appears in the shipped config - both come from the
        # defaults in tools/options.py - so these are appended as their own
        # tables rather than substituted.
        settle = contextlib.nullcontext()
        if mode == "settle-scans":
            settle = settle_constant("clock_settle_scans", 1)
        elif mode == "no-gate-settle":
            settle = settle_constant("gate_settle_scans", 0)
        image = work / f"clock-{mode}.hex"
        text, n = re.subn(r'^output_hex\s*=\s*"[^"]*"', f'output_hex = "{image}"', text, flags=re.M)
        if n != 1:
            raise SystemExit("Cannot redirect regression image")
        text, n = re.subn(r'^updaters?\s*=\s*(?:"[^"]*"|\[[^\]]*\])\n', "", text, flags=re.M)
        if n != 1 or any(k in tomllib.loads(text)["firmware"] for k in ("updater", "updaters")):
            raise SystemExit("Refusing a regression build that could rewrite the flashers")
        config = work / f"{mode}.toml"
        config.write_text(text)
        with settle:
            result = subprocess.run([sys.executable, "tools/build.py", "--no-ghidra",
                                     "--config", str(config)],
                                    cwd=REPO, text=True, capture_output=True)
        (work / f"{mode}-build.log").write_text(result.stdout + result.stderr)
        if result.returncode:
            raise SystemExit(result.stdout + result.stderr)
        print(f"Emulating {mode} firmware...", flush=True)
        command = [str(headless), str(work), "clock", "-import", str(image),
                   "-processor", "avr32:BE:32:default", "-noanalysis",
                   "-scriptPath", str(REPO / "src"), "-postScript", "ClockRegression.java",
                   "seq" if mode == "seq" else "arp"]
        if args.quick:
            command.append("quick")
        if mode in ("settle-scans", "no-gate-settle"):
            command.append("jitter")
        result = subprocess.run(command, cwd=REPO, text=True, capture_output=True)
        output = result.stdout + result.stderr
        (work / f"{mode}-emulation.log").write_text(output)
        for line in output.splitlines():
            if "ClockRegression.java>" in line:
                print(line.split("ClockRegression.java>", 1)[1].replace("(GhidraScript)", "").strip(), flush=True)
        # Ghidra can exit zero after a script exception. Require the positive
        # completion marker AND absence of a script error.
        if result.returncode or "ERROR REPORT SCRIPT ERROR" in output or "CLOCK REGRESSION PASS:" not in output:
            raise SystemExit(f"Clock regression failed; see {work / (mode + '-emulation.log')}\n{output[-4000:]}")
    print("All requested clock firmware regressions passed.", flush=True)


if __name__ == "__main__":
    main()
