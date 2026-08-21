#!/usr/bin/env python3
"""Mirror the parts of triglavmodular.hu that this page borrows.

Two of them, both copied by hand once and both drifting for it.  The header
menu: Open Source pointed at the GitHub org here and at /open-source/ there,
and later Cart and Account became marks over there and stayed words here.  And
the hover sweep, which this page invented and the theme then adopted - the
same gradient and the same .5s written out twice, in two repositories, with
nothing to hold them together but whoever remembered.

The theme is the source for both now.  Its palette and its sweep are pulled
into a generated block at the top of the stylesheet; the rules that use them
stay here, because where the sweep applies is this page's business and what it
is is the theme's.

    tools/sync-site.py web                  rewrite both in place
    tools/sync-site.py --check web          say whether they are in step
    tools/sync-site.py _site                what the deploy runs

The page cannot fetch this itself.  triglavmodular.hu sends no CORS header on
its HTML, so a browser on github.io would be refused; and the page is meant to
work opened straight off a local clone, with no network at all.  So the menu is
resolved when the site is built and baked into what ships, which also keeps a
WordPress install off the critical path of a page whose whole job is flashing
firmware.

Nothing crosses over but values.  From the markup: an href, a link's text, and
a class that has to be in an allowlist.  From the stylesheet: colours that have
to look like colours, an angle, four percentages and two timings - each matched
against a pattern narrow enough that nothing else can pass, and then written
out again here from the parts rather than copied as text.  So a compromise of
the site upstream could change what our menu says, where it points, and what
colour things go; it could not put script, markup, a selector or a url() into
this page.

Nothing here ever fails a build.  If the site is unreachable, or its markup has
moved, or what comes back does not look right, whatever is committed is left
exactly as it is and the reason is printed: a page one item out of date is a
far better outcome for this one than a deploy that does not happen.
"""

from __future__ import annotations

import argparse
import html
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser
from pathlib import Path

SOURCE = "https://triglavmodular.hu/"
SOURCE_HOST = "triglavmodular.hu"
# The theme labels its header nav, which is a far steadier handle than the
# generated wp-container-* class beside it - that one is a hash of the layout
# and changes whenever the block is edited.
NAV_LABEL = "Header navigation"
# The presentational classes carried across, as an allowlist rather than
# whatever the site happens to put on the item.  A class this stylesheet has no
# rule for would at best do nothing, and ICON at worst does harm: it hides the
# word, so an icon we cannot draw would leave an empty square where a link used
# to be.  Hence ICON only travels with a variant that is in ICONS - an icon we
# have never heard of arrives as an ordinary text link, which always works.
MINOR = "minor"
ICON = "nav-icon"
ICONS = ("nav-icon-cart", "nav-icon-account")
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
# The generated region of the stylesheet, markers and all.
CSS_BLOCK = re.compile(r"(/\* sync-site:begin \*/\n).*?(/\* sync-site:end \*/)", re.S)
# The theme's palette, under the names this page knows them by.  Anything the
# theme does not carry - --warn, the faces, the chevron - stays hand-written in
# the :root below the generated block.
PALETTE = ("bg", "panel", "panel2", "line", "ink", "muted", "accent", "accent2",
           "ok", "bad")
# Narrow enough that nothing but a colour can pass.  No url(), no function this
# does not name, no bare identifier that might be a var() in disguise.
COLOUR = re.compile(r"#[0-9a-fA-F]{3,8}$|rgba?\([\d\s.,%/]+\)$")
TIME = r"\d*\.?\d+m?s"
EASE = r"(?:linear|ease|ease-in|ease-out|ease-in-out|cubic-bezier\([\d\s.,-]+\))"
# linear-gradient(<angle>, <muted> 0 <p>, <accent2> <p>, <accent> <p> 100%) -
# the sweep, whatever the theme currently calls its tokens.
SWEEP = re.compile(
    r"linear-gradient\(\s*(\d*\.?\d+deg)\s*,\s*"
    r"var\(--(?:tm-)?muted\)\s+0\s+(\d*\.?\d+%)\s*,\s*"
    r"var\(--(?:tm-)?accent2\)\s+(\d*\.?\d+%)\s*,\s*"
    r"var\(--(?:tm-)?accent\)\s+(\d*\.?\d+%)\s+100%\s*\)", re.S)
LEN = r"-?\d*\.?\d+(?:px|rem|em)"
COMMENT = re.compile(r"/\*.*?\*/", re.S)
# Where the header puts the two things that sit outside the menu's row.  Each
# is only believed if the rule it comes from is the absolute one: a length
# lifted out of a rule that has stopped positioning anything would still look
# like a length, and would be quietly wrong rather than obviously missing.
BRAND_LEFT = re.compile(
    rf"\.sitenav\s+\.brand\s*\{{[^{{}}]*?position:\s*absolute[^{{}}]*?\bleft:\s*({LEN})", re.S)
SLIDE = re.compile(rf"transition:\s*background-position\s+({TIME})\s+({EASE})\s*;")
PASS = re.compile(rf"animation:\s*[\w-]*sweep-\w+\s+({TIME})\s+({TIME})\s+({EASE})\s+forwards")
THEME_CSS = re.compile(r'href=["\']([^"\']*themes/[^"\']+/style\.css[^"\']*)["\']')


class HeaderNav(HTMLParser):
    """Collect (href, label, classes) from the site's header navigation."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.items: list[tuple[str, str, tuple[str, ...]]] = []
        self._depth = 0          # nav nesting, so a nav inside ours cannot end it
        self._classes: tuple[str, ...] = ()
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
            self._classes = wanted(a.get("class", "").split())
        elif tag == "a" and self._href is None:
            self._href = a.get("href", "").strip()
            self._text = []

    def handle_endtag(self, tag: str) -> None:
        if tag == "nav" and self._depth:
            self._depth -= 1
        elif tag == "a" and self._depth and self._href is not None:
            label = " ".join("".join(self._text).split())
            if label:
                self.items.append((self._href, label, self._classes))
            self._href = None

    def handle_data(self, data: str) -> None:
        if self._depth and self._href is not None:
            self._text.append(data)


def wanted(classes: list[str]) -> tuple[str, ...]:
    """The classes worth carrying over, in a fixed order so two menus compare."""
    out = [MINOR] if MINOR in classes else []
    variant = next((c for c in ICONS if c in classes), None)
    if variant and ICON in classes:
        out += [ICON, variant]
    return tuple(out)


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


def read_menu(page: str) -> list[tuple[str, str, tuple[str, ...]]]:
    """The menu as the page currently has it, in the same shape as the fetch."""
    block = NAV_BLOCK.search(page)
    if not block:
        return []
    items = []
    for tag in re.finditer(r"<a\b([^>]*)>(.*?)</a>", block.group(0), re.S):
        attrs, inner = tag.group(1), tag.group(2)
        # An icon's word is wrapped so the stylesheet can hide it; strip the
        # wrapper back off, so what is compared is the label either way.
        label = " ".join(html.unescape(re.sub(r"<[^>]+>", "", inner)).split())
        href = re.search(r'href="([^"]*)"', attrs)
        cls = re.search(r'class="([^"]*)"', attrs)
        items.append((href.group(1) if href else "",
                      label,
                      wanted(cls.group(1).split() if cls else [])))
    return items


def render(items: list[tuple[str, str, tuple[str, ...]]], indent: str) -> str:
    lines = [f"{indent}<nav>"]
    for href, label, classes in items:
        attrs = f' class="{" ".join(classes)}"' if classes else ""
        attrs += f' href="{html.escape(href, quote=True)}"'
        if href.startswith(CURRENT_SECTION):
            attrs += ' aria-current="page"'
        # An icon still says its name to anyone not looking at it, so the word
        # is kept and wrapped for the stylesheet to hide.  Only there: the text
        # links carry their sweep on the anchor's own background, clipped to
        # the letters, and a span between the two would take the letters out
        # of the box doing the painting.
        text = (f"<span>{html.escape(label)}</span>" if ICON in classes
                else html.escape(label))
        lines.append(f"{indent}  <a{attrs}>{text}</a>")
    lines.append(f"{indent}</nav>")
    return "\n".join(lines)


def vet(items: list[tuple[str, str, tuple[str, ...]]]) -> str | None:
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


def theme_url(home: str) -> str | None:
    """The child theme's stylesheet, as linked by the page that uses it."""
    found = [urllib.parse.urljoin(SOURCE, h) for h in THEME_CSS.findall(home)]
    # Prefer the theme named after the site, matching on the directory rather
    # than the whole URL - the host is called triglavmodular too, so a plain
    # substring test picks the parent theme and finds none of its tokens.
    # Failing that the last, which wins the cascade and so is the one actually
    # dressing the page.
    for url in found:
        d = re.search(r"themes/([^/]+)/style\.css", url)
        if d and "triglav" in d.group(1):
            return url
    return found[-1] if found else None


def icon_right(css: str, cls: str) -> str | None:
    """How far the theme insets one shop icon from the window's right edge."""
    if not re.search(rf"\.sitenav[^{{}}]*\.{cls}\b[^{{}}]*\{{[^{{}}]*?position:\s*absolute", css, re.S) \
       and not re.search(rf"\.sitenav\s+\w*\.nav-icon\s*\{{[^{{}}]*?position:\s*absolute", css, re.S):
        return None
    m = re.search(rf"\.sitenav[^{{}}]*\.{cls}\b[^{{}}]*\{{[^{{}}]*?\bright:\s*({LEN})", css, re.S)
    return m.group(1) if m else None


def read_theme(raw: str) -> dict[str, str] | str:
    """The values this page borrows, or a sentence about why it cannot."""
    # Comments out of the way first: the theme explains itself in prose, and
    # its prose says things like "8 from the left" that a pattern looking for
    # a length after "left" would be delighted to find.
    css = COMMENT.sub(" ", raw)
    out = {}
    for name in PALETTE:
        m = re.search(rf"--tm-{name}\s*:\s*([^;]+);", css)
        if not m:
            return f"no --tm-{name}"
        value = " ".join(m.group(1).split())
        if not COLOUR.match(value):
            return f"--tm-{name} is {value!r}, which is not a plain colour"
        out[name] = value

    sweep = SWEEP.search(css)
    if not sweep:
        return "no sweep gradient in the shape this page uses"
    angle, first, mid, last = sweep.groups()
    # Written out from the parts, never from the matched text: an angle and
    # three percentages are the whole of what the theme gets to decide here.
    out["sweep"] = (f"linear-gradient({angle},\n"
                    f"                        var(--muted) 0 {first},\n"
                    f"                        var(--accent2) {mid},\n"
                    f"                        var(--accent) {last} 100%)")

    slide = SLIDE.search(css)
    if not slide:
        return "no background-position transition to take the slide from"
    out["slide"] = f"{slide.group(1)} {slide.group(2)}"

    run = PASS.search(css)
    if not run:
        return "no sweep animation to take the timing from"
    out["pass"] = f"{run.group(1)} {run.group(2)} {run.group(3)}"

    brand = BRAND_LEFT.search(css)
    if not brand:
        return "the brand mark is not positioned off the left edge"
    out["brand-left"] = brand.group(1)
    for cls in ICONS:
        inset = icon_right(css, cls)
        if not inset:
            return f"{cls} is not pinned to the right edge"
        out[cls] = inset
    return out


def render_theme(v: dict[str, str]) -> str:
    lines = [f"  --{name}: {v[name]};" for name in PALETTE]
    lines += [f"  --sweep: {v['sweep']};",
              f"  --sweep-slide: {v['slide']};",
              f"  --sweep-pass: {v['pass']};",
              f"  --brand-left: {v['brand-left']};"]
    lines += [f"  --{cls}-right: {v[cls]};" for cls in ICONS]
    return ("/* The palette and the hover sweep belong to the theme on\n"
            "   triglavmodular.hu, and are pulled from it so that the two cannot\n"
            "   drift apart by hand - which they had, twice, before this existed.\n"
            "   Only the values come across; the rules that use them are this\n"
            "   page's own, further down.  Written by tools/sync-site.py: change\n"
            "   the theme, not this block. */\n"
            ":root {\n" + "\n".join(lines) + "\n}\n")


def sync_theme(css_path: Path, home: str, timeout: int, check: bool) -> int:
    sheet = css_path.read_text(encoding="utf-8")
    block = CSS_BLOCK.search(sheet)
    if not block:
        print(f"{css_path}: no sync-site block to fill", file=sys.stderr)
        return 0

    url = theme_url(home)
    if not url:
        return keep("theme", "the page links no theme stylesheet")
    if urllib.parse.urlparse(url).hostname != SOURCE_HOST:
        return keep("theme", f"the theme stylesheet is on {url!r}, not {SOURCE_HOST}")
    try:
        values = read_theme(fetch(url, timeout))
    except (urllib.error.URLError, OSError, ValueError) as e:
        return keep("theme", f"{url} not read ({e})")
    if isinstance(values, str):
        return keep("theme", values)

    want = render_theme(values)
    if block.group(0) == block.group(1) + want + block.group(2):
        print(f"{css_path}: palette and sweep are in step with the theme")
        return 0
    if check:
        print(f"{css_path}: palette or sweep has drifted from the theme")
        return 1
    css_path.write_text(
        sheet[:block.start()] + block.group(1) + want + block.group(2)
        + sheet[block.end():], encoding="utf-8")
    print(f"{css_path}: palette and sweep updated from the theme")
    return 0


def keep(what: str, why: str) -> int:
    """Leave what is committed alone, and say why - never a failed build."""
    print(f"keeping the committed {what}: {why}")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("root", type=Path, nargs="?", default=Path("web"),
                    help="the directory holding index.html and style.css")
    ap.add_argument("--check", action="store_true",
                    help="report drift instead of rewriting; 1 if out of step")
    ap.add_argument("--timeout", type=int, default=20)
    args = ap.parse_args(argv[1:])

    # One fetch of the page feeds both halves: the menu is in it, and the theme
    # stylesheet is whichever one it links to.
    try:
        home = fetch(SOURCE, args.timeout)
    except (urllib.error.URLError, OSError, ValueError) as e:
        return keep("menu and sweep", f"{SOURCE} not read ({e})")

    drifted = sync_menu(args.root / "index.html", home, args.check)
    drifted |= sync_theme(args.root / "style.css", home, args.timeout, args.check)
    return drifted


def sync_menu(page_path: Path, home: str, check: bool) -> int:
    page = page_path.read_text(encoding="utf-8")
    block = NAV_BLOCK.search(page)
    if not block:
        print(f"{page_path}: no <nav> block to sync", file=sys.stderr)
        return 0

    parser = HeaderNav()
    parser.feed(home)
    live = parser.items

    why = vet(live)
    if why:
        return keep("menu", f"{SOURCE} gave {why}")

    have = read_menu(page)
    if have == live:
        print(f"{page_path}: menu is in step with {SOURCE} ({len(live)} entries)")
        return 0

    for line in describe(have, live):
        print(f"  {line}")
    if check:
        print(f"{page_path}: menu has drifted from {SOURCE}")
        return 1

    page_path.write_text(NAV_BLOCK.sub(
        lambda _: render(live, block.group(1)), page, count=1), encoding="utf-8")
    print(f"{page_path}: menu updated from {SOURCE} ({len(live)} entries)")
    return 0


def describe(have: list, live: list) -> list[str]:
    """The difference, as lines, so a warning in a log says what actually moved."""
    by_label = {label: (href, classes) for href, label, classes in have}
    out = []
    for href, label, classes in live:
        if label not in by_label:
            out.append(f"+ {label} -> {href}")
        elif by_label[label] != (href, classes):
            was, was_classes = by_label[label]
            if was != href:
                out.append(f"~ {label}: {was} -> {href}")
            if was_classes != classes:
                out.append(f"~ {label}: {' '.join(was_classes) or 'plain'}"
                           f" -> {' '.join(classes) or 'plain'}")
    live_labels = {label for _, label, _ in live}
    out += [f"- {label} ({by_label[label][0]})"
            for label in by_label if label not in live_labels]
    return out


if __name__ == "__main__":
    sys.exit(main(sys.argv))
