#!/usr/bin/env python3
"""Drive the flasher through a real terminal.

The menu reads raw keys and the window fit talks to the terminal, so a pipe
proves nothing about either: bash takes the no-terminal path there.  The last
check runs the real script far enough to catch what only shows up when it is
actually executed - a helper called above its own definition, say, which bash
accepts happily at parse time and then cannot find.

The menu reads raw keys and redraws by moving the cursor, so a pipe proves
nothing about it: bash takes the no-terminal path there.  This opens a pty,
presses actual arrow keys, and checks what came back.
"""
import fcntl
import os
import shutil
import signal
import pty
import re
import select
import struct
import sys
import termios
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


def window(rows, cols, term="xterm-256color"):
    """Run fit_window in a terminal of a given size; return what it asked for."""
    src = FLASHER.read_text(encoding="utf-8")
    i = src.index("# Terminal opens 80x24")
    j = src.index("banana() {", i)
    tmp = REPO / "build" / "_fit_test.sh"
    tmp.parent.mkdir(exist_ok=True)
    tmp.write_text(src[i:j] + 'fit_window\nprintf "DONE\\n"\n', encoding="utf-8")

    master, slave = pty.openpty()
    # The size has to be set before the shell starts, or it reads the old one.
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
    pid = os.fork()
    if pid == 0:
        os.setsid()
        os.dup2(slave, 0); os.dup2(slave, 1); os.dup2(slave, 2)
        os.close(master); os.close(slave)
        env = dict(os.environ)
        if term:
            env["TERM"] = term
        else:
            env.pop("TERM", None)
        os.execve("/bin/bash", ["/bin/bash", str(tmp)], env)
    os.close(slave)
    out = b""
    deadline = time.time() + 5
    while time.time() < deadline:
        r, _, _ = select.select([master], [], [], 0.2)
        if not r:
            continue
        try:
            chunk = os.read(master, 65536)
        except OSError:
            break
        if not chunk:
            break
        out += chunk
        if b"DONE" in out:
            break
    os.close(master)
    os.waitpid(pid, 0)
    tmp.unlink(missing_ok=True)
    m = re.search(r"\x1b\[8;(\d+);(\d+)t", out.decode("utf-8", "replace"))
    return (int(m.group(1)), int(m.group(2))) if m else None


def smoke_run():
    """Run the flasher as far as choosing an image, in an empty HOME.

    It stops at the prompt that asks for a path, so nothing reaches the
    instrument: no probe, no START, no flash.
    """
    home = REPO / "build" / "_smoke_home"
    for sub in ("Downloads", "Desktop"):
        (home / sub).mkdir(parents=True, exist_ok=True)

    master, slave = pty.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 100, 0, 0))
    pid = os.fork()
    if pid == 0:
        os.setsid()
        os.dup2(slave, 0); os.dup2(slave, 1); os.dup2(slave, 2)
        os.close(master); os.close(slave)
        env = dict(os.environ)
        env["TERM"] = "xterm-256color"
        env["HOME"] = str(home)
        env["REWIRED_WORKDIR"] = str(home / "work")
        env.pop("TERM_PROGRAM", None)      # no AppleScript detour in a test
        os.execve("/bin/bash", ["/bin/bash", str(FLASHER)], env)
    os.close(slave)

    out = b""

    def pump(seconds):
        nonlocal out
        deadline = time.time() + seconds
        while time.time() < deadline:
            r, _, _ = select.select([master], [], [], 0.15)
            if not r:
                continue
            try:
                chunk = os.read(master, 65536)
            except OSError:
                return
            if not chunk:
                return
            out += chunk

    pump(2.0)
    os.write(master, b"\r")        # the menu: flash firmware
    pump(1.0)
    os.write(master, b"YES\r")     # the warning
    pump(2.5)
    try:
        os.killpg(os.getpgid(pid), signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        pass
    os.close(master)
    try:
        os.waitpid(pid, 0)
    except ChildProcessError:
        pass
    shutil.rmtree(home, ignore_errors=True)
    return out.decode("utf-8", "replace")


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

    # bash -n does not notice a function called before it is defined: the
    # parse is fine, and only running it says "command not found".  That
    # shipped once, in the step that says where the image was looked for.
    out = smoke_run()
    for bad in ("command not found", "unbound variable", "syntax error",
                "No such file or directory"):
        if bad in out:
            print(f"  FAIL  the flasher printed \"{bad}\"")
            for line in out.splitlines():
                if bad in line:
                    print("        " + line.strip()[:160])
            failures += 1
        else:
            print(f"  ok    no \"{bad}\" up to the image step")
    if "Locating the firmware image" not in out:
        print("  FAIL  never reached the image step")
        failures += 1
    else:
        print("  ok    reached the image step")

    # The banner is 22 lines and Terminal opens 24, so the window is grown to
    # fit - but only grown.  This used to read the size with tput, which needs
    # TERM; without it tput failed silently and every window got resized,
    # including ones that were already the right size.
    for rows, cols, term, want, name in (
            (24, 80, "xterm-256color", (40, 80), "a default 80x24 window is grown"),
            (50, 120, "xterm-256color", None, "a window already large is left alone"),
            (24, 200, "xterm-256color", (40, 200), "a wide short window keeps its width"),
            (50, 120, None, None, "no TERM does not cause a needless resize")):
        got = window(rows, cols, term)
        if got == want:
            print(f"  ok    {name}")
        else:
            print(f"  FAIL  {name}: asked for {got}, expected {want}")
            failures += 1

    print()
    print("  menu is fine" if not failures else f"  {failures} failed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
