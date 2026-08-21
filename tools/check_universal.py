#!/usr/bin/env python3
"""Every Mach-O we ship has to carry both architectures.

Buchla's kit ships an x86_64-only dfu-programmer, which is why ours is built
universal in the first place - and an Intel-only binary in a bundle is what
makes macOS list an app under "support is ending", quite apart from needing
Rosetta to run at all.

Only dfu-programmer was checked before, so the other four were universal by
habit rather than by rule.  This walks whatever is there, so a tool added or
rebuilt later is held to the same line without anyone remembering to add it.

    tools/check_universal.py [directory ...]
"""
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
WANT = {"x86_64", "arm64"}


def mach_o_files(root):
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.is_symlink():
            continue
        kind = subprocess.run(["file", "-b", str(path)],
                              capture_output=True, text=True).stdout
        if "Mach-O" in kind:
            yield path


def main(argv):
    roots = [Path(a) for a in argv[1:]] or [REPO / "mac" / "support"]
    checked, bad = 0, []
    for root in roots:
        if not root.exists():
            print(f"  skip  {root} is not here")
            continue
        for path in mach_o_files(root):
            r = subprocess.run(["lipo", "-archs", str(path)],
                               capture_output=True, text=True)
            arches = set(r.stdout.split())
            checked += 1
            rel = path.relative_to(root.parent if root.parent != Path("/") else root)
            if not WANT <= arches:
                bad.append((rel, " ".join(sorted(arches)) or "unreadable"))
            else:
                print(f"  ok    {' '.join(sorted(arches)):16} {rel}")

    if not checked:
        print("::error::no Mach-O files found - the check looked in the wrong place")
        return 1
    for rel, arches in bad:
        print(f"::error::{rel} is {arches}, not universal")
    if bad:
        return 1
    print(f"\n  {checked} binaries, all universal")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
