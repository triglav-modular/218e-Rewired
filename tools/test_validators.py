#!/usr/bin/env python3
"""Run every validator over the same malformed images.

There are three of them - Python, the awk fallback inside the macOS flasher,
and the PowerShell scanner - and they only protect anything if they agree.
The fixtures come from tools/hex_fixtures.py so that all three, here and in
CI, are answering the same questions.

PowerShell needs a Windows runner, so it is checked there; this covers the two
that run anywhere.
"""
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import hex_fixtures  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
FLASHER = REPO / "mac/Program218e_v3_Rewired_macOS.command"


def awk_validator(tmp):
    """The flasher's own fallback, lifted out and callable."""
    src = FLASHER.read_text(encoding="utf-8")
    start = src.index("validate_hex() {")
    end = src.index("\n}\n", start) + 3
    path = tmp / "validate_hex.sh"
    path.write_text(src[start:end], encoding="utf-8")
    return path


def run_awk(script, target):
    r = subprocess.run(
        ["/bin/bash", "-c", f'VALIDATOR=""; . "{script}"; validate_hex "$1"',
         "_", str(target)],
        capture_output=True, text=True, cwd=REPO)
    return (r.stdout + r.stderr).strip().splitlines()[0] if (r.stdout or r.stderr) else ""


def run_python(target):
    r = subprocess.run([sys.executable, "tools/validate_hex.py", str(target)],
                       capture_output=True, text=True, cwd=REPO)
    return (r.stdout + r.stderr).strip().splitlines()[0] if (r.stdout or r.stderr) else ""


def main():
    tmp = REPO / "build" / "fixtures"
    names = hex_fixtures.build(tmp)
    script = awk_validator(REPO / "build")
    failures = 0

    for name in names:
        target = tmp / name
        should_pass = name.startswith("ok")
        got_py = run_python(target)
        got_awk = run_awk(script, target)
        ok_py = got_py.startswith("OK")
        ok_awk = got_awk.startswith("OK")
        if ok_py == should_pass and ok_awk == should_pass:
            verdict = "accepted" if should_pass else "refused"
            print(f"  ok    {name:16} {verdict} by both")
        else:
            print(f"  FAIL  {name}")
            print(f"        python: {got_py[:90]}")
            print(f"        awk:    {got_awk[:90]}")
            failures += 1

    # And the images that have to keep working, when this checkout has them.
    for real in (REPO / "firmware" / "218eV3_v369_DFU.hex",
                 REPO / "build" / "218eV3_v369_Rewired_DFU.hex"):
        if not real.exists():
            print(f"  skip  {real.name} is not in this checkout")
            continue
        got_py, got_awk = run_python(real), run_awk(script, real)
        if got_py.startswith("OK") and got_py == got_awk:
            print(f"  ok    {real.name} accepted, both agreeing: {got_py[3:]}")
        else:
            print(f"  FAIL  {real.name}\n        python: {got_py}\n        awk:    {got_awk}")
            failures += 1

    script.unlink(missing_ok=True)
    print()
    print("  the validators agree" if not failures else f"  {failures} failed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
