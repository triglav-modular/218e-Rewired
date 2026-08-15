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
import math
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

    header = "Semitone;Note;Key;Offset_Cents;Source\n"
    short = header + "".join(f"{i};;;0.0;t\n" for i in range(61))
    raises("short table rejected", lambda: B.read_calibration(tmp(short, "_short.csv")),
           "firmware reads")
    gap = header + "".join(f"{i};;;0.0;t\n" for i in range(80) if i != 40)
    raises("gapped table rejected", lambda: B.read_calibration(tmp(gap, "_gap.csv")), "no gaps")


def test_scala() -> None:
    print("scala parsing")
    for name in ("Sabat II", "ADDAC Just Intonation", "12TET"):
        cents = B.parse_scala(REPO / "tunings" / f"{name}.scl")
        ok = len(cents) == 12 and cents[0] == 0 and all(
            b > a for a, b in zip(cents, cents[1:]))
        check(f"{name} parses ascending", ok)

    def scale(body: str) -> str:
        return "! t\nt\n 12\n!\n" + body

    raises("descending scale rejected",
           lambda: B.parse_scala(tmp(scale(
               " 100.0\n 90.0\n 300.0\n 400.0\n 500.0\n 600.0\n 700.0\n 800.0\n"
               " 900.0\n 1000.0\n 1100.0\n 2/1\n"), "_desc.scl")), "ascending")
    raises("non-octave scale rejected",
           lambda: B.parse_scala(tmp(scale(
               " 100.0\n 200.0\n 300.0\n 400.0\n 500.0\n 600.0\n 700.0\n 800.0\n"
               " 900.0\n 1000.0\n 1100.0\n 1250.0\n"), "_oct.scl")), "2/1 octave")
    raises("wrong degree count rejected",
           lambda: B.parse_scala(tmp("! t\nt\n 3\n!\n 100.0\n 200.0\n 2/1\n", "_n.scl")),
           "12-note")


def test_tables(cfg: dict) -> None:
    print("generated tables")
    tuning = cfg["tuning"]
    table = B.tuning_table(B.parse_scala(REPO / "tunings" / "12TET.scl"),
                           tuning["base_units"], tuning["units_per_octave"])
    check("tuning table is 32 entries", len(table) == 32)
    check("tuning octaves are exact", all(
        table[k + 12] - table[k] == tuning["units_per_octave"] for k in range(20)))
    check("tuning table ascends", table == sorted(table))

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

    def new(a16, lvl):
        f, c = floor << bits, ceil << bits
        n = 0 if a16 <= f else span << bits if a16 >= c else (a16-f)*span//(ceil-floor)
        if lvl:
            i, fr = n >> bits, n & ((1 << bits) - 1)
            cv = (tab[i] << bits) + (tab[i+1] - tab[i]) * fr
            k = (lvl << 3) + (lvl >> 2)
            n -= ((n - cv) * k + 128) >> 8
        return (4095 * n + (span << bits)//2) // (span << bits)

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
    for lvl in (0, 31):
        reachable = len({new((total << bits) // taps, lvl)
                         for total in range(floor * taps, ceil * taps + 1)})
        check(f"level {lvl}: reachable codes beat the old {coarse} by 5x",
              reachable > 5 * coarse, f"{reachable}")
    check("curve monotone with the sentinel", tab == sorted(tab))


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


def test_hex_roundtrip(cfg: dict) -> None:
    print("hex handling")
    factory = REPO / cfg["firmware"]["factory_hex"]
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
    result = subprocess.run(
        [sys.executable, str(REPO / "tools" / "build.py"), "--expect-sha", expected],
        capture_output=True, text=True)
    check("build reproduces the golden image", result.returncode == 0,
          result.stdout.strip().splitlines()[-1] if result.stdout else result.stderr.strip())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--golden", action="store_true",
                        help="also build and compare against [firmware].golden_sha256")
    args = parser.parse_args()

    cfg = tomllib.loads((REPO / "config" / "218e.toml").read_text())
    test_pitch_table(cfg)
    test_scala()
    test_tables(cfg)
    test_resolution(cfg)
    test_overlap_and_range()
    test_hex_roundtrip(cfg)
    if args.golden:
        test_golden(cfg)

    print()
    if FAILURES:
        print(f"{len(FAILURES)} failure(s): {', '.join(FAILURES)}")
        raise SystemExit(1)
    print("all checks passed")


if __name__ == "__main__":
    main()
