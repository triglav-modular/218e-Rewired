#!/usr/bin/env python3
"""Malformed images that must be refused, written once and shared.

Every one of these is a file dfu-programmer's own parser rejects.  That is the
line that matters: the flasher erases the chip before it hands the image over,
so an image accepted here and refused there leaves the application gone and the
instrument in DFU with nothing to boot.

The rules being tested come from src/intel_hex.c at c204739:

  intel_read_data   sscanf(":%02x%02x%02x%02x") on every line, so a blank line
                    or a bare colon is a parse error, not something to skip
  intel_read_data   every record must be followed by \\n, optionally preceded
                    by \\r - including the last one
  intel_validate_line   type 4: address must be 0 and count exactly 2
                        type 5: address must be 0 and count exactly 4

    tools/hex_fixtures.py <directory>
"""
import sys
from pathlib import Path


def record(kind, addr, data):
    body = [len(data), (addr >> 8) & 0xFF, addr & 0xFF, kind] + list(data)
    return ":" + "".join("%02X" % b for b in body + [(-sum(body)) & 0xFF])


def build(out):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    data = [record(0, 0x2000 + 16 * i, [0] * 16) for i in range(1024)]
    good = [record(4, 0, [0x80, 0x00])] + data + [record(1, 0, [])]

    files = {
        # The shape everything else is a deviation from.  Both line endings,
        # because the factory image is CRLF and ours is LF.
        "ok.hex": "\n".join(good) + "\n",
        "ok_crlf.hex": "\r\n".join(good) + "\r\n",

        "blank.hex": "\n".join(good[:1] + [""] + good[1:]) + "\n",
        "colon.hex": "\n".join(good[:1] + [":"] + good[1:]) + "\n",
        "nonewline.hex": "\n".join(good),
        "t4addr.hex": "\n".join(
            [record(4, 0x0010, [0x80, 0x00])] + data + [record(1, 0, [])]) + "\n",
        "t4count.hex": "\n".join(
            [record(4, 0, [0x80, 0x00, 0x00])] + data + [record(1, 0, [])]) + "\n",
        "t5addr.hex": "\n".join(
            good[:-1] + [record(5, 0x0004, [0x80, 0x00, 0x20, 0x00]),
                         record(1, 0, [])]) + "\n",
        "t5count.hex": "\n".join(
            good[:-1] + [record(5, 0, [0x80, 0x00]), record(1, 0, [])]) + "\n",
        # One line, carriage returns inside it: fgetc finds no \n after the \r.
        "cronly.hex": "\r".join(good) + "\r",
    }
    for name, text in files.items():
        (out / name).write_bytes(text.encode())
    return sorted(files)


# Everything except the two that are meant to pass.
def bad_ones(names):
    return [n for n in names if not n.startswith("ok")]


if __name__ == "__main__":
    where = sys.argv[1] if len(sys.argv) > 1 else "build/fixtures"
    for name in build(where):
        print(f"  {where}/{name}")
