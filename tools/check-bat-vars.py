#!/usr/bin/env python3
"""Flag variables the batch flasher reads but never sets.

The kind of mistake this catches does not look like a mistake: %DFU% expands to
nothing, the command line becomes valid-but-empty, and cmd reports 9009.  Label
and encoding checks pass, and the script fails only when actually run on
Windows against a real tool.
"""
import re, sys
from pathlib import Path

BAT = Path(__file__).resolve().parent.parent / "windows" / "218e_Rewired_Flasher.bat"
# Set by the environment or by cmd itself, so not expected to be assigned here.
BUILTIN = {
    "TEMP", "TMP", "ERRORLEVEL", "DATE", "TIME", "USERPROFILE", "PATH",
    "RANDOM", "CD", "COMSPEC", "SYSTEMROOT", "WINDIR", "APPDATA", "HOMEPATH",
    "PROGRAMFILES", "OS", "USERNAME", "COMPUTERNAME",
}

if len(sys.argv) > 1:
    BAT = Path(sys.argv[1])
text = BAT.read_bytes().decode()
assigned = set(m.group(1).upper() for m in re.finditer(r'SET\s+"?([A-Za-z_]\w*)=', text))
assigned |= set(m.group(1).upper() for m in re.finditer(r'SET\s+/[AP]\s+"?([A-Za-z_]\w*)', text))
# A substring or replacement expansion, !NAME:~0,7! or %NAME:a=b%, reads
# NAME just the same; the bare patterns below stop at the colon and missed
# every one of them.
used = set(m.group(1).upper() for m in re.finditer(r'%([A-Za-z_]\w*)(?::[^%\n]*)?%', text))
used |= set(m.group(1).upper() for m in re.finditer(r'!([A-Za-z_]\w*)(?::[^!\n]*)?!', text))

# Indexed families: assigned as CALL SET "IMG_PATH_%%N=..." and read back as
# !IMG_PATH_%%I! or %%IMG_PATH_!PICK!%%.  Neither side is a plain name, so
# both were invisible to the checks above, and a family read under a name
# that nothing assigns passed as clean.  The trailing underscore is the
# family's name here.
families_assigned = set(m.group(1).upper() for m in re.finditer(
    r'SET\s+"?([A-Za-z_]\w*_)%%?[A-Za-z_]\w*%%?=', text))
families_used = set(m.group(1).upper() for m in re.finditer(
    r'!([A-Za-z_]\w*_)%%[A-Za-z_]\w*(?::[^!\n]*)?!', text))
families_used |= set(m.group(1).upper() for m in re.finditer(
    r'%%([A-Za-z_]\w*_)![A-Za-z_]\w*!%%', text))
used |= set(f + "*" for f in families_used)
assigned |= set(f + "*" for f in families_assigned)

missing = sorted(used - assigned - BUILTIN)
if missing:
    print(f"{BAT.name}: read but never assigned: {', '.join(missing)}", file=sys.stderr)
    for name in missing:
        stem = name.rstrip("*")
        line = next((i for i, l in enumerate(text.splitlines(), 1)
                     if f"%{stem}" in l.upper() or f"!{stem}" in l.upper()), "?")
        print(f"  {name}: first used at line {line}", file=sys.stderr)
    raise SystemExit(1)
print(f"{BAT.name}: {len(used)} variables read, all assigned")
