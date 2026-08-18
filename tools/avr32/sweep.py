#!/usr/bin/env python3
"""Build many configurations both ways and check the images agree.

For each variant: tools/build.py assembles it through Ghidra, then again with
--no-ghidra through the JavaScript toolchain, and the two SHA-256s must match.  Agreement on one configuration
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

# name -> list of (pattern, replacement) applied to config/218e.toml.
# Every one of the seven options at both settings, plus the interactions that
# one-at-a-time rows cannot reach.
VARIANTS: list[tuple[str, list[tuple[str, str]]]] = [
    ("defaults",              []),
    ("arp_off",               [(r"^latching_arp = true", "latching_arp = false")]),
    ("knobs_off",             [(r"^remap_knobs = true", "remap_knobs = false")]),
    ("pitch_correction",      [(r"^pitch_correction = false",
                               'pitch_correction = "calibration/218e-pitch-calibration.csv"')]),
    ("tunings_one",           [(r"^alternate_tunings = false",
                               'alternate_tunings = ["tunings/12TET.scl"]')]),
    ("tunings_three",         [(r"^alternate_tunings = false", 'alternate_tunings = ["tunings/Sabat II (C-rooted).scl",\n                     "tunings/ADDAC Just Intonation.scl",\n                     "tunings/12TET.scl"]')]),
    ("buchla_volts",          [(r"^volts_per_octave = 1.0", "volts_per_octave = 1.2")]),
    ("pressure_off",          [(r"^pressure_fix = true", "pressure_fix = false")]),
    ("portamento_off",        [(r"^pressure_portamento = true", "pressure_portamento = false")]),
    # Interactions the one-at-a-time rows cannot reach.
    ("arp_off_portamento_off",[(r"^latching_arp = true", "latching_arp = false"),
                               (r"^pressure_portamento = true", "pressure_portamento = false")]),
    ("pressure_off_porta_off",[(r"^pressure_fix = true", "pressure_fix = false"),
                               (r"^pressure_portamento = true", "pressure_portamento = false")]),
    ("buchla_volts_corrected",[(r"^volts_per_octave = 1.0", "volts_per_octave = 1.2"),
                               (r"^pitch_correction = false",
                                'pitch_correction = "calibration/218e-pitch-calibration.csv"')]),
    ("historical_config",     [(r"^alternate_tunings = false", 'alternate_tunings = ["tunings/Sabat II (C-rooted).scl",\n                     "tunings/ADDAC Just Intonation.scl",\n                     "tunings/12TET.scl"]'),
                               (r"^pitch_correction = false",
                                'pitch_correction = "calibration/218e-pitch-calibration.csv"')]),
    ("everything_off",        [(r"^latching_arp = true", "latching_arp = false"),
                               (r"^remap_knobs = true", "remap_knobs = false"),
                               (r"^pressure_fix = true", "pressure_fix = false"),
                               (r"^pressure_portamento = true", "pressure_portamento = false")]),
]

SHA_RE = re.compile(r"SHA-256 ([0-9a-f]{64})")

# Configurations whose image is known in advance.  historical_config describes
# the settings the firmware shipped with before the config was reduced to seven
# options, so it must still produce that exact image — which is what proves the
# simplification changed the surface and not the firmware.
EXPECTED = {
    "historical_config": "0134880586e556167d2676aa9f45ef9f0d26fe64e149b8e6fe1818dbab69be22",
}


def write_variant(edits: list[tuple[str, str]]) -> None:
    text = BASE.read_text()
    for pattern, replacement in edits:
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
            js = run([sys.executable, "tools/build.py", "--no-ghidra",
                      "--config", str(TEMP), "--expect-sha", sha])
            ok = js.returncode == 0
            note = "match" if ok else "MISMATCH"
            want = EXPECTED.get(name)
            if want and sha != want:
                note, ok = f"WRONG IMAGE (expected {want[:12]})", False
            elif want:
                note = "match + known image"
            rows.append((name, sha[:12], note))
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

    # Every variant must change the firmware.  Without this a substitution that
    # silently stopped matching would still report "match" — a green result
    # proving nothing.
    images = [r[1] for r in rows if r[1] != "-"]
    duplicates = {i for i in images if images.count(i) > 1}
    if duplicates:
        print(f"WARNING: {len(duplicates)} image(s) produced by more than one "
              f"configuration — a variant may not be taking effect")
        failures += 1
    else:
        print(f"all {len(images)} images distinct")
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
