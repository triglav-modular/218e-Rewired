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
    "knobs.knob4":            (["vibrato_engine"], ["knob4_vibrato"]),
    "arp.switch":             (["noteoff_pool_1", "noteoff_pool_2"], ["arp_latch"]),
    "midi.poly_default":      ([], ["poly_midi_default_off"]),
    "pressure.common_mode":   (["proximity_estimator"], ["pressure_common_mode"]),
    "portamento.pressure_blend": (["pitch_target_blend_hook"], []),
    "portamento.zero_snap":   (["glide_rate_hook"], []),
    "diagnostics.scan_profiler": (["scan_profiler", "profiler_pool"], ["scan_profiler"]),
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
    "portamento.pressure_blend": True,
    "portamento.zero_snap": True,
    "diagnostics.scan_profiler": True,
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


def pressure_curve(span: int, onset_db: float) -> list[int]:
    """218r-style response: 0 at the floor, an onset step, then a smooth rise.

    v(0) = 0; v(x) = span * 10**((x/span - 1) * -onset_db/20), clamped
    monotone and to `span`.  onset_db = -10 gives the 218r's ~32 % onset.
    """
    out, previous = [], 0
    exponent = -onset_db / 20.0
    for x in range(span + 1):
        if x == 0:
            value = 0
        else:
            value = math.floor(span * 10.0 ** ((x / span - 1.0) * exponent) + 0.5)
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
    memory: dict[int, int] = {}
    upper = 0
    start_linear = 0x80002000
    for number, text in enumerate(path.read_text().splitlines(), 1):
        if not text.startswith(":"):
            raise ValueError(f"{path.name} line {number}: missing Intel HEX colon")
        record = bytes.fromhex(text[1:])
        if sum(record) & 0xFF:
            raise ValueError(f"{path.name} line {number}: bad checksum")
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
            raise ValueError(f"{path.name} line {number}: record type {kind}")
    return memory, start_linear


def write_hex(path: Path, memory: dict[int, int], start_linear: int) -> None:
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
    path.write_text("\n".join(lines) + "\n")


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
    ghidra_home = Path(os.environ.get("GHIDRA_HOME", cfg["tools"]["ghidra_home"]))
    headless = ghidra_home / "support" / "analyzeHeadless"
    if not headless.exists():
        raise SystemExit(
            f"Ghidra not found at {headless}\n"
            "Set [tools].ghidra_home in the config or the GHIDRA_HOME env var."
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
    claimed: dict[int, str] = {}
    changed = added = 0
    for address, data, note in patches:
        for offset, value in enumerate(data):
            location = address + offset
            if not low <= location <= high:
                raise SystemExit(
                    f"patch at 0x{location:08X} lies outside the application image "
                    f"(0x{low:08X}..0x{high:08X})"
                )
            if location in claimed and claimed[location] != note:
                raise SystemExit(
                    f"patches overlap at 0x{location:08X}: "
                    f"{claimed[location]!r} and {note!r}"
                )
            claimed[location] = note
            if location not in memory:
                added += 1
            elif memory[location] != value:
                changed += 1
            memory[location] = value
    return changed, added


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
            cfg["pressure"]["curve"]["span"], cfg["pressure"]["curve"]["onset_db"]
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
    # so changing a default carries its trim range along with it.
    cfg["_numbers"]["trim_shift"] = {512: 1, 256: 2, 128: 3}[span]
    cfg["_numbers"]["floor_knob_base"] = calib["floor"] - span // 2
    cfg["_numbers"]["ceiling_knob_base"] = calib["ceiling"] - span // 2
    for what in ("floor", "ceiling"):
        base = cfg["_numbers"][f"{what}_knob_base"]
        if base < 128:
            raise SystemExit(
                f"[pressure.calibration]: {what} {calib[what]} with trim_span {span} "
                f"puts the knob base at {base}; raise the default or narrow the span")
    if not calib["floor"] + 32 <= calib["ceiling"]:
        raise SystemExit("[pressure.calibration]: ceiling must exceed floor by at least 32")

    blocks, features, summary = resolve_flags(cfg)

    mode = calib.get("trim_mode", "independent")
    if mode not in ("independent", "scale"):
        raise SystemExit("[pressure.calibration].trim_mode must be 'independent' or 'scale'")
    features["pressure_trim_scale"] = mode == "scale"
    if mode == "scale":
        # Knob 1 owns the whole calibration; knob 3 goes back to the factory so
        # nothing else can write an endpoint behind its back.
        blocks["knob3_pressure_floor"] = False
        blocks["knob3_pool"] = False
    summary.append(f"  {'pressure.trim_mode':28s} {mode!r}")


    # Output smoothing is a shift, not a toggle: 0 turns it off, and the three
    # patches that implement it stand or fall together — the scan's store is
    # redirected to a target only the interpolator reads.
    smoothing = cfg["pressure"]["output_smoothing"]
    if smoothing:
        cfg["_numbers"]["output_smoothing_shift"] = smoothing
    for name in ("dac_interpolator", "dac_flush_pool", "pressure_target_redirect"):
        blocks[name] = bool(smoothing)
    summary.append(f"  {'pressure.output_smoothing':28s} "
                   f"{smoothing if smoothing else 'off'!r}")
    print("settings:")
    print("\n".join(summary))

    if args.tables_only:
        print(f"tables written to {BUILD / 'tables.txt'} (Ghidra skipped)")
        return

    properties = BUILD / "build.properties"
    write_properties(properties, cfg, blocks, features, tables)

    # --- assemble ---------------------------------------------------------
    output = run_ghidra(cfg, properties, BUILD / "assemble.log")
    patches = parse_patches(output)
    print(f"  {len(patches)} patch record(s) assembled")

    # --- apply ------------------------------------------------------------
    original = dict(memory)
    changed, added = apply_patches(memory, patches)

    out_path = REPO / cfg["firmware"]["output_hex"]
    write_hex(out_path, memory, start_linear)

    # round-trip: the file we wrote must read back exactly as intended
    reread, reread_start = parse_hex(out_path)
    if reread != memory or reread_start != start_linear:
        raise SystemExit("round-trip check failed — written hex does not read back")

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

    digest = hashlib.sha256(out_path.read_bytes()).hexdigest()

    updater_name = cfg["firmware"].get("updater")
    if updater_name:
        updater = REPO / updater_name
        text = updater.read_text()
        patched, count = re.subn(
            r'EXPECTED_SHA256="[0-9a-f]{64}"', f'EXPECTED_SHA256="{digest}"', text
        )
        if count != 1:
            raise SystemExit(f"{updater_name}: expected exactly one EXPECTED_SHA256 line")
        if patched != text:
            updater.write_text(patched)
            print(f"updated {updater_name} checksum")

    print(f"wrote {out_path.relative_to(REPO)}")
    print(f"  {changed} bytes changed, {added} newly programmed into erased flash")
    print("  all differences from the factory image lie inside declared patches")
    print(f"  SHA-256 {digest}")

    if args.expect_sha and digest != args.expect_sha:
        raise SystemExit(f"output SHA mismatch — expected {args.expect_sha}")
    if args.expect_sha:
        print("  matches --expect-sha")


if __name__ == "__main__":
    main()
