#!/usr/bin/env python3
"""Build/emulate persistence variants and fault injection. Never flash hardware.

    python3 tools/test_persistence.py
    python3 tools/test_persistence.py --mode seq-clock --quick

Requires Ghidra's AVR32 language. All images, configs, logs and private
Ghidra projects stay under build/persistence-regression-*. Shared build
metadata is restored on exit; updaters and the shipped image are untouched.
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
METADATA = ("VERSION", "build.properties", "patch_manifest.txt", "tables.txt")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("presets", "seq", "clock", "seq-clock", "all"), default="all")
    parser.add_argument("--quick", action="store_true", help="skip the clock frequency/duty sweep")
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
    build = REPO / "build"
    build.mkdir(exist_ok=True)
    saved = {name: (build / name).read_bytes() if (build / name).exists() else None for name in METADATA}
    work = Path(tempfile.mkdtemp(prefix="persistence-regression-", dir=build))
    print(f"Artifacts: {work}", flush=True)
    modes = ("presets", "seq", "clock", "seq-clock") if args.mode == "all" else (args.mode,)
    try:
        for mode in modes:
            text = base
            for option, value in (("persist", True), ("sequencer", "seq" in mode), ("clock_divide", "clock" in mode)):
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
            result = subprocess.run([sys.executable, "tools/build.py", "--no-ghidra", "--config", str(config)],
                                    cwd=REPO, text=True, capture_output=True)
            (work / f"{mode}-build.log").write_text(result.stdout + result.stderr)
            if result.returncode:
                raise SystemExit(result.stdout + result.stderr)
            command = [str(headless), str(work), "persistence", "-import", str(image),
                       "-processor", "avr32:BE:32:default", "-noanalysis", "-scriptPath", str(REPO / "src"),
                       "-postScript", "PersistenceRegression.java", mode]
            if "clock" in mode:
                command += ["-postScript", "PersistenceClockRegression.java", "seq" if "seq" in mode else "arp"]
                if args.quick:
                    command.append("quick")
            print(f"Emulating {mode} firmware...", flush=True)
            result = subprocess.run(command, cwd=REPO, text=True, capture_output=True)
            output = result.stdout + result.stderr
            log = work / f"{mode}-emulation.log"
            log.write_text(output)
            for line in output.splitlines():
                if "Regression.java>" in line:
                    print(line.split("Regression.java>", 1)[1].replace("(GhidraScript)", "").strip(), flush=True)
            if (result.returncode or "ERROR REPORT SCRIPT ERROR" in output
                    or "PERSISTENCE REGRESSION PASS:" not in output
                    or ("clock" in mode and "CLOCK REGRESSION PASS:" not in output)):
                raise SystemExit(f"Persistence regression failed; see {log}\n{output[-5000:]}")
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
