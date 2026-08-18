#!/usr/bin/env python3
"""Show corpus samples for one operand shape, to derive its bit layout.

    python3 tools/avr32/samples.py "MOV R,IMM"
    python3 tools/avr32/samples.py --list

Deriving a layout from Ghidra's own output beats reading it out of the
architecture manual: it is the encoding the encoder has to match, including
which of several legal forms Ghidra picks.
"""
import json, re, sys
from collections import defaultdict
from pathlib import Path

CORPUS = Path(__file__).resolve().parent / "corpus.json"


def shape(text: str) -> str:
    return re.sub(r"-?0x[0-9a-fA-F]+", "IMM",
                  re.sub(r"\b(R\d+|LR|PC|SP)\b", "R", text))


def main() -> None:
    entries = json.loads(CORPUS.read_text())["entries"]
    if "--list" in sys.argv or len(sys.argv) < 2:
        counts = defaultdict(int)
        for e in entries:
            counts[shape(e["text"])] += 1
        for name, n in sorted(counts.items(), key=lambda kv: -kv[1]):
            print(f"{n:5d}  {name}")
        return

    want = sys.argv[1]
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 40
    seen: dict[str, tuple[str, str]] = {}
    for e in entries:
        if shape(e["text"]) == want:
            seen.setdefault(e["text"], (e["addr"], e["bytes"]))
    if not seen:
        raise SystemExit(f"no samples for {want!r} — try --list")
    for text, (addr, encoded) in sorted(seen.items())[:limit]:
        print(f"{addr}  {text:34s} {encoded}  ({len(encoded)//2} bytes)")
    print(f"\n{len(seen)} distinct operand combination(s)")


if __name__ == "__main__":
    main()
