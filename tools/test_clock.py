#!/usr/bin/env python3
"""Build and emulate clock-only and clock+sequencer firmware; never flash.

    python3 tools/test_clock.py
    python3 tools/test_clock.py --mode seq --quick

Requires the AVR32 Ghidra language used by the reference assembler. Logs,
test configurations, images and a private Ghidra project stay in build/.
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import tempfile
import tomllib
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("seq", "arp", "both"), default="both")
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
    modes = ("seq", "arp") if args.mode == "both" else (args.mode,)
    for mode in modes:
        text = base
        for option, value in (("clock_divide", "true"), ("sequencer", str(mode == "seq").lower())):
            text, n = re.subn(rf"^{option} = (?:true|false)$", f"{option} = {value}", text, flags=re.M)
            if n != 1:
                raise SystemExit(f"Cannot set {option} in regression config")
        image = work / f"clock-{mode}.hex"
        text, n = re.subn(r'^output_hex\s*=\s*"[^"]*"', f'output_hex = "{image}"', text, flags=re.M)
        if n != 1:
            raise SystemExit("Cannot redirect regression image")
        text, n = re.subn(r'^updaters?\s*=\s*(?:"[^"]*"|\[[^\]]*\])\n', "", text, flags=re.M)
        if n != 1 or any(k in tomllib.loads(text)["firmware"] for k in ("updater", "updaters")):
            raise SystemExit("Refusing a regression build that could rewrite the flashers")
        config = work / f"{mode}.toml"
        config.write_text(text)
        result = subprocess.run([sys.executable, "tools/build.py", "--no-ghidra", "--config", str(config)],
                                cwd=REPO, text=True, capture_output=True)
        (work / f"{mode}-build.log").write_text(result.stdout + result.stderr)
        if result.returncode:
            raise SystemExit(result.stdout + result.stderr)
        print(f"Emulating {mode} firmware...", flush=True)
        command = [str(headless), str(work), "clock", "-import", str(image),
                   "-processor", "avr32:BE:32:default", "-noanalysis",
                   "-scriptPath", str(REPO / "src"), "-postScript", "ClockRegression.java", mode]
        if args.quick:
            command.append("quick")
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
