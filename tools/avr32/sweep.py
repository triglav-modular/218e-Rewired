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
sys.path.insert(0, str(REPO / "tools"))
import build as build_mod  # noqa: E402
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
    # The 2.0 features.  They were missing from this sweep, which is how a
    # clock hook that jumped to an invalid address reached a release build:
    # the browser-parity matrix compares two toolchains against each other and
    # both were told the same wrong thing, and the emulations called each cave
    # directly rather than through its hook.  This sweep is the only check
    # that builds every one of them through Ghidra.
    ("sequencer",             [(r"^sequencer = false", "sequencer = true")]),
    ("clock_divide",          [(r"^clock_divide = false", "clock_divide = true")]),
    ("sequencer_and_clock",   [(r"^sequencer = false", "sequencer = true"),
                               (r"^clock_divide = false", "clock_divide = true")]),
    ("persist_only",          [(r"^persist = false", "persist = true")]),
    ("persist_sequencer",     [(r"^persist = false", "persist = true"),
                               (r"^sequencer = false", "sequencer = true")]),
    ("persist_clock",         [(r"^persist = false", "persist = true"),
                               (r"^clock_divide = false", "clock_divide = true")]),
    ("persist_seq_clock",     [(r"^persist = false", "persist = true"),
                               (r"^sequencer = false", "sequencer = true"),
                               (r"^clock_divide = false", "clock_divide = true")]),
    ("knob_roles",            [(r"^remap_knobs = true",
                               'remap_knobs = true\nknob1 = "orders"\nknob2 = "patterns"\nknob4 = "trn"')]),
    ("knob2_swing",           [(r"^remap_knobs = true", 'remap_knobs = true\nknob2 = "swing"')]),
    ("arp_patterns",          [(r"^remap_knobs = true",
                               'remap_knobs = true\nknob2 = "patterns"\n'
                               'arp_patterns = ["x...x...x...x...", "x.x.x.x.", ["xx..", 4]]')]),
    ("tuning_maps",           [(r"^alternate_tunings = false",
                               'alternate_tunings = [["tunings/24TET.scl", "tunings/24TET-full.kbm"]]')]),
    ("non_octave",            [(r"^alternate_tunings = false",
                               'alternate_tunings = [' + ', '.join(
                                   ['["tunings/BohlenPierce.scl", "tunings/BohlenPierce.kbm"]'] * 3)
                               + ']')]),
    ("everything_on",         [(r"^sequencer = false", "sequencer = true"),
                               (r"^clock_divide = false", "clock_divide = true"),
                               (r"^persist = false", "persist = true"),
                               (r"^pitch_correction = false",
                                'pitch_correction = "calibration/218e-pitch-calibration.csv"'),
                               (r"^alternate_tunings = false",
                                'alternate_tunings = ["tunings/12TET.scl"]')]),
    ("everything_off",        [(r"^latching_arp = true", "latching_arp = false"),
                               (r"^remap_knobs = true", "remap_knobs = false"),
                               (r"^pressure_fix = true", "pressure_fix = false"),
                               (r"^pressure_portamento = true", "pressure_portamento = false")]),
]

SHA_RE = re.compile(r"SHA-256 ([0-9a-f]{64})")


def audit_call_pools(image_path) -> list[str]:
    """Every MCALL in this image must read a word naming emitted code.

    Per-variant, because the bug class is per-variant: a pool that is right
    with everything on can name erased flash once the callee's block is off.
    """
    flash, _ = build_mod.parse_hex(image_path)
    word = lambda a: int.from_bytes(bytes(flash.get(a + i, 0xFF) for i in range(4)), "big")

    def called(target: int) -> bool:
        for pc in range(0x80002000, 0x80020000, 2):
            if flash.get(pc) != 0xF0 or flash.get(pc + 1) != 0x1F:
                continue
            d = (flash.get(pc + 2, 0) << 8) | flash.get(pc + 3, 0)
            if d & 0x8000:
                d -= 0x10000
            if (pc & ~3) + d * 4 == target:
                return True
        return False

    source = (REPO / "src" / "AssemblePressureFix.java").read_text()
    targets = sorted({int(m, 16) for m in
                      re.findall(r'emit\("MCALL PC\[(0x[0-9a-f]+)\]"\);', source)})
    bad = []
    for t in targets:
        if t not in flash or not called(t):
            continue
        v = word(t)
        if not (0x80000000 <= v < 0x80020000 and v % 2 == 0):
            bad.append(f"{t:#x} holds {v:#010x}")
        elif flash.get(v, 0xFF) == 0xFF:
            bad.append(f"{t:#x} -> {v:#x} (erased flash)")
    return bad

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
# Re-pinned after the calibration defaults moved to the settings that suit the
# instrument this was measured on, and both trims were centred on them; and
# again for 2.0, which added caves and moved the first-use clear; and again
# when rests and ties moved to an absolute strip position, which moved the
# clear once more; and for interrupt-timestamped clock capture, whose FIFO
# extends that clear through 0x62df; and for canonical persistence plus
# independent sequence transport, completed-gesture persistence, and
# one-shot preview/explicit CLEAR ownership.
# Both assemblers must verify this pin.
EXPECTED = {
    "historical_config": "0cb5611ffa75da8cd02dab9b0141195abf55ff29117c5728601eb097fde33981",
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
            pool_bad = audit_call_pools(REPO / "build" / "_sweep.hex")
            if pool_bad:
                rows.append((name, sha[:12], "BAD CALL POOL: " + "; ".join(pool_bad)))
                failures += 1
                continue
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
