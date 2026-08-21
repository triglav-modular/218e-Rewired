#!/bin/sh
# Build the Windows launcher: the banana-faced .exe whose whole job is to run
# Program218e_v3_Rewired_Windows.bat beside itself.  A batch file has no slot
# for an icon or a signature; this carries both on its behalf.
#
# Cross-compiled from macOS with mingw-w64 (brew install mingw-w64) and
# rsvg-convert (brew install librsvg).  The built .exe is committed, like the
# other Windows binaries in the kit: the pages deploy only copies.
set -eu
REPO="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$REPO/windows/launcher"
BUILD="$REPO/build/launcher"
mkdir -p "$BUILD"

python3 "$REPO/tools/make-icons.py"

# Every size as a PNG entry.  The classic BMP-in-ICO encoding buys
# compatibility with nothing newer than Windows XP, and the flasher already
# requires the WinUSB world.
for s in 16 24 32 48 64 256; do
    rsvg-convert -w "$s" -h "$s" "$SRC/AppIcon.svg" -o "$BUILD/$s.png"
done
python3 - "$SRC/AppIcon.ico" "$BUILD" <<'PY'
import struct, sys
from pathlib import Path
out, build = Path(sys.argv[1]), Path(sys.argv[2])
sizes = [256, 64, 48, 32, 24, 16]
blobs = [(s, (build / f"{s}.png").read_bytes()) for s in sizes]
offset = 6 + 16 * len(blobs)
head = struct.pack("<HHH", 0, 1, len(blobs))
body = b""
for s, b in blobs:
    w = 0 if s == 256 else s          # 0 means 256 in the one-byte field
    head += struct.pack("<BBBBHHII", w, w, 0, 0, 1, 32, len(b), offset)
    body += b
    offset += len(b)
out.write_bytes(head + body)
print(f"  wrote {out.relative_to(Path.cwd())} ({len(head) + len(body)} bytes)")
PY

x86_64-w64-mingw32-windres -I "$SRC" "$SRC/launcher.rc" "$BUILD/launcher.res.o"
x86_64-w64-mingw32-gcc -O2 -municode -static -s \
    -o "$BUILD/launcher.exe" "$SRC/launcher.c" "$BUILD/launcher.res.o"
cp "$BUILD/launcher.exe" "$SRC/218e Rewired Flasher.exe"
ls -la "$SRC/218e Rewired Flasher.exe"
