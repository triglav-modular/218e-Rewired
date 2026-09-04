#!/usr/bin/env python3
"""Execute knob/gesture regressions in emitted firmware; never flash hardware.

    python3 tools/test_controls.py
    python3 tools/test_controls.py --variant roles --persist off
    python3 tools/test_controls.py --variant default --persist off --image build/218eV3_v369_Rewired_DFU.hex

The default, six-order/transpose, tuned transpose, and lean (factory arp,
no sequencer/divider) builds run with and without persistence.
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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", choices=("default", "roles", "tuned", "lean", "all"), default="all")
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
    saved = {} if args.image else {
        name: (REPO / "build" / name).read_bytes() if (REPO / "build" / name).exists() else None
        for name in METADATA
    }
    variants = ("default", "roles", "tuned", "lean") if args.variant == "all" else (args.variant,)
    persists = (False, True) if args.persist == "both" else (args.persist == "on",)
    failures = []
    # Built one at a time - every build writes the same fixed paths under
    # build/ - then emulated together, since an emulation reads one image and
    # writes one log and shares nothing with its neighbours.
    jobs = args.jobs or min(len(variants) * len(persists), 8)
    planned: list[tuple[str, list[str]]] = []
    try:
        for variant in variants:
            for persist in persists:
                name = f"{variant}-{'persist' if persist else 'volatile'}"
                image = args.image.resolve() if args.image else work / f"{name}.hex"
                if not args.image:
                    text = base
                    # The preset quantiser rides on the default and tuned
                    # variants, so it is exercised against the factory key
                    # table and against an installed scale, and the other
                    # two prove the free add is untouched.
                    quantize = variant in ("default", "tuned")
                    for key, value in (("persist", persist), ("sequencer", variant != "lean"),
                                       ("clock_divide", variant != "lean"), ("latching_arp", variant != "lean"),
                                       ("quantize_presets", quantize)):
                        text, count = re.subn(rf"^{key} = (?:true|false)$",
                                             f"{key} = {str(value).lower()}", text, flags=re.M)
                        if count != 1:
                            raise SystemExit(f"Cannot set {key} in regression config")
                    # Replace explicit role choices as well as handling the
                    # shipped config, which leaves both at their defaults.
                    text = re.sub(r'^knob[14]\s*=.*\n', "", text, flags=re.M)
                    role = 'knob1 = "order"\nknob4 = "vibrato"\n' if variant == "default" else 'knob1 = "orders"\nknob4 = "trn"\n'
                    text = text.replace("[firmware]", role + "\n[firmware]", 1)
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
                    "quantized" if variant in ("default", "tuned") else "free"]))

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
