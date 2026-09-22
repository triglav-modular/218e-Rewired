#!/usr/bin/env python3
"""The settings record: the tables and numbers the firmware reads from RAM.

docs/PLAN-SETTINGS.md is the layout.  At boot the firmware fills a mirror at
RAM 0x6800 from the image's own tables, then overrides it from the newer of
two record slots in flash (0x8003d000 and 0x8003d800) when one validates.
This module serializes the record the build's tables and numbers would make,
which is what the browser pushes over MIDI later and what the regressions
plant in flash now.  web/buildlib.js carries the same serializer and the
parity matrix compares the two byte for byte.

The record's payload from offset 0x20 is the mirror's exact image, so the
firmware loads it with one copy and the numbers below are read as
`LD.UH 0x6800 + 2 * index`.
"""

from __future__ import annotations

import struct
import zlib

MARKER = 0x32313853          # "218S", committed; erased (0xffffffff) until then
VERSION = 1
HEADER = 0x10                # marker, version, length, generation, CRC
PAYLOAD = 0x298              # from offset 0x10 to the end
LENGTH = HEADER + PAYLOAD    # 0x2a8
SLOTS = (0x8003D000, 0x8003D800)
MIRROR = 0x6800              # RAM; record offset 0x20 lands here

# Offsets inside the record.  The mirror is the same layout from NUMBERS on,
# shifted by MIRROR - NUMBERS.
IMAGE_MARKER = 0x10          # the image's init_marker, halfword
OCTAVE_UNITS = 0x12          # the period the image was built for, halfword
NUMBERS = 0x20               # 32 halfwords; the ten below, the rest zero
PITCH = 0x60                 # 79 halfwords and a pad
TUNING = 0x100               # three slots of 32 halfwords
PERIOD_KEYS = 0x1C0          # 3 halfwords and a pad
BANK = 0x1C8                 # 32 masks, each two halfwords, low first
LENGTHS = 0x248              # 32 halfwords
RESERVED = 0x288             # 32 zero bytes
MIRROR_END = RESERVED        # what the firmware copies: 0x20..0x288

PITCH_ENTRIES = 79
TUNING_ENTRIES = 32
PATTERNS = 32
DAC_MAX = 0xFFF

# name, default, low, high - the same fallback and bounds the assembler's
# number() call at each site enforces, in the order of their RAM cells.
NUMBER_LIST = (
    ("tie_glide_rate", 60, 1, 1024),
    ("strip_halfway_units", 2048, 128, 3968),
    ("clock_min_ms", 4, 1, 4),
    ("clock_rearm_us", 250, 1, 1000),
    ("clock_lock_pulses", 5, 2, 32),
    ("transpose_cv_period", 123, 1, 1023),
    ("transpose_cv_zero", 0, 0, 1023),
    ("transpose_cv_hysteresis", 2, 0, 64),
    ("chord_hold_scans", 300, 20, 2000),
    ("latch_state_hold_scans", 200, 20, 2000),
)


def crc(data: bytes) -> int:
    """CRC-32/ISO-HDLC over header bytes 4..11 then the payload, as the
    firmware computes it: the marker and the CRC field itself are excluded."""
    return zlib.crc32(data[4:12] + data[HEADER:LENGTH]) & 0xFFFFFFFF


def payload(numbers: dict, tables: dict, pattern_tables: bool,
            init_marker: int, octave_units: int) -> bytes:
    """The record from offset 0x10: what the CRC covers after the header."""
    out = bytearray(PAYLOAD)
    body = memoryview(out)

    def halfwords(offset: int, values, name: str, low: int, high: int) -> None:
        for i, v in enumerate(values):
            if not isinstance(v, int) or isinstance(v, bool) or not low <= v <= high:
                raise ValueError(f"{name}[{i}] must be {low}..{high}, got {v!r}")
            struct.pack_into(">H", body, offset - HEADER + 2 * i, v)

    struct.pack_into(">HH", body, 0, init_marker & 0xFFFF, octave_units & 0xFFFF)
    values = []
    for name, default, low, high in NUMBER_LIST:
        value = numbers.get(name, default)
        if not low <= value <= high:
            raise ValueError(f"{name} must be {low}..{high}, got {value!r}")
        values.append(value)
    halfwords(NUMBERS, values, "numbers", 0, 0xFFFF)

    pitch = list(tables["pitch_remap"])
    if len(pitch) != PITCH_ENTRIES:
        raise ValueError(f"pitch_remap must have {PITCH_ENTRIES} entries, got {len(pitch)}")
    halfwords(PITCH, pitch, "pitch_remap", 0, DAC_MAX)
    for slot in range(3):
        table = list(tables[f"tuning_slot{slot}"])
        if len(table) != TUNING_ENTRIES:
            raise ValueError(f"tuning_slot{slot} must have {TUNING_ENTRIES} entries")
        halfwords(TUNING + 2 * TUNING_ENTRIES * slot, table, f"tuning_slot{slot}", 0, DAC_MAX)
    keys = list(tables["tuning_period_keys"])
    if len(keys) != 3:
        raise ValueError("tuning_period_keys must have 3 entries")
    # A .kbm may name up to 127 positions; the key-table rotation's own
    # limit of 32 is the build's to refuse and the firmware's to enforce,
    # in the images that carry the rotation.
    halfwords(PERIOD_KEYS, keys, "tuning_period_keys", 1, 127)

    # The bank is only emitted into the image when knob 2 plays patterns,
    # and the firmware copies it into the mirror only then; any other build
    # boots a zero bank, so the record carries zeros too and the two agree.
    if pattern_tables:
        bank = list(tables["arp_pattern_bank"])
        lengths = list(tables["arp_pattern_len"])
        if len(bank) != 2 * len(lengths) or not 1 <= len(lengths) <= PATTERNS:
            raise ValueError("arp_pattern_bank must hold two halfwords per length, 1..32 patterns")
        halfwords(BANK, bank, "arp_pattern_bank", 0, 0xFFFF)
        halfwords(LENGTHS, lengths, "arp_pattern_len", 1, 32)
    return bytes(out)


def record(numbers: dict, tables: dict, pattern_tables: bool,
           init_marker: int, octave_units: int, generation: int = 1) -> bytes:
    """A committed record, marker set, ready to plant in a slot."""
    if not 1 <= generation <= 0xFFFFFFFF:
        raise ValueError("generation must be 1..0xffffffff")
    out = bytearray(LENGTH)
    struct.pack_into(">IHHI", out, 0, MARKER, VERSION, PAYLOAD, generation)
    out[HEADER:] = payload(numbers, tables, pattern_tables, init_marker, octave_units)
    struct.pack_into(">I", out, 12, crc(bytes(out)))
    return bytes(out)


def parse(data: bytes) -> dict:
    """Read a record back, refusing anything the firmware would refuse on
    its header.  Bounds are the firmware's business too, and checked here
    so a test can tell a bad field from a bad checksum."""
    if len(data) < LENGTH:
        raise ValueError(f"record is {len(data)} bytes, needs {LENGTH}")
    marker, version, length, generation, sum_ = struct.unpack_from(">IHHII", data, 0)
    if marker != MARKER:
        raise ValueError("no commit marker")
    if version != VERSION:
        raise ValueError(f"version {version}, expected {VERSION}")
    if length != PAYLOAD:
        raise ValueError(f"payload length {length:#x}, expected {PAYLOAD:#x}")
    if generation == 0:
        raise ValueError("generation zero")
    if sum_ != crc(data):
        raise ValueError("CRC mismatch")

    def halfwords(offset: int, count: int) -> list[int]:
        return list(struct.unpack_from(f">{count}H", data, offset))

    image_marker, octave_units = struct.unpack_from(">HH", data, IMAGE_MARKER)
    out = {
        "generation": generation,
        "image_marker": image_marker,
        "octave_units": octave_units,
        "numbers": {},
        "pitch_remap": halfwords(PITCH, PITCH_ENTRIES),
        "tuning_period_keys": halfwords(PERIOD_KEYS, 3),
        "arp_pattern_bank": halfwords(BANK, 2 * PATTERNS),
        "arp_pattern_len": halfwords(LENGTHS, PATTERNS),
    }
    for i, (name, _default, low, high) in enumerate(NUMBER_LIST):
        value = struct.unpack_from(">H", data, NUMBERS + 2 * i)[0]
        if not low <= value <= high:
            raise ValueError(f"{name} is {value}, outside {low}..{high}")
        out["numbers"][name] = value
    for slot in range(3):
        out[f"tuning_slot{slot}"] = halfwords(TUNING + 2 * TUNING_ENTRIES * slot, TUNING_ENTRIES)
    for name in ("pitch_remap", "tuning_slot0", "tuning_slot1", "tuning_slot2"):
        if max(out[name]) > DAC_MAX:
            raise ValueError(f"{name} entry past the DAC range")
    if not all(1 <= k <= 127 for k in out["tuning_period_keys"]):
        raise ValueError("tuning_period_keys outside 1..127")
    if max(out["arp_pattern_len"]) > 32:
        raise ValueError("arp_pattern_len past 32")
    return out
