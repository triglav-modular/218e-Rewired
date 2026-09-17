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


def uniform():
    """Readings that step exactly a semitone apart: the oscillator tracks.

    The fold divides each correction by the pitch step the readings show
    between neighbours, so only readings with no step error at all reduce it
    to exactly minus the reading.  These are the readings the exactness checks
    use; the smooth fixture above has steps a few cents off and is checked
    against the rule instead.
    """
    return {s: 4.2 for s in range(LOW, HIGH + 1)}


def gain_of(meas, n):
    """The rule, restated: the mean step to each measured neighbour over 100,
    floored at a quarter."""
    steps = []
    if n + 1 in meas:
        steps.append(100.0 + meas[n + 1] - meas[n])
    if n - 1 in meas:
        steps.append(100.0 + meas[n] - meas[n - 1])
    return max(0.25, sum(steps) / len(steps) / 100.0) if steps else 1.0


def rows_before_the_fold(measured):
    """What the page built before it could accumulate, lifted from that version.

    A first calibration - no table loaded - has to keep producing exactly this
    for readings the oscillator tracked, because it is what everyone who is
    not doing a second round still does.
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


def js(expr, **args):
    """Evaluate one expression against buildlib.js with `a` as its input."""
    script = """
    var B = require('%s');
    var a = JSON.parse(process.argv[1]);
    process.stdout.write(JSON.stringify(%s));
    """ % (REPO / "web" / "buildlib.js", expr)
    out = subprocess.run(["node", "-e", script, "--", json.dumps(args)],
                         capture_output=True, text=True, check=True).stdout
    return json.loads(out)


def keyed(d):
    return {int(k): v for k, v in d.items()}


def js_fold(base, sources, meas, history=None):
    got = js("B.foldOffsets(a.base, a.meas, a.sources, a.history)",
             base={str(k): v for k, v in base.items()},
             sources={str(k): v for k, v in sources.items()},
             meas={str(k): v for k, v in meas.items()},
             history=history and {"read": {str(k): v for k, v in history[0].items()},
                                  "against": {str(k): v for k, v in history[1].items()}})
    return keyed(got)


def js_history(text):
    """What the page's parser reads back as the record: (read, against)."""
    h = js("B.parseCalibration(a.text, 79).history", text=text)
    return keyed(h["read"]), keyed(h["against"])


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
    even = uniform()
    got = js_fold(flat, flat_src, even)
    off = max(abs(got[s] - -even[s]) for s in even)
    if off > 1e-12:
        print(f"FAIL  on a flat table the fold is not minus the reading: off by {off:g}")
        return 1
    print(f"ok    on a flat table the fold is minus the reading (within {off:g})")
    # And with steps a few cents off, minus the reading over the measured step.
    got = js_fold(flat, flat_src, meas)
    off = max(abs(got[s] - -meas[s] / gain_of(meas, s)) for s in meas)
    if off > 1e-12:
        print(f"FAIL  on a flat table the fold is not the reading over its step: off by {off:g}")
        return 1
    print(f"ok    and over the measured step when the steps are off (within {off:g})")

    # --- an oscillator that compresses at the top ------------------------
    # The case the gain exists for: from semitone 56 up every semitone asked
    # for comes back as 60 cents, so the readings fall 40 cents per key.  The
    # old fold pushed each key by its error and got 60% of it back per pass;
    # this one pushes by the error over the step, in both toolchains alike.
    sag = {s: 0.0 if s <= 55 else -40.0 * (s - 55) for s in range(LOW, HIGH + 1)}
    want = python_fold(BASELINE, sag)
    got = js_fold(base, sources, sag)
    worst = max(abs(want[s] - got[s]) for s in want)
    if worst > 1e-6:
        where = max(want, key=lambda s: abs(want[s] - got[s]))
        print(f"FAIL  on a compressing oscillator the two disagree by {worst:g} at {where}")
        return 1
    for s in (60, 67):
        expect = base[s] - sag[s] * B.octave_width_volts(base, s) / 0.6
        if abs(got[s] - expect) > 1e-9:
            print(f"FAIL  semitone {s}: correction {got[s] - base[s]:.3f}, "
                  f"expected the error over a 0.6 step, {expect - base[s]:.3f}")
            return 1
    if abs((got[55] - base[55]) - 0.0) > 1e-9:
        print(f"FAIL  a key that read exactly right was moved by {got[55] - base[55]:g}")
        return 1
    print("ok    a compressing top is pushed by the error over the measured step, "
          "in both toolchains")

    # A key at the ceiling steps by nothing.  The floor keeps that finite and
    # no more than four times the error; without it this is a division by
    # zero on the page and a ZeroDivisionError in the CLI.
    capped = dict(sag)
    for s in range(63, HIGH + 1):
        capped[s] = capped[62] - 100.0 * (s - 62)      # pitch stops rising
    want = python_fold(BASELINE, capped)
    got = js_fold(base, sources, capped)
    worst = max(abs(want[s] - got[s]) for s in want)
    if worst > 1e-6:
        print(f"FAIL  at the ceiling the two disagree by {worst:g}")
        return 1
    expect = base[HIGH] - capped[HIGH] * B.octave_width_volts(base, HIGH) / 0.25
    if not all(abs(got[s]) < 1e6 for s in got) or abs(got[HIGH] - expect) > 1e-9:
        print(f"FAIL  at the ceiling the correction is not floored at four times: "
              f"{got[HIGH] - base[HIGH]:.3f} vs {expect - base[HIGH]:.3f}")
        return 1
    print("ok    a key at the ceiling is pushed by at most four times its error")

    # calibrationRows() is what every build on the page goes through, so the
    # first calibration - the common case, and the one nobody is watching -
    # has to keep producing what it produced before any of this existed.
    measured = [0.0] * ENTRIES
    for s, c in meas.items():
        measured[s] = c
    even_measured = [0.0] * ENTRIES
    for s, c in uniform().items():
        even_measured[s] = c
    flat = {n: 0.0 for n in range(ENTRIES)}
    flat_src = {n: "measured" for n in range(ENTRIES)}
    got = js_rows(flat, flat_src, even_measured, False)
    want = rows_before_the_fold(even_measured)
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

    # --- a second round uses the key's own slope -------------------------
    # An oscillator that sits a steady 0.3 cents sharp up to semitone 55 and
    # above it gives 0.6 cents of pitch per ramp-cent of CV, sagging 40 cents
    # a key on the baseline.  Linear on purpose: on a linear response the
    # secant through two rounds IS the slope, so the second round has to land
    # exactly, and anything short of exact is a wrong slope, a wrong record,
    # or the two toolchains disagreeing about either.  The 0.3 rather than 0
    # is because a reading of exactly zero is "not measured" to the page, and
    # a key it did not measure it does not record.
    S = 0.6

    def sag_of(n):
        return 0.0 if n <= 55 else 40.0 * (n - 55)

    def plays(table):
        return {n: (-sag_of(n) + S * (table[n] - base[n]) if n > 55 else 0.3)
                for n in range(LOW, HIGH + 1)}

    r1 = plays(base)
    with tempfile.TemporaryDirectory() as tmp:
        cal = Path(tmp) / "rounds.csv"
        cal.write_text(BASELINE.read_text())
        m1 = Path(tmp) / "m1.csv"
        m1.write_text("Semitone;Measured_Cents\n" +
                      "".join(f"{s};{c:.6f}\n" for s, c in sorted(r1.items())))
        B.fold_measurement({"pitch": {"bottom_key_semitone": LOW}}, cal, m1)
        t1_py = B.read_calibration(cal)
        h1_py = B.read_calibration_history(cal)
        round1_text = cal.read_text()

        t1 = js_fold(base, sources, r1)
        meas_list = [0.0] * ENTRIES
        for s_, c in r1.items():
            meas_list[s_] = c
        h1 = js("B.historyToSave(a.base, a.folded, a.measured, {}, null)",
                base={str(k): v for k, v in base.items()},
                folded={str(k): v for k, v in t1.items()}, measured=meas_list)
        h1 = (keyed(h1["read"]), keyed(h1["against"]))
        if max(abs(t1_py[n] - t1[n]) for n in t1) > 1e-6:
            print("FAIL  round one: the two toolchains disagree")
            return 1
        # What the CLI wrote into the file, what the page would have written,
        # and what the page reads back from the CLI's file: one record.
        if h1_py != h1 or js_history(round1_text) != h1:
            print("FAIL  the record of round one differs between writer, page and reader")
            print(f"      cli {sorted(h1_py[0].items())[:3]}.. page {sorted(h1[0].items())[:3]}..")
            return 1
        if set(h1[0]) != set(range(LOW, HIGH + 1)):
            print(f"FAIL  round one recorded the wrong keys: {sorted(h1[0])[:8]}")
            return 1
        print("ok    round one is recorded the same by the CLI, the page and the page's reader")

        # Round two starts from the file, on both sides: that is what gets
        # flashed, and what the page loads back, six decimals and all.
        r2 = plays(t1_py)
        if max(abs(v) for v in r2.values()) < 5:
            print("FAIL  the model left round one nearly right - nothing for round two to prove")
            return 1
        m2 = Path(tmp) / "m2.csv"
        m2.write_text("Semitone;Measured_Cents\n" +
                      "".join(f"{s};{c:.6f}\n" for s, c in sorted(r2.items())))
        B.fold_measurement({"pitch": {"bottom_key_semitone": LOW}}, cal, m2)
        t2_py = B.read_calibration(cal)
    t2 = js_fold(t1_py, sources, r2, h1_py)
    worst = max(abs(t2_py[n] - t2[n]) for n in t2)
    # Two files deep now, so two roundings to six decimals stack: a value
    # that lands on a half-ulp after the first can round the other way after
    # the second.  Two ulps of the file is still ten thousand times finer
    # than the DAC step.
    if worst > 2e-6:
        where = max(t2, key=lambda n: abs(t2_py[n] - t2[n]))
        print(f"FAIL  round two: the two toolchains disagree by {worst:g} at {where}")
        return 1
    r3 = plays(t2)
    left = max(abs(r3[n]) for n in range(56, HIGH + 1))
    if left > 1e-6:
        where = max(range(56, HIGH + 1), key=lambda n: abs(r3[n]))
        print(f"FAIL  round two left {left:g} cents at semitone {where} - "
              f"the key's own slope was not used (round one left {abs(r2[where]):.1f})")
        return 1
    print(f"ok    a second round lands on the key's own slope (within {left:g} cents, "
          f"from {max(abs(v) for v in r2.values()):.0f})")

    # The record counts only when it can say something: a push under 30
    # ramp-cents, or a pitch that moved against the CV, falls back to the
    # step between neighbours - identically in both toolchains.
    n = 67
    fallback = -meas[n] * B.octave_width_volts(base, n) / B.measured_gain(meas, n)
    cases = {
        "a push of 10": ({n: -50.0}, {n: base[n] - 10.0}, fallback),
        "pitch that fell as CV rose": ({n: meas[n] + 5.0}, {n: base[n] - 78.0}, fallback),
        "a push of 78 that gained 40": ({n: meas[n] - 40.0}, {n: base[n] - 78.0},
                                        -meas[n] / (40.0 / 78.0)),
        "a key at the ceiling": ({n: meas[n] - 2.0}, {n: base[n] - 78.0},
                                 -meas[n] / 0.25),
    }
    for name, (read, against, want_delta) in cases.items():
        py = B.key_delta(base, meas, n, (read, against))
        pg = js("B.keyDelta(a.base, a.meas, a.n, a.history)",
                base={str(k): v for k, v in base.items()},
                meas={str(k): v for k, v in meas.items()}, n=n,
                history={"read": {str(n): read[n]}, "against": {str(n): against[n]}})
        if abs(py - want_delta) > 1e-9 or abs(pg - want_delta) > 1e-9:
            print(f"FAIL  {name}: cli {py:.4f}, page {pg:.4f}, expected {want_delta:.4f}")
            return 1
    print("ok    the record is used only when it can say something, alike in both")

    # A table written before the record existed reads as one with none.
    if js_history(BASELINE.read_text()) != ({}, {}):
        print("FAIL  the page read a record out of a table that has none")
        return 1
    print("ok    a table without the record columns loads as before")

    print("ALL FOLD TESTS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
