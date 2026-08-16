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

REPO = Path(__file__).resolve().parent.parent
BUILD = REPO / "build"

# Flash address of the factory key -> pitch table (32 halfwords).  A tuning
# slot declared as "factory" is copied verbatim from here, which keeps the
# instrument's original temperament bit-exact instead of re-deriving it.
FACTORY_KEY_TABLE = 0x80016574

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
        ["vibrato_engine", "pressure_vibrato_scale", "pressure_vibrato_pool"],
        ["knob4_vibrato"],
    ),
    "arp.switch":             (
        ["noteoff_pool_1", "noteoff_pool_2", "latch_pitch_toggle"],
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
    "portamento.pressure_blend": (["pitch_target_blend_hook", "blend_offset_apply"], ["pressure_blend"]),
    "portamento.zero_snap":   (["glide_rate_hook"], []),
    "diagnostics.scan_profiler": (["scan_profiler", "profiler_pool"], ["scan_profiler"]),
    "diagnostics.telemetry_smoothing": ([], ["telemetry_smoothing"]),
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
    "portamento.pressure_blend": True,
    "portamento.zero_snap": True,
    "diagnostics.scan_profiler": True,
    "diagnostics.telemetry_smoothing": True,
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
def parse_scala(path: Path) -> list[float]:
    """Return the 12 scale degrees in cents, starting at 0 for the tonic.

    Scala format: '!' comments, then description, then the degree count, then
    that many pitches as either a ratio (a/b) or a cents value (contains '.').
    """
    lines = [
        ln.strip()
        for ln in path.read_text().splitlines()
        if ln.strip() and not ln.lstrip().startswith("!")
    ]
    if len(lines) < 2:
        raise ValueError(f"{path.name}: not a Scala file")
    count = int(lines[1].split()[0])
    pitches = lines[2 : 2 + count]
    if len(pitches) != count:
        raise ValueError(f"{path.name}: declares {count} degrees, found {len(pitches)}")

    cents = [0.0]
    for token in pitches:
        token = token.split()[0]
        if "." in token:
            cents.append(float(token))
        else:
            cents.append(1200.0 * math.log2(float(Fraction(token))))

    if count != 12:
        raise ValueError(
            f"{path.name}: {count} degrees — the key table repeats every octave, "
            "so a 12-note scale is required"
        )
    if any(b <= a for a, b in zip(cents, cents[1:])):
        raise ValueError(
            f"{path.name}: degrees are not strictly ascending — the key table would "
            "descend or repeat")
    if cents[1] <= 0.0:
        raise ValueError(f"{path.name}: first degree must be above the tonic")
    if abs(cents[-1] - 1200.0) > 0.001:
        raise ValueError(
            f"{path.name}: last degree is {cents[-1]:.3f} cents, not a 2/1 octave — "
            "the octave switches would go out of tune"
        )
    return cents[:12]  # degree 12 is the octave, supplied by the octave term


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


def tuning_table(cents: list[float], base: int, per_octave: int) -> list[int]:
    """32 key-table entries: octave-periodic, `per_octave` units per octave."""
    return [
        base + math.floor((1200 * (k // 12) + cents[k % 12]) * per_octave / 1200 + 0.5)
        for k in range(32)
    ]


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

    One row per semitone above the 208p's 0 V pitch.  Offset_Cents is measured
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
        offsets[int(row["Semitone"])] = float(raw)
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


def pitch_table(cfg: dict, offsets: dict[int, float]) -> list[int]:
    """Per-semitone pitch curve, in DAC counts.

    counts(i) = counts_per_volt * (i/12 + offset(i)/1200): an ideal
    1 V/octave ramp displaced by the measured calibration.
    """
    scale = counts_per_volt(cfg)
    table = [
        math.floor(scale * (i / 12.0 + offsets[i] / 1200.0) + 0.5)
        for i in range(max(offsets) + 1)
    ]
    if len(table) != PITCH_TABLE_ENTRIES:
        raise ValueError(
            f"pitch curve has {len(table)} entries, firmware needs {PITCH_TABLE_ENTRIES}")
    if table != sorted(table):
        raise ValueError("pitch curve is not monotonic — check the calibration table")
    if table[0] < 0 or table[-1] > 4095:
        raise ValueError("pitch curve leaves the 12-bit DAC range")
    return table


def octave_width_volts(offsets: dict[int, float], semitone: int) -> float:
    """Volts per octave the 208p actually needs around this semitone.

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
        # index into the calibration table (0 = the 208p's 0 V pitch, an A);
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
    out, applied, trailing = [], 0, 0
    for line in text:
        if line.lstrip().startswith("#") or line.startswith("Semitone"):
            out.append(line)
            continue
        parts = line.split(";")
        semitone = int(parts[0])
        if semitone in updates:
            error = updates[semitone]
            # a sharp note needs less voltage, scaled by the local octave width
            delta = -error * octave_width_volts(offsets, semitone)
            parts[3] = f"{offsets[semitone] + delta:.6f}"
            parts[4] = "measured"
            applied += 1
        elif semitone > highest and parts[4] == "extrapolated":
            parts[3] = f"{offsets[semitone] + tail_delta:.6f}"
            trailing += 1
        out.append(";".join(parts))
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
        "# Generated by tools/build.py — do not edit.",
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


PATCH_RE = re.compile(r"^PATCH ([0-9a-f]{8}) ([0-9a-f]+)(?: ; (.*))?$")
EXTENT_RE = re.compile(r"^EXTENT ([0-9a-f]{8}) ([0-9a-f]{8}) (\S+)$")

# Every RAM region our code owns, as (start, end, name).  Declaring them here
# means the build fails on an overlap instead of the instrument misbehaving:
# a cave writing into another's state is invisible in the patch bytes, since
# the addresses only exist as immediates.
RAM_REGIONS = [
    # Repurposed cells inside the factory's own 16-tap pressure history
    # (0x3216..0x3235).  That array is dead: we replaced the filter, and
    # pitch_clamp_skip_1 jumps over every instruction that touched it.  See
    # docs/FIRMWARE_CHANGES.md — disabling that patch takes these back.
    (0x3228, 0x322A, "tuning-apply guard"),
    (0x322A, 0x322C, "arp knob 2 latch"),
    (0x322E, 0x3230, "arp knob 3 latch"),
    (0x3232, 0x3233, "deferred-pulse flag"),
    (0x3233, 0x3234, "previous switch position"),
    (0x3234, 0x3236, "vibrato knob latch"),
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
    (0x6046, 0x604C, "octave-switch shadow"),
    (0x604C, 0x604E, "octave-switch boot counter"),
    (0x6050, 0x6080, "pressure history taps"),
    (0x6080, 0x6082, "filter sample count"),
    (0x6082, 0x6084, "filter depth"),
    (0x6084, 0x6086, "interpolator step count"),
    (0x6086, 0x6088, "filter ring index"),
    (0x6088, 0x608C, "filter running sum"),
    (0x608C, 0x608E, "filter newest sample"),
    (0x60A0, 0x60A2, "live transpose offset"),
    (0x60A2, 0x60DC, "latch pitch stamps"),
    (0x60E0, 0x60E2, "blend offset target"),
    (0x60E2, 0x60E4, "blend applied offset"),
    (0x608E, 0x608F, "latch-position mirror"),
    (0x6100, 0x613A, "corrected-pressure cache"),
]

# Factory-owned RAM the patches address absolutely.  Not ours to initialise —
# the factory already does — but listed so that a new cell of our own cannot
# be placed on top of one without the coverage check noticing.
FACTORY_CELLS = [
    (0x0854, 0x088E, "key pitch table"),
    (0x2EEE, 0x2EF0, "glide rate"),
    (0x3212, 0x3214, "pitch mirror"),
    (0x3490, 0x34AD, "per-key touch state"),
    (0x3686, 0x36C0, "per-key raw pressure"),
    (0x3866, 0x3868, "arp step state"),
    (0x38A0, 0x38AE, "state+0x340: latch, mode and last arp key"),
    (0x38B0, 0x38B2, "state+0x350: transpose"),
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
    mem = re.compile(r"^(LD|ST)\.(?:UB|SB|UH|SH|W|D|B|H) (?:(R\d+|LR),)?(R\d+|LR)\[")

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
            kind, dst, base = match.groups()
            if base in known:
                value = known[base]
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
    """
    mode = path.stat().st_mode if path.exists() else None
    temporary = path.with_name(path.name + ".new")
    temporary.write_text(text)
    if mode is not None:
        os.chmod(temporary, stat.S_IMODE(mode))
    os.replace(temporary, path)


def knob_line(cfg: dict) -> str:
    """Describe the outside-edit-mode knobs this configuration actually builds."""
    outside = [("knob1", "arp_order", "arp note order"),
               ("knob2", "arp_rhythm", "arp rhythm"),
               ("knob3", "arp_octaves", "arp random octaves"),
               ("knob4", "vibrato", "vibrato")]
    active = [label for key, enabled, label in outside if cfg["knobs"].get(key) == enabled]
    if len(active) == len(outside):
        return 'echo "Outside edit mode those knobs control the arpeggiator and vibrato."'
    if not active:
        return 'echo "Outside edit mode all four knobs keep their factory behaviour."'
    return f'echo "Outside edit mode: {", ".join(active)}; the others are factory."'


def updater_summary(cfg: dict) -> str:
    """The panel description the flasher prints, derived from this config."""
    calib, curve = cfg["pressure"]["calibration"], cfg["pressure"]["curve"]
    knobs = []
    if calib.get("trim_mode") == "scale":
        knobs.append("  knob 1 = pressure calibration, scaling both endpoints "
                     f"({calib['floor']}/{calib['ceiling']} at centre)")
        knobs.append("  knob 3 = factory behaviour")
    else:
        knobs.append(f"  knob 1 = full-pressure point (default {calib['ceiling']})")
        knobs.append(f"  knob 3 = pressure floor (default {calib['floor']})")
    knobs.append(f"  knob 4 = curve, linear (left) to full 218r (right), "
                 f"default {curve.get('default_level', 0)}")
    lines = [
        "# --- BEGIN GENERATED SUMMARY (tools/build.py rewrites this block) ---",
        'echo "Ordinary edit mode provides the pressure calibration:"',
        *[f'echo "{line}"' for line in knobs],
        knob_line(cfg),
    ]
    if cfg["arp"]["switch"] == "latch":
        lines.append('echo "Arp switch: latch / regular / off. In latch, keys toggle by"')
        lines.append('echo "sounding pitch, so any octave position can release a note."')
    if cfg["portamento"]["pressure_blend"]:
        lines.append('echo "Portamento knob = pressure needed to bend between held notes."')
    lines.append('echo ""')
    lines.append('echo "Calibrating, in ordinary edit mode:"')
    if calib.get("trim_mode") == "scale":
        lines.append('echo "  1. Knob 4 fully left for a linear response."')
        lines.append('echo "  2. Run ReadLEM218_Pressure.command; with no key held, turn knob 1"')
        span = cfg["_numbers"]["trim_scale_span"]
        unity_pct = round(128 * 1024 / span / 1023 * 100)
        lines.append(f'echo "     and type \'settings\' until floor/ceiling read near '
                     f'{calib["floor"]}/{calib["ceiling"]} — the built-in calibration,"')
        lines.append(f'echo "     at about {unity_pct}% of knob travel."')
        lines.append('echo "  3. Play light/mid/max touches; knob 1 scales the whole window,"')
        lines.append('echo "     so one control follows a change in how the instrument couples."')
        lines.append('echo "  4. Turn knob 4 right to taste, then leave edit mode to save."')
    else:
        lines.append('echo "  1. Knob 4 fully left for a linear response."')
        lines.append(f'echo "  2. Adjust knob 1 until \'settings\' reports a ceiling near '
                     f'{calib["ceiling"]}."')
        lines.append(f'echo "  3. Adjust knob 3 until it reports a floor near {calib["floor"]}."')
        lines.append('echo "  4. Turn knob 4 right to taste, then leave edit mode to save."')
    lines.append("# --- END GENERATED SUMMARY ---")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default="config/218e.toml")
    parser.add_argument("--tables-only", action="store_true")
    parser.add_argument("--expect-sha")
    parser.add_argument("--fold-measurement", metavar="CSV",
                        help="fold tuner readings into the pitch calibration table, then exit")
    args = parser.parse_args()

    config_path = (REPO / args.config) if not Path(args.config).is_absolute() else Path(args.config)
    cfg = tomllib.loads(config_path.read_text())
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
        fold_measurement(cfg, calibration, Path(args.fold_measurement))
        return

    print(f"config: {cfg['_config_name']}")

    # --- factory image ----------------------------------------------------
    factory = REPO / cfg["firmware"]["factory_hex"]
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
    for index, relative in enumerate(tuning["slots"]):
        if relative == "factory":
            tables[f"tuning_slot{index}"] = factory_tuning(memory)
            print(f"  tuning slot {index}: factory temperament (from the base image)")
            continue
        path = REPO / relative
        cents = parse_scala(path)
        tables[f"tuning_slot{index}"] = tuning_table(
            cents, tuning["base_units"], tuning["units_per_octave"]
        )
        print(f"  tuning slot {index}: {path.name}")
    if len(tuning["slots"]) != 3:
        raise SystemExit("[tuning].slots must list exactly three scales")

    # Per-key black-key correction as a Q8 excess: 0 for white keys, and
    # round(scale*256)-256 for black ones.  A table makes the correction
    # branchless at every use site and lets the same numbers serve the
    # pressure aggregate and the portamento weighting.
    black_mask = 0x0A54A54A
    excess = round(cfg["pressure"].get("black_key_scale", 1.0) * 256) - 256
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
        "factory_gain_shift": cfg["diagnostics"].get("factory_gain_shift", 3),
        "black_key_scale_32": round(cfg["pressure"].get("black_key_scale", 1.35) * 32),
        "smoothing_taps": cfg["pressure"].get("smoothing_taps", 8),
        "curve_default_level": cfg["pressure"]["curve"].get("default_level", 31),
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
    if features.get("scan_profiler") and features.get("telemetry_smoothing"):
        raise SystemExit(
            "diagnostics: scan_profiler and telemetry_smoothing both claim the "
            "scan-component telemetry fields — enable only one"
        )
    if get(cfg, "arp.switch") == "latch" and not get(cfg, "portamento.pressure_blend"):
        raise SystemExit(
            "arp latch needs portamento.pressure_blend: the latch transpose hold "
            "captures the live octave offset through the blend hook"
        )

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
    k_min = 0x80
    k_max = min(0x180, (0x3FF * 256) // calib["ceiling"])
    if mode == "scale" and k_max <= k_min:
        raise SystemExit(
            f"[pressure.calibration]: ceiling {calib['ceiling']} leaves no room to "
            "scale up (the pressure path rejects a ceiling above 1023)")
    cfg["_numbers"]["trim_scale_span"] = max(k_max - k_min, 0x10)
    if mode == "scale":
        summary.append(f"  {'pressure.trim_mode':28s} {mode!r}  "
                       f"({k_min/256:.2f}x..{k_max/256:.2f}x)")
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

    # Bend slew: 1/2^n of the remaining gap per scan, 0 meaning no smoothing.
    slew = cfg["portamento"].get("blend_slew_shift", 2)
    if isinstance(slew, bool) or not isinstance(slew, int) or not 0 <= slew <= 4:
        raise SystemExit("[portamento].blend_slew_shift must be an integer from 0 to 4")
    cfg["_numbers"]["blend_slew_shift"] = slew
    summary.append(f"  {'portamento.blend_slew_shift':28s} "
                   f"{slew}  ({'no smoothing' if slew == 0 else f'1/{1 << slew} of the gap per scan'})")
    for name in ("dac_interpolator", "dac_flush_pool", "pressure_target_redirect"):
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
    staged_updater: tuple[Path, str, str] | None = None
    updater_name = cfg["firmware"].get("updater")
    if updater_name:
        updater = REPO / updater_name
        text = updater.read_text()
        patched, count = re.subn(
            r'EXPECTED_SHA256="[0-9a-f]{64}"', f'EXPECTED_SHA256="{digest}"', text
        )
        if count != 1:
            raise SystemExit(f"{updater_name}: expected exactly one EXPECTED_SHA256 line")
        # The panel summary is generated from this configuration, so the
        # instructions the flasher prints can never describe a build it is not
        # actually installing.
        patched, count = re.subn(
            r"# --- BEGIN GENERATED SUMMARY.*?# --- END GENERATED SUMMARY ---",
            updater_summary(cfg), patched, flags=re.S,
        )
        if count != 1:
            raise SystemExit(f"{updater_name}: generated-summary markers missing")
        staged_updater = (updater, patched, text)

    # Each replacement is atomic (sibling file, then os.replace), so no reader
    # sees a half-written file.  The pair is still written in sequence: an
    # interruption between them can leave a new image beside an old updater,
    # which the flasher's checksum then refuses to flash.
    replace_atomically(out_path, rendered)
    if staged_updater is not None:
        updater, patched, text = staged_updater
        if patched != text:
            replace_atomically(updater, patched)
            print(f"updated {updater.name} checksum and summary")

    print(f"wrote {out_path.relative_to(REPO)}")
    print(f"  {changed} bytes changed, {added} newly programmed into erased flash")
    print("  all differences from the factory image lie inside declared patches")
    print(f"  SHA-256 {digest}")

    if args.expect_sha:
        print("  matches --expect-sha")


if __name__ == "__main__":
    main()
