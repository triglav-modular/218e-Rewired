#!/usr/bin/env python3
"""The page's fold must agree with tools/build.py's, entry for entry.

Accumulating a reading onto a table that is already flashed is the one piece
of calibration arithmetic the page did not have: it assumed a flat start,
where the octave width is exactly 1.000 and the correction is simply minus the
reading.  On a corrected table it is not, and a fold that disagreed with the
CLI would build a different image from the same measurements without saying so.

The baseline is the repository's own calibration file, which is corrected
enough to matter - it reaches +280 cents at the top, where the octave width is
furthest from 1.000 and a wrong scaling shows up first.

    python3 web/test_fold.py
"""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "tools"))
import build as B                                        # noqa: E402

BASELINE = REPO / "calibration" / "218e-pitch-calibration.csv"
LOW, HIGH = 3, 67


def readings():
    """Deterministic, and shaped like real ones rather than uniform noise.

    A sweep's errors drift smoothly with pitch and are a few cents wide, so a
    flat random field would exercise the arithmetic without exercising the
    case it exists for.  The last entry is deliberately non-zero: it is the one
    the extrapolated tail above it follows.
    """
    out = {}
    for s in range(LOW, HIGH + 1):
        x = (s - LOW) / (HIGH - LOW)
        out[s] = round(6.0 * (x - 0.5) ** 3 * 8 + 2.5 * ((s * 37) % 11 - 5) / 5, 6)
    return out


def python_fold(base_path, meas):
    with tempfile.TemporaryDirectory() as tmp:
        cal = Path(tmp) / "cal.csv"
        cal.write_text(base_path.read_text())
        m = Path(tmp) / "m.csv"
        m.write_text("Semitone;Measured_Cents\n" +
                     "".join(f"{s};{c:.6f}\n" for s, c in sorted(meas.items())))
        B.fold_measurement({"pitch": {"bottom_key_semitone": LOW}}, cal, m)
        return B.read_calibration(cal)


def js_fold(base, sources, meas):
    script = """
    var B = require('%s');
    var a = JSON.parse(process.argv[1]);
    process.stdout.write(JSON.stringify(B.foldOffsets(a.base, a.meas, a.sources)));
    """ % (REPO / "web" / "buildlib.js")
    arg = json.dumps({"base": {str(k): v for k, v in base.items()},
                      "sources": {str(k): v for k, v in sources.items()},
                      "meas": {str(k): v for k, v in meas.items()}})
    out = subprocess.run(["node", "-e", script, "--", arg],
                         capture_output=True, text=True, check=True).stdout
    return {int(k): v for k, v in json.loads(out).items()}


def main():
    base = B.read_calibration(BASELINE)
    sources = {}
    for line in BASELINE.read_text().splitlines():
        if line.startswith("#") or line.startswith("Semitone") or not line.strip():
            continue
        parts = line.split(";")
        sources[int(parts[0])] = parts[4].strip()

    meas = readings()
    want = python_fold(BASELINE, meas)
    got = js_fold(base, sources, meas)

    if set(want) != set(got):
        print(f"FAIL  different semitones: {sorted(set(want) ^ set(got))[:8]}")
        return 1
    worst, where = 0.0, None
    for s in sorted(want):
        d = abs(want[s] - got[s])
        if d > worst:
            worst, where = d, s
    # The CLI's answer reaches us through the CSV it rewrites, which stores six
    # decimals - so six decimals is the resolution of the artefact itself, and
    # the two sides cannot be compared any finer than the file they share.  A
    # genuine disagreement is not a rounding away: getting the octave width
    # wrong moves the top of this table by whole cents.
    if worst > 1e-6:
        print(f"FAIL  worst disagreement {worst:g} cents at semitone {where}")
        print(f"      python {want[where]:.9f}   page {got[where]:.9f}")
        return 1
    print(f"ok    {len(want)} semitones agree, worst difference {worst:g} cents")

    # The fold has to be an accumulation, not a replacement: folding nothing
    # must leave the table alone, and folding twice must not be folding once.
    if js_fold(base, sources, {}) != base:
        print("FAIL  an empty fold changed the table")
        return 1
    print("ok    an empty fold is a no-op")
    once = js_fold(base, sources, meas)
    twice = js_fold(once, sources, meas)
    if all(abs(once[s] - twice[s]) < 1e-9 for s in once):
        print("FAIL  folding the same readings twice changed nothing - not accumulating")
        return 1
    print("ok    folding accumulates rather than replacing")

    # The regression that matters most to people who are not doing this at all:
    # a first calibration, with no table loaded, must build what it built before
    # the fold existed.  On a flat table every octave is exactly 1.000 V wide,
    # so the fold reduces to minus the reading.
    #
    # To within floating point, not bit for bit: the width is computed from the
    # table rather than assumed, so it comes out a rounding away from 1.0 and
    # carries that into the product.  The bound below is twelve orders under a
    # cent - a scaling that was actually wrong would miss by percent.
    flat = {n: 0.0 for n in range(79)}
    flat_src = {n: "measured" for n in range(79)}
    got = js_fold(flat, flat_src, meas)
    off = max(abs(got[s] - -meas[s]) for s in meas)
    if off > 1e-12:
        print(f"FAIL  on a flat table the fold is not minus the reading: off by {off:g}")
        return 1
    print(f"ok    on a flat table the fold is minus the reading (within {off:g})")

    print("ALL FOLD TESTS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
