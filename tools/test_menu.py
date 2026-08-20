#!/usr/bin/env python3
"""Drive the flasher's menu through a real terminal.

The menu reads raw keys and redraws by moving the cursor, so a pipe proves
nothing about it: bash takes the no-terminal path there.  This opens a pty,
presses actual arrow keys, and checks what came back.
"""
import os
import pty
import re
import select
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
FLASHER = REPO / "Program218e_v3_Rewired_macOS.command"

UP, DOWN, RET = b"\x1b[A", b"\x1b[B", b"\r"


def harness(items, details, keys, extra=""):
    """Run just the menu, with the flasher's own definitions, over a pty."""
    src = FLASHER.read_text(encoding="utf-8")
    start = src.index("# A menu that answers to the arrow keys")
    end = src.index("# The builder page writes an image.txt", start)
    script = (
        "C_RESET=$'\\033[0m'; C_DIM=$'\\033[2m'; C_BOLD=$'\\033[1m'\n"
        "C_YELLOW=$'\\033[33m'; C_GREEN=; C_RED=\n"
        + src[start:end]
        + "MENU_ITEMS=(%s)\n" % " ".join("'%s'" % i for i in items)
        + "MENU_DETAILS=(%s)\n" % " ".join("'%s'" % d for d in details)
        + extra
        + "menu\n"
        + 'printf "\\nCHOICE=%s\\n" "$MENU_CHOICE"\n'
    )
    tmp = REPO / "build" / "_menu_test.sh"
    tmp.parent.mkdir(exist_ok=True)
    tmp.write_text(script, encoding="utf-8")

    pid, fd = pty.fork()
    if pid == 0:
        os.execv("/bin/bash", ["/bin/bash", str(tmp)])
    out = b""
    for key in keys:
        # Let the menu draw before the next key, or it reads them as a burst.
        deadline = time.time() + 0.6
        while time.time() < deadline:
            r, _, _ = select.select([fd], [], [], 0.1)
            if r:
                try:
                    out += os.read(fd, 65536)
                except OSError:
                    break
        os.write(fd, key)
    deadline = time.time() + 3
    while time.time() < deadline:
        r, _, _ = select.select([fd], [], [], 0.2)
        if not r:
            continue
        try:
            chunk = os.read(fd, 65536)
        except OSError:
            break
        if not chunk:
            break
        out += chunk
        if b"CHOICE=" in out:
            break
    os.close(fd)
    os.waitpid(pid, 0)
    tmp.unlink(missing_ok=True)
    text = out.decode("utf-8", "replace")
    m = re.search(r"CHOICE=(\d+)", text)
    return (int(m.group(1)) if m else None), text


def main():
    items = ["Flash firmware onto the 218e", "Get the keyboard out of DFU mode"]
    details = ["Erases the chip.", "Sends START."]
    failures = 0

    cases = [
        ("return takes the first entry",        [RET],            1),
        ("down then return takes the second",   [DOWN, RET],      2),
        ("down past the end stops at the end",  [DOWN, DOWN, RET], 2),
        ("up from the top stays at the top",    [UP, RET],        1),
        ("down then up comes back",             [DOWN, UP, RET],  1),
        ("typing the number still works",       [b"2"],           2),
        ("q chooses nothing",                   [b"q"],           0),
    ]
    for name, keys, want in cases:
        got, text = harness(items, details, keys)
        if got == want:
            print(f"  ok    {name}")
        else:
            print(f"  FAIL  {name}: chose {got}, expected {want}")
            print("        " + text.replace("\n", "\n        ")[:600])
            failures += 1

    # The detail lines belong under their entry, and the redraw has to count
    # them or the cursor walks up the screen.
    got, text = harness(items, details, [DOWN, RET])
    for needle in ("Erases the chip.", "Sends START."):
        if needle in text:
            print(f"  ok    detail shown: {needle}")
        else:
            print(f"  FAIL  detail missing: {needle}")
            failures += 1

    # A multi-line detail is the case the hex selector uses.
    got, text = harness(["one", "two"],
                        ["path/a.hex\\nPressure: fixed\\nTunings: 3 slots",
                         "path/b.hex"], [DOWN, RET])
    if got == 2 and "Pressure: fixed" in text and "Tunings: 3 slots" in text:
        print("  ok    multi-line details survive the redraw")
    else:
        print(f"  FAIL  multi-line details: chose {got}")
        failures += 1

    print()
    print("  menu is fine" if not failures else f"  {failures} failed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
