#!/usr/bin/env python3
"""Run AbTrace.java on two images and diff the traces; never flashes.

    python3 tools/ab_trace.py build/Rewired_marton_2.4.0_DFU.hex build/218eV3_v369_Rewired_DFU.hex
"""
from __future__ import annotations
import argparse, os, re, subprocess, sys, tempfile, tomllib
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

def parse_hex(path: Path) -> tuple[dict[int, int], None]:
    mem: dict[int, int] = {}; base = 0
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line.startswith(":"):
            continue
        n = int(line[1:3], 16); a = int(line[3:7], 16); t = int(line[7:9], 16)
        d = bytes.fromhex(line[9:9 + 2 * n])
        if t == 4:
            base = int(line[9:13], 16) << 16
        elif t == 0:
            for i, b in enumerate(d):
                mem[base + a + i] = b
    return mem, None

def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("a", type=Path); ap.add_argument("b", type=Path)
    ap.add_argument("--ghidra", type=Path)
    args = ap.parse_args()
    settings = tomllib.loads((REPO / "config/218e.toml").read_text()).get("tools", {})
    local = REPO / "config/local.toml"
    if local.exists():
        settings.update(tomllib.loads(local.read_text()).get("tools", {}))
    ghidra = args.ghidra or Path(os.environ.get("GHIDRA_HOME") or settings.get("ghidra_home", ""))
    headless = ghidra / "support/analyzeHeadless"
    if not headless.is_file():
        raise SystemExit("Set GHIDRA_HOME, config/local.toml [tools].ghidra_home, or --ghidra.")
    work = Path(tempfile.mkdtemp(prefix="ab-trace-", dir=REPO / "build"))
    print(f"Artifacts: {work}", flush=True)
    # The tables A bakes into flash - the pitch table, the three tuning
    # slots, the keys per period - planted into B's settings mirror after
    # its boot, so the two sides compute from the same tables and what is
    # left to differ is code.  Both images carry them at the same addresses.
    flash, _ = parse_hex(args.a)
    def halfwords(base: int, count: int) -> list[int]:
        return [(flash.get(base + 2 * i, 0xFF) << 8) | flash.get(base + 2 * i + 1, 0xFF) for i in range(count)]
    tables = halfwords(0x80019BC0, 79) + halfwords(0x80019AF8, 96) + halfwords(0x8001E2D0, 3)
    table_file = work / "tables.txt"
    table_file.write_text(" ".join(f"{v:04x}" for v in tables) + "\n")
    traces = []
    for tag, image in (("a", args.a), ("b", args.b)):
        cmd = [str(headless), str(work), tag, "-import", str(image.resolve()),
               "-processor", "avr32:BE:32:default", "-noanalysis",
               "-scriptPath", str(REPO / "src"), "-postScript", "AbTrace.java"]
        if tag == "b":
            cmd.append(str(table_file))
        out = subprocess.run(cmd, cwd=REPO, capture_output=True, text=True)
        log = work / f"{tag}.log"; log.write_text(out.stdout + out.stderr)
        lines = [re.sub(r"^INFO\s+\S+>\s*", "", l).replace(" (GhidraScript)", "").rstrip()
                 for l in (out.stdout + out.stderr).splitlines()]
        trace = [l for l in lines if l.startswith("TRACE ")]
        done = any(l.startswith("AB TRACE DONE") for l in lines)
        (work / f"{tag}.trace").write_text("\n".join(trace) + "\n")
        print(f"{tag}: {image.name}: {len(trace)} steps{'' if done else '  (did not finish - see ' + str(log) + ')'}")
        if not done:
            for l in lines:
                if "FAIL" in l or "Exception" in l or "ERROR" in l:
                    print("   ", l[:200])
        traces.append(trace)
    a, b = traces
    same = sum(1 for x, y in zip(a, b) if x == y)
    print(f"\n{same} of {max(len(a), len(b))} steps identical")
    for x, y in zip(a, b):
        if x != y:
            print("- " + x[6:]); print("+ " + y[6:])
    if len(a) != len(b):
        print(f"(lengths differ: {len(a)} vs {len(b)})")

if __name__ == "__main__":
    main()
