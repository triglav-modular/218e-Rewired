#!/usr/bin/env python3
"""Flag variables the batch flasher reads but never sets.

The kind of mistake this catches does not look like a mistake: %DFU% expands to
nothing, the command line becomes valid-but-empty, and cmd reports 9009.  Label
and encoding checks pass, and the script fails only when actually run on
Windows against a real tool.
"""
import re, sys
from pathlib import Path

BAT = Path(__file__).resolve().parent.parent / "Program218e_v3_Rewired.bat"
# Set by the environment or by cmd itself, so not expected to be assigned here.
BUILTIN = {
    "TEMP", "TMP", "ERRORLEVEL", "DATE", "TIME", "USERPROFILE", "PATH",
    "RANDOM", "CD", "COMSPEC", "SYSTEMROOT", "WINDIR", "APPDATA", "HOMEPATH",
    "PROGRAMFILES", "OS", "USERNAME", "COMPUTERNAME",
}

text = BAT.read_bytes().decode()
assigned = set(m.group(1).upper() for m in re.finditer(r'SET\s+"?([A-Za-z_]\w*)=', text))
assigned |= set(m.group(1).upper() for m in re.finditer(r'SET\s+/[AP]\s+"?([A-Za-z_]\w*)', text))
used = set(m.group(1).upper() for m in re.finditer(r'%([A-Za-z_]\w*)%', text))
used |= set(m.group(1).upper() for m in re.finditer(r'!([A-Za-z_]\w*)!', text))

missing = sorted(used - assigned - BUILTIN)
if missing:
    print(f"{BAT.name}: read but never assigned: {', '.join(missing)}", file=sys.stderr)
    for name in missing:
        line = next((i for i, l in enumerate(text.splitlines(), 1)
                     if f"%{name}%" in l.upper() or f"!{name}!" in l.upper()), "?")
        print(f"  {name}: first used at line {line}", file=sys.stderr)
    raise SystemExit(1)
print(f"{BAT.name}: {len(used)} variables read, all assigned")
