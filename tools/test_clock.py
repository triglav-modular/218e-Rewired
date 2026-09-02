#!/usr/bin/env python3
"""Build and emulate clock-only and clock+sequencer firmware; never flash.

    python3 tools/test_clock.py
    python3 tools/test_clock.py --mode seq --quick
    python3 tools/test_clock.py --mode pressure-off

The images are built one at a time and then emulated together; --jobs sets how
many emulations run at once, and --jobs 1 puts the whole run back in a line.

Requires the AVR32 Ghidra language used by the reference assembler. Logs,
test configurations, images and a private Ghidra project stay in build/.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import contextlib
import json
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


@contextlib.contextmanager
def internal_override(**settings):
    """Build with an internal constant changed, through the environment.

    Neither settle count is a build option, and the diagnostics are not
    among the seven a config carries; the firmware still branches on them,
    so the trigger has to meet its bound at every value they can take.  The
    build takes the change from REWIRED_INTERNAL_OVERRIDE (see
    tools/options.py) for the length of the subprocess.  This used to
    rewrite tools/options.py in place and restore it in a finally: a killed
    run left the wrong constant in the tree, and a second session sharing
    the checkout built with it meanwhile.
    """
    previous = os.environ.get(options.OVERRIDE_ENV)
    os.environ[options.OVERRIDE_ENV] = json.dumps(settings)
    try:
        yield
    finally:
        if previous is None:
            del os.environ[options.OVERRIDE_ENV]
        else:
            os.environ[options.OVERRIDE_ENV] = previous


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode",
                        choices=("seq", "arp", "pressure-off", "settle-scans",
                                 "no-gate-settle", "latency", "all", "both"),
                        default="all")
    parser.add_argument("--quick", action="store_true", help="skip the frequency/duty sweep")
    parser.add_argument("--ghidra", type=Path)
    parser.add_argument("--jobs", type=int, default=0,
                        help="emulations to run at once (default: one per mode, capped at 8)")
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
        modes = ("seq", "arp", "pressure-off", "settle-scans",
                 "no-gate-settle", "latency")
    elif args.mode == "both":
        modes = ("seq", "arp")
    else:
        modes = (args.mode,)
    # Two phases, because they have opposite constraints.  The builds must run
    # one at a time and in order: three of the modes reach their configuration
    # through an environment override every build would see, and
    # tools/build.py writes fixed paths under build/ that every build shares.
    # The emulations share
    # nothing - each reads one image and writes one log - so they run
    # together, and the suite takes as long as its slowest mode instead of the
    # sum of all six.
    jobs = args.jobs or min(len(modes), 8)
    images: list[tuple[str, Path]] = []
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
            settle = internal_override(clock_settle_scans=1)
        elif mode == "no-gate-settle":
            settle = internal_override(gate_settle_scans=0)
        elif mode == "latency":
            # The clock-latency diagnostic, so its own two tests run against
            # a real image instead of detecting an ordinary one and skipping.
            settle = internal_override(clock_latency=True)
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
        images.append((mode, image))

    def emulate(mode: str, image: Path) -> str:
        # Its own Ghidra project per mode.  One shared project would serialise
        # the modes again on the project lock, and it also kept every earlier
        # mode's image around in the project the next one opened.
        command = [str(headless), str(work), f"clock-{mode}", "-import", str(image),
                   "-processor", "avr32:BE:32:default", "-noanalysis",
                   "-scriptPath", str(REPO / "src"), "-postScript", "ClockRegression.java",
                   "seq" if mode == "seq" else "arp"]
        if args.quick:
            command.append("quick")
        if mode in ("settle-scans", "no-gate-settle", "latency"):
            command.append("jitter")
        result = subprocess.run(command, cwd=REPO, text=True, capture_output=True)
        output = result.stdout + result.stderr
        (work / f"{mode}-emulation.log").write_text(output)
        # Ghidra can exit zero after a script exception. Require the positive
        # completion marker AND absence of a script error.
        if result.returncode or "ERROR REPORT SCRIPT ERROR" in output or "CLOCK REGRESSION PASS:" not in output:
            return f"Clock regression failed; see {work / (mode + '-emulation.log')}\n{output[-4000:]}"
        return ""

    print(f"Emulating {len(images)} firmware image(s), {jobs} at a time...", flush=True)
    failures = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
        pending = [(mode, pool.submit(emulate, mode, image)) for mode, image in images]
        # Reported in the order the modes were asked for, not the order they
        # finish, so the run reads the same however the work was scheduled.
        for mode, future in pending:
            failure = future.result()
            print(f"--- {mode}", flush=True)
            for line in (work / f"{mode}-emulation.log").read_text().splitlines():
                if "ClockRegression.java>" in line:
                    print(line.split("ClockRegression.java>", 1)[1].replace("(GhidraScript)", "").strip(), flush=True)
            if failure:
                failures.append(failure)
    if failures:
        raise SystemExit("\n\n".join(failures))
    print("All requested clock firmware regressions passed.", flush=True)


if __name__ == "__main__":
    main()
