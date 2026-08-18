#!/usr/bin/env python3
"""Extract an (address, instruction, bytes) corpus from build/assemble.log.

Ghidra's AVR32 assembler is the oracle for the client-side encoder: whatever
it emitted for these instructions is what the encoder must reproduce, byte for
byte.  `emit` logs each line as

    INFO  AssemblePressureFix.java> <addr>  <%-36s instruction>  <hex> (GhidraScript)

so the listing in the log is already the corpus; this just lifts it into a
language-neutral fixture that both the JS encoder tests and any future port
can consume.
"""

from __future__ import annotations

import json
import re
import sys
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
OUT = REPO / "tools" / "avr32" / "corpus.json"

# Prefer the all-features log: finish() only prints a block's listing when the
# block is enabled, so a log from an ordinary build silently omits every
# instruction that lives in a disabled cave.  Regenerate it with
#
#   python3 - <<<'...force every block./feature. key to 1...'   (see README)
#   $GHIDRA_HOME/support/analyzeHeadless build/ghidra_project buchla218 \
#       -import firmware/218eV3_v369_DFU.hex -processor avr32:BE:32:default \
#       -noanalysis -readOnly -scriptPath src \
#       -postScript AssemblePressureFix.java build/build.allon.properties
# Merge every log present.  A single build never covers the whole program:
# finish() prints a block's listing only when that block is enabled, and a
# branch guarded by `!feature(x)` is unreachable in a build where x is on.  So
# coverage needs several configurations, and a run that later failed is still
# a source of valid (address, instruction, bytes) triples for the part it did
# assemble.
LOGS = sorted((REPO / "build").glob("assemble*.log"))

PREFIX = re.compile(r"^INFO\s+AssemblePressureFix\.java>\s")
SUFFIX = re.compile(r"\s*\(GhidraScript\)\s*$")


def shape(text: str) -> str:
    """Normalise an instruction to its operand shape, for coverage reporting."""
    body = re.sub(r"\b(R\d+|LR|PC|SP)\b", "R", text)
    return re.sub(r"-?0x[0-9a-fA-F]+", "IMM", body)


def main() -> None:
    logs = [Path(a) for a in sys.argv[1:] if not a.startswith("-")] or LOGS
    logs = [l for l in logs if l.exists()]
    if not logs:
        raise SystemExit("no build/assemble*.log found — run tools/build.py first")

    entries, malformed, seen = [], 0, set()
    for line in "\n".join(l.read_text() for l in logs).splitlines():
        if not PREFIX.match(line):
            continue
        rest = SUFFIX.sub("", PREFIX.sub("", line))
        if len(rest) < 10 or not re.match(r"^[0-9a-f]{8}  ", rest):
            continue
        address, remainder = rest[:8], rest[10:]
        # bytes are the final whitespace-delimited token; the instruction text
        # is %-36s padded, so everything before it (stripped) is the mnemonic
        parts = remainder.rsplit(None, 1)
        if len(parts) != 2:
            malformed += 1
            continue
        text, encoded = parts[0].strip(), parts[1]
        if not re.fullmatch(r"[0-9a-f]+", encoded) or len(encoded) % 2:
            malformed += 1
            continue
        key = (address, text, encoded)
        if key in seen:
            continue
        seen.add(key)
        entries.append({
            "addr": address,
            "text": text,
            "bytes": encoded,
            "width": len(encoded) // 2,
        })

    if malformed:
        raise SystemExit(f"{malformed} listing line(s) did not parse — check the log format")
    if not entries:
        raise SystemExit("no listing lines found in the log")

    mnemonics = Counter(e["text"].split()[0] for e in entries)
    shapes = Counter(shape(e["text"]) for e in entries)
    widths = Counter(e["width"] for e in entries)

    OUT.write_text(json.dumps({
        "sources": [str(l.relative_to(REPO)) for l in logs],
        "count": len(entries),
        "entries": entries,
    }, indent=1) + "\n")

    print(f"wrote {OUT.relative_to(REPO)}")
    print(f"  {len(entries)} instructions, {len(mnemonics)} mnemonics, "
          f"{len(shapes)} operand shapes")
    print(f"  widths: " + ", ".join(f"{w} bytes x{n}" for w, n in sorted(widths.items())))
    if "--shapes" in sys.argv:
        print("\noperand shapes by frequency:")
        for name, n in shapes.most_common():
            print(f"  {n:5d}  {name}")


if __name__ == "__main__":
    main()
