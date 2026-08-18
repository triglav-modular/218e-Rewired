#!/usr/bin/env python3
"""Build the firmware image using the JavaScript assembler instead of Ghidra.

Reuses tools/build.py for everything that is not assembly — the factory image,
patch application, hex rendering and the safety checks — and swaps only the
Ghidra step for encoder.js + runtime.js + program.js.  Compares the resulting
image against [firmware].golden_sha256, which is the end-to-end proof that the
JavaScript toolchain produces the same firmware.

    python3 tools/avr32/build_js.py
"""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import tomllib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import build as B  # noqa: E402

REPO = B.REPO
AVR32 = REPO / "tools" / "avr32"
JSC = Path("/System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc")


def run_js(properties: Path) -> str:
    if not JSC.exists():
        raise SystemExit(f"no JavaScript runtime at {JSC}")
    result = subprocess.run(
        [str(JSC), str(AVR32 / "encoder.js"), str(AVR32 / "runtime.js"),
         str(AVR32 / "program.js"), str(AVR32 / "assemble.js"), "--", str(properties)],
        capture_output=True, text=True, cwd=REPO,
    )
    if result.returncode != 0 or "ASSEMBLY FAILED" in result.stdout:
        sys.stderr.write(result.stdout + result.stderr)
        raise SystemExit("JavaScript assembly failed")
    return result.stdout


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default="config/218e.toml")
    parser.add_argument("--expect-sha", help="compare against this instead of golden_sha256")
    args = parser.parse_args()

    cfg = tomllib.loads((REPO / args.config).read_text())
    factory = REPO / cfg["firmware"]["factory_hex"]
    digest = hashlib.sha256(factory.read_bytes()).hexdigest()
    if digest != cfg["firmware"]["factory_sha256"]:
        raise SystemExit("factory image mismatch")
    memory, start_linear = B.parse_hex(factory)
    print(f"factory image verified: {factory.name}")

    # Regenerate program.js from the Java every time, so it cannot go stale
    # against a source edit.
    subprocess.run([sys.executable, str(AVR32 / "transpile.py")], check=True, cwd=REPO)

    properties = REPO / "build" / "build.properties"
    if not properties.exists():
        raise SystemExit("run tools/build.py once first to generate build/build.properties")
    output = run_js(properties)

    B.check_extents(output)
    patches = B.parse_patches(output)
    print(f"  {len(patches)} patch record(s) assembled by JavaScript")
    B.check_factory_entry_points(patches, cfg["firmware"]["factory_sha256"])

    original = dict(memory)
    changed, added = B.apply_patches(memory, patches)
    rendered = B.render_hex(memory, start_linear)
    reread, reread_start = B.parse_hex_text(rendered, "output")
    if reread != memory or reread_start != start_linear:
        raise SystemExit("round-trip check failed")

    covered = {a + i for a, data, _ in patches for i in range(len(data))}
    stray = [a for a in original if original[a] != memory[a] and a not in covered]
    if stray:
        raise SystemExit(f"{len(stray)} byte(s) changed outside any patch")

    built = hashlib.sha256(rendered.encode()).hexdigest()
    expected = args.expect_sha or cfg["firmware"]["golden_sha256"]
    label = "expected" if args.expect_sha else "golden  "
    print(f"  {changed} bytes changed, {added} newly programmed")
    print(f"  built    {built}")
    print(f"  {label} {expected}")
    if built != expected:
        raise SystemExit("MISMATCH — the JavaScript build differs from the reference image")
    print("\nMATCHES — the JavaScript toolchain reproduces the firmware.")


if __name__ == "__main__":
    main()
