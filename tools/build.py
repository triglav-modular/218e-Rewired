#!/usr/bin/env python3
"""Build the Buchla 218e V3 patched firmware from config/218e.toml.

Pipeline
--------
    config/218e.toml
        -> generated tables (pressure curve, tuning tables, pitch correction)
        -> build/build.properties          (settings handed to the assembler)
        -> Ghidra headless + src/AssemblePressureFix.java
        -> PATCH records
        -> factory hex + patches           (verified, non-overlapping)
        -> firmware/<output>.hex + build/patch_manifest.txt

Every byte that ends up in the output image is produced by the AVR32 assembler
in Ghidra; nothing here hand-encodes an instruction.

Usage
-----
    python3 tools/build.py                 # build with config/218e.toml
    python3 tools/build.py --config other.toml
    python3 tools/build.py --tables-only   # regenerate tables, skip Ghidra
    python3 tools/build.py --expect-sha <sha256>   # fail unless output matches
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import math
import os
import re
import shutil
import stat
import subprocess
import sys
import tomllib
from fractions import Fraction
from pathlib import Path

# tools/ on the path so `import options` works however build.py is invoked.
sys.path.insert(0, str(Path(__file__).resolve().parent))

REPO = Path(__file__).resolve().parent.parent
BUILD = REPO / "build"

# Flash address of the factory key -> pitch table (32 halfwords).  A tuning
# slot declared as "factory" is copied verbatim from here, which keeps the
# instrument's original temperament bit-exact instead of re-deriving it.
FACTORY_KEY_TABLE = 0x80016574

# Key names by semitone above the bottom key of the 218e, which is a C.  Used
# only to say in the build log which note a tuning is anchored to.
NOTE_NAMES = ["C", "Db", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"]

# The pitch remap clamps its semitone index to 0x4D and interpolates against
# index+1, so the calibration table must supply 79 entries.  A shorter table
# would leave the rest of the fixed table area as assembler padding, which the
# firmware would read as pitch values.
PITCH_TABLE_ENTRIES = 0x4D + 2


# ---------------------------------------------------------------------------
# Feature map: which patches carry which behaviour.
# ---------------------------------------------------------------------------
# Only the *activation* of a behaviour is optional.  Code caves are always
# assembled into unused flash; a disabled feature simply never gets the pointer
# or hook that would reach it, so the factory code path stays byte-for-byte
# intact.  Blocks not named here are core and are always applied.
#
#   setting -> (blocks gated by it, in-cave sections gated by it)
FEATURE_MAP = {
    "knobs.knob1":            (["arp_selector_pool"], []),
    "knobs.knob2":            (["arp_rhythm_hook"], []),
    "knobs.knob3":            (["arp_octave_hook"], []),
    "knobs.knob4":            (
        ["vibrato_engine", "vibrato_sine", "pressure_vibrato_scale", "pressure_vibrato_pool"],
        ["knob4_vibrato"],
    ),
    "arp.switch":             (
        ["noteoff_pool_1", "noteoff_pool_2", "latch_pitch_toggle",
         "release_count_guard", "latch_owner"],
        ["arp_latch"],
    ),
    "midi.poly_default":      (
        ["poly_powerup_default_off", "poly_factory_reset_default_off",
         "poly_arp_independence", "poly_settings_migration",
         "poly_persistence_marker", "poly_settings_loader_pool"],
        [],
    ),
    "pressure.common_mode":   (["proximity_estimator"], ["pressure_common_mode"]),
    "pressure.multi_key":     ([], ["multi_key_pressure"]),
    "pressure.error_diffusion": ([], ["error_diffusion"]),
    "portamento.pressure_blend": (["pitch_target_blend_hook", "blend_offset_apply", "blend_target_conditioner"], ["pressure_blend"]),
    "portamento.zero_snap":   (["glide_rate_hook"], []),
    "diagnostics.scan_profiler": (["scan_profiler", "profiler_pool"], ["scan_profiler"]),
    "diagnostics.clock_latency": (["clock_latency"], ["clock_latency"]),
    "diagnostics.telemetry_smoothing": ([], ["telemetry_smoothing"]),
    "diagnostics.latch_probe": ([], ["latch_probe"]),
    "diagnostics.pressure_ab_switch": (
        ["octswitch_sync"] + [f"octsw_redirect_{i}" for i in range(1, 10)],
        ["pressure_ab_switch"],
    ),
}

# The value that means "new behaviour" for each setting; anything else (i.e.
# "factory" / false) leaves the original firmware in charge.
ENABLED_WHEN = {
    "knobs.knob1": "arp_order",
    "knobs.knob2": "arp_rhythm",
    "knobs.knob3": "arp_octaves",
    "knobs.knob4": "vibrato",
    "arp.switch": "latch",
    "midi.poly_default": "off",
    "pressure.common_mode": True,
    "pressure.multi_key": "max",
    "pressure.error_diffusion": True,
    "portamento.pressure_blend": True,
    "portamento.zero_snap": True,
    "diagnostics.scan_profiler": True,
    "diagnostics.clock_latency": True,
    "diagnostics.telemetry_smoothing": True,
    "diagnostics.latch_probe": True,
    "diagnostics.pressure_ab_switch": True,
}


def get(cfg: dict, dotted: str):
    node = cfg
    for part in dotted.split("."):
        node = node[part]
    return node


# ---------------------------------------------------------------------------
# Scala parsing and table generation
# ---------------------------------------------------------------------------
def parse_scala(path: Path, *, mapped: bool = False) -> list[float]:
    """Return the scale degrees in cents, starting at 0 for the tonic.

    Scala format: '!' comments, then description, then the degree count, then
    that many pitches as either a ratio (a/b) or a cents value (contains '.').

    Without a keyboard mapping the scale must have twelve degrees, because one
    key table entry per semitone is all the instrument has: 12 degrees are
    returned, the octave implied.  With `mapped` the count is free and the
    whole list is returned, the final degree included - a .kbm then says which
    degree each key takes and which one is the period.
    """
    raw = [ln for ln in path.read_text().splitlines()
           if not ln.lstrip().startswith("!")]
    # The first non-comment line is the description, which the format allows
    # to be blank - so it is consumed by position, never filtered.  Dropping
    # blank lines first shifted the count into the description and the first
    # pitch into the count for every legal file with an empty description.
    body = [ln.strip() for ln in raw[1:] if ln.strip()]
    if not raw or not body:
        raise ValueError(f"{path.name}: not a Scala file")
    head = body[0].split()[0]
    try:
        count = int(head)
    except ValueError:
        raise ValueError(
            f"{path.name}: degree count {head!r} is not a number") from None
    pitches = body[1 : 1 + count]
    if len(pitches) != count:
        raise ValueError(f"{path.name}: declares {count} degrees, found {len(pitches)}")

    cents = [0.0]
    for index, token in enumerate(pitches, 1):
        token = token.split()[0]
        if "." in token:
            try:
                value = float(token)
            except ValueError:
                raise ValueError(f"{path.name}: degree {index} is {token!r}, "
                                 "which is not a number") from None
        else:
            # The .scl format: "Ratios are written with a slash, and only one",
            # an integer with neither slash nor period is that integer over 1,
            # and "negative ratios are meaningless and should give a read
            # error".  Spelled out rather than handed to Fraction, which reads
            # "1/2/3" as an error with a message about literals and left the
            # browser - which read it as 1/2 - free to build what this refused.
            parts = token.split("/")
            ratio = None
            if len(parts) <= 2 and all(p.lstrip("+-").isdigit() for p in parts):
                try:
                    ratio = (Fraction(int(parts[0]), int(parts[1]))
                             if len(parts) == 2 else Fraction(int(parts[0])))
                except (ValueError, ZeroDivisionError):
                    ratio = None
            if ratio is None or ratio <= 0:
                raise ValueError(
                    f"{path.name}: degree {index} is {token!r} — a ratio is one "
                    "whole number, or two separated by a single slash, and must "
                    "be above zero")
            value = 1200.0 * math.log2(float(ratio))
        # float() takes "1.0e999" as infinity without complaint, and every
        # check below is a comparison that an infinity or a NaN answers
        # meaninglessly.
        if not math.isfinite(value):
            raise ValueError(
                f"{path.name}: degree {index} is {token!r}, which is not a number")
        cents.append(value)

    if count != 12 and not mapped:
        raise ValueError(
            f"{path.name}: {count} degrees — the key table repeats every octave, "
            "so a 12-note scale is required, or a .kbm to map it"
        )
    if any(b <= a for a, b in zip(cents, cents[1:])):
        raise ValueError(
            f"{path.name}: degrees are not strictly ascending — the key table would "
            "descend or repeat")
    if cents[1] <= 0.0:
        raise ValueError(f"{path.name}: first degree must be above the tonic")
    if mapped:
        return cents  # the .kbm names the period; every degree stays reachable
    return cents[:12]  # degree 12 is the octave, supplied by the octave term


def parse_kbm(path: Path, cents: list[float]) -> tuple[list[int], int]:
    """Return (degree per map position, formal-octave degree) from a .kbm.

    Scala keyboard mapping: '!' comments, then seven header values - map size,
    first and last MIDI note, middle note, reference note, reference frequency,
    formal octave degree - then one line per map position naming a scale
    degree, or 'x' for a position that sounds nothing.

    Four of those seven describe a MIDI keyboard tuned in Hz, and this
    instrument is neither: it has 29 keys with no note numbers, and its
    absolute pitch comes from the 208's trimmer rather than from the firmware.
    So first, last, middle and the reference are read to prove the file is
    well formed and then ignored, map position 0 falling on the bottom key.
    Which key is pinned across slots stays [tuning].reference_key.

    Unmapped positions take the degree of the nearest mapped position, ties to
    the lower - the key sounds like the key beside it rather than falling
    silent, which the firmware has no way to do.
    """
    degree_count = len(cents) - 1
    raw = [ln for ln in path.read_text().splitlines()
           if not ln.lstrip().startswith("!")]
    header, index = [], 0
    while index < len(raw) and len(header) < 7:
        token = raw[index].strip()
        index += 1
        if token:
            header.append(token.split()[0])
    if len(header) < 7:
        raise ValueError(f"{path.name}: not a Scala keyboard mapping — "
                         f"needs seven header values, found {len(header)}")
    names = ("map size", "first MIDI note", "last MIDI note", "middle note",
             "reference note", "reference frequency", "formal octave degree")
    values = []
    for name, token in zip(names, header):
        try:
            values.append(float(token) if "frequency" in name else int(token))
        except ValueError:
            raise ValueError(
                f"{path.name}: {name} is {token!r}, which is not a number") from None
    size, _first, _last, _middle, _reference, ref_hz, formal = values
    if not math.isfinite(ref_hz) or ref_hz <= 0:
        raise ValueError(f"{path.name}: reference frequency must be above zero")
    if size < 0:
        raise ValueError(f"{path.name}: map size is {size}, which is negative")
    if not 1 <= formal <= degree_count:
        raise ValueError(
            f"{path.name}: formal octave degree is {formal}, but the scale has "
            f"{degree_count} degrees — it must name one of them")
    # Size zero is the format's "no mapping": every degree in order.
    if size == 0:
        return list(range(degree_count)), formal

    # Blank entries mean unmapped, so the mapping is read WITH its blank lines
    # - only the header skipped them.  The .kbm format also says: "At the end,
    # unmapped keys may be left out."  A map may
    # stop short of its own size and the positions after it are unmapped, so a
    # So a map may stop short of its own size: that is a legal file, not a
    # truncated one, and refusing it turned away maps Scala itself reads.  The
    # tail fills from the nearest mapped position, like any other gap.
    entries = raw[index:][:size]
    entries += [""] * (size - len(entries))
    degrees: list[int | None] = []
    for position, line in enumerate(entries):
        token = line.strip()
        if not token or token[0] in "xX":
            degrees.append(None)
            continue
        try:
            degree = int(token.split()[0])
        except ValueError:
            raise ValueError(
                f"{path.name}: position {position} is {token!r} — a scale "
                "degree or 'x' for unmapped") from None
        if not 0 <= degree <= degree_count:
            raise ValueError(
                f"{path.name}: position {position} names degree {degree}, but "
                f"the scale has degrees 0..{degree_count}")
        degrees.append(degree)

    mapped = [i for i, d in enumerate(degrees) if d is not None]
    if not mapped:
        raise ValueError(f"{path.name}: every position is unmapped")
    filled = []
    for position, degree in enumerate(degrees):
        if degree is not None:
            filled.append(degree)
            continue
        nearest = min(mapped, key=lambda i: (abs(i - position), i))
        filled.append(degrees[nearest])
    return filled, formal


def key_pitch(cents: list[float], degrees: list[int], period: float, key: int) -> float:
    """Where a key sounds, in cents above the bottom key."""
    size = len(degrees)
    return period * (key // size) + cents[degrees[key % size]]


def factory_tuning(memory: dict[int, int]) -> list[int]:
    """The original 32-entry key table, read straight out of the factory image."""
    try:
        return [
            (memory[FACTORY_KEY_TABLE + 2 * k] << 8) | memory[FACTORY_KEY_TABLE + 2 * k + 1]
            for k in range(32)
        ]
    except KeyError:
        raise SystemExit(
            f"factory key table missing at 0x{FACTORY_KEY_TABLE:08X} — wrong base image?"
        )


def anchor_offset(cents: list[float], reference_key: int,
                  degrees: list[int] | None = None,
                  period: float = 1200.0) -> float:
    """Cents to shift a scale so `reference_key` lands on the 12-TET grid.

    A scale's degree 0 sits on the bottom key, but its degrees do not otherwise
    agree with equal temperament, so each scale puts a given key at its own
    pitch.  Shifting by this offset pins one chosen key -- the note you tune the
    instrument to -- to the same place in every scale, so switching slots never
    moves it and one trim on the 208 serves all three.
    """
    if not isinstance(reference_key, int) or isinstance(reference_key, bool):
        raise ValueError(f"[tuning].reference_key must be a whole number, got {reference_key!r}")
    if not 0 <= reference_key <= 11:
        raise ValueError(
            f"[tuning].reference_key is {reference_key}; it is a semitone above the "
            "bottom key (a C), so it must be 0..11 — 0 = C, 9 = A"
        )
    if degrees is None:
        # reference_key is 0..11, inside the first period, so the period does
        # not enter here.
        return 100.0 * reference_key - cents[reference_key]
    # With a map the reference key's degree is whatever the map gives it, so
    # the pitch has to be looked up rather than indexed.  The target stays the
    # 12-TET grid: that is where the factory table puts the key, and it is
    # what the 208 was trimmed against.
    return 100.0 * reference_key - key_pitch(cents, degrees, period, reference_key)


def tuning_table(cents: list[float], base: int, per_octave: int,
                 offset: float = 0.0, degrees: list[int] | None = None,
                 period: float = 1200.0) -> list[int]:
    """32 key-table entries: octave-periodic, `per_octave` units per octave.

    `offset` shifts the whole table by a number of cents, for the anchoring
    above.  It is applied before quantising, so it costs no extra resolution:
    the table's own step is 1200/`per_octave` cents either way.
    """
    if degrees is None:
        # cents[12] is the scale's own octave.  Every scale that was legal
        # before declares 1200 there, so this is the same table it always
        # produced - but a scale that repeats somewhere else now says so.
        span = cents[12] if len(cents) > 12 else 1200.0
        return [
            base + math.floor(
                (span * (k // 12) + cents[k % 12] + offset) * per_octave / 1200 + 0.5)
            for k in range(32)
        ]
    return [
        base + math.floor(
            (key_pitch(cents, degrees, period, k) + offset) * per_octave / 1200 + 0.5)
        for k in range(32)
    ]


# The 29 keys the instrument actually has.  Entries 29..31 exist because the
# table is 32 long, but no key reaches them, so they say nothing about what two
# notes the player can sound together.
REAL_KEYS = 29

# The factory's trn transposes by ([state+0x6b] - 2) periods: nine positions,
# -2 to +6.  A latched note keeps the transpose it was pressed at, so two
# sounding notes can sit up to eight periods apart, and the arp's own octave
# randomiser adds one more either way.
#
# The exact width stops mattering well before that.  A transpose only brings
# two keys together while it is smaller than the span of the table itself;
# past that every pair is further apart than it started, so widening the sweep
# cannot lower the answer.  Measured: every shipped tuning and every fixture
# below gives the same number at +-2 as at +-32.  Eight is chosen to cover the
# instrument's real range with room to spare, not because the edge is delicate.
TRANSPOSE_STEPS = range(-8, 9)

# What the runtime transpose can differ by between the press that latches a
# note and the press meant to release it, in DAC units.  The transpose itself
# is an exact multiple of the period, but the two paths that publish it to
# 0x60A0 do not agree to the unit - one carries base_units, one octave_units,
# and base_units is octave_units plus one.  A probe measured exactly that: 485
# stored against 484 live.  So the gap the player actually gets is up to this
# much smaller than the gap in the table, and a spacing has to clear the
# tolerance by it.
TRANSPOSE_SLACK = 1


def ideal_key_pitches(cents: list[float], degrees: list[int] | None,
                      period: float, offset: float) -> list[float]:
    """The 29 real keys' pitches in cents, before the table quantises them."""
    if degrees is None:
        return [period * (k // 12) + cents[k % 12] + offset for k in range(REAL_KEYS)]
    return [key_pitch(cents, degrees, period, k) + offset for k in range(REAL_KEYS)]


def min_key_spacing(slots) -> int | None:
    """How close two DIFFERENT sounding pitches ever get, in DAC units.

    `slots` holds one (ideal, table, period_cents, period_units) per tuning
    slot: `ideal` the real keys' unquantised pitches from ideal_key_pitches,
    `table` the key table actually emitted.

    Every pair is compared at every transpose difference the instrument can put
    between them, not just at the same one.  The latch matches
    `table[key] + transpose`, and a latched note keeps the transpose it was
    pressed at, so a map can be comfortably spaced across the keyboard and
    still put two notes within the tolerance once one of them is an octave
    away.  Reading only the untransposed table missed that entirely.

    A pair whose IDEAL pitches coincide under some transpose is the same note -
    an octave-equivalent alias, or a degree a map deliberately doubles up - and
    is skipped.  That test is made on the cents, not on the emitted units,
    because rounding can leave such a pair a unit or two apart in the table;
    absorbing exactly that is what the tolerance is for.

    None when no slot carries a key table, which is the factory temperament's
    ~40-unit semitone.
    """
    closest = None
    for ideal, table, period_cents, period_units in slots:
        for a, pitch_a in enumerate(ideal):
            for step in TRANSPOSE_STEPS:
                shifted = pitch_a + step * period_cents
                emitted = table[a] + step * period_units
                for b, pitch_b in enumerate(ideal):
                    if abs(shifted - pitch_b) < 1e-9:
                        continue          # the same note, on purpose
                    gap = abs(emitted - table[b])
                    if closest is None or gap < closest:
                        closest = gap
    return closest


# The instrument's own transpose, in periods.  The factory's trn steps
# ([state+0x6b] - 2) of them, so the reach above an untransposed table entry is
# six periods.  The downward direction is not checked here: the table's base is
# one period above nothing so the lowest switch position still lands above
# zero, and below that the factory's own range checks hold it.
TRANSPOSE_UP = 6

# A pitch is carried through signed 16-bit storage and loads.  Above this it
# comes back negative and falls to the DAC floor - the bottom of the range
# rather than the top, which no clamp can correct.
MAX_PITCH = 0x7FFF


# Two faults at the two ends, and they are not the same fault.  Below zero the
# entry is stored as a halfword and the latch match and the pitch ranking read
# it back with LD.UH, so -39 becomes 65497 and they compare a pitch 65,536
# units from the one that sounds.  Above MAX_PITCH the signed load turns the
# pitch negative and the note drops to the floor.  Between the DAC ceiling and
# MAX_PITCH there is no fault at all: the pitch path clamps, so the note is
# flat rather than wrong.
def check_table_range(name: str, table: list[int], period_units: int) -> None:
    # Only the keys that exist.  Entries 29..31 are emitted because the table
    # is 32 long, and no key reaches them, so a pitch they hold is not one the
    # instrument can be made to play.
    table = table[:REAL_KEYS]
    low = min(table)
    if low < 0:
        raise ValueError(
            f"{name}: key table entry {low} is below zero — the anchor has "
            f"pushed the bottom of this scale under the instrument's lowest "
            f"pitch.  The firmware reads the table unsigned, so that entry "
            f"would come back as {low & 0xFFFF} and the latch would compare a "
            f"note that never sounds.  Use a scale or mapping whose keys stay "
            f"above the bottom, or an anchor key the map does not carry more "
            f"than an octave above it.")
    # The transpose is what makes this more than a check on the table: an entry
    # can sit under the limit and cross it the moment the player steps the
    # octave up, which is a note that plays at the bottom of the range instead
    # of the top.
    reach = max(table) + TRANSPOSE_UP * period_units
    if reach > MAX_PITCH:
        top = max(table)
        raise ValueError(
            f"{name}: key {table.index(top)} reaches {reach} once the octave "
            f"controls are stepped up ({top} in the table, plus "
            f"{TRANSPOSE_UP} periods of {period_units}), and the firmware "
            f"carries a pitch as a signed 16-bit value — past {MAX_PITCH} it "
            f"comes back negative and the note drops to the bottom of the "
            f"range instead of the top.  This scale's period spans "
            f"{period_units} units, so the keyboard runs out of pitch before "
            f"it runs out of keys: use a mapping with more degrees to the "
            f"period, or a smaller period.")


def pressure_curve(span: int, onset_db: float, fade: int = 0) -> list[int]:
    """218r-style response: 0 at the floor, an onset step, then a smooth rise.

    v(0) = 0; v(x) = span * 10**((x/span - 1) * -onset_db/20), clamped
    monotone and to `span`.  onset_db = -10 gives the 218r's ~32 % onset.

    `fade` linearises the first `fade` counts up to the curve, so the onset
    is a short ramp instead of a cliff — releases cross the floor smoothly
    while attacks still reach the onset level almost immediately.
    """
    out, previous = [], 0
    exponent = -onset_db / 20.0
    for x in range(span + 1):
        if x == 0:
            value = 0
        else:
            value = math.floor(span * 10.0 ** ((x / span - 1.0) * exponent) + 0.5)
        if fade and 0 < x < fade:
            value = min(value, value * x // fade)
        value = max(previous, min(span, value))
        out.append(value)
        previous = value
    return out


def read_calibration(path: Path) -> dict[int, float]:
    """Read the pitch calibration table: semitone -> offset in cents.

    One row per semitone above the 208's 0 V pitch.  Offset_Cents is measured
    against an ideal 1 V/octave ramp, positive raising the pitch; it absorbs
    both the coarse octave scaling and each key's own tracking error, so this
    is the only pitch calibration data there is.
    """
    lines = [ln for ln in path.read_text().splitlines() if not ln.lstrip().startswith("#")]
    if not lines:
        raise ValueError(f"{path.name}: empty calibration table")
    delimiter = ";" if lines[0].count(";") else ","
    offsets: dict[int, float] = {}
    for row in csv.DictReader(lines, delimiter=delimiter):
        raw = (row.get("Offset_Cents") or "").strip()
        if not raw:
            raise ValueError(f"{path.name}: semitone {row.get('Semitone')} has no Offset_Cents")
        semitone = int(row["Semitone"])
        if semitone in offsets:
            raise ValueError(
                f"{path.name}: semitone {semitone} appears twice - a stale row "
                "from a hand edit would silently win")
        offsets[semitone] = float(raw)
    if not offsets:
        raise ValueError(f"{path.name}: no rows")
    missing = set(range(max(offsets) + 1)) - set(offsets)
    if missing:
        raise ValueError(
            f"{path.name}: semitones must run 0..N with no gaps; missing {sorted(missing)[:8]}"
        )
    if len(offsets) < PITCH_TABLE_ENTRIES:
        raise ValueError(
            f"{path.name}: {len(offsets)} rows, but the firmware reads {PITCH_TABLE_ENTRIES} "
            "(semitones 0..78); a shorter table leaves assembler padding to be read as pitch"
        )
    return offsets


def counts_per_volt(cfg: dict) -> float:
    pitch = cfg["pitch"]
    return pitch["dac_counts"] / (pitch["dac_vref"] * pitch["dac_gain"])


# The volts/octave the pitch table has always produced.  Measured at the jack:
# 4096 counts / (2.5 V * 4.09) = 400.59 counts per volt, and the table puts one
# octave 400.59 counts apart — 1.000 V/oct, confirmed flat across the bottom
# five octaves of the shipped calibration.  (The top octave measures 1.216 V,
# but that is the 208's own tracking error being corrected, not a scaling.)
#
# So this is the reference: volts_per_octave = 1.0 leaves the ramp untouched,
# and any other value scales it uniformly — changing the octave span while
# keeping every relative pitch exactly where it was, because the calibration is
# stored in cents, a ratio.
CALIBRATION_VOLTS_PER_OCTAVE = 1.0


def pitch_table(cfg: dict, offsets: dict[int, float]) -> list[int]:
    """Per-semitone pitch curve, in DAC counts.

    counts(i) = counts_per_volt * vpo/1.2 * (i/12 + offset(i)/1200): an ideal
    1 V/octave ramp displaced by the measured calibration, then scaled from the
    1.2 V/oct the calibration assumes to the configured volts_per_octave.
    """
    vpo = cfg["pitch"].get("volts_per_octave", CALIBRATION_VOLTS_PER_OCTAVE)
    scale = counts_per_volt(cfg) * (vpo / CALIBRATION_VOLTS_PER_OCTAVE)
    table = [
        math.floor(scale * (i / 12.0 + offsets[i] / 1200.0) + 0.5)
        for i in range(max(offsets) + 1)
    ]
    if len(table) != PITCH_TABLE_ENTRIES:
        raise ValueError(
            f"Pitch curve has {len(table)} entries, firmware needs {PITCH_TABLE_ENTRIES}.")
    # Strictly increasing, not merely non-descending.  Two adjacent entries at
    # the same DAC count is a semitone that plays the pitch of its neighbour,
    # and the remap has no way to say so; the real tables step by 25 counts at
    # the closest, so a repeat is a corrupt table rather than a fine one.
    flat = [i for i in range(1, len(table)) if table[i] == table[i - 1]]
    if flat:
        raise ValueError(
            f"Pitch curve repeats a DAC count at semitone{'s' if len(flat) > 1 else ''} "
            f"{', '.join(str(i) for i in flat[:6])}"
            f"{'...' if len(flat) > 6 else ''} - that semitone would play its "
            f"neighbour's pitch. Check the calibration table.")
    if table != sorted(table):
        raise ValueError("Pitch curve is not monotonic. Check the calibration table.")
    if table[0] < 0 or table[-1] > 4095:
        raise ValueError("Pitch curve leaves the 12-bit DAC range.")
    return table


def octave_width_volts(offsets: dict[int, float], semitone: int) -> float:
    """Volts per octave the 208 actually needs around this semitone.

    A cent of pitch costs more voltage where the oscillator's scaling is
    stretched, so folding a tuner reading into the table has to use the local
    width rather than a nominal 1 V.
    """
    top = max(offsets)
    low = min(semitone, max(0, top - 12))
    high = min(low + 12, top)
    span = high - low
    if span == 0:
        return 1.0
    volts = (high / 12.0 + offsets[high] / 1200.0) - (low / 12.0 + offsets[low] / 1200.0)
    return volts * 12.0 / span


def fold_measurement(cfg: dict, calibration: Path, measurement: Path) -> None:
    """Fold fresh tuner readings into the calibration table.

    The measurement file needs a Semitone (or Key) column and a Measured_Cents
    column, positive when the note played sharp.  Corrections accumulate: the
    reading is relative to firmware that already applies the existing table.
    """
    offsets = read_calibration(calibration)
    lines = [ln for ln in measurement.read_text().splitlines() if not ln.lstrip().startswith("#")]
    delimiter = ";" if lines[0].count(";") else ","
    updates: dict[int, float] = {}
    for row in csv.DictReader(lines, delimiter=delimiter):
        raw = (row.get("Measured_Cents") or "").strip()
        if not raw:
            continue
        # Three accepted ways to say which note was measured.  "Semitone" is an
        # index into the calibration table (0 = the 208's 0 V pitch, an A);
        # "Semitones" and "Key" are relative to the bottom key, which is a C,
        # three semitones higher.
        if (row.get("Semitone") or "").strip():
            semitone = int(row["Semitone"])
        elif (row.get("Semitones") or "").strip():
            semitone = int(row["Semitones"]) + 3
        elif (row.get("Key") or "").strip():
            semitone = int(row["Key"]) + 2
        else:
            raise SystemExit(f"{measurement.name}: rows need a Semitone, Semitones or Key column")
        if semitone not in offsets:
            raise SystemExit(f"{measurement.name}: semitone {semitone} is outside the table")
        updates[semitone] = float(raw)

    if not updates:
        raise SystemExit(f"{measurement.name}: no Measured_Cents values")

    # Rows above the highest measured note only ever held that note's
    # correction, so they follow it rather than keeping a stale value.
    highest = max(updates)
    tail_delta = -updates[highest] * octave_width_volts(offsets, highest)

    text = calibration.read_text().splitlines()
    # The reader detects the delimiter and reads columns by header name; the
    # rewrite used to hard-code ';' and columns 3/4, so a comma-delimited
    # table that every build path accepts crashed the fold with a traceback.
    header_line = next(ln for ln in text
                       if not ln.lstrip().startswith("#") and ln.strip())
    cal_delim = ";" if header_line.count(";") else ","
    columns = [c.strip() for c in header_line.split(cal_delim)]
    try:
        cents_col = columns.index("Offset_Cents")
        source_col = columns.index("Source")
    except ValueError:
        raise SystemExit(
            f"{calibration.name}: the fold needs Offset_Cents and Source "
            "columns to rewrite") from None
    out, applied, trailing = [], 0, 0
    for line in text:
        if line.lstrip().startswith("#") or line.startswith("Semitone"):
            out.append(line)
            continue
        parts = line.split(cal_delim)
        semitone = int(parts[0])
        if semitone in updates:
            error = updates[semitone]
            # a sharp note needs less voltage, scaled by the local octave width
            delta = -error * octave_width_volts(offsets, semitone)
            parts[cents_col] = f"{offsets[semitone] + delta:.6f}"
            parts[source_col] = "measured"
            applied += 1
        elif semitone > highest and parts[source_col] == "extrapolated":
            parts[cents_col] = f"{offsets[semitone] + tail_delta:.6f}"
            trailing += 1
        out.append(cal_delim.join(parts))
    calibration.write_text("\n".join(out) + "\n")
    print(f"folded {applied} reading(s) into {calibration.relative_to(REPO)}")
    if trailing:
        print(f"  {trailing} extrapolated row(s) above semitone {highest} followed it")
    print("  rebuild to apply them")


# ---------------------------------------------------------------------------
# Intel HEX
# ---------------------------------------------------------------------------
def parse_hex(path: Path) -> tuple[dict[int, int], int]:
    return parse_hex_text(path.read_text(), path.name)


def parse_hex_text(text: str, name: str) -> tuple[dict[int, int], int]:
    memory: dict[int, int] = {}
    upper = 0
    start_linear = 0x80002000
    for number, line in enumerate(text.splitlines(), 1):
        if not line.startswith(":"):
            raise ValueError(f"{name} line {number}: missing Intel HEX colon")
        record = bytes.fromhex(line[1:])
        if sum(record) & 0xFF:
            raise ValueError(f"{name} line {number}: bad checksum")
        length, kind = record[0], record[3]
        # The checksum cannot catch a wrong partition - the byte sum is zero
        # however the bytes are split - so the declared length is held to the
        # bytes actually present: 4 of header, the payload, 1 of checksum.
        if len(record) != 5 + length:
            raise ValueError(
                f"{name} line {number}: declares {length} data bytes, "
                f"carries {len(record) - 5}")
        address = (record[1] << 8) | record[2]
        data = record[4 : 4 + length]
        if kind == 0:
            for offset, value in enumerate(data):
                location = upper + address + offset
                if location in memory:
                    raise ValueError(f"duplicate byte at 0x{location:08X}")
                memory[location] = value
        elif kind == 4:
            upper = int.from_bytes(data, "big") << 16
        elif kind == 5:
            start_linear = int.from_bytes(data, "big")
        elif kind == 1:
            break
        else:
            raise ValueError(f"{name} line {number}: record type {kind}")
    return memory, start_linear


def render_hex(memory: dict[int, int], start_linear: int) -> str:
    def record(kind: int, address: int, data: bytes = b"") -> str:
        body = bytes([len(data), (address >> 8) & 0xFF, address & 0xFF, kind]) + data
        return ":" + (body + bytes([(-sum(body)) & 0xFF])).hex().upper()

    lines: list[str] = []
    addresses = sorted(memory)
    index = 0
    current_upper = None
    while index < len(addresses):
        start = addresses[index]
        upper = start >> 16
        if upper != current_upper:
            lines.append(record(4, 0, upper.to_bytes(2, "big")))
            current_upper = upper
        chunk = bytearray([memory[start]])
        index += 1
        while index < len(addresses) and len(chunk) < 16:
            expected = start + len(chunk)
            if addresses[index] != expected or (expected >> 16) != upper:
                break
            chunk.append(memory[expected])
            index += 1
        lines.append(record(0, start & 0xFFFF, bytes(chunk)))
    lines.append(record(5, 0, start_linear.to_bytes(4, "big")))
    lines.append(record(1, 0))
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# Assembler driver
# ---------------------------------------------------------------------------
def resolve_flags(cfg: dict) -> tuple[dict[str, bool], dict[str, bool], list[str]]:
    blocks: dict[str, bool] = {}
    features: dict[str, bool] = {}
    summary: list[str] = []
    for setting, (block_names, feature_names) in FEATURE_MAP.items():
        value = get(cfg, setting)
        expected = ENABLED_WHEN[setting]
        enabled = value == expected
        if setting == "pressure.multi_key":
            enabled = value in ("mean", "max")
        if not enabled and isinstance(expected, bool) is False and value != "factory":
            raise ValueError(
                f"{setting}: {value!r} is not a known setting "
                f"(expected {expected!r} or 'factory')"
            )
        for name in block_names:
            blocks[name] = enabled
        for name in feature_names:
            features[name] = enabled
        summary.append(f"  {setting:28s} {value!r}{'' if enabled else '  (factory)'}")
    return blocks, features, summary


def write_properties(path: Path, cfg: dict, blocks, features, tables) -> None:
    lines = [
        "# Generated by tools/build.py. Do not edit.",
        f"# Source: {cfg['_config_name']}",
    ]
    for name, enabled in sorted(blocks.items()):
        lines.append(f"block.{name}={1 if enabled else 0}")
    for name, enabled in sorted(features.items()):
        lines.append(f"feature.{name}={1 if enabled else 0}")
    for name, value in sorted(cfg.get("_numbers", {}).items()):
        lines.append(f"number.{name}={value}")
    for name, values in sorted(tables.items()):
        lines.append(f"table.{name}=" + ",".join(str(v) for v in values))
    path.write_text("\n".join(lines) + "\n")


def run_ghidra(cfg: dict, properties: Path, log: Path) -> str:
    configured = cfg.get("tools", {}).get("ghidra_home", "")
    ghidra_home = Path(os.environ.get("GHIDRA_HOME") or configured)
    headless = ghidra_home / "support" / "analyzeHeadless"
    if not str(ghidra_home) or not headless.exists():
        raise SystemExit(
            f"Ghidra not found at {headless}\n"
            "Set GHIDRA_HOME, or put ghidra_home under [tools] in "
            "config/local.toml (untracked)."
        )
    project_dir = BUILD / "ghidra_project"
    project_dir.mkdir(parents=True, exist_ok=True)
    factory = REPO / cfg["firmware"]["factory_hex"]
    imported = (project_dir / "buchla218.rep").exists()

    command = [str(headless), str(project_dir), "buchla218"]
    if imported:
        command += ["-process", factory.name, "-noanalysis"]
    else:
        command += ["-import", str(factory), "-processor", "avr32:BE:32:default",
                    "-noanalysis"]
    command += [
        "-readOnly",
        "-scriptPath", str(REPO / "src"),
        "-postScript", "AssemblePressureFix.java", str(properties),
    ]
    result = subprocess.run(command, capture_output=True, text=True)
    output = result.stdout + result.stderr
    log.write_text(output)
    if result.returncode != 0 or "IllegalStateException" in output or "ERROR REPORT SCRIPT ERROR" in output:
        for line in output.splitlines():
            if "Exception" in line or "ERROR" in line:
                print(line, file=sys.stderr)
        raise SystemExit(f"Ghidra assembly failed — full log: {log}")
    return output



# The JavaScript assembler in tools/avr32/, used instead of Ghidra when
# --no-ghidra is given.  It reproduces Ghidra's encoding exactly; see
# tools/avr32/README.md for how that is established and checked.
AVR32 = REPO / "tools" / "avr32"

# jsc ships with macOS and needs nothing installed; node is the fallback.
JS_ENGINES = [
    Path("/System/Library/Frameworks/JavaScriptCore.framework/Versions/A/"
         "Helpers/jsc"),
]


def find_js_engine() -> tuple[list[str], str]:
    for engine in JS_ENGINES:
        if engine.exists():
            return [str(engine)], engine.name
    for name in ("node", "nodejs", "bun", "deno"):
        found = shutil.which(name)
        if found:
            return ([found, "run"] if name == "deno" else [found]), name
    raise SystemExit(
        "no JavaScript engine found for --no-ghidra.\n"
        "  macOS ships one at /System/Library/Frameworks/JavaScriptCore."
        "framework/Versions/A/Helpers/jsc\n"
        "  otherwise install Node.")


def run_javascript(properties: Path, log: Path) -> str:
    """Assemble the patch set without Ghidra.

    The sources are concatenated into one bundle rather than passed as
    separate files, because only jsc loads several scripts into a shared
    scope; Node would run just the first.  One code path for every engine, and
    the bundle is also exactly what a browser build would load.
    """
    # Regenerate program.js from the Java first, so it cannot go stale against
    # a source edit.
    subprocess.run([sys.executable, str(AVR32 / "transpile.py")],
                   check=True, cwd=REPO, capture_output=True)

    parts = ["shim.js", "encoder.js", "runtime.js", "program.js", "assemble.js"]
    bundle = BUILD / "assemble_bundle.js"
    bundle.write_text("\n".join((AVR32 / name).read_text() for name in parts))

    command, engine = find_js_engine()
    argv = command + [str(bundle)]
    # jsc needs `--` before script arguments; node takes them directly.
    argv += ["--", str(properties)] if engine == "jsc" else [str(properties)]

    result = subprocess.run(argv, capture_output=True, text=True, cwd=REPO)
    output = result.stdout + result.stderr
    log.write_text(output)
    if result.returncode != 0 or "ASSEMBLY FAILED" in output:
        for line in output.splitlines()[:20]:
            print(line, file=sys.stderr)
        raise SystemExit(f"JavaScript assembly failed — full log: {log}")
    print(f"  assembled with {engine} (no Ghidra)")
    return output


PATCH_RE = re.compile(r"^PATCH ([0-9a-f]{8}) ([0-9a-f]+)(?: ; (.*))?$")
EXTENT_RE = re.compile(r"^EXTENT ([0-9a-f]{8}) ([0-9a-f]{8}) (\S+)$")

# Every RAM region our code owns, as (start, end, name).  Declaring them here
# means the build fails on an overlap instead of the instrument misbehaving:
# a cave writing into another's state is invisible in the patch bytes, since
# the addresses only exist as immediates.
RAM_REGIONS = [
    # These used to sit inside the factory's own 16-tap pressure history at
    # 0x3216..0x3235, which was only free because pitch_clamp_skip_1 jumped
    # over the filter that shifts it.  That made the skip load-bearing and
    # meant the factory filter could never come back, however the options
    # were set.  The block moved here whole, keeping its relative offsets:
    # the initialiser addresses it as base+2/+6/+a/+c.
    (0x60E4, 0x60E6, "tuning-apply guard"),
    (0x60E6, 0x60E8, "arp knob 2 latch"),
    # 0x60E8 and 0x60EC were "arp last countdown" and "arp gate threshold".
    # Nothing in the firmware reads or writes either any more - the audit
    # walked every base-plus-offset access in the built image and found none -
    # so they are gone rather than left looking like live state.
    (0x60EA, 0x60EC, "arp knob 3 latch"),
    (0x60EE, 0x60EF, "deferred-pulse countdown, in scans"),
    (0x60EF, 0x60F0, "previous switch position"),
    (0x60F0, 0x60F2, "knob 4 latch: vibrato raw value or transpose zone"),
    # Our own cell, not the factory's state+0x38c: that byte is the factory
    # weighted-random selector's bias parameter, and borrowing it meant a
    # factory-knobs build still had knob 1 writing over a live factory
    # setting.
    (0x60F2, 0x60F3, "arp knob 1 latch"),
    (0x6000, 0x6021, "arp press-order list"),
    (0x6024, 0x6026, "vibrato LFO phase"),
    (0x6026, 0x6028, "vibrato smoothed depth"),
    (0x6028, 0x602A, "vibrato output offset"),
    (0x602A, 0x602C, "power-up marker"),
    (0x602C, 0x602E, "interpolator target snapshot"),
    (0x602E, 0x6030, "interpolator ticks remaining"),
    (0x6032, 0x6036, "profiler reports"),
    (0x6036, 0x6038, "interpolator target"),
    (0x6038, 0x6044, "profiler accumulators"),
    (0x6044, 0x6046, "clock-latency edge-to-claim age"),
    (0x6046, 0x604C, "octave-switch shadow"),
    (0x604C, 0x604E, "octave-switch boot counter"),
    (0x6050, 0x6080, "pressure history taps"),
    (0x6080, 0x6082, "filter sample count"),
    (0x6082, 0x6084, "filter depth"),
    (0x6084, 0x6086, "interpolator step count"),
    (0x6086, 0x6088, "filter ring index"),
    (0x6088, 0x608C, "filter running sum"),
    (0x608C, 0x608E, "filter newest sample"),
    # The pitch the DAC was showing when a beat claimed the step, republished
    # every unclaimed scan.  A claimed beat's scan puts it back, so the new
    # note's pitch cannot reach the output before the gate it belongs to.
    (0x609C, 0x609E, "held pitch, published for a claimed beat"),
    (0x60A0, 0x60A2, "live transpose offset"),
    (0x60A2, 0x60DC, "latch pitch stamps"),
    # The gate's absolute COUNT target.  Declared, so that the next cell to be
    # picked out of this page collides here instead of silently inside the
    # stamps above - which is what 0x60A8 did: slot 3.
    (0x60DC, 0x60E0, "claimed beat's gate target, absolute COUNT"),
    (0x60E0, 0x60E2, "blend offset target"),
    (0x60E2, 0x60E4, "blend applied offset"),
    (0x60F4, 0x60F6, "blend previous base"),
    (0x60F6, 0x60F8, "blend target filter"),
    (0x60F8, 0x60FA, "blend hysteresis hold"),
    # Decoupled preset voltages.  The stored value is what the preset output
    # and the pitch adder both read; the snapshot and the flag are what stop a
    # pad hold from snatching the stored value to wherever the knob happens to
    # be standing.
    (0x613A, 0x6142, "preset voltage store"),
    (0x6142, 0x614A, "preset knob snapshot"),
    (0x614A, 0x614E, "preset following flags"),
    # Which way the mirror order is travelling; it turns at the ends.
    (0x614E, 0x614F, "arp mirror direction"),
    # Where knob 2's pattern has got to, wrapped at that pattern's length.
    (0x6150, 0x6152, "arp pattern step"),
    # Which half of the swung pair the next step is.
    (0x6152, 0x6153, "arp swing parity"),
    # The sequencer's pad chord: hold counter, armed, selected, mode, the pad
    # the selection is frozen at, last scan's touch levels, and the blink
    # counter every light this firmware adds shares.
    (0x6154, 0x6160, "sequencer chord and mode"),
    # 64 recorded pitches, then how many there are, where play has got to,
    # and the pitch the step about to sound carries.
    (0x6160, 0x61E6, "sequencer steps"),
    # The external clock: diagnostic milliseconds / dispatch stamp, measured
    # period, acquisition confidence, and divide phase. The active-divider
    # latch and physical edge timestamps are separate, below.
    (0x61E6, 0x61EE, "external clock divider"),
    # The key each recorded step was played on.  The pitch beside it is what
    # the CV plays; this is what MIDI names the note by.
    (0x61EE, 0x622E, "sequencer step keys"),
    # The strip's own mode, plus one, for as long as record has it aside.
    # Zero means nothing is being held and a restore cannot fire twice.
    (0x622E, 0x6230, "strip mode borrowed by record"),
    # The key a note-on left for record to sound, plus one, so that the
    # cleared state is "nothing waiting" rather than key zero.
    (0x6230, 0x6232, "the key record has yet to sound"),
    (0x6232, 0x6233, "unconsumed low interval, GPIO ISR"),
    (0x6233, 0x6234, "acquired divider latch"),
    (0x6234, 0x6236, "clock FIFO producer and consumer indices"),
    (0x6236, 0x6238, "input-present and output-step-in-flight flags"),
    (0x6238, 0x6244, "low, accepted and consumed COUNT timestamps"),
    (0x6244, 0x6254, "cycles/ms, low qualification, refractory and release"),
    (0x6254, 0x6258, "last physical output COUNT timestamp"),
    # A bare pad press has to be HELD to mean preview or backspace: a quick
    # tap still belongs to whatever else the pad does, which is how octave 3
    # became impossible to choose without deleting a note.
    (0x6258, 0x625A, "saturating capture FIFO overrun count"),
    (0x625A, 0x625B, "physical output timestamp valid"),
    # Set beside the scan's countdown when a clock is present, cleared by
    # whichever context takes the step. Inside the 0x6232..0x62E0 block the
    # startup initialiser zeroes, so a warm restart cannot fire a stale one.
    (0x625B, 0x625C, "the step's trigger is claimable by the 1 kHz flush"),
    (0x625C, 0x625D, "which pad a bare hold is counting, plus one"),
    (0x625D, 0x625E, "how many scans it has been held"),
    (0x6260, 0x62E0, "32-entry clock timestamp FIFO (31 usable)"),
    # Completed edit gestures commit immediately. A failed
    # lap is latched, not retried on every scan: 0 clean, 1 pending, 2 failed.
    (0x62E0, 0x62E1, "persistence request/result"),
    (0x62E1, 0x62E2, "which rotation page holds the newest record"),
    (0x62E4, 0x62E8, "the sequence number that record carries"),
    # One stamp per knob, the raw ADC value plus one; zero means no edit has
    # parked anything and the knob's other job may follow it live.
    (0x62E8, 0x62F0, "where each preset edit left its knob, plus one"),
    (0x62F0, 0x62F4, "clock-latency internal-beat claim stamp"),
    (0x62F4, 0x62F6, "the transpose the take was born under (its reference)"),
    # The scan watches for two gestures ENDING, so it has to remember what
    # they looked like on the previous scan.
    (0x62F8, 0x62F9, "logical sequencer mode last scan, preview counts as WRITE"),
    (0x62F9, 0x62FD, "each preset was edited since its last full release"),
    (0x62FD, 0x62FE, "the stored record has been restored this power-up"),
    # A preview is a take being listened back to, not a take being
    # finished: it must not read as leaving record mode.
    (0x62FE, 0x62FF, "a one-shot preview of the take is playing"),
    (0x62FF, 0x6300, "explicit CLEAR event awaiting persistence scan"),
    # The release is timed from the 1 ms task, not from COUNT: COUNT is
    # scaled by the CPU-frequency word, and on the instrument a nominal
    # 2600 ms release expired in well under a second.
    (0x62F6, 0x62F8, "the millisecond count at the last accepted edge"),
    # The record staged for writing, 8-byte aligned and a multiple of 8 long,
    # so the flash driver takes its simple aligned path - the same reason the
    # factory stages its own record rather than writing from scattered state.
    (0x6300, 0x63E0, "canonical v2 record, staged for body then marker commit"),
    (0x6400, 0x64CC, "canonical musical payload from completed edit gestures"),
    (0x608E, 0x608F, "latch-position mirror"),
    (0x6090, 0x6091, "tuning slot"),
    (0x6094, 0x6098, "output error accumulator"),
    (0x6098, 0x609A, "vibrato error accumulator"),
    # Two bytes, not four: the second halfword was reserved for a pressed-
    # pitch snapshot that was never implemented.
    (0x609A, 0x609C, "latch probe snapshot"),
    (0x6100, 0x613A, "corrected-pressure cache"),
    # The recording audition's pinned pitch and the delete-pad flash, with
    # the pressure-ownership maps, above the persistence staging and
    # snapshot blocks.  Every cell is written before it is read or
    # validated before it is trusted, so none of this needs the first-use
    # fill.  The flash countdown is also cleared by the startup wrapper,
    # because SRAM survives a same-image warm restart.
    (0x6500, 0x6502, "the audition's pinned pitch, plus one"),
    (0x6502, 0x6503, "delete-pad flash countdown, in scans"),
    (0x6504, 0x6521, "owner: which key's press made each slot's note, plus one"),
    (0x6521, 0x653E, "current: the slot each key's note lives in, plus one"),
    (0x6540, 0x657A, "slot-indexed pressure weights, rebuilt per scan"),
    # The strip lamps' cave.  The shadow is where the factory's own DAC slot 5
    # store was redirected, so it is written every scan whether or not a take
    # is running; the other three only mean anything inside one.
    (0x657A, 0x657C, "strip DAC slot 5, redirected out of the factory store"),
    (0x657C, 0x657D, "strip lamp acknowledgment countdown, in scans"),
    (0x657D, 0x657E, "which lamp it is: 1 a rest, 2 a tie"),
    (0x657E, 0x657F, "last scan's step count, for spotting an append"),
]

# Factory-owned RAM the patches address absolutely.  Not ours to initialise —
# the factory already does — but listed so that a new cell of our own cannot
# be placed on top of one without the coverage check noticing.
FACTORY_CELLS = [
    (0x29CC, 0x29D0, "CPU frequency, also used by the factory COUNT delay"),
    # 32 halfwords - the tuning applier loop counts MOV R9,0x20 - so the
    # cell ends at 0x894.  It was declared 6 bytes short, which left the
    # last three entries outside the overlap protection this map exists
    # to provide.
    (0x0854, 0x0894, "key pitch table"),
    # The note the arp step sounded, and the flag saying one is sounding.
    # Both are read by the release that stop and clear now do for themselves.
    (0x2EE4, 0x2EE6, "the note the arp is sounding"),
    # table[knob] + CV/2, clamped - written at 0x80002b62.  The divisor must
    # NOT read this: the knob half is the tempo table's OUTPUT, not the knob
    # position, and the CV half is the arp-rate CV input (the 218K+'s own
    # jack; a reassigned input on a modified V3).  The knob alone, as a
    # position, is state+0x2fc raw.
    (0x2EE6, 0x2EE8, "arp rate knob and CV, combined"),
    (0x2EED, 0x2EEE, "arp active-note flag"),
    # Read before the 208-bus note-off and before every bend send.  Named for
    # what it is seen to do, not for a writer this repo has found.
    (0x2EFA, 0x2EFB, "send-enable flag"),
    (0x2EEE, 0x2EF0, "glide rate"),
    (0x3212, 0x3214, "pitch mirror"),
    # The pads' own touch states, the same shape as the keys' array: one byte
    # each, 2 meaning held.  Read only - the factory owns the writing.
    (0x46F0, 0x46F4, "pad touch state"),
    # Live again whenever pressure_fix is off: the clamp skips are gated now,
    # so the factory 16-tap pressure history shifts through here in that
    # build.  Declared so no region of ours can ever move back in.
    (0x3216, 0x3236, "factory 16-tap pressure history"),
    (0x3490, 0x34AD, "per-key touch state"),
    (0x3599, 0x359A, "state+0x39: global edit mode"),
    (0x35CA, 0x35CC, "state+0x6a/0x6b: transpose enable and knob zone"),
    (0x3686, 0x36C0, "per-key raw pressure"),
    (0x377B, 0x3798, "state+0x21b: per-slot held flags"),
    # state+0x306: the PORTAMENTO knob, 0..1023.  Six knobs are conditioned
    # together at 0x80007ad8 - portamento, arp rate, and the four preset
    # voltage knobs at 0x30a/0x30c/0x30e/0x310.  The strip is not among them:
    # it is a seven-segment capacitive sensor, and its position is a centroid
    # (0x8000aa98) mapped to state+0x1fe.
    (0x3866, 0x3868, "portamento knob mirror"),
    (0x386A, 0x3872, "state+0x30a..0x310: the four preset knob mirrors"),
    (0x38A0, 0x38AE, "state+0x340: latch, mode and last arp key"),
    (0x38B0, 0x38B2, "state+0x350: transpose"),
    # The pitch the 1 kHz flush transfers.  Declared now that a patch names it
    # by an address the map can see: clock_settle captures it at an external
    # claim and clock_output restores it until completion, so a held beat's
    # gate and pitch leave on one transfer.
    (0x38B8, 0x38BA, "state+0x358: DAC slot 2, the pitch"),
    # The slot the strip's three position lamps follow.  The factory wrote it
    # every scan at 0x80003120; strip_dac_redirect sends that store to a
    # shadow of ours and seq_strip_led becomes the only writer of the slot.
    (0x38BE, 0x38C0, "state+0x35e: DAC slot 5, the strip"),
]

# Immediates that are values rather than addresses, so the coverage check does
# not mistake them for a cell.  Anything new landing here deserves a look.
NON_ADDRESS_IMMEDIATES = {0xFFF}


def declared_ram() -> list[tuple[int, int, str]]:
    return sorted(RAM_REGIONS + FACTORY_CELLS)


def check_ram_coverage() -> None:
    """Every RAM cell the assembler addresses must be declared somewhere.

    The patches reach their own state by building the address with `MOV Rn,imm`
    and then loading or storing through it, so that idiom is the inventory.
    Anything it turns up that no region covers is a cell someone added without
    putting it on the map — which is how the vibrato latch came to sit
    undeclared inside the factory's dead filter array.
    """
    source = (REPO / "src" / "AssemblePressureFix.java").read_text()
    emits = re.findall(r'emit\((?:String\.format\()?"([^"]+)"', source)
    movi = re.compile(r"^MOV (R\d+|LR),0x([0-9a-f]+)$")
    addx = re.compile(r"^ADD (R\d+),(R\d+),(R\d+) << 0x\d$")
    # Stores carry no signedness, so ST.H must be accepted alongside LD.SH.
    mem = re.compile(
        r"^(LD|ST)\.(?:UB|SB|UH|SH|W|D|B|H) (?:(R\d+|LR),)?(R\d+|LR)"
        r"\[(?:(-?0x[0-9a-fA-F]+)\])?")

    known: dict[str, int] = {}
    used: dict[int, str] = {}
    for text in emits:
        match = movi.match(text)
        if match:
            known[match.group(1)] = int(match.group(2), 16)
            continue
        match = addx.match(text)
        if match:
            if match.group(2) in known:
                known[match.group(1)] = known[match.group(2)]
            else:
                known.pop(match.group(1), None)
            continue
        match = mem.match(text)
        if match:
            kind, dst, base, disp = match.groups()
            if base in known:
                # The cell actually touched is base plus the literal
                # displacement; recording only the base let a store through a
                # declared base land in undeclared RAM unnoticed.  Indexed
                # accesses keep the base, the only static fact about them.
                value = known[base] + (int(disp, 16) if disp else 0)
                if 0x800 <= value < 0x10000 and value not in NON_ADDRESS_IMMEDIATES:
                    used.setdefault(value, text)
            if kind == "LD" and dst:
                known.pop(dst, None)
            continue
        match = re.match(r"^\w[\w.{}]*\s+(R\d+|LR)\b", text)
        if match and not text.startswith(("ST.", "CP.", "BR", "TST")):
            known.pop(match.group(1), None)

    regions = declared_ram()
    stray = [(a, t) for a, t in sorted(used.items())
             if not any(start <= a < end for start, end, _ in regions)]
    if stray:
        raise SystemExit(
            "RAM addressed by the patches but declared nowhere:\n"
            + "\n".join(f"  0x{a:04X}  first used by: {t}" for a, t in stray)
            + "\nAdd it to RAM_REGIONS (ours) or FACTORY_CELLS (theirs) in "
              "tools/build.py.")
    print(f"  {len(used)} addressed RAM cells, all declared")


def check_ram_regions() -> None:
    ordered = declared_ram()
    for (a1, b1, n1), (a2, b2, n2) in zip(ordered, ordered[1:]):
        if a2 < b1:
            raise SystemExit(
                f"RAM regions overlap: {n1} [0x{a1:04X}..0x{b1-1:04X}] and "
                f"{n2} [0x{a2:04X}..0x{b2-1:04X}]")


def check_extents(output: str) -> None:
    """No two blocks may claim the same flash, emitted or not."""
    extents = []
    for raw in output.splitlines():
        line = re.sub(r"^INFO\s+\S+>\s*", "", raw.rstrip())
        line = re.sub(r"\s*\(GhidraScript\)\s*$", "", line)
        match = EXTENT_RE.match(line)
        if match:
            extents.append((int(match.group(1), 16), int(match.group(2), 16), match.group(3)))
    extents.sort()
    for (a1, b1, n1), (a2, b2, n2) in zip(extents, extents[1:]):
        if a2 < b1:
            raise SystemExit(
                f"blocks overlap in flash: {n1} [0x{a1:08X}..0x{b1:08X}] and "
                f"{n2} [0x{a2:08X}..0x{b2:08X}] — even a disabled block must "
                "have its own address")
    print(f"  {len(extents)} block extents, no flash collisions")


CONTROL_FLOW = REPO / "tools" / "factory_control_flow.txt"


def check_factory_entry_points(patches, factory_sha: str) -> None:
    """No patch may bury a factory branch target that is still branched to.

    Overwriting factory code is normal here — most patches do.  What is not
    safe is overwriting an address some *other* piece of factory code still
    jumps to, because the jump then lands in the middle of our instructions.
    A target inside a patch whose only sources are inside the same patch is
    fine: we replaced the branch and its destination together.
    """
    if not CONTROL_FLOW.exists():
        raise SystemExit(f"missing {CONTROL_FLOW} — cannot verify patch entry points")
    lines = CONTROL_FLOW.read_text().splitlines()
    recorded = next((l.split()[1] for l in lines if l.startswith("factory_sha256 ")), None)
    if recorded != factory_sha:
        raise SystemExit(
            f"{CONTROL_FLOW.name} was generated from a different base image\n"
            f"  recorded {recorded}\n  current  {factory_sha}\n"
            "Regenerate it (see the header of that file) before building.")
    transfers = [(int(a, 16), int(b, 16)) for a, b in
                 (l.split() for l in lines if re.match(r"^[0-9a-f]{8} [0-9a-f]{8}$", l))]

    problems = []
    for start, payload, description in patches:
        end = start + len(payload)
        # The patch's own start is a legitimate entry point: callers are meant
        # to keep reaching it.  Anything past it is interior.
        for source, target in transfers:
            if start < target < end and not (start <= source < end):
                problems.append(
                    f"  {description or 'patch'} [0x{start:08X}..0x{end:08X}) buries "
                    f"0x{target:08X}, still branched to from 0x{source:08X}")
    if problems:
        raise SystemExit("patch overwrites a live factory branch target:\n"
                         + "\n".join(sorted(set(problems))))
    print(f"  {len(transfers)} factory control transfers checked, no buried entry points")


def parse_patches(output: str) -> list[tuple[int, bytes, str]]:
    patches, skipped = [], []
    for raw in output.splitlines():
        line = re.sub(r"^INFO\s+\S+>\s*", "", raw.rstrip())
        line = re.sub(r"\s*\(GhidraScript\)\s*$", "", line)
        if line.startswith("SKIP "):
            skipped.append(line[5:].split(" ")[0])
            continue
        match = PATCH_RE.match(line)
        if match:
            patches.append(
                (int(match.group(1), 16), bytes.fromhex(match.group(2)), match.group(3) or "")
            )
    if not patches:
        raise SystemExit("assembler produced no PATCH records")
    if skipped:
        print(f"  skipped {len(skipped)} patch(es): {', '.join(sorted(set(skipped)))}")
    return patches


def apply_patches(memory: dict[int, int], patches) -> tuple[int, int]:
    """Apply every patch, returning (bytes changed, bytes newly programmed).

    Code caves sit in erased flash that the factory image never programs, so a
    patch may legitimately add addresses.  Anything landing outside the span of
    the application image is a bug and stops the build — in particular nothing
    may reach the protected DFU bootloader.
    """
    low, high = min(memory), max(memory)
    claimed: dict[int, tuple[int, str]] = {}
    changed = added = 0
    for index, (address, data, note) in enumerate(patches):
        for offset, value in enumerate(data):
            location = address + offset
            if not low <= location <= high:
                raise SystemExit(
                    f"patch at 0x{location:08X} lies outside the application image "
                    f"(0x{low:08X}..0x{high:08X})"
                )
            if location in claimed and claimed[location][0] != index:
                raise SystemExit(
                    f"patches overlap at 0x{location:08X}: "
                    f"{claimed[location][1]!r} and {note!r}"
                )
            claimed[location] = (index, note)
            if location not in memory:
                added += 1
            elif memory[location] != value:
                changed += 1
            memory[location] = value
    return changed, added


def replace_atomically(path: Path, text: str) -> None:
    """Write via a sibling temporary file so the replacement cannot tear.

    The replacement carries the temporary file's permissions, so the original
    mode has to be copied across first — otherwise this silently strips the
    execute bit from the updater and Finder refuses to launch it.

    Bytes are written exactly as given: text mode would translate newlines on
    a Windows host, which would corrupt the line endings this deliberately
    preserves.
    """
    mode = path.stat().st_mode if path.exists() else None
    temporary = path.with_name(path.name + ".new")
    temporary.write_bytes(text.encode())
    if mode is not None:
        os.chmod(temporary, stat.S_IMODE(mode))
    os.replace(temporary, path)



def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default="config/218e.toml")
    parser.add_argument("--tables-only", action="store_true")
    parser.add_argument("--expect-sha")
    parser.add_argument("--no-ghidra", action="store_true",
                        help="assemble with the JavaScript toolchain in "
                             "tools/avr32/ instead of Ghidra")
    parser.add_argument("--fold-measurement", metavar="CSV",
                        help="fold tuner readings into the pitch calibration table, then exit")
    args = parser.parse_args()

    config_path = (REPO / args.config) if not Path(args.config).is_absolute() else Path(args.config)
    raw = tomllib.loads(config_path.read_text())
    # A misspelled table name - [option], [Options] - or an option line that
    # drifted below a later section header used to vanish without a word,
    # and the build proceeded on defaults.
    import options
    stray = sorted(set(raw) - {"options", "firmware", "tools"})
    if stray:
        hints = [k for k in stray if k in options.OPTION_TYPES]
        raise SystemExit(
            f"{config_path.name}: unknown table(s) {', '.join(stray)}"
            + (f" - {', '.join(hints)} belong(s) under [options]" if hints
               else " - only [options], [firmware] and [tools] are read"))
    # The config holds seven user options plus [firmware]/[tools] paths.
    # options.expand() turns those into the full internal settings this script
    # has always consumed, so everything below is unchanged.
    cfg = options.expand(raw.get("options", {}))
    cfg["firmware"] = raw["firmware"]
    if "tools" in raw:
        cfg["tools"] = raw["tools"]
    # config/local.toml (untracked) overrides machine-specific settings, so the
    # committed configuration stays portable.  Only [tools] is merged.
    local = config_path.parent / "local.toml"
    if local.exists():
        for key, value in tomllib.loads(local.read_text()).get("tools", {}).items():
            cfg.setdefault("tools", {})[key] = value
    cfg["_config_name"] = str(config_path.relative_to(REPO)) if config_path.is_relative_to(REPO) else str(config_path)
    BUILD.mkdir(exist_ok=True)
    calibration = REPO / cfg["pitch"]["calibration_csv"]

    if args.fold_measurement:
        # With pitch_correction off, the calibration target is the generated
        # all-zero file that options.expand() rewrites on every run - this
        # very invocation regenerated it a few lines up.  Folding readings
        # into it would print success and lose them on the next build.
        if calibration.resolve() == options.FLAT_CALIBRATION.resolve():
            raise SystemExit(
                "pitch_correction is false, so the pitch table is built from a generated\n"
                "all-zero calibration that every build rewrites - readings folded into it\n"
                "would be lost.  Set pitch_correction to your calibration CSV (for example\n"
                "pitch_correction = \"calibration/218e-pitch-calibration.csv\") and run\n"
                "this again.")
        fold_measurement(cfg, calibration, Path(args.fold_measurement))
        return

    print(f"config: {cfg['_config_name']}")

    # --- factory image ----------------------------------------------------
    factory = REPO / cfg["firmware"]["factory_hex"]
    if not factory.exists():
        raise SystemExit(
            f"factory image not found: {cfg['firmware']['factory_hex']}\n"
            "\n"
            "  No firmware image ships with this repository — the factory image\n"
            "  is Buchla's, and the patched one is that firmware with our\n"
            "  changes in it, so neither is ours to redistribute.\n"
            "\n"
            "  Copy your own 218eV3_v369_DFU.hex to that path.  It comes with\n"
            "  the official Buchla flashing kit; the build checks it against\n"
            f"  SHA-256 {cfg['firmware']['factory_sha256']}\n"
            "  before touching it, so a wrong or altered file is rejected.")
    digest = hashlib.sha256(factory.read_bytes()).hexdigest()
    if digest != cfg["firmware"]["factory_sha256"]:
        raise SystemExit(
            f"factory image mismatch\n  expected {cfg['firmware']['factory_sha256']}"
            f"\n  found    {digest}"
        )
    memory, start_linear = parse_hex(factory)
    print(f"factory image verified: {factory.name}")

    # --- tables -----------------------------------------------------------
    tuning = cfg["tuning"]
    tables = {
        "pressure_curve": pressure_curve(
            cfg["pressure"]["curve"]["span"],
            cfg["pressure"]["curve"]["onset_db"],
            cfg["pressure"]["curve"].get("onset_fade", 0),
        ),
        "pitch_remap": pitch_table(cfg, read_calibration(calibration)),
    }
    reference_key = tuning.get("reference_key", 9)
    # What one step of the octave controls should be, in DAC units.  The
    # factory temperament and every 2/1 scale make this 484.
    periods = set()
    for relative in tuning["slots"]:
        if relative == "factory":
            periods.add(tuning["units_per_octave"])
            continue
        name, map_name = relative if isinstance(relative, (list, tuple)) else (relative, None)
        probe = parse_scala(REPO / name, mapped=True)
        if map_name is None:
            span = probe[12] if len(probe) > 12 else 1200.0
        else:
            span = probe[parse_kbm(REPO / map_name, probe)[1]]
        periods.add(int(math.floor(span * tuning["units_per_octave"] / 1200 + 0.5)))
    if len(periods) > 1:
        raise SystemExit(
            "[tuning].slots disagree about the period: "
            + ", ".join(f"{p} units" for p in sorted(periods))
            + " — the octave controls step one period, and there is one set of "
              "them for the whole instrument, so every slot must repeat at the "
              "same interval (the factory temperament repeats at 484)")
    # The bottom key sits one period above nothing, so the switch's lowest
    # position still lands above zero.  485 for a 2/1, which is what every
    # octave build already has.
    tuning["base_units"] = max(periods) + 1
    periods = set()
    # One (ideal, table, period_cents, period_units) per slot that carries a
    # scale, for the latch-spacing check below.  The factory temperament is not
    # among them: it is copied bit-exact and its semitones are ~40 units apart.
    spacing_slots = []
    for index, relative in enumerate(tuning["slots"]):
        if relative == "factory":
            periods.add(tuning["units_per_octave"])
            tables[f"tuning_slot{index}"] = factory_tuning(memory)
            print(f"  tuning slot {index}: factory temperament (from the base image, "
                  "copied bit-exact, so the anchor does not apply)")
            continue
        scale_name, map_name = (
            (relative, None) if isinstance(relative, str)
            else (relative[0], relative[1] if len(relative) > 1 else None))
        path = REPO / scale_name
        degrees = period = None
        try:
            if map_name is None:
                cents = parse_scala(path, mapped=True)
                if len(cents) - 1 != 12:
                    raise ValueError(
                        f"{path.name}: {len(cents) - 1} degrees — the key table "
                        "gives one entry per key, so without a .kbm to map them "
                        "a 12-note scale is required")
                period = cents[12]
            else:
                cents = parse_scala(path, mapped=True)
                degrees, formal = parse_kbm(REPO / map_name, cents)
                period = cents[formal]
            offset = anchor_offset(cents, reference_key, degrees, period or 1200.0)
        except ValueError as error:
            raise SystemExit(str(error))
        # The anchor keeps one key where equal temperament puts it, so one trim
        # on the 208 serves every slot.  That is a statement about a 12-TET
        # grid, and a scale that does not repeat at the octave has no place on
        # one: pinning its ninth key to A's pitch only transposes the whole
        # scale by whatever the difference happens to be, and spends the
        # headroom the octave switch needs.  Degree 0 keeps the bottom key.
        if abs((period or 1200.0) - 1200.0) > 0.001:
            offset = 0.0
        table = tuning_table(
            cents, tuning["base_units"], tuning["units_per_octave"], offset,
            degrees, period or 1200.0
        )
        period_units = int(math.floor(
            (period or 1200.0) * tuning["units_per_octave"] / 1200 + 0.5))
        try:
            check_table_range(path.name, table, period_units)
        except ValueError as error:
            raise SystemExit(str(error))
        tables[f"tuning_slot{index}"] = table
        periods.add(period_units)
        spacing_slots.append((
            ideal_key_pitches(cents, degrees, period or 1200.0, offset),
            table, period or 1200.0, period_units))
        anchor = (NOTE_NAMES[reference_key] if abs((period or 1200.0) - 1200.0) <= 0.001
                  else "bottom key")
        shape = ("" if degrees is None else
                 f", {Path(map_name).name}: {len(degrees)} keys per octave")
        print(f"  tuning slot {index}: {path.name}"
              f"  ({anchor} anchored, {offset:+.2f} cents{shape})")
    cfg["_min_key_spacing"] = min_key_spacing(spacing_slots)
    # The octave controls - the panel switch, the arpeggiator's random octave,
    # knob 3's span - are one setting for the whole build, so every slot has to
    # agree about how big an octave is.  Mixing a 2/1 scale with one that
    # repeats somewhere else would leave the controls right for one slot and
    # wrong for the others; the factory temperament counts as a 2/1.
    if len(periods) > 1:
        raise SystemExit(
            "[tuning].slots disagree about the period: "
            + ", ".join(f"{p} units" for p in sorted(periods))
            + " — the octave controls step one period, and there is one set of "
              "them for the whole instrument, so every slot must repeat at the "
              "same interval (the factory temperament repeats at 484)")
    cfg["_octave_units"] = periods.pop() if periods else tuning["units_per_octave"]
    if len(tuning["slots"]) != 3:
        raise SystemExit("[tuning].slots must list exactly three scales")

    # Per-key black-key correction as a Q8 excess: 0 for white keys, and
    # round(scale*256)-256 for black ones.  A table makes the correction
    # branchless at every use site and lets the same numbers serve the
    # pressure aggregate and the portamento weighting.
    black_mask = 0x0A54A54A
    excess = round(cfg["pressure"]["black_key_scale"] * 256) - 256
    if not 0 <= excess <= 0x400:
        raise SystemExit("[pressure].black_key_scale must be between 1.0 and 5.0")
    tables["black_key_excess"] = [excess if (black_mask >> k) & 1 else 0 for k in range(32)]

    (BUILD / "tables.txt").write_text(
        "\n".join(f"{name} ({len(v)}):\n  " + ", ".join(map(str, v)) for name, v in sorted(tables.items()))
        + "\n"
    )

    # --- settings ---------------------------------------------------------
    calib = cfg["pressure"]["calibration"]
    cfg["_numbers"] = {
        "pressure_floor_default": calib["floor"],
        "pressure_ceiling_default": calib["ceiling"],
        "scan_period_ms": cfg["timing"]["scan_period_ms"],
        "proximity_reference": cfg["pressure"].get("proximity_reference", 300),
        "octave_units": cfg.get("_octave_units", cfg["tuning"]["units_per_octave"]),
        "factory_gain_shift": cfg["diagnostics"].get("factory_gain_shift", 3),
        # Direct indexing, as above at the excess table: the two dead
        # fallbacks here used to disagree (1.0 vs 1.35), which would have
        # split one correction across its two consumers had the key ever
        # gone missing from the frozen defaults.
        "black_key_scale_32": round(cfg["pressure"]["black_key_scale"] * 32),
        "smoothing_taps": cfg["pressure"].get("smoothing_taps", 8),
        "curve_default_level": cfg["pressure"]["curve"].get("default_level", 31),
        # One more than the top level, because the knob maps adc*steps>>10.
        "curve_knob_steps": cfg["pressure"]["curve"].get("knob_max_level", 31) + 1,
        "resolution_bits": cfg["pressure"].get("resolution_bits", 4),
        "multi_key_max": 1 if cfg["pressure"].get("multi_key", "max") == "max" else 0,
    }
    period = cfg["timing"]["scan_period_ms"]
    if period != 5:
        print(f"  scan period {period} ms ({1000/period:.0f} Hz) — NON-DEFAULT.")
        print(f"    glide, vibrato and the pressure attack ramp all run "
              f"{5/period:.2f}x faster; scan CPU load is {5/period:.2f}x.")
        print("    Verify the instrument keeps up before trusting it "
              "(see docs/BUILD.md).")
    span = calib["trim_span"]
    if span not in (128, 256, 512):
        raise SystemExit("[pressure.calibration].trim_span must be 128, 256 or 512")
    # The knob centres on the configured default and reaches +/- half the span,
    # so changing a default carries its trim range along with it.  The bases
    # only exist in independent mode; scale mode multiplies the defaults.
    cfg["_numbers"]["trim_shift"] = {512: 1, 256: 2, 128: 3}[span]
    cfg["_numbers"]["floor_knob_base"] = max(calib["floor"] - span // 2, 128)
    cfg["_numbers"]["ceiling_knob_base"] = max(calib["ceiling"] - span // 2, 128)
    if calib.get("trim_mode", "independent") == "independent":
        for what in ("floor", "ceiling"):
            base = calib[what] - span // 2
            if base < 128:
                raise SystemExit(
                    f"[pressure.calibration]: {what} {calib[what]} with trim_span {span} "
                    f"puts the knob base at {base}; raise the default or narrow the span")
    if not calib["floor"] + 32 <= calib["ceiling"]:
        raise SystemExit("[pressure.calibration]: ceiling must exceed floor by at least 32")

    check_ram_regions()
    check_ram_coverage()
    blocks, features, summary = resolve_flags(cfg)
    # The factory's own octave arithmetic only needs rewriting when an octave
    # has stopped being a 2/1; at 484 these patches would write back the bytes
    # that are already there.
    for name in ("octave_step_down", "octave_step_up", "octave_step_up2",
                 "octave_scale_mul", "octave_scale_bias"):
        blocks[name] = (cfg.get("_octave_units", cfg["tuning"]["units_per_octave"])
                        != cfg["tuning"]["units_per_octave"])
    claims = [n for n in ("scan_profiler", "telemetry_smoothing", "latch_probe",
                          "clock_latency")
              if features.get(n)]
    if len(claims) > 1:
        raise SystemExit(
            "diagnostics: " + " and ".join(claims) + " all claim the same two "
            "telemetry fields; enable one at a time")
    if features.get("scan_profiler") and features.get("telemetry_smoothing"):
        raise SystemExit(
            "diagnostics: scan_profiler and telemetry_smoothing both claim the "
            "scan-component telemetry fields — enable only one"
        )
    # Pressure response fix off: return every pointer that reaches the reworked
    # pressure path to its factory value, and drop the one hook that overwrites
    # factory code outright.  The caves are still assembled into unused flash —
    # nothing reaches them, so the original curve, filter and single-key
    # sourcing run exactly as they shipped.
    #
    # note_on_pool / active_key_pool are deliberately NOT gated here: those
    # wrappers also carry the arp latch toggle and the press-order append, and
    # their filter-reset stores land on a cell the factory path never reads.
    # The arp gate hook exists to latch knobs 1-3 for the replacement arp
    # behaviours.  With all three left factory nothing consumes the latches,
    # and a hook that only feeds our own RAM would still replace factory
    # code the config promised to keep - so it stays out entirely.
    blocks["arp_gate_hook"] = any(
        get(cfg, f"knobs.knob{i}") != "factory" for i in (1, 2, 3))

    if cfg.get("_pressure_factory"):
        # knob4_pool goes back too: edit-mode knob 4 is the curve selector,
        # which is pressure work - left routed, a pressure-off build's knob-4
        # sweep wrote curve-marked values into the factory velocity-min byte,
        # which the factory persists.
        for name in ("pressure_fn_pool", "pressure_float_helper_pool",
                     "knob1_pool", "knob4_pool", "pressure_gain_nop"):
            blocks[name] = False
        # The clamp skips jump over the factory's own 16-tap pressure filter.
        # They used to be unconditional, so "pressure off" still ran without
        # that filter and without ours - neither factory nor Rewired.  The
        # cells that made the skip necessary have moved out of the array, so
        # it can go with the rest of the pressure work.
        blocks["pitch_clamp_skip_1"] = False
        blocks["pitch_clamp_skip_2"] = False

    # No Scala file supplied means no tuning to switch between, so the edit
    # keys and their LEDs stay factory.  Both key blocks overwrite factory code
    # in place — key 27 was the transpose-mode toggle, key 28 the remote-enable
    # toggle — and the applier asserts the LEDs and zeroes the old
    # transpose-mode byte, so all three have to go, not just the keys.
    factory_tunings = all(slot == "factory" for slot in cfg["tuning"]["slots"])
    if factory_tunings:
        features["alternate_tunings"] = False
        blocks["edit_key27_tuning_slot1"] = False
        blocks["edit_key28_tuning_slot0"] = False
        # Remote enable goes back with them.  The guards were added when the
        # tuning selector lived in state+0x2, the factory's remote-enable
        # flag; it moved to RAM 0x6090 and nothing shares that byte any more,
        # so with no tuning installed there is nothing to protect against.
        for name in ("remote_guard_1", "remote_guard_2", "remote_guard_3"):
            blocks[name] = False
    else:
        features["alternate_tunings"] = True

    # Transpose mode survives only when nothing has taken what it needs.  The
    # tuning applier zeroes the transpose-mode byte outright, and the knob
    # remap takes the knobs transpose is driven with, so either option retires
    # it.  With both off there is nothing in its way, so key 27 and the trn
    # LED work as they shipped and these three forcing patches stay out.
    factory_knobs = all(v == "factory" for v in cfg["knobs"].values())
    if factory_tunings and factory_knobs:
        for name in ("transpose_force_1", "transpose_force_2", "transpose_force_3"):
            blocks[name] = False

    # Arp latch reads the live octave offset through the blend hook, so the
    # blend *caves* have to exist whenever latch is on — but the pressure
    # *following* inside them (feature.pressure_blend) is independent, and can
    # be off.  Forcing the blocks on here decouples "latch" from "pressure
    # portamento": each is its own switch.
    if get(cfg, "arp.switch") == "latch":
        blocks["pitch_target_blend_hook"] = True
        blocks["blend_offset_apply"] = True
        # The conditioner ends in a call to the apply shim, so the two exist
        # together - with neither the blend nor the latch, that call would
        # name erased flash.  Dead today (the pitch pool routes around the
        # conditioner when the blend is off), but not something to leave
        # where a future route could reach it.
        blocks["blend_target_conditioner"] = True
    else:
        # The factory's long-hold on the arp switch toggles polyphonic MIDI.
        # We suppress it so the edit-mode setting has one owner, but that is
        # only needed while we own the switch: with the factory arp switch
        # back, its long-hold comes back with it.
        blocks["poly_arp_independence"] = False

    # How far apart two derived pitches may be and still count as the same
    # note.  Both sides of the toggle's match are built from the transpose at
    # 0x60A0, which rounds: the probe measured it moving by one unit between a
    # latch and the press meant to release it.  Semitones are ~40 units apart,
    # so anything well under 20 cannot reach the neighbouring note.  The
    # assembler compares against tolerance+1, which must stay inside imm6.
    tolerance = cfg["arp"].get("latch_match_tolerance", 8)
    if isinstance(tolerance, bool) or not isinstance(tolerance, int) or not 0 <= tolerance <= 30:
        raise SystemExit("[arp].latch_match_tolerance must be an integer from 0 to 30")
    closest = cfg.get("_min_key_spacing")
    if closest is not None and get(cfg, "arp.switch") == "latch":
        # The table's gap is the nominal one, and the runtime's is up to a unit
        # smaller: the note that was latched keeps the transpose it was pressed
        # at, and the two paths that publish it do not always agree to the unit
        # - one carries the base, one the period, and those differ by exactly
        # the one the base adds.  So a nominal 9 is an 8 under the fingers,
        # which a tolerance of 8 matches.  Comparing the nominal gap alone
        # emitted an image where the second note cleared the first.
        if tolerance + TRANSPOSE_SLACK >= closest:
            raise SystemExit(
                f"[arp].latch_match_tolerance is {tolerance}, but the closest two "
                f"different keys get in this build is {closest} units "
                f"({closest * 2.48:.0f} cents), and the transpose the latch "
                f"compares against can move by {TRANSPOSE_SLACK} between the "
                f"press that latches a note and the press meant to release it "
                f"— so the latch would treat them as one note.  Use less than "
                f"{closest - TRANSPOSE_SLACK}.")
        if tolerance * 2 > closest:
            print(f"  note: latch_match_tolerance {tolerance} is over half the "
                  f"{closest}-unit gap between the closest keys; {closest // 2 - 1} "
                  "or less keeps the margin the semitone default has")
    cfg["_numbers"]["latch_match_tolerance"] = tolerance
    if get(cfg, "arp.switch") == "latch":
        gap = ("semitone is ~40 units" if closest is None
               else f"closest keys are {closest} units apart")
        summary.append(f"  {'arp.latch_match_tolerance':28s} "
                       f"{tolerance}  (+-{tolerance * 2.48:.0f} cents, "
                       f"{'exact match' if tolerance == 0 else gap})")

    mode = calib.get("trim_mode", "independent")
    if mode not in ("independent", "scale"):
        raise SystemExit("[pressure.calibration].trim_mode must be 'independent' or 'scale'")
    features["pressure_trim_scale"] = mode == "scale"
    if mode == "scale":
        # Knob 1 owns the whole calibration; knob 3 goes back to the factory so
        # nothing else can write an endpoint behind its back.
        blocks["knob3_pressure_floor"] = False
        blocks["knob3_pool"] = False
    # Scale mode multiplies both endpoints by k/256.  The pressure path treats
    # a ceiling above 1023 as invalid, so cap the multiplier at the value that
    # keeps the scaled ceiling inside that limit: the knob then stays
    # proportional across its whole travel instead of pinning the ceiling and
    # narrowing the window as it climbs.
    # The bottom of the trim range, as a multiplier in 1/256ths.  It is a
    # setting rather than a constant because it decides what the knob can still
    # reach: capacitive coupling falls by about a third when the player's feet
    # leave the floor, and a range that starts above that cannot get back to it.
    k_min = int(round(calib.get("trim_min", 0.70) * 256))
    k_max = min(0x180, (0x3FF * 256) // calib["ceiling"])
    if mode == "scale" and k_max <= k_min:
        raise SystemExit(
            f"[pressure.calibration]: ceiling {calib['ceiling']} leaves no room to "
            "scale up (the pressure path rejects a ceiling above 1023)")
    if mode == "scale" and k_max - k_min < 0x10:
        raise SystemExit(
            f"[pressure.calibration]: trim_min {calib.get('trim_min', 0.70)} "
            f"leaves only {k_max - k_min} of the 16 steps the knob encoding "
            "needs below the ceiling cap - lower trim_min or the ceiling")
    cfg["_numbers"]["trim_scale_span"] = max(k_max - k_min, 0x10)
    cfg["_numbers"]["trim_scale_base"] = k_min
    # Where the configured calibration lands on the knob, which is what anyone
    # setting these numbers actually wants to know.
    if mode == "scale" and k_max > k_min:
        # The knob is reversed - clockwise lowers the multiplier - so the
        # position counts down from the top of the range, not up from k_min.
        at = 10 - (256 - k_min) * 10 / (k_max - k_min)
        summary.append(f"  {'pressure.trim_unity_at':28s} {at:.1f} of 10")
    if mode == "scale":
        # Printed in the order the knob sweeps them.
        summary.append(f"  {'pressure.trim_mode':28s} {mode!r}  "
                       f"({k_max/256:.2f}x..{k_min/256:.2f}x clockwise)")
    else:
        summary.append(f"  {'pressure.trim_mode':28s} {mode!r}")


    # Output smoothing is a finite interpolation length in 1 kHz DAC ticks.
    # Zero turns it off, and the three patches that implement it stand or fall
    # together — the scan's store is redirected to a target only the
    # interpolator reads.
    smoothing = cfg["pressure"]["output_smoothing"]
    if isinstance(smoothing, bool) or not isinstance(smoothing, int) or not 0 <= smoothing <= 8:
        raise SystemExit("[pressure].output_smoothing must be an integer from 0 to 8")
    if smoothing:
        cfg["_numbers"]["output_interpolation_steps"] = smoothing

    # Extra scans the trigger waits after the pitch reaches the DAC, so the CV
    # has time to arrive.  The output stage is a single pole of tau ~= 0.9 ms,
    # so one scan (5.6 tau at the default period) lands within 0.4% of target.
    settle = cfg["timing"].get("gate_settle_scans", 1)
    if isinstance(settle, bool) or not isinstance(settle, int) or not 0 <= settle <= 3:
        raise SystemExit("[timing].gate_settle_scans must be an integer from 0 to 3")
    cfg["_numbers"]["gate_settle_scans"] = settle
    period = cfg["timing"]["scan_period_ms"]
    summary.append(f"  {'timing.gate_settle_scans':28s} {settle}  "
                   + ("fire as soon as the pitch lands" if settle == 0 else
                      f"trigger held up to {settle * period} ms longer "
                      f"(total up to {(settle + 1) * period} ms after the event)"))

    # Bend slew: 1/2^n of the remaining gap per scan, 0 meaning no smoothing.
    slew = cfg["portamento"].get("blend_slew_shift", 2)
    if isinstance(slew, bool) or not isinstance(slew, int) or not 0 <= slew <= 4:
        raise SystemExit("[portamento].blend_slew_shift must be an integer from 0 to 4")
    cfg["_numbers"]["blend_slew_shift"] = slew
    summary.append(f"  {'portamento.blend_slew_shift':28s} "
                   f"{slew}  ({'no smoothing' if slew == 0 else f'1/{1 << slew} of the gap per scan'})")
    # Vibrato output rounding.  Diffusion keeps the average exact at the cost
    # of ~100 Hz toggling between adjacent pitch units; truncation is coarser
    # and silent.
    dither = cfg.get("vibrato", {}).get("dither", 1)
    if isinstance(dither, bool) or not isinstance(dither, int) or dither not in (0, 1):
        raise SystemExit("[vibrato].dither must be 0 or 1")
    cfg["_numbers"]["vibrato_dither"] = dither
    summary.append(f"  {'vibrato.dither':28s} "
                   f"{dither}  ({'error diffusion' if dither else 'truncate'})")
    # Blend target conditioning: an EMA (1/2^n per scan) and a backlash band
    # (in pitch units, 2.48 cents each) between the published offset target
    # and the slew that chases it.  0/0 reproduces the unconditioned chain.
    filt = cfg["portamento"].get("blend_filter_shift", 2)
    if isinstance(filt, bool) or not isinstance(filt, int) or not 0 <= filt <= 4:
        raise SystemExit("[portamento].blend_filter_shift must be an integer from 0 to 4")
    cfg["_numbers"]["blend_filter_shift"] = filt
    hyst = cfg["portamento"].get("blend_hysteresis", 3)
    if isinstance(hyst, bool) or not isinstance(hyst, int) or not 0 <= hyst <= 8:
        raise SystemExit("[portamento].blend_hysteresis must be an integer from 0 to 8")
    cfg["_numbers"]["blend_hysteresis"] = hyst
    summary.append(f"  {'portamento.blend_filter_shift':28s} "
                   f"{filt}  ({'off' if filt == 0 else f'1/{1 << filt} of the gap per scan'})")
    summary.append(f"  {'portamento.blend_hysteresis':28s} "
                   f"{hyst}  ({'off' if hyst == 0 else f'+-{hyst * 2.48:.1f} cents of backlash'})")
    # Knob-scaled slew: the portamento knob picks the glide's smoothing rate
    # (1/2..1/16 of the gap per scan across the travel).  0 keeps the fixed
    # blend_slew_shift rate at every knob position.
    staper = cfg["portamento"].get("blend_slew_taper", 1)
    if isinstance(staper, bool) or not isinstance(staper, int) or staper not in (0, 1):
        raise SystemExit("[portamento].blend_slew_taper must be 0 or 1")
    cfg["_numbers"]["blend_slew_taper"] = staper
    summary.append(f"  {'portamento.blend_slew_taper':28s} "
                   f"{staper}  ({'knob picks the rate' if staper else 'fixed rate'})")
    # Knob 1: 0 keeps the 1.x blend from press order into randomness, 1 cuts
    # the travel into six zones - ascending, descending, mirror, press order,
    # reverse press order, random.
    orders = cfg.get("arp_order", {}).get("knob1_orders", 0)
    if isinstance(orders, bool) or not isinstance(orders, int) or orders not in (0, 1):
        raise SystemExit("[arp_order].knob1_orders must be 0 or 1")
    cfg["_numbers"]["knob1_orders"] = orders
    blocks["arp_order_zones"] = orders == 1
    summary.append(f"  {'arp.knob1_orders':28s} "
                   f"{orders}  ({'six zones' if orders else 'press-to-random blend'})")
    # Knob 4: vibrato as in 1.x, or an octave switch.  Both cannot run - they
    # would each want RAM 0x6028, which is the offset the pitch remap adds -
    # so choosing the octave switch takes the vibrato engine out of the build.
    k4 = cfg.get("knob4", {}).get("octaves", 0)
    if isinstance(k4, bool) or not isinstance(k4, int) or k4 not in (0, 1):
        raise SystemExit("[knob4].octaves must be 0 or 1")
    cfg["_numbers"]["knob4_octaves"] = k4
    # How many positions knob 4 gets.  The factory has nine: three that mean
    # no transpose, then six steps up.  Six OCTAVES is the reach, so a scale
    # whose period is wider gets proportionally fewer steps rather than a
    # knob whose top half pushes everything past the DAC and the oscillator.
    # An octave build comes out at nine, which is the factory's own count.
    step = cfg.get("_octave_units", cfg["tuning"]["units_per_octave"])
    cfg["_numbers"]["knob4_zones"] = 3 + max(
        1, (6 * cfg["tuning"]["units_per_octave"]) // step)
    if blocks.get("knob4_octave_switch"):
        summary.append(f"  {'knob4.zones':28s} "
                       f"{cfg['_numbers']['knob4_zones']}  "
                       f"(3 silent, then {cfg['_numbers']['knob4_zones'] - 3} up)")
    blocks["knob4_octave_switch"] = k4 == 1 and get(cfg, "knobs.knob4") == "vibrato"
    if blocks["knob4_octave_switch"]:
        features["knob4_vibrato"] = False
        for name in ("vibrato_engine", "vibrato_sine",
                     "pressure_vibrato_scale", "pressure_vibrato_pool"):
            blocks[name] = False
    summary.append(f"  {'knob4.octaves':28s} "
                   f"{k4}  ({'octave switch' if blocks['knob4_octave_switch'] else 'vibrato'})")
    # Knob 2: randomness as in 1.x, or a bank of step patterns the knob
    # selects from.  A pattern says whether a step sounds at all, which is a
    # different question from how long the step is, so it is gated at the note
    # selector rather than in the rhythm randomiser.
    k2 = cfg.get("knob2", {}).get("mode", "randomness")
    if k2 not in ("randomness", "patterns", "swing"):
        raise SystemExit("[knob2].mode must be 'randomness', 'swing' or 'patterns'")
    bank = list(cfg.get("knob2", {}).get("patterns") or [])
    lens = list(cfg.get("knob2", {}).get("lengths") or [])
    if k2 == "patterns":
        if not bank:
            import clix
            bank, lens = list(clix.CLIX), [32] * len(clix.CLIX)
        if not lens:
            lens = [32] * len(bank)
        if len(lens) != len(bank):
            raise SystemExit("[knob2].lengths must have one entry per pattern")
        if not 1 <= len(bank) <= 32:
            raise SystemExit("[knob2].patterns: give one to 32 patterns")
        for i, (m, n) in enumerate(zip(bank, lens)):
            if not isinstance(m, int) or isinstance(m, bool) or not 0 <= m <= 0xFFFFFFFF:
                raise SystemExit(f"[knob2].patterns[{i}] must be a 32-bit mask")
            if not isinstance(n, int) or isinstance(n, bool) or not 1 <= n <= 32:
                raise SystemExit(f"[knob2].lengths[{i}] must be 1..32")
            if m == 0:
                raise SystemExit(f"[knob2].patterns[{i}] is empty — it would "
                                 "never sound a note")
        tables["arp_pattern_bank"] = [h for m in bank
                                      for h in (m & 0xFFFF, (m >> 16) & 0xFFFF)]
        tables["arp_pattern_len"] = list(lens)
        cfg["_numbers"]["pattern_count"] = len(bank)
    else:
        tables["arp_pattern_bank"] = [0, 0]
        tables["arp_pattern_len"] = [32]
        cfg["_numbers"]["pattern_count"] = 1
    blocks["arp_pattern_gate"] = k2 == "patterns"
    blocks["arp_pattern_tables"] = k2 == "patterns"
    if k2 == "patterns":
        # The rhythm randomiser reads the SAME knob latch, so leaving it in
        # would mean a denser pattern also bought more jitter in the step
        # interval - the hits land unevenly and the pattern is unreadable.
        # A pattern is about which steps sound, and the steps have to be
        # evenly spaced for that to mean anything, so the randomiser goes and
        # the factory's own reload stands.
        blocks["arp_rhythm_hook"] = False
    cfg["_numbers"]["knob2_patterns"] = 1 if k2 == "patterns" else 0
    cfg["_numbers"]["knob2_swing"] = 1 if k2 == "swing" else 0
    cfg["_numbers"]["chord_hold_scans"] = int(cfg.get("sequencer", {}).get("chord_hold_scans", 200))
    cfg["_numbers"]["strip_halfway_units"] = int(
        cfg.get("sequencer", {}).get("strip_halfway_units", 2048))
    cfg["_numbers"]["tie_glide_rate"] = int(cfg.get("sequencer", {}).get("tie_glide_rate", 60))
    cfg["_numbers"]["strip_ack_scans"] = int(
        cfg.get("sequencer", {}).get("strip_ack_scans", 20))
    cfg["_numbers"]["strip_led_rest_units"] = int(
        cfg.get("sequencer", {}).get("strip_led_rest_units", 512))
    cfg["_numbers"]["strip_led_tie_units"] = int(
        cfg.get("sequencer", {}).get("strip_led_tie_units", 4095))
    cfg["_numbers"]["strip_led_dark_units"] = int(
        cfg.get("sequencer", {}).get("strip_led_dark_units", 0))
    cfg["_numbers"]["clock_min_ms"] = int(cfg.get("sequencer", {}).get("clock_min_ms", 4))
    cfg["_numbers"]["clock_lock_pulses"] = int(
        cfg.get("sequencer", {}).get("clock_lock_pulses", 5))
    cfg["_numbers"]["clock_settle_scans"] = int(
        cfg.get("sequencer", {}).get("clock_settle_scans", 0))
    cfg["_numbers"]["clock_deadline_ms"] = int(
        cfg.get("sequencer", {}).get("clock_deadline_ms", 4))
    cfg["_numbers"]["clock_rearm_us"] = int(
        cfg.get("sequencer", {}).get("clock_rearm_us", 250))
    cfg["_numbers"]["clock_max_ms"] = int(
        cfg.get("sequencer", {}).get("clock_max_ms", 2400))
    cfg["_numbers"]["clock_release_ms"] = int(
        cfg.get("sequencer", {}).get("clock_release_ms", 2600))
    cfg["_numbers"]["seq_edit_hold_scans"] = int(
        cfg.get("sequencer", {}).get("seq_edit_hold_scans", 60))
    cfg["_numbers"]["persist_page_count"] = int(
        cfg.get("persist", {}).get("page_count", 8))
    # The trigger spike's length, in scheduler units of (n - 1) milliseconds:
    # the factory's 3 measured 2 ms on the jack. 5 is the ~4 ms Buchla spike
    # the owner asked for, and the ceiling the attack-age guards cover.
    cfg["_numbers"]["trigger_spike_units"] = int(
        cfg.get("sequencer", {}).get("trigger_spike_units", 5))
    seq = bool(cfg.get("sequencer", {}).get("on"))
    div = bool(cfg.get("clock", {}).get("divide"))
    for name in ("clock_scan", "clock_pulse", "clock_hook",
                 "clock_tempo", "clock_tempo_hook",
                 "clock_ms_tick", "clock_ms_pool",
                 "clock_gate", "clock_gate_hook", "clock_settle",
                 "clock_capture", "clock_irq_hook", "clock_irq_pool",
                 "clock_edge_mode", "clock_init", "clock_init_pool",
                 "clock_service", "clock_output", "clock_low_age", "clock_attack_guard",
                 "clock_spike_units", "clock_fast_trigger", "clock_remap_bare",
                 "clock_deadline", "clock_pitch_target"):
        blocks[name] = div
    blocks["profiler_pool"] = div or features.get("scan_profiler", False)
    summary.append(f"  {'clock.divide':28s} {'on' if div else 'off'}")
    keep = bool(cfg.get("persist", {}).get("on"))
    for name in ("persist_crc", "persist_record_crc", "persist_pack",
                 "persist_valid", "persist_newest", "persist_load",
                 "persist_same", "persist_verify", "persist_save", "persist_tick",
                 "persist_capture", "persist_boot", "persist_scan_shim", "persist"):
        blocks[name] = keep
    blocks["clock_init_pool"] = div or keep or seq
    summary.append(f"  {'persist':28s} {'on' if keep else 'off'}")
    blocks["seq_chord"] = seq
    for name in ("seq_enter", "seq_record", "seq_select", "seq_pitch",
                 "seq_clock_enabled", "seq_transport", "seq_clock_rate_hook",
                 "seq_clock_change_hook", "seq_clock_setup_hook", "seq_clock_tick_hook",
                 "seq_clock_input_hook", "seq_clock_midi_hook",
                 "seq_strip", "seq_gate", "seq_glide", "strip_pool",
                 "seq_gate_clear", "seq_gate_clear_hook",
                 "seq_pulse_drop", "pulse_drop_pool", "seq_next_step",
                 "seq_noteoff", "seq_noteoff_hook",
                 "seq_trigger_led", "seq_trigger_led_hook",
                 "seq_strip_led", "strip_dac_redirect",
                 "seq_edit", "seq_preview_step", "seq_command",
                 "seq_preview_next", "seq_preview_start", "seq_preview_transport",
                 "seq_record_pitch", "seq_preview_pin", "seq_hold", "seq_flash",
                 "seq_restart_init", "seq_boot"):
        blocks[name] = seq
    blocks["seq_clock_input_hook"] = seq and not div
    summary.append(f"  {'sequencer':28s} {'on' if seq else 'off'}")
    blocks["arp_swing"] = k2 == "swing"
    summary.append(f"  {'knob2.mode':28s} {k2!r}"
                   + (f"  ({len(bank)} patterns)" if k2 == "patterns" else ""))
    # The event-17 wrapper is shared: pressure smoothing runs its
    # interpolation there, and clock division raises the trigger there. It has
    # to exist for either. `dac_interpolate` is the pressure half alone -
    # pressure_fix off sets smoothing to zero while clock division stays on,
    # and gating the whole wrapper on smoothing left that build's trigger back
    # on the 5 ms scan with the fast-trigger cave unreachable.
    for name in ("dac_interpolator", "dac_flush_pool"):
        blocks[name] = bool(smoothing) or div
    for name in ("dac_interpolate", "pressure_target_redirect"):
        blocks[name] = bool(smoothing)
    summary.append(f"  {'pressure.output_smoothing':28s} "
                   f"{smoothing if smoothing else 'off'!r}")
    print("settings:")
    print("\n".join(summary))

    if args.tables_only:
        print(f"tables written to {BUILD / 'tables.txt'} (Ghidra skipped)")
        return

    # The power-up marker is derived from everything that shapes the build, so
    # any change to the initialised set (or to what it initialises to) produces
    # a different marker and forces a fresh init on the next power-up — SRAM
    # survives a DFU update, and a fixed marker would let an older build's
    # value suppress newly added initialisation.
    fingerprint = hashlib.sha256(
        repr(sorted(blocks.items())).encode()
        + repr(sorted(features.items())).encode()
        + repr(sorted(cfg["_numbers"].items())).encode()
        + repr(sorted(tables.items())).encode()
        + (REPO / "src" / "AssemblePressureFix.java").read_bytes()
    ).digest()
    cfg["_numbers"]["init_marker"] = 0x1000 + (int.from_bytes(fingerprint[:2], "big") % 0xDFFE)

    properties = BUILD / "build.properties"
    write_properties(properties, cfg, blocks, features, tables)

    # --- assemble ---------------------------------------------------------
    if args.no_ghidra:
        output = run_javascript(properties, BUILD / "assemble.js.log")
    else:
        output = run_ghidra(cfg, properties, BUILD / "assemble.log")
    check_extents(output)
    patches = parse_patches(output)
    print(f"  {len(patches)} patch record(s) assembled")
    check_factory_entry_points(patches, cfg["firmware"]["factory_sha256"])

    # --- apply ------------------------------------------------------------
    original = dict(memory)
    changed, added = apply_patches(memory, patches)

    out_path = REPO / cfg["firmware"]["output_hex"]

    # Nothing is written until every check has passed: render the image to a
    # string, verify it round-trips and matches --expect-sha, and only then
    # touch the working tree.  A failed build must never leave an unexpected
    # image or a rewritten updater behind.
    rendered = render_hex(memory, start_linear)
    reread, reread_start = parse_hex_text(rendered, out_path.name)
    if reread != memory or reread_start != start_linear:
        raise SystemExit("round-trip check failed — rendered hex does not read back")
    digest = hashlib.sha256(rendered.encode()).hexdigest()
    if args.expect_sha and digest != args.expect_sha:
        raise SystemExit(
            f"output SHA mismatch — expected {args.expect_sha}, built {digest}"
            "\n  nothing was written"
        )

    version = cfg["firmware"].get("version", "0.0.0")
    version_string = f"Rewired {version} ({digest[:8]})"
    (BUILD / "VERSION").write_text(version_string + "\n")

    # every difference from the factory image must be inside a declared patch
    covered = {a + i for a, data, _ in patches for i in range(len(data))}
    stray = [a for a in original if original[a] != memory[a] and a not in covered]
    if stray:
        raise SystemExit(f"{len(stray)} byte(s) changed outside any patch")

    manifest = [
        f"{'address':10s} {'bytes':>6s}  description",
        *(f"0x{a:08X} {len(d):6d}  {n}" for a, d, n in sorted(patches)),
    ]
    (BUILD / "patch_manifest.txt").write_text("\n".join(manifest) + "\n")

    # Stage the updater's edits too, so a malformed updater cannot leave a new
    # image paired with an old flasher.  Both tracked outputs are validated
    # first, then written together.
    staged_updaters: list[tuple[Path, str, str]] = []
    names = cfg["firmware"].get("updaters") or (
        [cfg["firmware"]["updater"]] if cfg["firmware"].get("updater") else [])
    for updater_name in names:
        updater = REPO / updater_name
        if not updater.exists():
            raise SystemExit(f"{updater_name}: listed in [firmware].updaters but missing")
        # cmd.exe scripts are CRLF; keep whatever the file already uses so the
        # rewrite does not flip line endings underneath it.
        raw = updater.read_bytes().decode()
        # Matches both quoting styles: the shell's EXPECTED_SHA256="..." and
        # cmd's SET "EXPECTED_SHA256=...", replacing only the digest so each
        # file keeps its own syntax.
        patched, count = re.subn(
            r'(EXPECTED_SHA256="?)[0-9a-f]{64}',
            lambda m: m.group(1) + digest, raw)
        if count != 1:
            raise SystemExit(f"{updater_name}: expected exactly one EXPECTED_SHA256 line")
        # The factory digest travels with the build too, so a flasher can name
        # Buchla's stock image in the list instead of showing it as unknown.
        patched, count = re.subn(
            r'(FACTORY_SHA256="?)[0-9a-f]{64}',
            lambda m: m.group(1) + cfg["firmware"]["factory_sha256"], patched)
        if count != 1:
            raise SystemExit(f"{updater_name}: expected exactly one FACTORY_SHA256 line")
        # The version travels with the checksum so a flasher can never announce
        # one build while installing another.
        # Only the declared line, whose value is a literal "Rewired ...".  The
        # flashers also assign FIRMWARE_VERSION at runtime for a custom image
        # ("custom image (...)"), and the build must leave those alone.
        patched, count = re.subn(
            r'(FIRMWARE_VERSION="?)Rewired [^"\r\n]*',
            lambda m: m.group(1) + version_string, patched)
        if count != 1:
            raise SystemExit(f"{updater_name}: expected exactly one declared FIRMWARE_VERSION line")
        # The .bat is committed LF-only and cmd runs it fine; this branch
        # normalised to CRLF only when CRLF was already present, so for the
        # whole life of the LF file it never fired.  Kept for the day the
        # file is converted, with its trigger stated honestly.
        if "\r\n" in raw:
            patched = patched.replace("\r\n", "\n").replace("\n", "\r\n")
        staged_updaters.append((updater, patched, raw))

    # Each replacement is atomic (sibling file, then os.replace), so no reader
    # sees a half-written file.  The pair is still written in sequence: an
    # interruption between them can leave a new image beside an old updater,
    # which the flasher's checksum then refuses to flash.
    replace_atomically(out_path, rendered)
    for updater, patched, text in staged_updaters:
        if patched != text:
            replace_atomically(updater, patched)
            print(f"updated {updater.name} checksum and summary")

    print(f"wrote {out_path.relative_to(REPO)}")
    print(f"  {changed} bytes changed, {added} newly programmed into erased flash")
    print("  all differences from the factory image lie inside declared patches")
    print(f"  SHA-256 {digest}")
    print(f"  {version_string}")

    if args.expect_sha:
        print("  matches --expect-sha")


if __name__ == "__main__":
    main()
