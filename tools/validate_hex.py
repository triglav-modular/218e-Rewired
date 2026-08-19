#!/usr/bin/env python3
"""Structurally validate an Intel HEX image for the AT32UC3B1256.

Used by the flashers when an image is chosen explicitly, where the built-in
checksum no longer vouches for the file.  Prints OK <lo>..<hi> and exits 0, or
BAD <reason> and exits 1.  No dependencies, so both flashers can shell out to
it — the Windows one has Python as a build/runtime assumption already.
"""
import sys

APP_LOW, APP_HIGH = 0x80002000, 0x8003FFFF

def main(path):
    upper = 0
    lo = hi = None
    saw_eof = False
    try:
        lines = open(path, "r", errors="replace").read().splitlines()
    except OSError as e:
        print(f"BAD cannot read the file: {e}"); return 1
    if not lines:
        print("BAD empty file"); return 1
    for n, line in enumerate(lines, 1):
        line = line.strip()
        if not line:
            continue
        if line[0] != ":":
            print(f"BAD line {n} is not an Intel HEX record"); return 1
        body = line[1:]
        if len(body) < 10 or len(body) % 2:
            print(f"BAD malformed record at line {n}"); return 1
        try:
            raw = bytes.fromhex(body)
        except ValueError:
            print(f"BAD non-hex characters at line {n}"); return 1
        if sum(raw) & 0xFF:
            print(f"BAD checksum mismatch at line {n} - the file is corrupted"); return 1
        length, addr, kind = raw[0], (raw[1] << 8) | raw[2], raw[3]
        if kind == 4:
            upper = ((raw[4] << 8) | raw[5]) << 16
        elif kind == 0:
            a = upper + addr
            lo = a if lo is None else min(lo, a)
            hi = a + length - 1 if hi is None else max(hi, a + length - 1)
        elif kind == 1:
            saw_eof = True
    if not saw_eof:
        print("BAD no end-of-file record - truncated download?"); return 1
    if lo is None:
        print("BAD no data records"); return 1
    if lo < APP_LOW:
        print(f"BAD data at 0x{lo:X} - inside the bootloader region, or not AVR32 firmware"); return 1
    if hi > APP_HIGH:
        print(f"BAD data at 0x{hi:X} - beyond the AT32UC3B1256 flash"); return 1
    print(f"OK 0x{lo:X}..0x{hi:X}")
    return 0

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: validate_hex.py <image.hex>"); sys.exit(2)
    sys.exit(main(sys.argv[1]))
