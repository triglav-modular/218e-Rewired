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
LOW, HIGH, ENTRIES = 3, 67, 79


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


def js_rows(base, sources, measured, has_base):
    script = """
    var B = require('%s');
    var a = JSON.parse(process.argv[1]);
    process.stdout.write(JSON.stringify(
        B.calibrationRows(a.base, a.sources, a.measured, %d, %d, %d, a.hasBase)));
    """ % (REPO / "web" / "buildlib.js", LOW, HIGH, ENTRIES)
    arg = json.dumps({"base": {str(k): v for k, v in base.items()},
                      "sources": {str(k): v for k, v in sources.items()},
                      "measured": measured, "hasBase": has_base})
    return json.loads(subprocess.run(["node", "-e", script, "--", arg],
                                     capture_output=True, text=True, check=True).stdout)


def rows_before_the_fold(measured):
    """What the page built before it could accumulate, lifted from that version.

    A first calibration - no table loaded - has to keep producing exactly this,
    because it is what everyone who is not doing a second round still does.
    """
    full = [-v for v in measured]
    for n in range(LOW - 1, -1, -1):
        full[n] = 0.0
    slope = full[HIGH] - full[HIGH - 1]
    for n in range(HIGH + 1, ENTRIES):
        full[n] = full[n - 1] + slope
    return full


def sources_of(text):
    """The Source column, semitone -> whatever the cell says, blanks included."""
    out = {}
    for line in text.splitlines():
        if line.startswith("#") or line.startswith("Semitone") or not line.strip():
            continue
        parts = line.split(";")
        out[int(parts[0])] = parts[4].strip()
    return out


def with_blank_source(text, semitone):
    """The baseline with one Source cell emptied, as a hand edit would leave it.

    The page's own export always fills the column, so only a hand-edited or
    third-party file reaches this - which is exactly why nothing caught the two
    toolchains reading a blank differently.
    """
    out, seen = [], False
    for line in text.splitlines():
        parts = line.split(";")
        if (not line.startswith("#") and not line.startswith("Semitone")
                and line.strip() and int(parts[0]) == semitone):
            parts[4] = ""
            line, seen = ";".join(parts), True
        out.append(line)
    if not seen:
        raise SystemExit(f"no semitone {semitone} row to blank")
    return "\n".join(out) + "\n"


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
    sources = sources_of(BASELINE.read_text())

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

    # The same comparison on a file whose Source column has a hole in it.  The
    # two toolchains used to disagree about what a blank cell means - Python
    # reads it as "not extrapolated", so the row holds its own correction and
    # ends the tail; the page read it as falsy and shifted it.  Blank is
    # unknown, and unknown is not extrapolated, so Python's reading is the one
    # both now use.
    #
    # The blank goes in the extrapolated tail above the highest reading, which
    # is the only place the rule applies at all.
    BLANK_AT = 72
    holed = with_blank_source(BASELINE.read_text(), BLANK_AT)
    with tempfile.TemporaryDirectory() as tmp:
        cal = Path(tmp) / "holed.csv"
        cal.write_text(holed)
        holed_base = B.read_calibration(cal)
        holed_sources = sources_of(holed)
        want = python_fold(cal, meas)
    got = js_fold(holed_base, holed_sources, meas)
    worst, where = 0.0, None
    for s in sorted(want):
        d = abs(want[s] - got[s])
        if d > worst:
            worst, where = d, s
    if worst > 1e-6:
        print(f"FAIL  with semitone {BLANK_AT}'s Source blank the two disagree by "
              f"{worst:g} cents at semitone {where}")
        print(f"      python {want[where]:.9f}   page {got[where]:.9f}")
        return 1
    # Agreement is only worth something if the blank changed an answer: two
    # toolchains that both stopped applying the tail rule would agree here
    # perfectly.  So this comes second, after the disagreement it would
    # otherwise explain away, and asks whether the hole did anything at all.
    plain = js_fold(base, sources, meas)
    if all(abs(plain[n] - got[n]) < 1e-9 for n in got):
        print(f"FAIL  blanking the Source of semitone {BLANK_AT} changed no row - "
              "the tail rule is not being exercised")
        return 1
    print(f"ok    a blank Source ends the tail in both, worst difference {worst:g} cents")

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

    # calibrationRows() is what every build on the page goes through, so the
    # first calibration - the common case, and the one nobody is watching -
    # has to keep producing what it produced before any of this existed.
    measured = [0.0] * ENTRIES
    for s, c in meas.items():
        measured[s] = c
    flat = {n: 0.0 for n in range(ENTRIES)}
    flat_src = {n: "measured" for n in range(ENTRIES)}
    got = js_rows(flat, flat_src, measured, False)
    want = rows_before_the_fold(measured)
    worst = max(abs(a - b) for a, b in zip(want, got))
    if worst > 1e-12:
        bad = [i for i, (a, b) in enumerate(zip(want, got)) if abs(a - b) > 1e-12]
        print(f"FAIL  a first calibration no longer builds what it used to: "
              f"off by {worst:g} at semitone {bad[:6]}")
        return 1
    print(f"ok    a first calibration builds what it always did (within {worst:g})")

    # With a table loaded the ends are its own rows, shifted - not invented.
    # Getting this wrong would silently discard the correction above the top
    # key, where a real table carries its largest values.
    #
    # The repository's own table happens to be zero below the bottom key, where
    # the invented rule also writes zero - so on that file the two are
    # indistinguishable and the check would pass without testing anything.  A
    # marked baseline tells them apart.
    marked = dict(base)
    for n in range(0, LOW):
        marked[n] = -7.5
    got = js_rows(marked, sources, measured, True)
    folded = js_fold(marked, sources, meas)
    ends = [n for n in list(range(0, LOW)) + list(range(HIGH + 1, ENTRIES))
            if abs(got[n] - folded[n]) > 1e-12]
    if ends:
        print(f"FAIL  a loaded table's ends were overwritten rather than carried: {ends[:6]}")
        return 1
    if any(abs(got[n] - (-7.5)) > 1e-12 for n in range(0, LOW)):
        print(f"FAIL  a loaded table's rows below the bottom key were not carried: "
              f"{[got[n] for n in range(0, LOW)]}")
        return 1
    print("ok    a loaded table's ends are carried, not invented over")

    # And the same rows with no table loaded are zeroed, which is the other
    # half of the same decision.
    if any(abs(js_rows(marked, sources, measured, False)[n]) > 1e-12
           for n in range(0, LOW)):
        print("FAIL  with no table loaded the rows below the bottom key are not zero")
        return 1
    print("ok    with no table loaded those rows are invented instead")

    print("ALL FOLD TESTS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
