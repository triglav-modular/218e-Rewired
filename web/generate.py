#!/usr/bin/env python3
"""Emit web/generated.js: the fixed data a browser build needs.

Everything here is derived from the Python build, so there is one source of
truth and no hand-copied constants to drift.  Re-run after changing
tools/options.py, tools/build.py, src/AssemblePressureFix.java or
tools/factory_control_flow.txt.
"""

from __future__ import annotations

import base64
import datetime
import email.utils
import json
import re
import subprocess
import sys
import tomllib
from pathlib import Path
from xml.sax.saxutils import escape, quoteattr

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "tools"))
import build as B          # noqa: E402
import options as O        # noqa: E402

OUT = REPO / "web" / "generated.js"
FEED = REPO / "web" / "feed.xml"

# The feed's own words.  Everything else in it is the changelog, verbatim.
FEED_TITLE = "218e Rewired"
FEED_DESCRIPTION = "Firmware releases for the Buchla 218e V3."

# The same heading the page's changelog panel recognises (app.js), so a line
# the panel shows as a release is a release here too, and nothing else is.
HEADING = re.compile(r"^(\d+\.\d+(?:\.\d+)?)\s*(?:\((.+)\))?\s*$")


def releases(changelog: str) -> list[tuple[str, str | None, list[str]]]:
    """(version, date, entries) per release, newest first, as the file has them."""
    out: list[tuple[str, str | None, list[str]]] = []
    for line in changelog.splitlines():
        if not line.strip():
            continue
        head = HEADING.match(line)
        if head:
            out.append((head.group(1), head.group(2), []))
        elif out:
            out[-1][2].append(re.sub(r"^[-–—]\s*", "", line.strip()))
    return out


def rfc822(date: str) -> str | None:
    """A changelog date as RSS wants it, or None if it is not a plain date.

    Noon UTC, because the changelog records a day and not a moment: midnight
    would show every release a day early to a reader west of Greenwich, and
    noon is the same calendar day from UTC-11 to UTC+11.
    """
    try:
        d = datetime.date.fromisoformat(date.strip())
    except ValueError:
        return None
    # email.utils rather than strftime, whose %a and %b follow the locale.
    return email.utils.format_datetime(
        datetime.datetime(d.year, d.month, d.day, 12, tzinfo=datetime.timezone.utc))


def feed(changelog: str, root: str) -> str:
    """The changelog as RSS 2.0, one item per release.

    Deterministic - no build time anywhere - so it can be committed and
    checked for staleness exactly as generated.js is.  The item's guid is the
    version rather than the text, so correcting a line later does not announce
    the release a second time.
    """
    items = []
    newest = None
    for version, date, entries in releases(changelog):
        shown = ".".join(version.split(".")[:2])
        when = rfc822(date) if date else None
        newest = newest or when
        body = "<ul>" + "".join(f"<li>{escape(e)}</li>" for e in entries) + "</ul>"
        items.append("\n".join(filter(None, [
            "    <item>",
            f"      <title>Rewired {escape(shown)}</title>",
            f"      <link>{escape(root)}</link>",
            f"      <guid isPermaLink=\"false\">{escape(root)}#{escape(version)}</guid>",
            f"      <pubDate>{when}</pubDate>" if when else None,
            f"      <description>{escape(body)}</description>",
            "    </item>",
        ])))
    return "\n".join(filter(None, [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom">',
        "  <channel>",
        f"    <title>{escape(FEED_TITLE)}</title>",
        f"    <link>{escape(root)}</link>",
        f"    <atom:link href={quoteattr(root + FEED.name)} rel=\"self\" "
        f"type=\"application/rss+xml\"/>",
        f"    <description>{escape(FEED_DESCRIPTION)}</description>",
        "    <language>en</language>",
        f"    <pubDate>{newest}</pubDate>" if newest else None,
        *items,
        "  </channel>",
        "</rss>",
    ])) + "\n"


def canonical_root() -> str:
    """Where the page lives, from its own canonical link.

    The feed has to name the page absolutely - a reader resolves nothing
    relative - and the canonical is already the one place that says where the
    site is (tools/version-assets.py reads it for the same reason).  The dev
    build moves it under /dev/ and the feed with it; see pages.yml.
    """
    page = (REPO / "web" / "index.html").read_text(encoding="utf-8")
    m = re.search(r'<link\b[^>]*\brel="canonical"[^>]*\bhref="([^"]+)"', page)
    if not m:
        raise SystemExit("web/index.html has no canonical link to build the feed from")
    return m.group(1).rstrip("/") + "/"


def main() -> None:
    cfg = tomllib.loads((REPO / "config" / "218e.toml").read_text())
    java = (REPO / "src" / "AssemblePressureFix.java").read_bytes()

    # The init marker hashes the assembler source itself, so a browser build
    # has to hash the identical bytes or every image would differ.  Base64
    # keeps them exact through the JS string.
    parts = [
        "// GENERATED by web/generate.py — do not edit.",
        "",
        "var GEN = {",
        f"  factorySha256: {json.dumps(cfg['firmware']['factory_sha256'])},",
        f"  version: {json.dumps(cfg['firmware'].get('version', '0.0.0'))},",
        f"  factoryKeyTable: {B.FACTORY_KEY_TABLE},",
        f"  pitchTableEntries: {B.PITCH_TABLE_ENTRIES},",
        f"  bottomKeyIndex: {B.BOTTOM_KEY_INDEX},",
        f"  calibrationVoltsPerOctave: {B.CALIBRATION_VOLTS_PER_OCTAVE},",
        f"  internalDefaults: {json.dumps(O.INTERNAL_DEFAULTS, sort_keys=True)},",
        f"  featureMap: {json.dumps({k: list(v) for k, v in B.FEATURE_MAP.items()}, sort_keys=True)},",
        f"  enabledWhen: {json.dumps(B.ENABLED_WHEN, sort_keys=True)},",
        f"  javaSourceBase64: {json.dumps(base64.b64encode(java).decode())},",
        f"  changelog: {json.dumps((REPO / 'CHANGELOG.txt').read_text())},",
        f"  clix: {json.dumps(__import__('clix').CLIX)},",
    ]

    # Both flashers, whole.  The page substitutes the checksum and version of
    # the image it just built, so a download arrives as a bundle that flashes
    # that image without asking.  Both files are LF - the .bat too, which cmd
    # accepts - and JSON round-trips whatever the bytes are.
    for key, name in (("flasherMac",  "mac/Program218e_v3_Rewired_macOS.command"),
                      ("flasherWin",  "windows/218e_Rewired_Flasher.bat")):
        parts.append(f"  {key}: {json.dumps((REPO / name).read_bytes().decode())},")

    # The bundled tunings, preloaded into the page's slots (behind a checkbox
    # that defaults to off).  Shipped as content rather than fetched, so the
    # page stays a self-contained set of files.
    bundled = [
        "tunings/Sabat II (C-rooted).scl",
        "tunings/5-Limit JI with Septimal 7th.scl",
        "tunings/12TET.scl",
    ]
    tunings = [{"name": Path(t).name, "text": (REPO / t).read_text()} for t in bundled]
    parts.append(f"  bundledTunings: {json.dumps(tunings)},")

    # Factory control transfers, as a flat [src, dst, pool, ...] array — about
    # 3,600 triples, and much smaller than re-parsing the text file.
    #
    # The pool word is the third field of a call made through one, and 0 for a
    # direct branch, which no real address is.  It has to travel: a patch that
    # rewrites the pool word has redirected that call, so its factory target is
    # no longer live from it, and web/build.js drops it exactly as
    # tools/build.py does.  Matching only two-field lines dropped the 948 pool
    # calls on the floor and left the page's guard checking 2,665 of 3,613.
    lines = (REPO / "tools" / "factory_control_flow.txt").read_text().splitlines()
    recorded = next(l.split()[1] for l in lines if l.startswith("factory_sha256 "))
    if recorded != cfg["firmware"]["factory_sha256"]:
        raise SystemExit("factory_control_flow.txt is for a different base image")
    flat = []
    for line in lines:
        if re.match(r"^[0-9a-f]{8} [0-9a-f]{8}( [0-9a-f]{8})?$", line):
            fields = [int(f, 16) for f in line.split()]
            flat += [fields[0], fields[1], fields[2] if len(fields) == 3 else 0]
    parts.append(f"  controlFlow: {json.dumps(flat)},")
    parts.append("};")
    parts.append("")
    parts.append("if (typeof module !== 'undefined' && module.exports) module.exports = GEN;")

    OUT.write_text("\n".join(parts) + "\n")

    # The changelog again, for feed readers.  Rewritten whenever generated.js
    # is, since both carry the same file: a changelog edit already means
    # running this, and it now brings the feed along.
    FEED.write_text(feed((REPO / "CHANGELOG.txt").read_text(), canonical_root()),
                    encoding="utf-8")

    # Bundle the assembler into web/ so the page loads only files beside it,
    # and so program.js is always the one transpiled from the current Java.
    subprocess.run([sys.executable, str(REPO / "tools" / "avr32" / "transpile.py")],
                   check=True, cwd=REPO, capture_output=True)
    bundle = REPO / "web" / "assembler.js"
    bundle.write_text("\n".join(
        (REPO / "tools" / "avr32" / name).read_text()
        for name in ("shim.js", "encoder.js", "runtime.js", "program.js")))
    print(f"  bundled assembler -> {bundle.relative_to(REPO)} "
          f"({bundle.stat().st_size // 1024} KB)")
    kb = OUT.stat().st_size // 1024
    print(f"wrote {OUT.relative_to(REPO)} ({kb} KB)")
    print(f"wrote {FEED.relative_to(REPO)} "
          f"({len(releases((REPO / 'CHANGELOG.txt').read_text()))} releases)")
    print(f"  {len(flat)//3} control transfers, {len(java)} bytes of assembler source")


if __name__ == "__main__":
    main()
