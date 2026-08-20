#!/usr/bin/env python3
"""Mirror the header menu on triglavmodular.hu into the builder page.

The menu was copied by hand, and a hand copy drifts: by the time this was
written Open Source already pointed at the GitHub org here and at
/open-source/ there, and nothing on either side would ever have said so.

    tools/sync-menu.py web/index.html          rewrite the menu in place
    tools/sync-menu.py --check web/index.html  say whether it is in step

The page cannot fetch this itself.  triglavmodular.hu sends no CORS header on
its HTML, so a browser on github.io would be refused; and the page is meant to
work opened straight off a local clone, with no network at all.  So the menu is
resolved when the site is built and baked into what ships, which also keeps a
WordPress install off the critical path of a page whose whole job is flashing
firmware.

Only two things are taken from the fetched markup - the href and the link's
text - and the markup that goes into the page is written here from scratch:
schemes are checked, text is escaped, and anything unexpected in the shape of
the response drops the whole update rather than half-applying it.  A compromise
of the site upstream can therefore change what our menu says and where it
points, which is unavoidable when mirroring someone's menu, but it cannot put
script or markup of its own into this page.

Nothing here ever fails a build.  If the site is unreachable, or its markup has
moved, or the fetched menu looks wrong, the committed menu is left exactly as
it is and the reason is printed: a menu one item out of date is a far better
outcome for a page like this one than a deploy that does not happen.
"""

from __future__ import annotations

import argparse
import html
import re
import sys
import urllib.error
import urllib.request
from html.parser import HTMLParser
from pathlib import Path

SOURCE = "https://triglavmodular.hu/"
# The theme labels its header nav, which is a far steadier handle than the
# generated wp-container-* class beside it - that one is a hash of the layout
# and changes whenever the block is edited.
NAV_LABEL = "Header navigation"
# Carried across as-is: the site already marks Cart and Account this way, and
# the stylesheet here already knows what to do with it.
MINOR = "minor"
# This page is a mod, so this is the entry it should light up.  Matched on the
# section rather than the exact URL, which lets the site point Mods at whichever
# mod it likes without the marker going out.
CURRENT_SECTION = "https://triglavmodular.hu/mods/"
# Sanity bounds on what comes back.  A menu outside these is not a menu we
# recognise, and the right move is to keep the one we have.
MIN_ITEMS, MAX_ITEMS = 4, 24
MAX_LABEL = 40
SCHEMES = ("https://", "http://")

NAV_BLOCK = re.compile(r"([ \t]*)<nav>\n.*?^[ \t]*</nav>", re.S | re.M)


class HeaderNav(HTMLParser):
    """Collect (href, label, minor) from the site's header navigation."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.items: list[tuple[str, str, bool]] = []
        self._depth = 0          # nav nesting, so a nav inside ours cannot end it
        self._minor = False
        self._href: str | None = None
        self._text: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        a = {k: (v or "") for k, v in attrs}
        if tag == "nav":
            if self._depth or a.get("aria-label", "").strip() == NAV_LABEL:
                self._depth += 1
            return
        if not self._depth:
            return
        if tag == "li":
            self._minor = MINOR in a.get("class", "").split()
        elif tag == "a" and self._href is None:
            self._href = a.get("href", "").strip()
            self._text = []

    def handle_endtag(self, tag: str) -> None:
        if tag == "nav" and self._depth:
            self._depth -= 1
        elif tag == "a" and self._depth and self._href is not None:
            label = " ".join("".join(self._text).split())
            if label:
                self.items.append((self._href, label, self._minor))
            self._href = None

    def handle_data(self, data: str) -> None:
        if self._depth and self._href is not None:
            self._text.append(data)


def fetch(url: str, timeout: int) -> str:
    req = urllib.request.Request(url, headers={
        # Named rather than anonymous: if this ever misbehaves, whoever reads
        # the site's logs should be able to tell what it is at a glance.
        "User-Agent": "218e-Rewired menu sync (+https://github.com/triglav-modular/218e-Rewired)",
        "Accept": "text/html",
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        charset = r.headers.get_content_charset() or "utf-8"
        return r.read(4_000_000).decode(charset, "replace")


def read_menu(page: str) -> list[tuple[str, str, bool]]:
    """The menu as the page currently has it, in the same shape as the fetch."""
    block = NAV_BLOCK.search(page)
    if not block:
        return []
    items = []
    for tag in re.finditer(r"<a\b([^>]*)>(.*?)</a>", block.group(0), re.S):
        attrs, label = tag.group(1), " ".join(html.unescape(tag.group(2)).split())
        href = re.search(r'href="([^"]*)"', attrs)
        cls = re.search(r'class="([^"]*)"', attrs)
        items.append((href.group(1) if href else "",
                      label,
                      bool(cls and MINOR in cls.group(1).split())))
    return items


def render(items: list[tuple[str, str, bool]], indent: str) -> str:
    lines = [f"{indent}<nav>"]
    for href, label, minor in items:
        attrs = f' class="{MINOR}"' if minor else ""
        attrs += f' href="{html.escape(href, quote=True)}"'
        if href.startswith(CURRENT_SECTION):
            attrs += ' aria-current="page"'
        lines.append(f"{indent}  <a{attrs}>{html.escape(label)}</a>")
    lines.append(f"{indent}</nav>")
    return "\n".join(lines)


def vet(items: list[tuple[str, str, bool]]) -> str | None:
    """Why this menu should not be used, or None if it is fine."""
    if not MIN_ITEMS <= len(items) <= MAX_ITEMS:
        return f"{len(items)} entries, which is not a menu we recognise"
    for href, label, _ in items:
        if not href.startswith(SCHEMES):
            return f"{label!r} points at {href!r}, which is not http(s)"
        if len(label) > MAX_LABEL:
            return f"an entry's text is {len(label)} characters long"
    if not any(href.startswith(CURRENT_SECTION) for href, _, _ in items):
        return f"nothing under {CURRENT_SECTION}, so this page has no section"
    return None


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("page", type=Path, nargs="?", default=Path("web/index.html"))
    ap.add_argument("--check", action="store_true",
                    help="report drift instead of rewriting; 1 if out of step")
    ap.add_argument("--timeout", type=int, default=20)
    args = ap.parse_args(argv[1:])

    page = args.page.read_text(encoding="utf-8")
    block = NAV_BLOCK.search(page)
    if not block:
        print(f"{args.page}: no <nav> block to sync", file=sys.stderr)
        return 0

    try:
        parser = HeaderNav()
        parser.feed(fetch(SOURCE, args.timeout))
        live = parser.items
    except (urllib.error.URLError, OSError, ValueError) as e:
        print(f"keeping the committed menu: {SOURCE} not read ({e})")
        return 0

    why = vet(live)
    if why:
        print(f"keeping the committed menu: {SOURCE} gave {why}")
        return 0

    have = read_menu(page)
    if have == live:
        print(f"{args.page}: menu is in step with {SOURCE} ({len(live)} entries)")
        return 0

    for line in describe(have, live):
        print(f"  {line}")
    if args.check:
        print(f"{args.page}: menu has drifted from {SOURCE}")
        return 1

    args.page.write_text(NAV_BLOCK.sub(
        lambda _: render(live, block.group(1)), page, count=1), encoding="utf-8")
    print(f"{args.page}: menu updated from {SOURCE} ({len(live)} entries)")
    return 0


def describe(have: list, live: list) -> list[str]:
    """The difference, as lines, so a warning in a log says what actually moved."""
    by_label = {label: (href, minor) for href, label, minor in have}
    out = []
    for href, label, minor in live:
        if label not in by_label:
            out.append(f"+ {label} -> {href}")
        elif by_label[label] != (href, minor):
            was, was_minor = by_label[label]
            if was != href:
                out.append(f"~ {label}: {was} -> {href}")
            if was_minor != minor:
                out.append(f"~ {label}: {'now' if minor else 'no longer'} {MINOR}")
    live_labels = {label for _, label, _ in live}
    out += [f"- {label} ({by_label[label][0]})"
            for label in by_label if label not in live_labels]
    return out


if __name__ == "__main__":
    sys.exit(main(sys.argv))
