#!/usr/bin/env python3
"""Configurations as settings records, for the Ghidra suites' one image.

Every option that can ship changes only data: the settings record at
0x8001fb00 (settings_numbers) and the tables the settings mirror is filled
from.  So the suites build one image and run each check under the
configurations it needs by laying each configuration's record in a
settings slot before every boot (src/SettingsRecord.java), the way a send
leaves it committed.

A configuration is a set of option values applied to config/218e.toml.
build() builds it with tools/build.py, and keeps its image beside the
record and tables the same build serialized.  static_differences() holds
such an image to the one under test: the same code, with the record's own
bytes as its data, so a record laid in a slot and the image built with
those defaults describe the same instrument.  src/SettingsEquivalence.java
boots all three ways and compares the RAM they leave.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import tomllib
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "tools"))
import build as build_mod  # noqa: E402
import settings as SETTINGS  # noqa: E402

# Where an image keeps what its record carries: the 32 cells, the three
# tuning slots, the pitch curve, keys per period, the pattern bank and its
# lengths.  Each is (address, bytes, record offset).
DATA = (
    (0x8001FB00, 0x40, SETTINGS.NUMBERS),     # settings_numbers
    (0x80019AF8, 0xC0, SETTINGS.TUNING),      # the three tuning slots
    (0x80019BC0, 0x9E, SETTINGS.PITCH),       # the pitch curve
    (0x8001E2D0, 0x06, SETTINGS.PERIOD_KEYS), # keys per period
)
BANK, LENGTHS, BANK_BYTES, LENGTHS_BYTES = 0x80019F20, 0x80019FA0, 0x80, 0x40
# settings_defaults copies the image's bank into the mirror with two
# MOV R10,imm8 - two halfwords a pattern, then one - so the count of
# patterns an image carries is also an immediate in its code.  A record
# replaces the mirror's bank whole, so these two are data like the bank.
COPY_LENGTHS = (0x8001F254, 0x8001F260)


def configure(base: str, options: dict) -> str:
    """config/218e.toml with each option set.  A value is True, False, or a
    TOML literal as a string ('"orders"', '["tunings/12TET.scl"]').  An
    option the file names is replaced where it stands; one it leaves out
    (the knobs, the pattern bank) goes at the end of [options].  The file is
    read back and every value must have landed."""
    text = base
    for key, value in options.items():
        literal = str(value).lower() if isinstance(value, bool) else value
        text, n = re.subn(rf"^{key}\s*=.*$", f"{key} = {literal}", text, flags=re.M)
        if n == 0:
            text, n = re.subn(r"^\[firmware\]", f"{key} = {literal}\n\n[firmware]", text, count=1, flags=re.M)
        if n != 1:
            raise SystemExit(f"Cannot set {key} in a regression config")
        if tomllib.loads(text)["options"].get(key) != tomllib.loads(f"v = {literal}")["v"]:
            raise SystemExit(f"{key} did not land in a regression config")
    return text


def redirect(text: str, image: Path) -> str:
    """The image written to `image`, and no flasher rewritten with its sum."""
    text, n = re.subn(r'^output_hex\s*=\s*"[^"]*"', f'output_hex = "{image}"', text, flags=re.M)
    if n != 1:
        raise SystemExit("Cannot redirect a regression image")
    text, n = re.subn(r'^updaters?\s*=\s*(?:"[^"]*"|\[[^\]]*\])\n', "", text, flags=re.M)
    if n != 1 or any(k in tomllib.loads(text)["firmware"] for k in ("updater", "updaters")):
        raise SystemExit("Refusing a regression build that could rewrite the flashers")
    return text


def build(name: str, text: str, work: Path, env: dict | None = None) -> dict:
    """Build one configuration into `work`: <name>.hex, and the record,
    properties and tables that build wrote, which build/ keeps only until
    the next build.  Call one at a time: every build writes build/."""
    image = work / f"{name}.hex"
    config = work / f"{name}.toml"
    config.write_text(redirect(text, image))
    result = subprocess.run([sys.executable, "tools/build.py", "--no-ghidra", "--config", str(config)],
                            cwd=REPO, capture_output=True, text=True, env=env)
    (work / f"{name}-build.log").write_text(result.stdout + result.stderr)
    if result.returncode:
        raise SystemExit(result.stdout + result.stderr)
    out = {"image": image, "config": config, "text": text}
    for kind, source in (("record", "settings.bin"), ("properties", "build.properties"), ("tables", "tables.txt")):
        path = work / f"{name}.{source}"
        path.write_bytes((REPO / "build" / source).read_bytes())
        out[kind] = path
    return out


def static_differences(built: dict, under_test: Path) -> list[str]:
    """What stops `built`'s record from standing for its image on the image
    under test: code that differs, or data that is not the record's."""
    image, _ = build_mod.parse_hex(built["image"])
    base, _ = build_mod.parse_hex(under_test)
    record = built["record"].read_bytes()
    problems = []
    data = set()
    for address, size, _offset in DATA:
        data.update(range(address, address + size))
    data.update(range(BANK, BANK + BANK_BYTES))
    data.update(range(LENGTHS, LENGTHS + LENGTHS_BYTES))
    for at in COPY_LENGTHS:
        data.update((at, at + 1))
    code = sorted(a for a in set(image) | set(base) if a not in data and image.get(a) != base.get(a))
    if code:
        problems.append(f"{len(code)} byte(s) of code differ from the image under test, from {code[0]:#x}")
    for address, size, offset in DATA:
        have = bytes(image.get(address + i, 0xFF) for i in range(size))
        if have != record[offset:offset + size]:
            problems.append(f"the image's data at {address:#x} is not the record's at {offset:#x}")

    def imm8(at: int) -> int:
        b0, b1 = image.get(at, 0), image.get(at + 1, 0)
        if b0 & 0xF0 != 0x30 or b1 & 0x0F != 10:
            raise SystemExit(f"settings_defaults at {at:#x} is not MOV R10,imm8: {b0:02x}{b1:02x}")
        return ((b0 & 0x0F) << 4) | (b1 >> 4)
    patterns = imm8(COPY_LENGTHS[1])
    if imm8(COPY_LENGTHS[0]) != 2 * patterns:
        problems.append("the bank copy's two lengths disagree")
    bank = bytes(image.get(BANK + i, 0xFF) for i in range(4 * patterns))
    lengths = bytes(image.get(LENGTHS + i, 0xFF) for i in range(2 * patterns))
    rbank = record[SETTINGS.BANK:SETTINGS.BANK + 4 * SETTINGS.PATTERNS]
    rlengths = record[SETTINGS.LENGTHS:SETTINGS.LENGTHS + 2 * SETTINGS.PATTERNS]
    if (bank != rbank[:len(bank)] or any(rbank[len(bank):])
            or lengths != rlengths[:len(lengths)] or any(rlengths[len(lengths):])):
        problems.append(f"the image's {patterns}-pattern bank is not the record's")
    return problems


def equivalence_arguments(built: dict[str, dict]) -> list[str]:
    """SettingsEquivalence's name=<image>|<record> for each configuration."""
    return [f"{name}={b['image']}|{b['record']}" for name, b in built.items()]


def headless(settings_base: str) -> Path:
    """Ghidra's analyzeHeadless, from GHIDRA_HOME or [tools].ghidra_home."""
    tools = tomllib.loads(settings_base).get("tools", {})
    local = REPO / "config/local.toml"
    if local.exists():
        tools.update(tomllib.loads(local.read_text()).get("tools", {}))
    return Path(os.environ.get("GHIDRA_HOME") or tools.get("ghidra_home", "")) / "support/analyzeHeadless"
