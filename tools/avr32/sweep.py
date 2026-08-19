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
    ("tunings_three",         [(r"^alternate_tunings = false", 'alternate_tunings = ["tunings/Sabat II (C-rooted).scl",\n                     "tunings/5-Limit JI with Septimal 7th.scl",\n                     "tunings/12TET.scl"]')]),
    ("one_volt",              [(r"^volts_per_octave = 1.2", "volts_per_octave = 1.0")]),
    ("pressure_off",          [(r"^pressure_fix = true", "pressure_fix = false"),
                               (r"^pressure_portamento = true", "pressure_portamento = false")]),
    ("portamento_off",        [(r"^pressure_portamento = true", "pressure_portamento = false")]),
    # Interactions the one-at-a-time rows cannot reach.
    ("arp_off_portamento_off",[(r"^latching_arp = true", "latching_arp = false"),
                               (r"^pressure_portamento = true", "pressure_portamento = false")]),
    ("one_volt_corrected",    [(r"^volts_per_octave = 1.2", "volts_per_octave = 1.0"),
                               (r"^pitch_correction = false",
                                'pitch_correction = "calibration/218e-pitch-calibration.csv"')]),
    # The author's own instrument: the shipped calibration, three tunings, and
    # the 1 V/oct ramp that 208 is trimmed to.  Those go together — that table
    # was measured at that scaling — so this is the one configuration where
    # the calibration in calibration/ is the right one to use.
    ("historical_config",     [(r"^volts_per_octave = 1.2", "volts_per_octave = 1.0"),
                               (r"^alternate_tunings = false", 'alternate_tunings = ["tunings/Sabat II (C-rooted).scl",\n                     "tunings/5-Limit JI with Septimal 7th.scl",\n                     "tunings/12TET.scl"]'),
                               (r"^pitch_correction = false",
                                'pitch_correction = "calibration/218e-pitch-calibration.csv"')]),
    ("everything_off",        [(r"^latching_arp = true", "latching_arp = false"),
                               (r"^remap_knobs = true", "remap_knobs = false"),
                               (r"^pressure_fix = true", "pressure_fix = false"),
                               (r"^pressure_portamento = true", "pressure_portamento = false")]),
]

SHA_RE = re.compile(r"SHA-256 ([0-9a-f]{64})")

# Configurations whose image is pinned, so an unintended change shows up here
# rather than in someone's instrument.
#
# historical_config is the fully-specified build — measured calibration, three
# tunings, 1 V/oct — that the firmware shipped with before the config was
# reduced to seven options.  It no longer reproduces that old image byte for
# byte, and cannot: the init marker is a hash over the build settings AND the
# assembler source, so renaming a block or even editing a comment in
# AssemblePressureFix.java moves it.  That is the marker working as intended —
# a changed build forces a fresh power-up init.  What this pin still buys is a
# stable anchor for the most complex configuration.
# Re-pinned after the 0x32xx scratch moved to 0x60E4 and the transpose,
# remote-enable and clamp-skip patches were gated on their options: every
# image changed, this one with them, and the Ghidra and JS toolchains agree
# on the new bytes.
EXPECTED = {
    "historical_config": "1d00d905b7b850287565ba1085e1b2af7cafbd4fe06b85012bf6f4e94401a9a7",
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
    # Strip the updater list so a variant build cannot rewrite the real
    # flashers with its own checksum.  This is asserted rather than assumed:
    # it was previously a single-line regex, the key became a multi-line array,
    # the match silently stopped happening, and every sweep quietly left the
    # shipped flashers expecting a variant image.
    text, removed = re.subn(r'^updaters?\s*=\s*(?:".*"|\[[^\]]*\])\n', "",
                            text, flags=re.M | re.S)
    if removed != 1:
        raise SystemExit("could not strip [firmware].updaters from the config — "
                         "a variant build would overwrite the real flashers")
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
