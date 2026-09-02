#!/usr/bin/env python3
"""Regression tests for the build's generators, validators and safety checks.

Fast tests run without Ghidra:

    python3 tools/test.py

Add --golden to also build the firmware and compare it against the SHA-256
recorded in config/218e.toml under [firmware].golden_sha256, which catches any
change in the assembly, the tables or the patch set:

    python3 tools/test.py --golden
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import math
import os
import re
import subprocess
import sys
import tomllib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build as B  # noqa: E402

REPO = B.REPO
FAILURES: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    print(f"  {'ok  ' if condition else 'FAIL'}  {name}{'' if condition else f'  — {detail}'}")
    if not condition:
        FAILURES.append(name)


def raises(name: str, fn, fragment: str) -> None:
    try:
        fn()
    except BaseException as exc:  # noqa: BLE001 — SystemExit counts too
        check(name, fragment.lower() in str(exc).lower(), f"wrong error: {exc}")
        return
    check(name, False, "no error raised")


def tmp(text: str, suffix: str) -> Path:
    path = REPO / "build" / f"_test{suffix}"
    path.parent.mkdir(exist_ok=True)
    path.write_text(text)
    return path


# ---------------------------------------------------------------------------
def test_pitch_table(cfg: dict) -> None:
    print("pitch calibration")
    offsets = B.read_calibration(REPO / cfg["pitch"]["calibration_csv"])
    check("shipped table has every index the firmware reads",
          len(offsets) >= B.PITCH_TABLE_ENTRIES, f"{len(offsets)} rows")
    table = B.pitch_table(cfg, offsets)
    check("generated curve is monotonic", table == sorted(table))
    check("generated curve stays in the 12-bit DAC", 0 <= table[0] and table[-1] <= 4095)
    check("length matches the firmware's reach", len(table) == B.PITCH_TABLE_ENTRIES)

    # A 208c build lays the same curve out three entries later: the bottom
    # key, which the firmware reads at entry 3, gets the 0 V pitch, and the
    # entries under it sit at 0 V.  Nothing else about the curve changes.
    c_cfg = json.loads(json.dumps(cfg))
    c_cfg["pitch"]["bottom_key_semitone"] = 0
    shifted = B.pitch_table(c_cfg, offsets)
    check("208c table keeps the firmware's length", len(shifted) == B.PITCH_TABLE_ENTRIES)
    check("208c table puts the 0 V pitch at the bottom key",
          shifted[:3] == [0, 0, 0] and shifted[3:] == table[:-3])
    check("the offset build's table is untouched",
          B.pitch_table(cfg, offsets) == table)
    c_cfg["pitch"]["bottom_key_semitone"] = 1
    raises("only the two layouts are accepted",
           lambda: B.pitch_table(c_cfg, offsets), "must be 3 or 0")

    header = "Semitone;Note;Key;Offset_Cents;Source\n"
    short = header + "".join(f"{i};;;0.0;t\n" for i in range(61))
    raises("short table rejected", lambda: B.read_calibration(tmp(short, "_short.csv")),
           "firmware reads")
    gap = header + "".join(f"{i};;;0.0;t\n" for i in range(80) if i != 40)
    raises("gapped table rejected", lambda: B.read_calibration(tmp(gap, "_gap.csv")), "no gaps")


def test_scala() -> None:
    print("scala parsing")
    for name in ("Sabat II", "Sabat II (C-rooted)", "5-Limit JI with Septimal 7th", "12TET"):
        cents = B.parse_scala(REPO / "tunings" / f"{name}.scl")
        ok = len(cents) == 12 and cents[0] == 0 and all(
            b > a for a, b in zip(cents, cents[1:]))
        check(f"{name} parses ascending", ok)

    def scale(body: str) -> str:
        return "! t\nt\n 12\n!\n" + body

    # Archive files are often Latin-1 in the description, and a spreadsheet's
    # CSV starts with a byte-order mark; neither may stop the read.
    latin = REPO / "build" / "_test_latin.scl"
    latin.write_bytes("! t\nD\xe9scription\n 12\n!\n".encode("latin-1")
                      + "".join(f" {100 * i}.0\n" for i in range(1, 13)).encode())
    check("a Latin-1 description does not stop the read", len(B.parse_scala(latin)) == 12)
    bom = REPO / "build" / "_test_bom.csv"
    bom.write_bytes(b"\xef\xbb\xbf" + (REPO / "calibration" / "218e-pitch-calibration.csv").read_bytes())
    check("a byte-order mark does not hide the calibration header",
          B.read_calibration(bom) == B.read_calibration(REPO / "calibration" / "218e-pitch-calibration.csv"))

    raises("descending scale rejected",
           lambda: B.parse_scala(tmp(scale(
               " 100.0\n 90.0\n 300.0\n 400.0\n 500.0\n 600.0\n 700.0\n 800.0\n"
               " 900.0\n 1000.0\n 1100.0\n 2/1\n"), "_desc.scl")), "ascending")
    # A scale is free to repeat somewhere other than the octave: the table
    # steps the period the file declares, and the octave controls are rebuilt
    # from it.  Twelve degrees to a 1250-cent period is a legal instrument.
    stretched = B.parse_scala(tmp(scale(
        " 100.0\n 200.0\n 300.0\n 400.0\n 500.0\n 600.0\n 700.0\n 800.0\n"
        " 900.0\n 1000.0\n 1100.0\n 1250.0\n"), "_oct.scl"), mapped=True)
    check("a scale may repeat off the octave",
          abs(stretched[12] - 1250.0) < 1e-9
          and B.tuning_table(stretched, 485, 484)[12] - 485 == 504)
    raises("wrong degree count rejected",
           lambda: B.parse_scala(tmp("! t\nt\n 3\n!\n 100.0\n 200.0\n 2/1\n", "_n.scl")),
           "12-note")

    # The format allows a blank description, and the parser used to filter
    # blank lines before indexing - shifting the count into the description
    # and the first pitch into the count, so every such legal file died with
    # a raw ValueError.
    twelve = (" 100.0\n 200.0\n 300.0\n 400.0\n 500.0\n 600.0\n 700.0\n"
              " 800.0\n 900.0\n 1000.0\n 1100.0\n 2/1\n")
    blank = B.parse_scala(tmp("! made elsewhere\n\n 12\n" + twelve, "_blank.scl"))
    check("blank description line accepted", len(blank) == 12 and blank[0] == 0)
    raises("degree count that is not a number is refused cleanly",
           lambda: B.parse_scala(tmp("! t\nt\n 12x\n" + twelve, "_cnt.scl")),
           "not a number")

    # The .scl format's own examples of valid pitch lines: a cents value may
    # end in the period, an integer with no slash is that integer over 1, and
    # "anything after a valid pitch value should be ignored".
    lenient = B.parse_scala(tmp(
        "! t\nt\n 12\n 100.\n 200.0 cents\n 300.0 C#\n 400.0\n 500.0\n"
        " 600.0\n 700.0\n 800.0\n 900.0\n 1000.0\n 1100.0\n 2\n",
        "_lenient.scl"), mapped=True)
    check("the format's own valid pitch lines are read",
          abs(lenient[1] - 100.0) < 1e-9 and abs(lenient[3] - 300.0) < 1e-9
          and abs(lenient[12] - 1200.0) < 1e-9, lenient)

    def ratio(token: str) -> "Path":
        body = "".join(f" {100.0 * k}\n" for k in range(1, 12))
        return tmp(f"! t\nt\n 12\n{body} {token}\n", "_ratio.scl")

    # "Ratios are written with a slash, and only one."  The browser used to
    # divide the first two parts and ignore the rest, so a file tools/build.py
    # refused outright built an ordinary fifth on the page.
    raises("a ratio with two slashes is refused",
           lambda: B.parse_scala(ratio("3/2/9"), mapped=True), "single slash")
    # "Negative ratios are meaningless and should give a read error."
    raises("a negative ratio is refused",
           lambda: B.parse_scala(ratio("-2/1"), mapped=True), "above zero")
    raises("a zero ratio is refused",
           lambda: B.parse_scala(ratio("0/1"), mapped=True), "above zero")
    raises("a ratio that is not whole numbers is refused",
           lambda: B.parse_scala(ratio("2/x"), mapped=True), "single slash")


def test_keyboard_maps() -> None:
    print("keyboard mapping (.kbm)")

    def kbm(body: str, size: int = 12, formal: int = 12) -> "Path":
        return tmp(f"! t\n {size}\n 0\n 127\n 60\n 69\n 440.0\n {formal}\n" + body,
                   "_m.kbm")

    twelve = B.parse_scala(REPO / "tunings" / "12TET.scl", mapped=True)
    check("mapped parse keeps the octave degree",
          len(twelve) == 13 and abs(twelve[12] - 1200.0) < 1e-9)

    # The identity map has to reproduce the unmapped table exactly, or every
    # existing build would move the moment .kbm support shipped.
    plain = B.parse_scala(REPO / "tunings" / "12TET.scl")
    degrees, formal = B.parse_kbm(kbm("".join(f" {d}\n" for d in range(12))), twelve)
    check("identity map reproduces the unmapped table",
          degrees == list(range(12)) and formal == 12
          and B.tuning_table(twelve, 485, 484,
                             B.anchor_offset(twelve, 9, degrees, twelve[formal]),
                             degrees, twelve[formal])
          == B.tuning_table(plain, 485, 484, B.anchor_offset(plain, 9)))

    # Size zero is the format's "no mapping at all".
    check("size zero maps every degree in order",
          B.parse_kbm(kbm("", size=0), twelve)[0] == list(range(12)))

    # Blank lines are not entries: one after the header, or between two
    # entries, used to move every key after it up by one.
    check("a blank line after the header is skipped",
          B.parse_kbm(kbm("\n" + "".join(f" {d}\n" for d in range(12))), twelve)[0]
          == list(range(12)))
    check("a blank line between entries is skipped",
          B.parse_kbm(kbm("".join(f" {d}\n" + ("\n" if d == 5 else "") for d in range(12))),
                      twelve)[0] == list(range(12)))

    # Unmapped positions take the nearest mapped one, ties to the lower.
    filled, _ = B.parse_kbm(kbm(" 0\n x\n 1\n x\n x\n 2\n" + " x\n" * 6), twelve)
    check("unmapped positions take the nearest degree",
          filled == [0, 0, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2], filled)

    raises("formal octave outside the scale is refused",
           lambda: B.parse_kbm(kbm(" 0\n" * 12, formal=99), twelve), "must name one")
    # "At the end, unmapped keys may be left out" - the .kbm format allows a
    # map to stop short of its own size, and the tail is unmapped like any
    # other gap.  Both builders used to refuse these, and did not even agree on
    # how many entries they had found.
    short, _ = B.parse_kbm(kbm(" 0\n 1\n"), twelve)
    check("a map may stop early, the rest unmapped",
          short == [0, 1] + [1] * 10, short)
    raises("but a map with nothing in it is still refused",
           lambda: B.parse_kbm(kbm(""), twelve), "every position is unmapped")
    raises("an all-unmapped map is refused",
           lambda: B.parse_kbm(kbm(" x\n" * 12), twelve), "every position is unmapped")
    raises("a degree outside the scale is refused",
           lambda: B.parse_kbm(kbm(" 0\n 99\n" + " 0\n" * 10), twelve), "degrees 0..12")
    raises("a header that is not a number is refused",
           lambda: B.parse_kbm(tmp("! t\n twelve\n 0\n 127\n 60\n 69\n 440.0\n 12\n",
                                   "_bad.kbm"), twelve), "not a number")
    # A map can be structurally fine and still belong to another scale: the
    # degree it calls the octave has to BE an octave in this one.
    quarter = B.parse_scala(REPO / "tunings" / "24TET.scl", mapped=True)
    check("a map may call any degree the period",
          B.parse_kbm(kbm(" 0\n" * 12, formal=7), quarter)[1] == 7)

    # A scale that repeats somewhere other than the octave is legal now: the
    # table uses the period the file declares, and the octave controls are
    # rebuilt to match it.
    bp = B.parse_scala(REPO / "tunings" / "BohlenPierce.scl", mapped=True)
    bpmap, formal = B.parse_kbm(REPO / "tunings" / "BohlenPierce.kbm", bp)
    period = bp[formal]
    table = B.tuning_table(bp, 485, 484, 0.0, bpmap, period)
    check("a non-2/1 scale repeats at its own period",
          len(bpmap) == 13 and abs(period - 1901.955) < 0.01
          and table[13] - table[0] == 767, table[13] - table[0])

    # Found on hardware: a tritave build put the bottom eight keys below zero
    # once the octave switch went down, and the DAC clamped them all to one
    # pitch.  The table has to clear one period, not one octave, and a scale
    # with no 2/1 must not be shifted onto the 12-TET grid by the anchor.
    bpcents = B.parse_scala(REPO / "tunings" / "BohlenPierce.scl", mapped=True)
    bpdeg, bpformal = B.parse_kbm(REPO / "tunings" / "BohlenPierce.kbm", bpcents)
    bpperiod = bpcents[bpformal]
    step = int(math.floor(bpperiod * 484 / 1200 + 0.5))
    bptable = B.tuning_table(bpcents, step + 1, 484, 0.0, bpdeg, bpperiod)[:29]
    check("a non-octave table clears its own period at the bottom",
          min(bptable) - step >= 0, min(bptable) - step)
    check("and still fits the DAC two positions up",
          max(bptable) + 2 * step <= 4095, max(bptable) + 2 * step)

    raises("a truncated header is refused",
           lambda: B.parse_kbm(tmp("! t\n 12\n 0\n", "_short.kbm"), twelve),
           "seven header values")


def test_latch_spacing() -> None:
    """The latch's safety gap, over pitches and over transposes.

    The latch calls two notes the same when `table[key] + transpose` lands
    within the match tolerance, so the number that has to clear the tolerance
    is the closest any two DIFFERENT sounding pitches get.  Two readings of
    that were wrong in turn: physically adjacent keys, which a .kbm may
    interleave; and then the untransposed table, which misses a map that is
    comfortably spaced until one of its notes is latched an octave away.
    """
    print("latch key spacing")

    def scale(count: int, span: float = 1200.0) -> "Path":
        body = "".join(f" {span * k / count:.10f}\n" for k in range(1, count + 1))
        return tmp(f"! t\nt\n {count}\n!\n" + body, f"_{count}_{int(span)}.scl")

    def kbm(order: list[int], formal: int | None = None) -> "Path":
        formal = len(order) if formal is None else formal
        return tmp(f"! t\n {len(order)}\n 0\n 127\n 60\n 69\n 440.0\n"
                   f" {formal}\n" + "".join(f" {d}\n" for d in order), "_ord.kbm")

    def spacing(cents, degrees, period, offset=0.0, base=485, per=484):
        table = B.tuning_table(cents, base, per, offset, degrees, period)
        units = int(math.floor(period * per / 1200 + 0.5))
        return B.min_key_spacing(
            [(B.ideal_key_pitches(cents, degrees, period, offset),
              table, period, units)]), table

    cents = B.parse_scala(scale(72), mapped=True)

    # Degrees 0,36,1,37,... : two keys apart on the keyboard is half an octave,
    # but keys 0 and 2 are one 72-TET step - 6 units - from each other.
    order = [d for k in range(36) for d in (k, k + 36)]
    degrees, formal = B.parse_kbm(kbm(order, 72), cents)
    gap, table = spacing(cents, degrees, cents[formal])
    adjacent = min(abs(b - a) for a, b in zip(table[:29], table[1:29]) if b != a)
    check("a reordered map hides the collision from adjacent keys",
          adjacent == 235, adjacent)
    check("the distinct-pitch gap finds it anyway", gap == 6, gap)

    # Comfortably spaced across the keyboard - 13 units at the closest - until
    # key 0 is latched an octave up, where it lands 6 units from key 1.
    sparse = [0, 71] + list(range(2, 56, 2))
    degrees, formal = B.parse_kbm(kbm(sparse, 72), cents)
    # With the anchor the build actually applies: it moves the whole table, and
    # the rounding it leaves behind is part of where the two notes land.
    anchored = B.anchor_offset(cents, 9, degrees, cents[formal])
    gap, table = spacing(cents, degrees, cents[formal], anchored)
    untransposed = min(b - a for a, b in zip(sorted(set(table[:29])),
                                             sorted(set(table[:29]))[1:]))
    check("a sparse map looks safe until it is transposed",
          untransposed == 13, untransposed)
    check("the transpose sweep finds the collision", gap == 6, gap)
    check("and it is the octave that causes it",
          abs(table[0] + 484 - table[1]) == 6, (table[0], table[1]))

    # In order, both readings agree - this is the case that always worked.
    degrees, formal = B.parse_kbm(kbm(list(range(72)), 72), cents)
    check("an in-order map still measures the same gap",
          spacing(cents, degrees, cents[formal])[0] == 6)

    # A map is allowed to sound one pitch from several keys, and a scale is
    # allowed to repeat at the octave.  Both are the same note on purpose and
    # must not read as a gap no tolerance could clear - not even after the
    # rounding that an eight-period transpose can accumulate.
    twelve = B.parse_scala(REPO / "tunings" / "12TET.scl", mapped=True)
    doubled = [d for k in range(12) for d in (k, k)][:12]
    degrees, formal = B.parse_kbm(kbm(doubled, 12), twelve)
    check("keys deliberately doubled up are one note, not a zero gap",
          spacing(twelve, degrees, twelve[formal])[0] == 40)
    check("and a plain octave scale keeps its semitone",
          spacing(twelve, None, 1200.0)[0] == 40)

    # Every tuning the repo ships still clears the default tolerance of 8.
    for name, map_name in (("12TET.scl", None),
                           ("Sabat II (C-rooted).scl", None),
                           ("5-Limit JI with Septimal 7th.scl", None),
                           ("24TET.scl", "24TET-full.kbm"),
                           ("diatonic7.scl", "diatonic7.kbm"),
                           ("BohlenPierce.scl", "BohlenPierce.kbm")):
        shipped = B.parse_scala(REPO / "tunings" / name, mapped=True)
        degrees = period = None
        if map_name:
            degrees, formal = B.parse_kbm(REPO / "tunings" / map_name, shipped)
            period = shipped[formal]
        else:
            period = shipped[12]
        offset = (0.0 if abs(period - 1200.0) > 0.001
                  else B.anchor_offset(shipped, 9, degrees, period))
        units = int(math.floor(period * 484 / 1200 + 0.5))
        gap, _ = spacing(shipped, degrees, period, offset, units + 1)
        check(f"{name} clears the tolerance and the slack",
              gap > 8 + B.TRANSPOSE_SLACK, gap)

    check("no key table at all leaves the factory semitone",
          B.min_key_spacing([]) is None)

    # 53-TET, one degree per key: 9 units between neighbours, which clears a
    # tolerance of 8 on paper.  It does not clear it under the fingers - the
    # transpose the latch compares against moves by a unit between the press
    # that latches a note and the press meant to release it, so the 9 becomes
    # an 8 and the second note clears the first.  The build used to emit this.
    fine = B.parse_scala(scale(53), mapped=True)
    degrees, formal = B.parse_kbm(kbm([], 53), fine)
    gap, table = spacing(fine, degrees, fine[formal],
                         B.anchor_offset(fine, 9, degrees, fine[formal]))
    check("53-TET neighbours are nine units apart", gap == 9, gap)
    check("which the nominal comparison called safe", gap > 8)
    check("and the slack correctly does not", 8 + B.TRANSPOSE_SLACK >= gap)

    # The boundary, both sides of it, at the shipped tolerance of 8.
    for nominal, safe in ((8, False), (9, False), (10, True), (11, True)):
        check(f"a {nominal}-unit gap is {'accepted' if safe else 'refused'}",
              (8 + B.TRANSPOSE_SLACK < nominal) == safe)
    # And the margin is a margin, not a hardcoded 9: at tolerance 7 the same
    # nine-unit tuning is buildable again.
    check("a tolerance of 7 makes the same tuning legal",
          7 + B.TRANSPOSE_SLACK < 9)


def test_table_range() -> None:
    """A key table entry below zero is read back as a huge one.

    The anchor pins one key to its 12-TET pitch.  A map with few degrees per
    period carries the anchor key more than an octave above the bottom, so the
    shift needed to pin it drags the bottom of the table under zero.  The
    entries are halfwords, and the latch match and the pitch ranking both read
    them with LD.UH: -39 comes back as 65497, and they compare a pitch the
    instrument can never sound.
    """
    print("key table range")

    pentatonic = tmp("Minor pentatonic\n5\n300.0\n500.0\n700.0\n1000.0\n1200.0\n",
                     "_pent.scl")
    linear = tmp("! t\n 0\n 0\n 127\n 60\n 69\n 440.0\n 5\n", "_pent.kbm")
    cents = B.parse_scala(pentatonic, mapped=True)
    degrees, formal = B.parse_kbm(linear, cents)
    offset = B.anchor_offset(cents, 9, degrees, cents[formal])
    check("the anchor drags a five-degree map below the bottom",
          round(offset) == -1300, offset)
    table = B.tuning_table(cents, 485, 484, offset, degrees, cents[formal])
    check("which puts a negative entry in the table", table[0] == -39, table[:3])
    check("that the firmware would read as 65497", (table[0] & 0xFFFF) == 65497)
    raises("so the build refuses it",
           lambda: B.check_table_range("_pent.scl", table, 484), "below zero")

    # Between the DAC ceiling and the signed limit there is no fault: the pitch
    # path clamps, so the note is flat rather than wrong.
    B.check_table_range("clamped", [4095] * 29, 0)
    check("a table above the DAC ceiling is left to the clamp", True)
    B.check_table_range("zero", [0] + [40 * k for k in range(1, 29)], 484)
    check("and zero itself is a legal entry", True)

    # Past the signed limit is a different fault, and this test used to assert
    # the opposite: it accepted 60000 on the assumption that anything positive
    # would clamp high.  A pitch is carried through signed 16-bit loads, so
    # 60000 comes back negative and the note drops to the FLOOR.
    raises("an entry past the signed limit is refused",
           lambda: B.check_table_range("wrapped", [0] * 28 + [60000], 0),
           "signed 16-bit")
    check("the limit itself is still legal",
          B.check_table_range("edge", [0] * 28 + [0x7FFF], 0) is None)

    # And the transpose is what makes this more than a check on the table: an
    # entry can sit under the limit and cross it when the octave is stepped up.
    B.check_table_range("headroom", [0] * 28 + [0x7FFF - 6 * 484], 484)
    check("an entry with room for the octave controls passes", True)
    raises("one without it is refused",
           lambda: B.check_table_range(
               "no headroom", [0] * 28 + [0x7FFF - 6 * 484 + 1], 484),
           "octave controls are stepped up")

    # Only the 29 keys that exist: entries 29..31 are emitted because the table
    # is 32 long, and no key can be made to play them.
    B.check_table_range("tail", [40 * k for k in range(29)] + [60000] * 3, 0)
    check("the three unreachable entries are not ranged", True)


def test_tables(cfg: dict) -> None:
    print("generated tables")
    tuning = cfg["tuning"]
    table = B.tuning_table(B.parse_scala(REPO / "tunings" / "12TET.scl"),
                           tuning["base_units"], tuning["units_per_octave"])
    check("tuning table is 32 entries", len(table) == 32)
    check("tuning octaves are exact", all(
        table[k + 12] - table[k] == tuning["units_per_octave"] for k in range(20)))
    check("tuning table ascends", table == sorted(table))

    # The anchor's whole point is that the reference key holds still across
    # slots, so check the shipped scales agree there rather than checking the
    # offsets one at a time.
    reference_key = tuning.get("reference_key", 9)
    anchored = {}
    for name in ("Sabat II (C-rooted)", "5-Limit JI with Septimal 7th", "12TET"):
        cents = B.parse_scala(REPO / "tunings" / f"{name}.scl")
        offset = B.anchor_offset(cents, reference_key)
        anchored[name] = B.tuning_table(cents, tuning["base_units"],
                                        tuning["units_per_octave"], offset)
    entries = {t[reference_key] for t in anchored.values()}
    check("every slot puts the reference key at the same pitch", len(entries) == 1,
          f"{ {n: t[reference_key] for n, t in anchored.items()} }")

    # And that pitch is the 12-TET one, so tuning to it needs no correction.
    equal = anchored["12TET"]
    check("the reference key sits on the 12-TET grid",
          entries == {equal[reference_key]})
    for name, t in anchored.items():
        check(f"{name} stays ascending and octave-exact when anchored",
              t == sorted(t) and all(
                  t[k + 12] - t[k] == tuning["units_per_octave"] for k in range(20)))

    # An anchor of 0 is the old unshifted behaviour, since degree 0 is 0 cents.
    plain12 = B.parse_scala(REPO / "tunings" / "12TET.scl")
    check("anchoring on the bottom key is a no-op",
          B.anchor_offset(plain12, 0) == 0.0 and B.tuning_table(
              plain12, tuning["base_units"], tuning["units_per_octave"], 0.0)
          == B.tuning_table(plain12, tuning["base_units"], tuning["units_per_octave"]))

    raises("out-of-range reference key rejected",
           lambda: B.anchor_offset(plain12, 12), "0..11")
    raises("negative reference key rejected",
           lambda: B.anchor_offset(plain12, -1), "0..11")
    raises("non-integer reference key rejected",
           lambda: B.anchor_offset(plain12, 9.0), "whole number")

    curve = cfg["pressure"]["curve"]
    plain = B.pressure_curve(curve["span"], curve["onset_db"], 0)
    faded = B.pressure_curve(curve["span"], curve["onset_db"], curve.get("onset_fade", 0))
    check("curve is monotonic", faded == sorted(faded))
    check("curve spans 0..span", faded[0] == 0 and faded[-1] == curve["span"])
    fade = curve.get("onset_fade", 0)
    if fade:
        check("fade softens the onset", faded[1] < plain[1] // 4)
        check("fade rejoins the curve", faded[fade:] == plain[fade:])


def test_resolution(cfg: dict) -> None:
    """The fixed-point chain must be the same mapping, finely sampled."""
    print("pressure resolution")
    curve = cfg["pressure"]["curve"]
    calib = cfg["pressure"]["calibration"]
    bits = cfg["pressure"].get("resolution_bits", 4)
    span = curve["span"]
    tab = B.pressure_curve(span, curve["onset_db"], curve.get("onset_fade", 0))
    tab = tab + [tab[-1]]                       # the firmware's sentinel entry
    floor, ceil = calib["floor"], calib["ceiling"]

    def old(avg, lvl):
        n = 0 if avg <= floor else span if avg >= ceil else (avg-floor)*span//(ceil-floor)
        if lvl:
            k = (lvl << 3) + (lvl >> 2)
            n -= ((n - tab[n]) * k + 128) >> 8
        return (4095 * n + span//2) // span

    diffuse = cfg["pressure"].get("error_diffusion", False)
    extra = 4 if diffuse else 0
    scaled = (span << bits) << extra

    def blended(a16, lvl):
        """The normalised, curve-blended value handed to the quantiser."""
        f, c = floor << bits, ceil << bits
        n = 0 if a16 <= f else span << bits if a16 >= c else (a16-f)*span//(ceil-floor)
        i, fr = n >> bits, n & ((1 << bits) - 1)
        cv = (tab[i] << bits) + (tab[i+1] - tab[i]) * fr
        k = (lvl << 3) + (lvl >> 2)
        if diffuse:                       # level 0 gives k = 0, so one path
            return (n << 4) - (((n - cv) * k + 8) >> 4)
        return n - (((n - cv) * k + 128) >> 8) if lvl else n

    def new(a16, lvl):
        n = blended(a16, lvl)
        if not diffuse:
            return (4095 * n + scaled//2) // scaled
        # Hold the input and let the diffuser settle; its mean is the value
        # the instrument actually delivers, which is the point of the change.
        err, total, ticks = 0, 0, 64
        for _ in range(ticks):
            q, err = divmod(4095 * n + err, scaled)
            total += q
        return round(total / ticks)

    ok = True
    for lvl in (0, 15, 31):
        for avg in range(floor, ceil):
            step = abs(old(avg+1, lvl) - old(avg, lvl))
            if abs(new(avg*16, lvl) - old(avg, lvl)) > max(step, 1):
                ok = False
    check("fixed-point chain stays within one old quantisation step", ok)
    check("endpoints preserved",
          new(floor << bits, 31) == 0 and new(ceil << bits, 31) == old(ceil, 31))
    # Only averages the filter can actually produce count: avg16 is
    # (sum << bits) // taps, so an 8-tap mean reaches 1/8-count states, not
    # the full 1/16 grid.  Measuring the grid would overstate the resolution.
    taps = cfg["pressure"].get("smoothing_taps", 8)
    coarse = ceil - floor
    states = range(floor * taps, ceil * taps + 1)
    for lvl in (0, 31):
        reachable = len({new((total << bits) // taps, lvl) for total in states})
        check(f"level {lvl}: reachable codes beat the old {coarse} by 5x",
              reachable > 5 * coarse, f"{reachable}")

    if diffuse:
        # An 8-tap mean of integer counts has exactly this many states; no
        # output stage can distinguish more, so it is the bar to measure against.
        limit = len({(total << bits) // taps for total in states})
        for lvl in (0, 31):
            instant = len({(4095 * blended((t << bits)//taps, lvl) + scaled//2) // scaled
                           for t in states})
            effective = len({new((t << bits)//taps, lvl) for t in states})
            check(f"level {lvl}: diffusion resolves more than the bare quantiser",
                  effective >= instant, f"{effective} vs {instant}")
            check(f"level {lvl}: within the {limit}-state ceiling of an 8-tap mean",
                  effective <= limit, f"{effective}")
        # The error carried between scans cannot run away.
        err, worst = 0, 0
        for t in list(states) + list(reversed(states)):
            _, err = divmod(4095 * blended((t << bits)//taps, 31) + err, scaled)
            worst = max(worst, err)
        check("the error accumulator stays inside one divisor",
              worst < scaled, f"{worst} vs {scaled}")
    check("curve monotone with the sentinel", tab == sorted(tab))


def test_filter_equivalence(cfg: dict) -> None:
    """The ring buffer must produce exactly the shift-and-resum result."""
    print("pressure filter")
    import random
    bits = cfg["pressure"].get("resolution_bits", 4)

    def shifted(taps, count, depth, new):
        count = min(count + 1, depth)
        for i in range(depth - 1, 0, -1):
            taps[i] = taps[i - 1]
        taps[0] = new
        return (sum(taps[:count]) << bits) // count, count

    class Ring:
        def __init__(self, depth):
            self.t = [0] * 24
            self.idx = self.sum = self.count = 0
            self.depth = depth

        def push(self, new):
            # Reject state that cannot be true of a live ring, rather than
            # trusting SRAM that the power-up marker may have let through.
            if self.count == 0 or self.count > self.depth or self.idx >= self.depth:
                self.count = self.idx = self.sum = 0
            if self.count >= self.depth:
                self.sum -= self.t[self.idx]
            else:
                self.count += 1
            self.t[self.idx] = new
            self.sum += new
            self.idx = 0 if self.idx + 1 >= self.depth else self.idx + 1
            return (self.sum << bits) // self.count

    random.seed(11)
    ok = True
    for depth in (8, 16, 24):
        for _ in range(200):
            taps, count, ring = [0] * 24, 0, Ring(depth)
            for value in [random.randint(0, 4000) for _ in range(random.randint(1, 60))]:
                a, count = shifted(taps, count, depth, value)
                if a != ring.push(value):
                    ok = False
    check("ring buffer matches shift-and-resum at every depth", ok)

    ring = Ring(8)
    for value in (900, 880, 870):
        ring.push(value)
    ring.count = 0                       # what the note-on wrapper does
    check("a note-on reset restarts the average cleanly", ring.push(500) == (500 << bits))

    # Garbage that a marker collision or a brownout could leave behind.  The
    # index scales a store off 0x6050, so an out-of-range one is a wild write.
    for name, state in (("index past the ring", {"idx": 0x4000, "count": 3}),
                        ("count past the depth", {"idx": 2, "count": 0x7FFF}),
                        ("both nonsense", {"idx": 0xFFFF, "count": 0xFFFF})):
        ring = Ring(8)
        ring.__dict__.update(state)
        ring.sum = 0x123456
        out = ring.push(700)
        check(f"{name} resets rather than indexing out of bounds",
              out == (700 << bits) and ring.idx == 1 and ring.count == 1)

    source = (REPO / "src" / "AssemblePressureFix.java").read_text()
    cave = source[source.index("begin(0x8001a800L)"):source.index('finish("variable_filter"')]
    check("the firmware validates count and index, not just depth",
          'emit("CP.W R10,R11");' in cave and 'emit("CP.W R11,R12");' in cave)


def test_blend(cfg: dict) -> None:
    """The portamento blend: no doubled stamp, no overflow, no stray slots."""
    print("portamento blend")
    table = {k: 485 + round(k * 484 / 12) for k in range(29)}

    def blend(keys, base_key, latch, stamp, press, thresh=0):
        base = table[base_key]
        measured_from = base
        weight_sum = weighted = 0
        for k in range(28, -1, -1):
            if k not in keys:
                continue
            pitch = table[k]
            anchor = pitch == base
            if latch:
                pitch += stamp.get(k, 0)
            if anchor:
                measured_from = pitch
            z = press.get(k, 0) - (0 if anchor else thresh)
            if z <= 0:
                continue
            z = min(z >> 4, 63)
            w = (z * z * z) >> 3
            weight_sum += w
            weighted += w * pitch
        if weight_sum == 0:
            return 0
        return weighted // weight_sum - measured_from

    # The glide target already carries the stamp, so a lone latched key must
    # publish no offset: otherwise the stamp is applied twice.
    lone = [blend({12}, 12, True, {12: 484}, {12: p}) for p in (100, 500, 900)]
    check("a single latched key publishes no offset", lone == [0, 0, 0], str(lone))

    # Pressure balance must still sweep the pitch between two held keys.
    swept = [blend({12, 19}, 12, False, {}, {12: a, 19: b})
             for a, b in ((900, 100), (500, 500), (100, 900))]
    check("pressure balance still steers the pitch",
          swept[0] == 0 and swept[1] > 100 and swept[2] > 270, str(swept))

    # 32-bit accumulators with every key contributing at maximum weight.
    weight = (63 ** 3) >> 3
    check("accumulators cannot overflow with 29 contributors",
          29 * weight * 4095 < 2 ** 32, f"{29 * weight * 4095:,}")

    # Shifting only as far as overflow safety requires preserves the cubic
    # ratio at light pressure.  The old >>6 quantised z=4 and z=5 to the same
    # weight, making a genuinely stronger second touch inaudible.
    check("light-pressure cubic weights retain useful resolution",
          ((4 ** 3) >> 3, (5 ** 3) >> 3, (6 ** 3) >> 3) == (8, 15, 27))

    # Slots the cache and the stamps do not cover must never be read.  The
    # fixture above cannot say anything about the firmware's loop bound - it
    # was built by this test - so the source is held to it directly: every
    # walk over the key array counts from 0x1c (key 28), and the one 0x20 is
    # the tuning applier's 32-halfword table copy, a different array.  The
    # three bytes past key 28 sometimes hold live state, which is how phantom
    # keys got into the arp once.
    source = (REPO / "src" / "AssemblePressureFix.java").read_text()
    def key_walkers(text: str) -> list[str]:
        # Pin 5's write-one-to-clear mask is 0x20 too, but is not an array
        # bound. Exclude only that exact store pair, not all new uses of 32.
        text = re.sub(
            r'emit\("MOV (R\d+),0x20"\);\s*emit\("ST.W R\d+\[0xd8\],\1"\);',
            "", text)
        # Boot captures all four presets plus the sequence with mask 0x1f.
        # Exempt only that argument/call pair, never a loop using 31 keys.
        text = re.sub(
            r'emit\("MOV R12,0x1f"\);\s*emit\("MCALL PC\[0x8001d5bc\]"\);',
            "", text)
        return sorted(re.findall(r'emit\("MOV R\d+,0x(1[c-f]|2[0-9a-f])"\);', text))
    # The property, not a headcount: adding a legitimate walk should not
    # fail this, but a walk that starts past key 28 must.
    walkers = key_walkers(source)
    # 0x1c is a walk over the keys and 0x20 the tuning applier's 32-halfword
    # table copy - counts over arrays that are not the key array, and only a
    # walk over the KEYS starting past 28 is the bug this guards.  The
    # first-use clear used to need naming here too, at 0x25; it now runs to
    # 0x76 and is out of this range entirely, which is why it is gone.
    stray = [w for w in walkers if w not in ("1c", "20")]
    check("every key walk starts at the last real key",
          not stray and walkers.count("20") == 1,
          f"unexpected loop bounds {stray or walkers}")
    bad_walk = source.replace('emit("MOV R12,0x1c");', 'emit("MOV R12,0x1f");')
    check("capture-mask exception still rejects an oversized key walk",
          bad_walk != source and "1f" in key_walkers(bad_walk))


# The assembler prints a listing line for every instruction it emits - the
# address, the instruction as assembled, and its encoding - under a BLOCK
# header naming the cave.  That listing is the only place a call built with
# String.format() appears in the form it was actually emitted, so the checks
# that have to see *every* instruction read it rather than the Java source.
LISTING_RE = re.compile(r"^([0-9a-f]{8})  (\S.*?)\s+([0-9a-f]{4,})$")


def emitted_blocks(cfg: dict) -> dict[str, list[tuple[int, str]]] | None:
    """Each emitted cave's instructions, or None if no log matches the image.

    build/ accumulates logs from earlier configurations and from the option
    sweep, so the newest one need not describe the firmware sitting next to
    it.  Pairing is established by replaying the log's own PATCH records
    against the built image: if every patch is there byte for byte, log and
    image came out of the same run.
    """
    out = REPO / cfg["firmware"]["output_hex"]
    if not out.exists():
        return None
    flash, _ = B.parse_hex(out)
    for name in ("assemble.js.log", "assemble.log"):
        log = REPO / "build" / name
        if not log.exists():
            continue
        blocks: dict[str, list[tuple[int, str]]] = {}
        current, paired = None, True
        for raw in log.read_text(errors="ignore").splitlines():
            line = re.sub(r"^INFO\s+\S+>\s*", "", raw.rstrip())
            line = re.sub(r"\s*\(GhidraScript\)\s*$", "", line)
            patch = B.PATCH_RE.match(line)
            if patch:
                start = int(patch.group(1), 16)
                if any(flash.get(start + i) != v
                       for i, v in enumerate(bytes.fromhex(patch.group(2)))):
                    paired = False
                    break
                current = None
                continue
            if line.startswith("BLOCK "):
                current = line[6:].strip()
                blocks[current] = []
                continue
            if line.startswith(("EXTENT ", "SKIP ")):
                current = None
                continue
            listed = LISTING_RE.match(line)
            if listed and current is not None:
                blocks[current].append((int(listed.group(1), 16), listed.group(2).strip()))
        if paired and blocks:
            return blocks
    return None


def test_call_pools(cfg: dict) -> None:
    """Every MCALL must name a word that holds a code address, not code.

    MCALL is memory-indirect: `MCALL PC[x]` calls whatever the WORD at x
    says, so pointing it straight at a routine calls that routine's first
    instruction *as an address*.  The clock divider shipped exactly that bug -
    it read back 0xebcd4080, the encoding of its own STM - and neither the
    emulation nor the browser-parity matrix could see it, because the one
    called the cave directly and the other compares two toolchains that were
    both told the same wrong thing.

    The calls are decoded out of the built image.  Reading them out of the
    Java source instead missed every one written through String.format(),
    which is how the clock and gate caves name their pools, and it missed the
    factory calls that read a pool word we repoint - those hang just as hard
    when the word is wrong.
    """
    print("call pools")
    out = REPO / cfg["firmware"]["output_hex"]
    if not out.exists():
        # Nothing to check yet, which is the normal state of a fresh checkout:
        # CI runs the plain suite before anything has built.  Skipping is right
        # here and costs no coverage, because --golden builds first and then
        # comes back through this.
        print("  skip  no built image yet - --golden builds one and re-checks")
        return
    if not (REPO / cfg["firmware"]["factory_hex"]).exists():
        print("  skip  factory image not present")
        return
    flash, _ = B.parse_hex(out)
    factory, _ = B.parse_hex(REPO / cfg["firmware"]["factory_hex"])
    ours = {a for a, v in flash.items() if factory.get(a) != v}

    # Every `MCALL PC[x]` in the application image, as (call, pool).  MCALL is
    # f0 1f <signed word displacement from pc & ~3>.
    calls = []
    for pc in range(0x80002000, 0x80020000, 2):
        if flash.get(pc) != 0xF0 or flash.get(pc + 1) != 0x1F:
            continue
        d = (flash.get(pc + 2, 0) << 8) | flash.get(pc + 3, 0)
        if d & 0x8000:
            d -= 0x10000
        calls.append((pc, (pc & ~3) + d * 4))

    # Ours are the calls we assembled, plus the calls - factory ones included -
    # that read a pool word we wrote.  The rest of the image is Buchla's and
    # was right before we touched it.
    emitted = [(pc, pool) for pc, pool in calls
               if any(a in ours for a in range(pc, pc + 4))]
    mine = [(pc, pool) for pc, pool in calls
            if any(a in ours for a in range(pc, pc + 4))
            or any(a in ours for a in range(pool, pool + 4))]

    def faults(memory: dict[int, int]) -> list[str]:
        word = lambda a: int.from_bytes(bytes(memory.get(a + i, 0) for i in range(4)), "big")
        bad = []
        for pc, pool in mine:
            if pool not in memory:
                bad.append(f"{pc:#x} calls through {pool:#x}, outside the image")
                continue
            value = word(pool)
            # A code address in this part is 0x8000xxxx..0x8002xxxx and even.
            if not (0x80000000 <= value < 0x80020000 and value % 2 == 0):
                bad.append(f"{pc:#x} -> {pool:#x} holds {value:#010x}")
                continue
            # The address must land on emitted code, not erased flash: a cave
            # whose callee's block is off ships an MCALL into 0xff.  The audit
            # found exactly that in a portamento-off build.
            if memory.get(value, 0xFF) == 0xFF:
                bad.append(f"{pc:#x} -> {pool:#x} -> {value:#x}, which is erased flash")
        return bad

    check("the image holds MCALLs of ours to check", bool(mine), "none decoded")
    if not mine:
        return
    check(f"all {len(mine)} MCALLs of ours name a pool word, not code",
          not faults(flash), "; ".join(faults(flash)))

    # Decoding is only as good as its coverage, so hold it against the
    # assembler's own listing: every call it says it emitted has to be one the
    # scan found.
    blocks = emitted_blocks(cfg)
    if blocks is not None:
        listed = sum(1 for body in blocks.values() for _, text in body
                     if text.startswith("MCALL"))
        check("every MCALL the assembler emitted was decoded from the image",
              len(emitted) == listed,
              f"the listing has {listed}, the image scan found {len(emitted)}")

    # A check nobody has watched fail is a check nobody knows works, so plant
    # the failure it exists for: the clock divider's own STM encoding, sitting
    # in a pool word an MCALL reads.
    planted = dict(flash)
    for offset, byte in enumerate((0xEB, 0xCD, 0x40, 0x80)):
        planted[mine[0][1] + offset] = byte
    check("a pool holding instruction bytes is still caught", bool(faults(planted)))


def test_output_interpolation(cfg: dict) -> None:
    """Every pressure target must be reached in a bounded number of DAC ticks."""
    print("pressure output interpolation")
    steps = cfg["pressure"]["output_smoothing"]

    def run(current, target):
        out = []
        remaining = steps
        while remaining:
            gap = target - current
            current += int(gap / remaining)
            remaining -= 1
            if remaining == 0:
                current = target
            out.append(current)
        return out

    for start, target in ((0, 4095), (4095, 0), (137, 3021), (3021, 137)):
        values = run(start, target)
        direction = 1 if target >= start else -1
        check(f"{start}->{target} reaches target in exactly {steps} ticks",
              len(values) == steps and values[-1] == target, str(values))
        check(f"{start}->{target} stays monotonic",
              all(direction * (b - a) >= 0 for a, b in zip([start] + values, values)))


def test_vibrato_pressure_scaling() -> None:
    """The effective vibrato knob must span exactly 50% to 100%."""
    print("vibrato pressure scaling")

    def effective_knob(knob: int, pressure: int) -> int:
        return (knob * (4096 + pressure) + 4096) >> 13

    zero_ok = full_ok = monotonic = True
    for knob in range(4096):
        zero_ok &= effective_knob(knob, 0) == (knob + 1) // 2
        full_ok &= effective_knob(knob, 4095) == knob
        values = [effective_knob(knob, pressure)
                  for pressure in range(0, 4096, 127)]
        values.append(effective_knob(knob, 4095))
        monotonic &= values == sorted(values) and values[-1] <= knob

    check("zero pressure halves the effective knob with rounding", zero_ok)
    check("maximum pressure preserves the original knob exactly", full_ok)
    check("effective knob rises monotonically with pressure", monotonic)


def test_vibrato() -> None:
    """Fractional depth and an interpolated, diffused LFO."""
    print("vibrato")
    SINE = [0, 12, 25, 37, 49, 60, 71, 81, 90, 98, 106, 112, 117, 122, 125, 126,
            127, 126, 125, 122, 117, 112, 106, 98, 90, 81, 71, 60, 49, 37, 25, 12,
            0, -12, -25, -37, -49, -60, -71, -81, -90, -98, -106, -112, -117, -122,
            -125, -126, -127, -126, -125, -122, -117, -112, -106, -98, -90, -81,
            -71, -60, -49, -37, -25, -12]
    SINE = SINE + [SINE[0]]

    def run(knob, scans, interpolate=True, diffuse=True, depth_q4=True):
        target = ((0xD0 if depth_q4 else 0xE) * knob) >> 10
        depth = phase = err = 0
        out = []
        for _ in range(scans):
            gap = target - depth
            step = 16 if depth_q4 else 1
            depth = target if abs(gap) <= step else depth + (step if gap > 0 else -step)
            phase = (phase + (((knob * 0x6B8) >> 10) + 0x148)) & 0xFFFF
            i, fr = phase >> 10, phase & 0x3FF
            v = SINE[i] + (((SINE[i+1] - SINE[i]) * fr) >> 10) if interpolate else SINE[i]
            if diffuse:
                total = v * depth + err
                q = total >> 11                      # floor, so err stays >= 0
                err = total - (q << 11)
                out.append((q, err))
            else:
                out.append(((v * depth) >> 7, 0))
        return out

    full = [q for q, _ in run(1023, 4000)]
    # The old chain truncated a 12.84-unit peak to 12 every cycle; the diffuser
    # alternates 12 and 13 so the mean is the real amplitude.  Depth is
    # therefore unchanged in substance and must not exceed one more unit.
    ideal = 127 * ((0xD0 * 1023) >> 10) / 2048
    peak = max(full)
    check("full-knob peak brackets the true amplitude, not above it",
          peak == math.ceil(ideal) and ideal < 13, f"peak {peak}, ideal {ideal:.2f}")
    check("the carried error stays inside one step",
          all(0 <= e < 2048 for _, e in run(1023, 4000)))

    # 13 whole-unit steps at 16 Q4-units each is the same ~65 ms swell.
    depth, target, scans = 0, (0xD0 * 1023) >> 10, 0
    while depth != target:
        gap = target - depth
        depth = target if abs(gap) <= 16 else depth + 16
        scans += 1
    check("swell still takes ~13 scans", scans == 13, f"{scans}")

    # At shallow depth the old chain collapses the LFO to a few levels.
    shallow = 120
    old = [q for q, _ in run(shallow, 600, interpolate=False, diffuse=False, depth_q4=False)]
    new = [q for q, _ in run(shallow, 600)]
    check("shallow vibrato resolves more than the integer chain",
          len(set(new)) > len(set(old)), f"{len(set(new))} vs {len(set(old))} levels")
    check("shallow vibrato still swings both ways",
          min(new) < 0 < max(new), f"{min(new)}..{max(new)}")

    source = (REPO / "src" / "AssemblePressureFix.java").read_text()
    cave = source[source.index("begin(0x8001a350L)"):source.index('finish("vibrato_engine"')]
    check("the engine interpolates between table entries",
          'emit("LD.SH R8,R0[0x2]");' in cave)
    check("the sine carries a wrap sentinel",
          "halfword(sine[0]);" in source)


def test_poly_midi_lifecycle() -> None:
    """Defaults, edit persistence and the arp switch have separate roles."""
    print("polyphonic MIDI lifecycle")

    nv = {"marker": False, "poly": True}     # record from older firmware

    def boot() -> bool:
        if not nv["marker"]:
            nv["poly"] = False
            nv["marker"] = True
        return nv["poly"]

    live = boot()
    check("the first new-firmware boot migrates only poly MIDI to off", live is False)
    nv["poly"] = live = True                  # edit-mode key 29
    live = boot()
    check("an edit-mode choice survives a power cycle", live is True)
    live_after_arp = live                     # arp switch is read-only here
    check("the arpeggiator switch cannot change poly MIDI", live_after_arp is live)
    nv["poly"] = live = False                 # edit-mode key 29 again
    check("the saved off choice also survives", boot() is False)


def test_local_proximity() -> None:
    """A chord must sample the field beside each held key, not one active key."""
    print("local proximity correction")
    raw = [300] * 29
    touched = {4, 24}
    raw[6] = 900       # strong hand field near the low note
    raw[22] = 420      # much weaker field near the high note

    probe_counts = []

    def estimate(key):
        refs = []
        used = 0
        for direction in (1, -1):
            k = key + 2 * direction
            probes = 3
            while 0 <= k < 29 and k in touched and probes > 1:
                used += 1
                k += direction
                probes -= 1
            if 0 <= k < 29:
                used += 1
                if k not in touched:
                    refs.append(raw[k])
        probe_counts.append(used)
        return max(0, max([0] + refs) - 300)

    low, high = estimate(4), estimate(24)
    check("distant held keys receive independent field estimates",
          low == 600 and high == 120, f"{low}, {high}")
    touched.update(range(29))
    check("a fully occupied region falls back to zero correction", estimate(14) == 0)
    check("reference search is bounded to three probes per side",
          max(probe_counts) <= 6, str(probe_counts))


def test_held_flag_bounds() -> None:
    """Nothing may walk the held-flag array past key 28.

    The array is 29 entries; the factory's own selectors start their walk at
    0x1c.  Reading further treats unrelated state as held keys, and the arp's
    random branch plays whatever it is handed without re-checking — which is
    how a phantom key 29..31 reached the DAC as a pitch an octave up.
    """
    print("held-flag array bounds")
    source = (REPO / "src" / "AssemblePressureFix.java").read_text()

    def cave(start: str, name: str) -> str:
        head = source.index(f"begin({start})")
        return source[head:source.index(f'finish("{name}"', head)]

    selector = cave("0x8001a020L", "arp_order_selector")
    scan = re.findall(r'emit\("CP\.W R3,0x([0-9a-f]+)"\)', selector)
    check("the arp candidate scan stops at key 28", scan and scan[0] == "1d",
          f"scans 0..0x{scan[0] if scan else '?'}")

    # The latch exit copies the touch flags over the held flags rather than
    # zeroing them, so a key still under a finger keeps playing; the bound is
    # what matters here either way, and reading past 28 would hand the
    # selector three bytes of unrelated state as held keys.
    housekeeping = cave("0x8001a480L", "scan_housekeeping")
    walk = re.search(r'emit\("MOV R9,0x([0-9a-f]+)"\);\s*\n\s*padTo\(0x8001a4faL\)',
                     housekeeping)
    check("the latch-exit walk stops at key 28",
          walk is not None and walk.group(1) == "1c",
          f"walks 0..0x{walk.group(1) if walk else '?'}")
    check("the latch exit keeps physically-held keys",
          'emit("LD.UB R11,R12[0x239]");' in housekeeping
          and 'emit("ST.B R12[0x21b],R11");' in housekeeping)

    # The press-order path is bounded by the list's own length rather than a
    # key count, but every candidate it returns is re-checked against the held
    # flags — that re-check is what kept knob 1 at zero free of this bug.
    check("the press-order path re-checks the held flag",
          'emit("LD.UB R9,R9[0x0]");' in selector
          and 'emit("CP.W R9,0x1");' in selector)


def test_factory_entry_points(cfg: dict) -> None:
    """Burying a live factory branch target must fail the build."""
    print("factory entry points")
    sha = cfg["firmware"]["factory_sha256"]
    transfers = [(int(a, 16), int(b, 16)) for a, b in
                 (l.split() for l in B.CONTROL_FLOW.read_text().splitlines()
                  if re.match(r"^[0-9a-f]{8} [0-9a-f]{8}$", l))]
    # A target whose branch comes from far enough away to be outside any patch
    # we could plausibly build around it.
    source, target = next((s, t) for s, t in transfers if abs(t - s) > 0x200)

    raises("a patch burying a live branch target is rejected",
           lambda: B.check_factory_entry_points(
               [(target - 4, b"\0" * 16, "synthetic")], sha),
           "live factory branch target")
    # The same target as the patch's first byte is the legitimate case: callers
    # are meant to keep arriving there.
    B.check_factory_entry_points([(target, b"\0" * 16, "synthetic")], sha)
    check("a patch starting exactly on the target is allowed", True)
    raises("a control-flow table from another image is rejected",
           lambda: B.check_factory_entry_points([], "0" * 64),
           "different base image")
    recorded = next(l.split()[1] for l in B.CONTROL_FLOW.read_text().splitlines()
                    if l.startswith("factory_sha256 "))
    check("the recorded table matches the pinned factory image", recorded == sha)


def test_migration_and_empty_hand() -> None:
    """Two contracts that are invisible in behaviour but bite at the edges."""
    print("register contracts")
    source = (REPO / "src" / "AssemblePressureFix.java").read_text()

    def cave(start: str, name: str) -> str:
        head = source.index(f"begin({start})")
        return source[head:source.index(f'finish("{name}"', head)]

    migration = cave("0x8001aca4L", "poly_settings_migration")
    check("the migration returns the loader's value, not the saver's",
          'emit("ST.W --SP,R12");' in migration
          and 'emit("LD.W R12,SP++");' in migration)
    check("it restores on both the migrating and the already-migrated path",
          migration.index('emit("LD.W R12,SP++");') > migration.index("padTo(0x8001ace0L)"))

    cache = cave("0x8001aa10L", "pressure_cache")
    zero = cache.index('emit("MOV R12,0x0");')
    check("an empty hand returns zero pressure explicitly",
          zero < cache.index('emit("CP.W R2,0x0");'))

    # state+0x2 is the factory's remote-enable flag: it gates the MIDI command
    # handler and two commands write it.  The tuning slot must not share it,
    # or picking a tuning enables remote control and a remote-enable message
    # retunes the instrument.
    for start, name in (("0x80003d82L", "edit_key27_tuning_slot1"),
                        ("0x80003db8L", "edit_key28_tuning_slot0"),
                        ("0x80019a40L", "tuning_applier_tables")):
        body = cave(start, name)
        check(f"{name} keeps the tuning slot off state+0x2",
              'emit("MOV R9,0x6090");' in body
              and not re.search(r'emit\("(LD|ST)\.\w+ R\d+,?R?\d*\[0x2\]', body))

    # Knob 4 sets the pressure curve level, and it does so from wherever the
    # knob physically is - mode 0 is "no pads held".  Removing this once made
    # the response linear for every instrument, which is only what one already
    # sitting at level 0 had; everyone else lost a curve with no control left
    # to get it back.  It is not a setting anyone chose, so nothing recorded
    # that it had gone.
    knob4 = cave("0x80014380L", "knob4_curve")
    check("knob4_curve reads the knob and writes the curve level",
          'emit("LD.UH R9,R10[0x310]");' in knob4
          and 'emit("MUL R9,R9,R11");' in knob4
          and 'emit("LSR R9,0xa");' in knob4
          and 'emit("ST.B R10[0x2db],R9");' in knob4)

    import options as _options
    with open(REPO / "config" / "218e.toml", "rb") as fh:
        cfg = _options.expand(tomllib.load(fh).get("options", {}))

    # Knob 1 runs reversed - clockwise lowers the multiplier, the way the
    # owner plays it - and the default is meant to sit near the middle of the
    # travel.  A knob whose useful settings are all in the first eighth of its
    # travel is the thing this replaced, so the arithmetic is checked rather
    # than trusted.
    calib = cfg["pressure"]["calibration"]
    curve = cfg["pressure"]["curve"]
    if calib.get("trim_mode") == "scale":
        k_min = int(round(calib.get("trim_min", 0.70) * 256))
        k_max = min(0x180, (0x3FF * 256) // calib["ceiling"])
        at = 10 - (256 - k_min) * 10 / (k_max - k_min)
        check("the calibration default sits near the middle of knob 1",
              3.5 <= at <= 6.0, f"{at:.1f} of 10")
        check("the trim still reaches the feet-up 0.70x case",
              k_min <= int(0.70 * 256), f"{k_min/256:.2f}x")
        check("the scaled ceiling cannot pass the 1023 the path rejects",
              calib["ceiling"] * k_max // 256 <= 1023)
    steps = curve.get("knob_max_level", 31) + 1
    # The default is 0 - curve off until the knob raises it - so it sits at
    # the bottom of the travel rather than the middle; what has to hold is
    # that the knob can reach it and every level above it.
    check("knob 4 reaches the configured default level",
          0 <= curve.get("default_level", 31) <= curve.get("knob_max_level", 31),
          f"default {curve.get('default_level')}")
    check("the knob-4 pool word reaches it",
          'wordPatch("knob4_pool", 0x800043d0L, 0x80014380L' in source)
    check("the bootstrap does not force the curve level back to 0",
          'emit("ST.B R10[0x2db],R11");' not in source)

    # Pads 2+3 with knob 1 is the factory key-threshold adjustment - the touch
    # sensitivity a note triggers at, which the manual says not to change often
    # and which is nothing to do with pressure.  Our wrapper owns that knob to
    # trim the pressure calibration, so it has to hand the pad combination
    # straight back: internal mode 6 for knob 1, mode 3 for knob 3, each
    # calling the handler the factory pointed at.  Lose this and a setting
    # people are told to leave alone becomes one they cannot reach.
    for start, name, mode, factory in (
            ("0x800194c0L", "knob1_pressure_ceiling", "0x6", "0x80004188"),
            ("0x80014300L", "knob3_pressure_floor",   "0x3", "0x800040c8")):
        body = cave(start, name)
        check(f"{name} hands mode {mode} back to the factory handler",
              f'emit("CP.W R8,{mode}");' in body
              and 'emit("MCALL PC[' in body
              and factory.lower() in body.lower())

    # The low scratch is cleared with whatever R8 holds, and the latch section
    # loads the switch position into it.  Clearing after that would seed the
    # pulse flag with a 1 and fire a trigger at power-up.
    init = cave("0x8001ab60L", "first_use_initializer")
    check("the low scratch is cleared while R8 is still zero",
          init.index('emit("MOV R9,0x60e4");') < init.index('emit("LD.UB R8,R10[0x340]");'))


def test_overlap_and_range() -> None:
    print("patch safety")
    memory = {a: 0 for a in range(0x1000, 0x2000)}
    raises("overlapping patches rejected (same description)",
           lambda: B.apply_patches(dict(memory), [
               (0x1000, b"\x01\x02", "dup"), (0x1001, b"\x03", "dup")]), "overlap")
    raises("patch outside the image rejected",
           lambda: B.apply_patches(dict(memory), [(0x9000, b"\x01", "stray")]), "outside")
    changed, added = B.apply_patches(dict(memory), [(0x1000, b"\x01", "a"), (0x1002, b"\x00", "b")])
    check("non-overlapping patches apply", (changed, added) == (1, 0), f"{changed},{added}")


def test_fold_measurement() -> None:
    """A reading's extrapolated tail follows the highest key it measured -
    and only when that tail actually holds that key's correction."""
    print("fold_measurement")

    def table() -> Path:
        rows = ["Semitone;Note;Key;Offset_Cents;Source"]
        for s in range(B.PITCH_TABLE_ENTRIES):
            source = "octave" if s < 3 else "measured" if s <= 67 else "extrapolated"
            rows.append(f"{s};X;;{s * 2.0:.6f};{source}")
        return tmp("\n".join(rows) + "\n", "_fold.csv")

    def fold(readings: dict[int, float], column: str = "Semitone",
             cfg: dict | None = None) -> tuple[dict[int, float], dict[int, float]]:
        cal = table()
        before = B.read_calibration(cal)
        meas = tmp(f"{column},Measured_Cents\n"
                   + "".join(f"{s},{c}\n" for s, c in readings.items()), "_meas.csv")
        B.fold_measurement(cfg or {}, cal, meas)
        return before, B.read_calibration(cal)

    def moved(before, after) -> list[int]:
        return [s for s in before if abs(after[s] - before[s]) > 1e-9]

    # A sweep of the lower keys leaves the measured rows above it alone, and
    # the extrapolated tail beyond THOSE: it holds row 67's correction, not
    # row 31's.  The defect dragged rows 68..78 along with a reading that
    # never went above 31.
    before, after = fold({s: 5.0 for s in range(3, 32)})
    check("partial sweep moves only the keys it measured",
          moved(before, after) == list(range(3, 32)), str(moved(before, after)))
    check("tail keeps its step above the last measured row",
          abs((after[68] - after[67]) - (before[68] - before[67])) < 1e-9)

    # A reading of the last measured row is what the tail follows.
    before, after = fold({67: 5.0})
    check("tail follows the highest measured row",
          moved(before, after) == list(range(67, B.PITCH_TABLE_ENTRIES)), str(moved(before, after)))
    delta = after[67] - before[67]
    check("tail follows by the same delta",
          all(abs((after[s] - before[s]) - delta) < 1e-9 for s in range(68, B.PITCH_TABLE_ENTRIES)))

    # A reading named by key lands where the build puts that key: three
    # semitones up with the offset, on the 0 V pitch for a 208c build.
    before, after = fold({1: 5.0}, "Key")
    check("key 1 is semitone 3 with the offset", moved(before, after) == [3], str(moved(before, after)))
    before, after = fold({1: 5.0}, "Key", {"pitch": {"bottom_key_semitone": 0}})
    check("key 1 is semitone 0 without it", moved(before, after) == [0], str(moved(before, after)))
    before, after = fold({4: 5.0}, "Semitones", {"pitch": {"bottom_key_semitone": 0}})
    check("Semitones follows the same layout", moved(before, after) == [4], str(moved(before, after)))

    # A single key below the top does not reach the tail either.
    before, after = fold({60: 5.0})
    check("a single lower reading leaves the tail alone", moved(before, after) == [60],
          str(moved(before, after)))


def test_latency_report_clears() -> None:
    """A run of cleared frames is ONE clear, and separate runs are several.

    The validator kept zero frames so an explicit reset could not hide, then
    counted every zero ROW as its own clear.  A cell stays zero until a valid
    sample arrives, so an honest internal-to-external hand-off read twice
    before the first measurement was condemned as contaminated, and
    --external-start could not rescue it: it skipped one zero row, not the
    run.  Both directions are checked here, because the fix must not soften
    the rejection the kept frames exist for.
    """
    print("latency report")
    import subprocess as sp
    import tempfile

    def run(rows, *flags):
        with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False) as fh:
            fh.write("scan_component_a,scan_component_b\n")
            for a, b in rows:
                fh.write(f"{a},{b}\n")
            name = fh.name
        out = sp.run([sys.executable, str(REPO / "tools" / "clock_latency_report.py"),
                      name, *flags], capture_output=True, text=True)
        Path(name).unlink()
        return out.returncode, out.stdout + out.stderr

    hand_off = [(10000, 15000), (0, 0), (0, 0), (1000, 7500), (1200, 7700)]
    code, out = run(hand_off)
    check("a clear read twice is one clear", code == 0 and "CONTAMINATED" not in out,
          out.strip().splitlines()[-1] if out.strip() else "no output")
    check("and the run is skipped, not one row of it",
          "measured population        2" in out)
    code, out = run(hand_off, "--external-start", "2")
    check("--external-start on the clear reaches the measurements",
          code == 0 and "CONTAMINATED" not in out)
    code, out = run([(10000, 15000), (0, 0), (0, 0), (1000, 7500), (0, 0),
                     (1200, 7700)])
    check("two separated runs are still two clears",
          code != 0 and "2 clears" in out)
    code, out = run([(1000, 12000), (0, 0), (900, 13000)], "--internal")
    check("an explicit reset under an internal run is still refused",
          code != 0 and "reset" in out)


def test_flashers_expect_the_golden(cfg: dict) -> None:
    """The shipped flashers must name the DEFAULT image, not a test build.

    Every ordinary build rewrites the flashers with its own image's hash, so
    a hand-run build of a sequencer or persistence config silently repoints
    the released flashers at that config.  That reached a commit: they were
    left expecting a persistence audit build while golden_sha256 named the
    default one, and the only symptom was the web bundle going stale,
    because generated.js embeds the flasher scripts.

    The regression harnesses already refuse to run a build that could rewrite
    the flashers.  This catches the hand-run case, which is how it happened.
    """
    print("flashers")
    golden = cfg["firmware"].get("golden_sha256", "")
    if not golden:
        check("a golden to check against", False, "golden_sha256 is not set")
        return
    for name, pattern in (("mac/Program218e_v3_Rewired_macOS.command",
                           r'^EXPECTED_SHA256="([0-9a-f]{64})"'),
                          ("windows/218e_Rewired_Flasher.bat",
                           r'^SET "EXPECTED_SHA256=([0-9a-f]{64})"')):
        path = REPO / name
        if not path.exists():
            continue
        found = re.search(pattern, path.read_text(), re.M)
        got = found.group(1) if found else "(no EXPECTED_SHA256 line)"
        check(f"{name} expects the golden image", got == golden,
              f"it expects {got[:8]}, golden is {golden[:8]} - "
              f"rebuild the DEFAULT config to put it back")


def test_pool_fallthrough() -> None:
    """A literal pool must not sit where code can fall into it.

    `padTo` fills with NOPs, so a `word(...)` placed after code that does not
    end in an unconditional transfer is reached by falling through the
    padding - and the address literal is then executed as instructions.  That
    shipped in the clock_gate hook: the allowed branch fell straight out of
    the replaced code and into its own pool word.  Nothing caught it, because
    emulation calls caves directly and the parity matrix compares two
    toolchains that were both told the same wrong thing.
    """
    print("pool fall-through")
    source = (REPO / "src" / "AssemblePressureFix.java").read_text()
    ENDS = ("RJMP", "BR{al}", "MOV PC,", "LDM SP++", "RET")
    bad = []
    for block in re.split(r"\n\s*begin\(", source)[1:]:
        block = block.split("finish(")[0]
        name = "?"
        m = re.search(r'finish\("([^"]+)"', source[source.index(block) + len(block):
                                                   source.index(block) + len(block) + 200])
        if m:
            name = m.group(1)
        last = None
        for line in block.split("\n"):
            mw = re.search(r"^\s*word\(", line)
            # String.format() emits count too.  Matching only the literal
            # form left every computed branch invisible to this check - which
            # is most of the clock caves, where the labels are variables.
            me = re.search(r'emit\((?:String\.format\()?"([^"]+)"', line)
            if me:
                last = me.group(1)
            elif mw and last is not None:
                if not last.startswith(ENDS):
                    bad.append(f"{name}: word() after {last!r}")
                last = None          # report the first word of a pool only
    check("no literal pool is reachable by falling through padding",
          not bad, "; ".join(bad))


def test_leaf_with_call(cfg: dict) -> None:
    """A routine returning `MOV PC,LR` has not saved LR, so it cannot call.

    MCALL writes LR, which turns such a return into a jump to itself.  That
    shipped once in pulse_defer_set and hung the running instrument: the
    panel died while USB kept enumerating, because the hang was in the main
    loop and USB is interrupt-driven.

    Read from the emitted listing rather than the Java source, which was
    blind twice over: a call written through String.format() is not there to
    find, and a cave routinely holds several routines, so asking whether
    *some* line in the cave saved LR let a leaf routine pass on a save made
    by the routine next to it.  Here each routine is taken on its own and the
    save has to come before the call.
    """
    print("leaf with call")
    blocks = emitted_blocks(cfg)
    if blocks is None:
        print("  skip  no assembler listing matching the built image - "
              "--golden builds one and re-checks")
        return

    def bare(text: str) -> str:
        """The instruction without its condition, so `MOV{eq} PC,LR` is seen."""
        return re.sub(r"\{[a-z]+\}", "", text)

    def ends_routine(text: str) -> bool:
        """Does control leave here for good, whatever the flags say?

        Only unconditional transfers end a routine.  A conditional one falls
        through when its condition fails, and cutting there would hide the
        prologue from everything below it.
        """
        if "{" in text:
            return False
        plain = bare(text)
        return (plain.startswith(("MOV PC,", "RET"))
                or re.match(r"LDM SP\+\+,.*\bPC\b", plain) is not None)

    def faults(listing: dict[str, list[tuple[int, str]]]) -> list[str]:
        bad = []
        for name, body in listing.items():
            routine: list[tuple[int, str]] = []
            for address, text in body:
                routine.append((address, text))
                if not ends_routine(text):
                    continue
                calls = [a for a, t in routine if bare(t).startswith(("MCALL", "RCALL"))]
                saves = [a for a, t in routine
                         if t.startswith("STM --SP") and re.search(r"\bLR\b", t)]
                if (bare(text).startswith("MOV PC,LR") and calls
                        and not (saves and min(saves) < min(calls))):
                    bad.append(f"{name}: the routine at {routine[0][0]:#x} returns "
                               f"MOV PC,LR yet calls at {min(calls):#x} with LR unsaved")
                routine = []
        return bad

    check(f"no routine in {len(blocks)} emitted caves calls with LR unsaved",
          not faults(blocks), "; ".join(faults(blocks)))

    # Plant the hang this exists to catch, and the safe shape beside it, so a
    # future rewrite cannot quietly stop looking.
    leaf = [(0x80010000, "SUB R8,1"),
            (0x80010002, "MCALL PC[0x80010010]"),
            (0x80010006, "MOV PC,LR")]
    check("a leaf routine that calls is still caught", bool(faults({"planted": leaf})))
    check("a routine that saves LR first is not",
          not faults({"planted": [(0x8000fffe, "STM --SP,R7,LR")] + leaf}))


def test_option_messages() -> None:
    """Wrong options answer with a sentence, and advertised ones are taken."""
    print("option messages")
    import options as _options
    raises("a bare-type option refuses with a sentence",
           lambda: _options.check({"knob1": 1}), "knob1 must be str")
    raises("pitch_offset takes only true or false",
           lambda: _options.check({"pitch_offset": "208c"}), "pitch_offset must be true or false")
    check("pitch_offset = false puts the bottom key on the 0 V pitch",
          _options.expand({"pitch_offset": False})["pitch"]["bottom_key_semitone"] == 0)
    check("leaving pitch_offset out keeps the three semitones",
          _options.expand({})["pitch"]["bottom_key_semitone"] == 3)
    check("arp_patterns = true is the default bank",
          _options.expand({"arp_patterns": True})["knob2"] == _options.expand({})["knob2"])
    slots = _options.expand({"alternate_tunings":
                             ["tunings/12TET.scl", "factory", "tunings/12TET.scl"]})["tuning"]["slots"]
    check("'factory' is accepted as a middle slot", slots[1] == "factory", str(slots))


def test_persist_required() -> None:
    """persist = false is not a configuration; it is a diagnostic.

    A volatile image restores a runtime that never reloads its committed
    musical data, and a warm reset that finds the initialisation marker
    already matching comes back in whatever mode it left - PLAY included,
    where seq_noteon_mute eats every key and the keyboard reads as dead.  So
    the option is not a default any more: the only way to build one is to ask
    for the unsupported image by name, which the parity sweep and the control
    and persistence regressions do and nothing that ships does.
    """
    print("persistence is mandatory")
    import options as _options
    saved = os.environ.pop(_options.VOLATILE_ENV, None)
    try:
        raises("persist = false is refused",
               lambda: _options.expand({"persist": False}),
               "not a supported configuration")
        check("persist = true is accepted", _options.expand({"persist": True})["persist"]["on"])
        check("leaving it out is accepted, and persistent",
              _options.expand({})["persist"]["on"])
        with open(REPO / "config" / "218e.toml", "rb") as fh:
            shipped = tomllib.load(fh).get("options", {})
        check("the shipped config is persistent", _options.expand(shipped)["persist"]["on"])
        os.environ[_options.VOLATILE_ENV] = "1"
        check("a fixture that asks for the unsupported image still gets one",
              not _options.expand({"persist": False})["persist"]["on"])
    finally:
        os.environ.pop(_options.VOLATILE_ENV, None)
        if saved is not None:
            os.environ[_options.VOLATILE_ENV] = saved


def test_atomic_replace() -> None:
    """The atomic replace must preserve the file mode: the updater is executable."""
    print("file replacement")
    import os
    import stat as stat_module
    path = REPO / "build" / "_test_mode.sh"
    path.write_text("#!/bin/sh\n")
    os.chmod(path, 0o755)
    B.replace_atomically(path, "#!/bin/sh\n# rewritten\n")
    mode = stat_module.S_IMODE(path.stat().st_mode)
    check("replacement keeps the executable bit", mode == 0o755, oct(mode))
    check("replacement writes the new content", "rewritten" in path.read_text())
    updater = REPO / "mac/Program218e_v3_Rewired_macOS.command"
    if updater.exists():
        mode = stat_module.S_IMODE(updater.stat().st_mode)
        check("the shipped updater is executable", bool(mode & 0o111), oct(mode))


def test_generated_is_current() -> None:
    """The page carries the flashers inside generated.js.

    Editing a flasher without regenerating leaves the page handing out the
    previous version, and nothing about the edit says so.  CI catches it, but
    by then it has been pushed.
    """
    print("web bundle")
    # Both outputs: generate.py rewrites generated.js AND assembler.js, and
    # only the first was compared - so an encoder or runtime edit left a
    # stale assembler.js shipping while this check stayed green.
    outputs = [REPO / "web" / "generated.js", REPO / "web" / "assembler.js"]
    if not all(p.exists() for p in outputs):
        print("  skip  web bundle is not built")
        return
    before = {p: p.read_bytes() for p in outputs}
    r = subprocess.run([sys.executable, "web/generate.py"],
                       capture_output=True, text=True, cwd=REPO)
    if r.returncode != 0:
        check("generate.py runs", False, r.stderr.strip()[:200])
        return
    for p in outputs:
        after = p.read_bytes()
        check(f"{p.name} is current", after == before[p],
              f"it was stale - regenerated, commit web/{p.name}")


def test_hex_roundtrip(cfg: dict) -> None:
    print("hex handling")
    factory = REPO / cfg["firmware"]["factory_hex"]
    # Buchla's image is not ours to redistribute, so a checkout does not have
    # one.  Skip the checks that need it rather than failing: everything else
    # in the suite still runs, which is what makes it useful on a fork and in
    # CI.  --golden is where its absence is meant to be felt.
    if not factory.exists():
        print(f"  skip  factory image checks - {cfg['firmware']['factory_hex']} not present")
        raises("bad checksum rejected",
               lambda: B.parse_hex_text(":020000040000FA\n:0400000012345678FF\n", "bad"),
               "checksum")
        return
    memory, start = B.parse_hex(factory)
    rendered = B.render_hex(memory, start)
    again, again_start = B.parse_hex_text(rendered, "rendered")
    check("factory image round-trips", again == memory and again_start == start)
    raises("bad checksum rejected",
           lambda: B.parse_hex_text(":020000040000FA\n:0400000012345678FF\n", "bad"), "checksum")


def test_golden(cfg: dict) -> None:
    print("golden build")
    expected = cfg["firmware"].get("golden_sha256")
    if not expected:
        check("golden_sha256 recorded in config", False, "missing")
        return
    if not (REPO / cfg["firmware"]["factory_hex"]).exists():
        check("factory image present for the golden build", False,
              f"{cfg['firmware']['factory_hex']} not present - supply your own copy")
        return
    # --no-ghidra: the JavaScript toolchain reproduces the same image, and
    # requiring Ghidra made the one check that guards a release unrunnable
    # anywhere headless.  sweep.py is what proves the two agree.
    result = subprocess.run(
        [sys.executable, str(REPO / "tools" / "build.py"), "--no-ghidra",
         "--expect-sha", expected],
        capture_output=True, text=True)
    check("build reproduces the golden image", result.returncode == 0,
          result.stdout.strip().splitlines()[-1] if result.stdout else result.stderr.strip())


JSC = Path("/System/Library/Frameworks/JavaScriptCore.framework"
           "/Versions/A/Helpers/jsc")

PATTERN_PROBE = """
var hex = readFile('firmware/218eV3_v369_DFU.hex');
[1, 21, 22, 23, 24, 31, 32, 33].forEach(function (n) {
    var pats = [];
    for (var i = 0; i < n; i++) pats.push('x...');
    try {
        print(n + ' ' + WEBBUILD.build(
            { knob2: 'patterns', arp_patterns: pats }, hex).sha256);
    } catch (e) { print(n + ' REFUSED ' + e.message); }
});
"""


def test_pattern_bank_capacity() -> None:
    """Every pattern count the editor offers has table space to land in.

    The bank was cut for 22 masks while the config, the validator and the
    editor all allowed 32, so a bank a user could assemble in the page stopped
    the build dead on the 23rd pattern - a valid configuration that could not
    be built.  Driven through the browser builder because that is the one with
    the 32-pattern editor in front of it, and it is quick: no Ghidra, no CLI
    build per count.
    """
    print("pattern bank capacity")
    if not JSC.exists():
        print("  skip  no jsc to run the browser builder")
        return
    if not (REPO / "firmware" / "218eV3_v369_DFU.hex").exists():
        # Buchla's image is not in the repository; every other test that
        # needs it says so and steps aside, and this one read it blind.
        print("  skip  factory image not present")
        return
    probe = REPO / "build" / "_patterns_probe.js"
    probe.parent.mkdir(exist_ok=True)
    probe.write_text(PATTERN_PROBE)
    try:
        r = subprocess.run(
            [str(JSC), "web/generated.js", "web/sha256.js", "web/buildlib.js",
             "tools/avr32/encoder.js", "tools/avr32/runtime.js",
             "tools/avr32/program.js", "web/build.js", str(probe)],
            capture_output=True, text=True, cwd=REPO)
    finally:
        probe.unlink(missing_ok=True)
    built = {}
    refused = {}
    for line in (r.stdout + r.stderr).splitlines():
        parts = line.split(None, 2)
        if len(parts) < 2 or not parts[0].isdigit():
            continue
        if parts[1] == "REFUSED":
            refused[int(parts[0])] = parts[2] if len(parts) > 2 else ""
        else:
            built[int(parts[0])] = parts[1]
    for n in (1, 21, 22, 23, 24, 31, 32):
        check(f"{n} patterns build", n in built,
              refused.get(n, "no result — " + (r.stdout + r.stderr).strip()[:120]))
    # Distinct images, or a count could be silently ignored and still "build".
    check("each count produces its own image",
          len(set(built.values())) == len(built), sorted(built))
    # And the limit is still a limit, refused where the config says it is
    # rather than wherever the assembler happens to run out of table.
    check("33 patterns are refused", 33 in refused, built.get(33))
    check("and refused by the count rule, not by the table running out",
          "one to 32 patterns" in refused.get(33, ""), refused.get(33))


def test_corpus_current() -> None:
    """The encoder corpus must match the assembler it vouches for.

    The corpus is Ghidra's answer sheet for the JS encoder, and it can only
    fail on entries it has: an assembler edit without a regeneration leaves
    the test passing while covering the previous program.  That happened -
    two functional changes shipped against an answer sheet from before them.
    The corpus carries the sha256 of the program.js it was extracted from,
    and this refuses a mismatch.
    """
    print("encoder corpus")
    corpus_path = REPO / "tools" / "avr32" / "corpus.json"
    if not corpus_path.exists():
        check("corpus present", False, "tools/avr32/corpus.json is missing")
        return
    corpus = json.loads(corpus_path.read_text())
    stamp = corpus.get("program_sha256")
    current = hashlib.sha256(
        (REPO / "tools" / "avr32" / "program.js").read_bytes()).hexdigest()
    check("corpus matches the current assembler", stamp == current,
          "program.js changed since the corpus was extracted - regenerate "
          "with tools/avr32/make_corpus.py (needs Ghidra)")


def test_encoder_refusals() -> None:
    """The JS encoder must refuse what its fields cannot hold; see
    tools/avr32/test_encoder.js for the cases and why each one is there."""
    print("encoder refusals")
    jsc = Path("/System/Library/Frameworks/JavaScriptCore.framework/Versions/Current/Helpers/jsc")
    if not jsc.exists():
        print("  skip  jsc is not on this machine")
        return
    result = subprocess.run(
        [str(jsc), "tools/avr32/encoder.js", "tools/avr32/test_encoder.js"],
        cwd=REPO, text=True, capture_output=True)
    check("out-of-field immediates are refused, in-field ones encode",
          result.returncode == 0 and "encoder checks pass" in result.stdout,
          (result.stdout + result.stderr).strip().splitlines()[-1:] and
          (result.stdout + result.stderr).strip().splitlines()[-1] or "no output")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--golden", action="store_true",
                        help="also build and compare against [firmware].golden_sha256")
    args = parser.parse_args()

    # The config holds the seven user options; the generators and validators
    # below work on the expanded internal settings, same as build.py.
    import options
    raw = tomllib.loads((REPO / "config" / "218e.toml").read_text())
    cfg = options.expand(raw.get("options", {}))
    cfg["firmware"] = raw["firmware"]
    if "tools" in raw:
        cfg["tools"] = raw["tools"]
    test_pitch_table(cfg)
    test_scala()
    test_keyboard_maps()
    test_latch_spacing()
    test_table_range()
    test_tables(cfg)
    test_resolution(cfg)
    test_blend(cfg)
    test_output_interpolation(cfg)
    test_vibrato_pressure_scaling()
    test_vibrato()
    test_poly_midi_lifecycle()
    test_local_proximity()
    test_held_flag_bounds()
    test_factory_entry_points(cfg)
    test_migration_and_empty_hand()
    test_filter_equivalence(cfg)
    test_overlap_and_range()
    test_flashers_expect_the_golden(cfg)
    test_latency_report_clears()
    test_fold_measurement()
    test_pool_fallthrough()
    test_persist_required()
    test_option_messages()
    test_atomic_replace()
    test_generated_is_current()
    test_corpus_current()
    test_encoder_refusals()
    test_pattern_bank_capacity()
    test_hex_roundtrip(cfg)
    if args.golden:
        test_golden(cfg)
    # After the golden build, so there is an image - and an assembler
    # listing beside it - to read on a clean tree.
    test_call_pools(cfg)
    test_leaf_with_call(cfg)

    print()
    if FAILURES:
        print(f"{len(FAILURES)} failure(s): {', '.join(FAILURES)}")
        raise SystemExit(1)
    print("all checks passed")


if __name__ == "__main__":
    main()
