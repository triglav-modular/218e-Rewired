#!/usr/bin/env python3
"""Structurally validate an Intel HEX image for the AT32UC3B1256.

Used by the flashers when an image is chosen explicitly, where the built-in
checksum no longer vouches for the file.  Prints OK <lo>..<hi> and exits 0, or
BAD <reason> and exits 1.  No dependencies, so both flashers can shell out to
it — the Windows one has Python as a build/runtime assumption already.
"""
import sys

APP_LOW, APP_HIGH = 0x80002000, 0x8003FFFF

# Structural checks are all this can offer - it cannot know what a given build
# should contain - but "valid Intel HEX inside the right window" was far too
# little.  A four-byte file passed, and passing means the flasher erases the
# whole application and writes those four bytes, leaving an instrument that
# cannot boot.  The real images carry 92,797 (factory) and 100,327 (Rewired)
# bytes and both start at APP_LOW, so an image that does not begin at the
# reset vector, or that carries a few hundred bytes, is not firmware whatever
# its checksums say.
# AVR32 flash is mapped here.  dfu-programmer masks bit 31 off every address
# it computes; this puts it back, so the numbers below and the ones printed
# are the addresses people recognise.
FLASH_BASE = 0x80000000
MIN_BYTES = 16384
ALLOWED_TYPES = {0, 1, 4, 5}

def main(path):
    upper = FLASH_BASE
    lo = hi = None
    saw_eof = False
    try:
        blob = open(path, "rb").read()
        # dfu-programmer reads each record with fgets and then insists on the
        # newline itself: `if ('\n' != c) return -7`, after allowing one \r.
        # A file whose last record has no newline is refused there - after the
        # erase has already run.
        if not blob.endswith(b"\n"):
            print("BAD the last record has no newline after it - the flasher "
                  "refuses that, and it erases before it reads"); return 1
        stray = blob.replace(b"\r\n", b"")
        if b"\r" in stray:
            print("BAD a carriage return that is not part of a CRLF line "
                  "ending - the flasher reads one character past the \\r and "
                  "wants a newline"); return 1
        lines = blob.decode("utf-8", "replace").splitlines()
    except OSError as e:
        print(f"BAD cannot read the file: {e}"); return 1
    if not lines:
        print("BAD empty file"); return 1
    prev_end = None
    covered = 0
    for n, line in enumerate(lines, 1):
        # Not stripped.  Every field is read with a fixed-width fgets and
        # nothing skips whitespace: the colon is matched literally, so a space
        # in front of it is not a record at all, and the byte after the last
        # data byte has to be the line ending.  Trimming here made this
        # validator the most permissive of the three, and it disagreed with
        # the awk one in the same flasher.
        if line != line.strip():
            print(f"BAD whitespace around the record at line {n} - the flasher "
                  f"matches the colon and the line ending exactly"); return 1
        if not line:
            # Blank lines are not skipped, they are read: sscanf gets nothing
            # to match and the parse fails.  After the end-of-file record
            # nothing is read at all, so those do not matter.
            if saw_eof:
                continue
            print(f"BAD blank line at line {n} - every line before the "
                  f"end-of-file record has to be a record"); return 1
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
        # The declared length has to match what the record carries, or the
        # coverage it claims is not the coverage it has.
        if len(raw) != length + 5:
            print(f"BAD record at line {n} declares {length} bytes but carries "
                  f"{len(raw) - 5}"); return 1
        # dfu-programmer keeps one address_offset and lets types 4 AND 5 set it,
        # masking the result with 0x7fffffff.  Type 5 is nominally the entry
        # point, so a validator that treats it as decoration and a flasher that
        # treats it as an offset disagree about where every following record
        # lands - which is a way to have a file approved and something else
        # written.  The mask only ever clears bit 31, so it is put back for the
        # comparisons and the message below, all of which live in flash space.
        # intel_validate_line pins the shape of these two exactly, and a
        # record that does not match is a parse error rather than something to
        # be interpreted generously.
        if kind == 1 and length != 0:
            print(f"BAD end-of-file record at line {n} carries {length} bytes "
                  f"- it must carry none"); return 1
        if kind == 4 and (addr != 0 or length != 2):
            print(f"BAD type 4 record at line {n} has address 0x{addr:X} and "
                  f"{length} bytes - it must be address 0 and 2 bytes"); return 1
        if kind == 5 and (addr != 0 or length != 4):
            print(f"BAD type 5 record at line {n} has address 0x{addr:X} and "
                  f"{length} bytes - it must be address 0 and 4 bytes"); return 1
        if kind == 4:
            upper = (((raw[4] << 24) | (raw[5] << 16)) & 0x7FFFFFFF) | FLASH_BASE
        elif kind == 5:
            upper = ((((raw[4] << 24) | (raw[5] << 16) |
                       (raw[6] << 8) | raw[7]) & 0x7FFFFFFF)) | FLASH_BASE
        elif kind == 0:
            a = (((upper - FLASH_BASE) + addr) & 0x7FFFFFFF) | FLASH_BASE
            # Strictly non-descending, the same rule as the other two
            # validators.  This validator used to track a written-set and so
            # accepted out-of-order records the others refused - a divergence
            # among the very tools whose agreement is the point.  Monotonic
            # order also subsumes overlap: any overlap in an ordered stream
            # shows as a record starting before the previous one ended.
            # dfu-programmer itself takes records in any order, so refusing
            # disorder is stricter than the flasher - the safe direction,
            # and no real image is disordered.
            if prev_end is not None and a < prev_end:
                print(f"BAD record at line {n} runs backwards or overlaps "
                      f"flash already written at 0x{a:X} - no real image "
                      "is disordered"); return 1
            prev_end = a + length
            covered += length
            lo = a if lo is None else min(lo, a)
            hi = a + length - 1 if hi is None else max(hi, a + length - 1)
        elif kind == 1:
            saw_eof = True
            # Everything past here would be counted by us and never seen by
            # the flasher: sixteen bytes before the marker and sixteen
            # kilobytes after it would have passed as a whole image.
            rest = [ln for ln in lines[n:] if ln.strip()]
            if rest:
                print(f"BAD {len(rest)} records after the end-of-file record at "
                      f"line {n} - the flasher stops there and would never "
                      f"write them"); return 1
        if kind not in ALLOWED_TYPES:
            print(f"BAD record type {kind} at line {n} - not an AVR32 firmware image")
            return 1
    if not saw_eof:
        print("BAD no end-of-file record - truncated download?"); return 1
    if lo is None:
        print("BAD no data records"); return 1
    if lo < APP_LOW:
        print(f"BAD data at 0x{lo:X} - inside the bootloader region, or not AVR32 firmware"); return 1
    if hi > APP_HIGH:
        print(f"BAD data at 0x{hi:X} - beyond the AT32UC3B1256 flash"); return 1
    if lo != APP_LOW:
        print(f"BAD starts at 0x{lo:X}, not the reset vector at 0x{APP_LOW:X} - "
              f"a partial image would erase the application and not replace it")
        return 1
    # covered sums record lengths; with monotonic order enforced above, no
    # byte can be counted twice.
    if covered < MIN_BYTES:
        print(f"BAD only {covered} bytes of firmware - a real image carries tens of "
              f"thousands, and flashing this would leave the instrument unbootable")
        return 1
    print(f"OK 0x{lo:X}..0x{hi:X} ({covered} bytes)")
    return 0

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: validate_hex.py <image.hex>"); sys.exit(2)
    sys.exit(main(sys.argv[1]))
