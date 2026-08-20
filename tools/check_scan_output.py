#!/usr/bin/env python3
"""Hold a validator's verdicts to the fixtures, whatever the fixtures are.

The Windows job asked about eight fixtures by name, so three added later were
never put to the PowerShell scanner - and the step passed while saying "eight
malformed images refused".  Naming them is the mistake; this reads the
directory.

    tools/check_scan_output.py <fixtures-dir> <scan-output> [--python-too]

<scan-output> is what Scan-Images.ps1 printed: a well-formed fixture must be
named in it and a malformed one must not.  --python-too runs validate_hex.py
over the same files and insists it agrees.
"""
import subprocess
import sys
from pathlib import Path


def main(argv):
    if len(argv) < 3:
        sys.exit(__doc__)
    fixtures = Path(argv[1])
    listed = Path(argv[2]).read_text(errors="replace")
    also_python = "--python-too" in argv

    files = sorted(fixtures.glob("*.hex"))
    if not files:
        sys.exit(f"no fixtures in {fixtures}")

    problems = []
    for f in files:
        should_pass = f.name.startswith("ok")
        was_listed = f.name in listed
        if was_listed != should_pass:
            problems.append(
                f"scanner {'accepted' if was_listed else 'refused'} {f.name}")
        if also_python:
            r = subprocess.run([sys.executable, "tools/validate_hex.py", str(f)],
                               capture_output=True)
            if (r.returncode == 0) != should_pass:
                problems.append(
                    f"validate_hex.py "
                    f"{'accepted' if r.returncode == 0 else 'refused'} {f.name}")

    for p in problems:
        print(f"FAILED: {p}")
    if problems:
        return 1
    what = "scanner and validate_hex.py agree" if also_python else "scanner agrees"
    print(f"PASS: {what} on all {len(files)} fixtures")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
