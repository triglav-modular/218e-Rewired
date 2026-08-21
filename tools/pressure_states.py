#!/usr/bin/env python3
"""Count the distinct pressure states the chain can actually deliver.

The numbers in docs/FIRMWARE_CHANGES.md about resolution - how many levels
survive the curve blend, and what the wider blend recovers - were measured
once and then went stale twice, because they depend on the calibration
window and on how far knob 4 can push the curve.  Both are settings now, so
the numbers are computed here instead of remembered.

    python3 tools/pressure_states.py

Simulates the integer chain in AssemblePressureFix.java exactly: an 8-tap
mean of raw counts, normalisation onto `span`, the interpolated curve blend,
and the final quantiser - and reports how many distinct outputs each stage
can produce.
"""
import sys
import tomllib
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "tools"))

import options as _options
from build import pressure_curve


def blend(n, k, table, bits, wide):
    """One sample through the curve blend, in the assembler's arithmetic."""
    if bits > 0:
        i, frac = n >> bits, n & ((1 << bits) - 1)
        cv = (table[i] << bits) + (table[i + 1] - table[i]) * frac
    else:
        cv = table[n]
    d = n - cv
    weight = (k << 3) + (k >> 2)          # k*8 + k/4
    d *= weight
    if wide:
        # ((n-cv)*k*16 + 128) >> 8 written as ((n-cv)*k + 8) >> 4
        d = (d + 8) >> 4
        n <<= 4
    else:
        d = (d + 0x80) >> 8
    return n - d


def states(floor, ceiling, span, table, bits, k, wide):
    """Distinct blend outputs over every 8-tap mean the window can produce."""
    window = ceiling - floor
    lo, hi = floor << bits, ceiling << bits
    seen = set()
    # The mean of eight integer counts, in 1/(1<<bits) units: 8*window+1 of
    # them, and they are the chain's real input alphabet.
    for s in range(8 * window + 1):
        n = ((floor * 8 + s) << bits) // 8
        if n <= lo:
            v = 0
        elif n >= hi:
            v = span << bits
        else:
            v = ((n - lo) * span) // window
        seen.add(blend(v, k, table, bits, wide))
    return len(seen)


def codes(floor, ceiling, span, table, bits, k):
    """Distinct DAC codes the same alphabet reaches, diffusion switched off."""
    window = ceiling - floor
    lo, hi = floor << bits, ceiling << bits
    seen = set()
    for s in range(8 * window + 1):
        n = ((floor * 8 + s) << bits) // 8
        if n <= lo:
            v = 0
        elif n >= hi:
            v = span << bits
        else:
            v = ((n - lo) * span) // window
        seen.add((0xfff * blend(v, k, table, bits, True)) // ((span << bits) << 4))
    return len(seen)


def main():
    with open(REPO / "config" / "218e.toml", "rb") as fh:
        cfg = _options.expand(tomllib.load(fh).get("options", {}))
    p = cfg["pressure"]
    calib, curve = p["calibration"], p["curve"]
    window = calib["ceiling"] - calib["floor"]
    span = curve["span"]
    bits = p["resolution_bits"]
    table = pressure_curve(span, curve["onset_db"], curve["onset_fade"])
    # The assembler appends one sentinel repeat of the last entry, because the
    # interpolating lookup reads table[i+1] and at full scale i is the last
    # index.  The simulation needs it for the same reason.
    table = table + [table[-1]]
    top = curve.get("knob_max_level", 31)

    print(f"  window            {calib['floor']}..{calib['ceiling']}  "
          f"({window} counts)")
    print(f"  8-tap mean states {8 * window + 1}")
    print(f"  curve levels      0..{top}  (default {curve['default_level']})")
    for k in (0, top):
        wide = states(calib["floor"], calib["ceiling"], span, table, bits, k, True)
        narrow = states(calib["floor"], calib["ceiling"], span, table, bits, k, False)
        label = "linear" if k == 0 else f"level {k}"
        print(f"  {label:16s}  {wide} states  ({narrow} with the old >>8)")

    # What survives the final divide, which is the number the player hears.
    # Truncation alone drops states whenever two of them land in the same
    # code; diffusion carries the remainder so they separate over time.
    for k in (0, top):
        plain = codes(calib["floor"], calib["ceiling"], span, table, bits, k)
        label = "linear" if k == 0 else f"level {k}"
        print(f"  {label:16s}  {plain} of 4096 output codes without diffusion")


if __name__ == "__main__":
    main()
