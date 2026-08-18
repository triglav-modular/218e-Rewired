#!/usr/bin/env python3
"""Build many configurations both ways and check the images agree.

For each variant: tools/build.py assembles it through Ghidra, then
tools/avr32/build_js.py assembles the same settings through the JavaScript
toolchain, and the two SHA-256s must match.  Agreement on one configuration
proves very little — the whole point of the feature gating is that different
settings emit different code — so this walks the options.

    python3 tools/avr32/sweep.py            # all variants
    python3 tools/avr32/sweep.py latch      # only variants matching a substring
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
BASE = REPO / "config" / "218e.toml"
TEMP = REPO / "config" / "_sweep.toml"

SLOTS_RE = re.compile(r"^slots = \[.*?^\]", re.S | re.M)

# name -> list of (pattern, replacement) applied to config/218e.toml.
# Combinations that tools/build.py rejects on purpose are avoided: arp latch
# needs portamento.pressure_blend, and the three diagnostics share the same
# two telemetry fields so only one may be on at a time.
VARIANTS: list[tuple[str, list[tuple[str, str]]]] = [
    ("baseline",              []),
    ("knob1_factory",         [(r'^knob1 = "arp_order"',  'knob1 = "factory"')]),
    ("knob2_factory",         [(r'^knob2 = "arp_rhythm"', 'knob2 = "factory"')]),
    ("knob3_factory",         [(r'^knob3 = "arp_octaves"','knob3 = "factory"')]),
    ("knob4_factory",         [(r'^knob4 = "vibrato"',    'knob4 = "factory"')]),
    ("all_knobs_factory",     [(r'^knob1 = "arp_order"',  'knob1 = "factory"'),
                               (r'^knob2 = "arp_rhythm"', 'knob2 = "factory"'),
                               (r'^knob3 = "arp_octaves"','knob3 = "factory"'),
                               (r'^knob4 = "vibrato"',    'knob4 = "factory"')]),
    ("arp_factory",           [(r'^switch = "latch"',     'switch = "factory"')]),
    ("poly_factory",          [(r'^poly_default = "off"', 'poly_default = "factory"')]),
    ("multi_key_mean",        [(r'^multi_key = "max"',    'multi_key = "mean"')]),
    ("multi_key_factory",     [(r'^multi_key = "max"',    'multi_key = "factory"')]),
    ("common_mode_off",       [(r'^common_mode = true',   'common_mode = false')]),
    ("blend_off",             [(r'^pressure_blend = true','pressure_blend = false'),
                               (r'^switch = "latch"',     'switch = "factory"')]),
    ("zero_snap_off",         [(r'^zero_snap = true',     'zero_snap = false')]),
    ("scan_profiler",         [(r'^scan_profiler = false','scan_profiler = true')]),
    ("telemetry_smoothing",   [(r'^telemetry_smoothing = false','telemetry_smoothing = true')]),
    ("latch_probe",           [(r'^latch_probe = false',  'latch_probe = true')]),
    ("pressure_ab_switch",    [(r'^pressure_ab_switch = false','pressure_ab_switch = true')]),
    ("smoothing_off",         [(r'^output_smoothing = 5', 'output_smoothing = 0')]),
    ("smoothing_max",         [(r'^output_smoothing = 5', 'output_smoothing = 8')]),
    ("resolution_0",          [(r'^resolution_bits = 4',  'resolution_bits = 0')]),
    ("error_diffusion_off",   [(r'^error_diffusion = true','error_diffusion = false')]),
    ("trim_independent",      [(r'^trim_mode = "scale"',  'trim_mode = "independent"')]),
    ("tuning_factory",        [("SLOTS", 'slots = ["factory", "tunings/12TET.scl", "tunings/12TET.scl"]')]),
    ("gate_settle_0",         [(r'^gate_settle_scans = 1','gate_settle_scans = 0')]),
    ("scan_period_4",         [(r'^scan_period_ms = 5',   'scan_period_ms = 4')]),
    ("blend_slew_0",          [(r'^blend_slew_shift = 2', 'blend_slew_shift = 0')]),
    ("tolerance_0",           [(r'^latch_match_tolerance = 8','latch_match_tolerance = 0')]),
    ("taps_24",               [(r'^smoothing_taps = 8',   'smoothing_taps = 24')]),
    ("black_key_1",           [(r'^black_key_scale = 1.2','black_key_scale = 1.0')]),
    ("curve_full",            [(r'^default_level = 0',    'default_level = 31')]),
]

SHA_RE = re.compile(r"SHA-256 ([0-9a-f]{64})")


def write_variant(edits: list[tuple[str, str]]) -> None:
    text = BASE.read_text()
    for pattern, replacement in edits:
        if pattern == "SLOTS":
            text, n = SLOTS_RE.subn(replacement, text)
        else:
            text, n = re.subn(pattern, replacement, text, flags=re.M)
        if n != 1:
            raise SystemExit(f"edit {pattern!r} matched {n} times, expected 1")
    # Never touch the shipped image or the updater.
    text, n = re.subn(r'^output_hex\s*=\s*".*"', 'output_hex     = "build/_sweep.hex"',
                      text, flags=re.M)
    assert n == 1
    text = re.sub(r'^updater\s*=\s*".*"\n', "", text, flags=re.M)
    TEMP.write_text(text)


def run(cmd: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, cwd=REPO)


def main() -> None:
    wanted = sys.argv[1] if len(sys.argv) > 1 else ""
    variants = [v for v in VARIANTS if wanted in v[0]]
    rows, failures = [], 0
    try:
        for name, edits in variants:
            write_variant(edits)
            ghidra = run([sys.executable, "tools/build.py", "--config", str(TEMP)])
            if ghidra.returncode != 0:
                reason = (ghidra.stdout + ghidra.stderr).strip().splitlines()[-1][:58]
                rows.append((name, "-", f"build.py rejected: {reason}"))
                failures += 1
                continue
            match = SHA_RE.search(ghidra.stdout)
            if not match:
                rows.append((name, "-", "no SHA in build.py output"))
                failures += 1
                continue
            sha = match.group(1)
            js = run([sys.executable, "tools/avr32/build_js.py",
                      "--config", str(TEMP), "--expect-sha", sha])
            ok = js.returncode == 0
            rows.append((name, sha[:12], "match" if ok else "MISMATCH"))
            if not ok:
                failures += 1
    finally:
        TEMP.unlink(missing_ok=True)
        (REPO / "build" / "_sweep.hex").unlink(missing_ok=True)

    width = max(len(r[0]) for r in rows)
    print(f"\n{'config'.ljust(width)}  {'image':12s}  result")
    for name, sha, result in rows:
        print(f"{name.ljust(width)}  {sha:12s}  {result}")
    print(f"\n{len(rows) - failures}/{len(rows)} configurations agree")
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
