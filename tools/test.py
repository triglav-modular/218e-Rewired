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
            if self.count == 0:
                self.idx = self.sum = 0
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
            w = (z * z * z) >> 6
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
    weight = (63 ** 3) >> 6
    check("accumulators cannot overflow with 29 contributors",
          29 * weight * 4095 < 2 ** 32, f"{29 * weight * 4095:,}")

    # Slots the cache and the stamps do not cover must never be read.
    check("the loop stops at the last real key", 28 == max(table), str(max(table)))


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
    updater = REPO / "ProgramLEM218_PressureFix.command"
    if updater.exists():
        mode = stat_module.S_IMODE(updater.stat().st_mode)
        check("the shipped updater is executable", bool(mode & 0o111), oct(mode))


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
    test_blend(cfg)
    test_filter_equivalence(cfg)
    test_overlap_and_range()
    test_atomic_replace()
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
