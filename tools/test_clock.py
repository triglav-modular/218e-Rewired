#!/usr/bin/env python3
"""Emulate the clock divider, the trigger and the pitch it carries; never flash.

    python3 tools/test_clock.py
    python3 tools/test_clock.py --mode seq --quick
    python3 tools/test_clock.py --mode pressure-off

seq, arp and pressure-off are configurations of one image, the shipped one:
seq is its own defaults, and arp (the sequencer off) and pressure-off (the
arp with the pressure fix and its portamento off) are laid as settings
records the way a send leaves one (src/SettingsRecord.java).  The settings
equivalence check boots each built in, sent over MIDI and laid, side by side
(src/SettingsEquivalence.java).  settle-scans, no-gate-settle and latency
change internal constants, which move code, so each is an image of its
own.  --trace prints what each check reads of what a mode sets.

The images and records are built one at a time and then emulated together;
--jobs sets how many emulations run at once, and --jobs 1 puts the whole run
back in a line.

Requires the AVR32 Ghidra language used by the reference assembler. Logs,
test configurations, images and a private Ghidra project stay in build/.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import contextlib
import json
import os
import subprocess
import sys
import tempfile
import tomllib
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "tools"))
import options  # noqa: E402
import profiles  # noqa: E402


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


def expectations(text: str) -> list[str]:
    """What a configuration builds, as ClockRegression's setting=value arguments.

    The internal beat's settle is gate_settle_scans scans of scan_period_ms and
    the external beat's is clock_settle_scans of them; pressure_portamento says
    whether the blend owns the portamento, which decides whether the glide can
    make the trigger decline.  All three come from tools/options.py's expansion
    of the configuration, with REWIRED_INTERNAL_OVERRIDE applied, so call this
    where the build sees the same override.  The suite used to ask the image
    under test what settle it carried and hold it to that answer: a settle cut
    short stayed green, and a lost one turned its tests into SKIPs.
    """
    cfg = options.expand(tomllib.loads(text).get("options", {}))
    period = cfg["timing"]["scan_period_ms"]
    return [f"internal_settle_ms={cfg['timing']['gate_settle_scans'] * period}",
            f"external_settle_ms={cfg['sequencer']['clock_settle_scans'] * period}",
            f"pressure_portamento={int(cfg['portamento']['pressure_blend'])}"]


# The configurations of the one image, as option values over
# config/218e.toml, and the three that are images of their own: each
# changes an internal constant that moves code, which a record cannot carry.
PROFILES: dict[str, dict] = {
    "seq": {},
    "arp": {"sequencer": False},
    # pressure-off is the same clock as arp, the way `pressure_fix = false`
    # sets it. The trigger's rise shares the event-17 wrapper with the
    # pressure interpolator, and that configuration turns smoothing off
    # while leaving clock division on - so it is the one where the wrapper
    # can go missing under the fix and take it with it.
    "pressure-off": {"sequencer": False, "pressure_fix": False, "pressure_portamento": False},
}
# The two settle settings used to decide whether the trigger rode the 1 kHz
# flush at all: a nonzero clock_settle_scans handed the external step back
# to the 5 ms scan, and a zero gate_settle_scans left the internal beat
# unclaimed. Both now keep the flush, so both are built and held to the same
# 1 ms bound as the defaults.  latency is the clock-latency diagnostic, so
# its own two tests run against a real image instead of detecting an
# ordinary one and skipping.  Neither settle nor the diagnostic appears in
# the shipped config - all three come from tools/options.py - so they reach
# the build through REWIRED_INTERNAL_OVERRIDE.
OVERRIDES: dict[str, dict] = {
    "settle-scans": {"clock_settle_scans": 1},
    "no-gate-settle": {"gate_settle_scans": 0},
    "latency": {"clock_latency": True},
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode",
                        choices=(*PROFILES, *OVERRIDES, "all", "both"),
                        default="all")
    parser.add_argument("--quick", action="store_true", help="skip the frequency/duty sweep")
    parser.add_argument("--trace", action="store_true", help="print what each check reads of the settings")
    parser.add_argument("--ghidra", type=Path)
    parser.add_argument("--jobs", type=int, default=0,
                        help="emulations to run at once (default: one per mode, capped at 8)")
    args = parser.parse_args()
    base = (REPO / "config/218e.toml").read_text()
    headless = args.ghidra / "support/analyzeHeadless" if args.ghidra else profiles.headless(base)
    if not headless.is_file():
        raise SystemExit("Set GHIDRA_HOME, config/local.toml [tools].ghidra_home, or --ghidra.")
    (REPO / "build").mkdir(exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="clock-regression-", dir=REPO / "build"))
    print(f"Artifacts: {work}", flush=True)
    if args.mode == "all":
        modes = (*PROFILES, *OVERRIDES)
    elif args.mode == "both":
        modes = ("seq", "arp")
    else:
        modes = (args.mode,)
    # Two phases, because they have opposite constraints.  The builds must run
    # one at a time and in order: three of the modes reach their configuration
    # through an environment override every build would see, and
    # tools/build.py writes fixed paths under build/ that every build shares.
    # The emulations share nothing - each reads one image and writes one log -
    # so they run together, and the suite takes as long as its slowest mode
    # instead of the sum of all of them.
    jobs = args.jobs or min(len(modes) + 1, 8)
    image = profiles.build("image", base, work)
    built: dict[str, dict] = {}
    images: list[tuple[str, Path, Path | None, list[str]]] = []
    for mode in modes:
        if mode in PROFILES:
            built[mode] = profiles.build(mode, profiles.configure(base, PROFILES[mode]), work)
            # seq is the image's own defaults; the others are laid on it.
            record = None if mode == "seq" else built[mode]["record"]
            images.append((mode, image["image"], record, expectations(built[mode]["text"])))
            continue
        # The arp clock, built with an internal constant changed.
        text = profiles.configure(base, {"clock_divide": True, "sequencer": False})
        with internal_override(**OVERRIDES[mode]):
            own = profiles.build(f"clock-{mode}", text, work, env=dict(os.environ))
            expect = expectations(text)
        images.append((mode, own["image"], None, expect))
    static = [f"{mode}: {problem}" for mode, b in built.items()
              for problem in profiles.static_differences(b, image["image"])]
    if static:
        raise SystemExit("A mode here is not data on the image under test:\n" + "\n".join(static))

    def ghidra(project: str, target: Path, *script: str) -> list[str]:
        # Its own Ghidra project per emulation.  One shared project would
        # serialise them again on the project lock.
        return [str(headless), str(work), project, "-import", str(target),
                "-processor", "avr32:BE:32:default", "-noanalysis",
                "-scriptPath", str(REPO / "src"), "-postScript", *script]

    emulations = []
    if built:
        emulations.append(("equivalence", "SETTINGS EQUIVALENCE PASS:", None,
                           ghidra("clock-equivalence", image["image"], "SettingsEquivalence.java",
                                  *profiles.equivalence_arguments(built))))
    for mode, target, record, expect in images:
        script = ["ClockRegression.java", "seq" if mode == "seq" else "arp"]
        if args.quick:
            script.append("quick")
        if mode in OVERRIDES:
            script.append("jitter")
        emulations.append((mode, "CLOCK REGRESSION PASS:", record,
                           ghidra(f"clock-{mode}", target, *script, *expect)))

    def emulate(mode: str, marker: str, record: Path | None, command: list[str]) -> str:
        env = dict(os.environ)
        env.pop("REWIRED_SETTINGS_RECORD", None)
        env.pop("REWIRED_READ_TRACE", None)
        if record:
            env["REWIRED_SETTINGS_RECORD"] = str(record)
        if args.trace:
            env["REWIRED_READ_TRACE"] = "1"
        result = subprocess.run(command, cwd=REPO, text=True, capture_output=True, env=env)
        output = result.stdout + result.stderr
        (work / f"{mode}-emulation.log").write_text(output)
        # Ghidra can exit zero after a script exception. Require the positive
        # completion marker AND absence of a script error.
        if result.returncode or "ERROR REPORT SCRIPT ERROR" in output or marker not in output:
            return f"Clock regression failed; see {work / (mode + '-emulation.log')}\n{output[-4000:]}"
        return ""

    print(f"Emulating {len(emulations)} job(s), {jobs} at a time...", flush=True)
    failures = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
        pending = [(mode, pool.submit(emulate, mode, marker, record, command))
                   for mode, marker, record, command in emulations]
        # Reported in the order the modes were asked for, not the order they
        # finish, so the run reads the same however the work was scheduled.
        for mode, future in pending:
            failure = future.result()
            print(f"--- {mode}", flush=True)
            for line in (work / f"{mode}-emulation.log").read_text().splitlines():
                for script in ("ClockRegression.java>", "SettingsEquivalence.java>"):
                    if script in line:
                        print(line.split(script, 1)[1].replace("(GhidraScript)", "").strip(), flush=True)
            if failure:
                failures.append(failure)
    if failures:
        raise SystemExit("\n\n".join(failures))
    print("All requested clock firmware regressions passed.", flush=True)


if __name__ == "__main__":
    main()
