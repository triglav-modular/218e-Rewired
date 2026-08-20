#!/usr/bin/env python3
"""Check that the archive the builder page produces still holds a trusted app.

The page carries the signed bundle's entries across without touching them.
This runs that code under jsc, writes what it produced, and asks ditto,
stapler and spctl whether the app inside survived the trip.
"""
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
JSC = Path("/System/Library/Frameworks/JavaScriptCore.framework"
           "/Versions/A/Helpers/jsc")
SRC = REPO / "mac" / "Flasher.zip"


def main():
    if not SRC.exists():
        print(f"  skip  {SRC.relative_to(REPO)} is not built - "
              f"run tools/make-app.sh --notarize")
        return 0
    if not JSC.exists():
        print("  skip  no jsc on this machine")
        return 0

    r = subprocess.run([str(JSC), "web/zip.js", "web/test_zip.js", "--", str(SRC)],
                       capture_output=True, text=True, cwd=REPO)
    if r.returncode != 0:
        print(r.stdout + r.stderr)
        print("  FAIL  the page's zip writer errored")
        return 1

    m = re.search(r"^ZIPHEX ([0-9a-f]+)$", r.stdout, re.M)
    if not m:
        print(r.stdout + r.stderr)
        print("  FAIL  no archive came out")
        return 1
    blob = bytes.fromhex(m.group(1))
    entries = int(re.search(r"^entries (\d+)$", r.stdout, re.M).group(1))
    print(f"  ok    carried {entries} entries out of the signed bundle")

    tmp = Path(tempfile.mkdtemp())
    try:
        zip_path = tmp / "download.zip"
        zip_path.write_bytes(blob)
        out = tmp / "out"
        subprocess.run(["ditto", "-x", "-k", str(zip_path), str(out)], check=True)
        app = next(out.glob("*.app"), None)
        if app is None:
            print("  FAIL  no .app in the download")
            return 1

        # The executable bit does not survive a careless writer, and without it
        # the launcher cannot start.
        launcher = app / "Contents" / "MacOS" / "launcher"
        if not launcher.exists():
            print("  FAIL  the launcher is missing")
            return 1
        if launcher.stat().st_mode & 0o111 == 0:
            print("  FAIL  the launcher came out without its execute bit")
            return 1
        print("  ok    the launcher is executable")

        for name, cmd in (("signature", ["codesign", "--verify", "--deep",
                                         "--strict", str(app)]),
                          ("notarisation ticket", ["xcrun", "stapler", "validate",
                                                   str(app)]),
                          ("Gatekeeper", ["spctl", "-a", "-t", "exec", str(app)])):
            c = subprocess.run(cmd, capture_output=True, text=True)
            if c.returncode != 0:
                print((c.stdout + c.stderr).strip())
                print(f"  FAIL  {name} does not survive the download")
                return 1
            print(f"  ok    {name} survives the download")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("\n  the app in a page-built download is still trusted")
    return 0


if __name__ == "__main__":
    sys.exit(main())
