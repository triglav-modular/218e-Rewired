#!/usr/bin/env python3
"""Stamp every asset URL in the built site with a hash of the file it points at.

A browser told it may keep style.css for ten minutes will keep it for ten
minutes, and behind a proxy that can be much longer.  Renaming the URL on every
change sidesteps the question: style.css?v=1f4a90c2 is a different URL from
style.css?v=0b32ee17, so there is nothing stale to serve.

Only the page itself still has to be revalidated, and it is the one file small
enough for that to cost nothing.

    tools/version-assets.py _site
"""
import hashlib
import re
import sys
from pathlib import Path

# href/src in HTML, url() in CSS.  Anything absolute, protocol-relative, a data
# URI, a fragment, or already carrying a query is left alone - except the one
# absolute case META_REF picks up further down.
HTML_REF = re.compile(r'\b(href|src)=(["\'])([^"\']+)\2')
CSS_REF = re.compile(r'url\((["\']?)([^)"\']+)\1\)')
# The flashing tools are fetched by script, so their URLs are string literals
# rather than markup, and nothing above would ever see them.  Only kit/ paths:
# a sweep of every string in a JavaScript file would eventually stamp something
# that only looked like a filename.
JS_REF = re.compile(r'(["\'])(kit/[^"\']+)\1')
SKIP = re.compile(r'^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|//|#|/)')
# The link preview card, which og:image has to name absolutely - no scraper
# resolves a relative one - so SKIP passes over it with every other absolute
# URL and it would go out unstamped.  It is the asset that most needs the
# stamp: a scraper shows what it cached to everyone who never opens the page,
# and Facebook holds a card for weeks.  The base comes from the page's own
# canonical link rather than a constant here, so this file keeps no second
# opinion about where the site lives.
CANONICAL = re.compile(r'<link\b[^>]*\brel=["\']canonical["\'][^>]*\bhref='
                       r'["\']([^"\']+)["\']')
META_REF = re.compile(r'<meta\b[^>]*\bcontent=(["\'])([^"\']+)\1')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()[:8]


def stamp(text, pattern, group, base, site, seen):
    def replace(m):
        ref = m.group(group)
        if SKIP.match(ref) or "?" in ref:
            return m.group(0)
        target = (base.parent / ref).resolve()
        try:
            target.relative_to(site.resolve())
        except ValueError:
            return m.group(0)          # outside the site, not ours to version
        if not target.is_file():
            return m.group(0)
        seen.append(str(target.relative_to(site.resolve())))
        return m.group(0).replace(ref, f"{ref}?v={digest(target)}", 1)
    return pattern.sub(replace, text)


def stamp_meta(text, base, site, seen):
    """Stamp any <meta content> that names a file of this site absolutely."""
    canonical = CANONICAL.search(text)
    if not canonical:
        return text
    href = canonical.group(1)
    # The canonical is the URL of the directory this page sits in, so a URL
    # under it is a path relative to the page, resolved the way every other
    # reference in the file is.
    root = href if href.endswith("/") else href + "/"

    def replace(m):
        url = m.group(2)
        # og:url is the page itself and every other meta is a word or a
        # number; neither is a file, and is_file below turns both away.
        if not url.startswith(root) or "?" in url:
            return m.group(0)
        ref = url[len(root):]
        target = (base.parent / ref).resolve()
        try:
            target.relative_to(site.resolve())
        except ValueError:
            return m.group(0)
        if not target.is_file():
            return m.group(0)
        seen.append(str(target.relative_to(site.resolve())))
        return m.group(0).replace(url, f"{url}?v={digest(target)}", 1)
    return META_REF.sub(replace, text)


def main(argv):
    site = Path(argv[1] if len(argv) > 1 else "_site")
    if not site.is_dir():
        sys.exit(f"{site} is not a directory")

    stamped = []
    # Stylesheets first: versioning a font inside style.css changes style.css,
    # and its own hash has to be taken after that, not before.
    for css in sorted(site.rglob("*.css")):
        text = css.read_text(encoding="utf-8")
        out = stamp(text, CSS_REF, 2, css, site, stamped)
        if out != text:
            css.write_text(out, encoding="utf-8")

    # Then scripts, and only then the page: stamping a kit path rewrites
    # app.js, and app.js's own hash has to be taken after that.
    for js in sorted(site.rglob("*.js")):
        text = js.read_text(encoding="utf-8")
        out = stamp(text, JS_REF, 2, js, site, stamped)
        if out != text:
            js.write_text(out, encoding="utf-8")

    for html in sorted(site.rglob("*.html")):
        text = html.read_text(encoding="utf-8")
        out = stamp(text, HTML_REF, 3, html, site, stamped)
        out = stamp_meta(out, html, site, stamped)
        if out != text:
            html.write_text(out, encoding="utf-8")

    if not stamped:
        sys.exit("no asset URLs were versioned - the page would go on being "
                 "served from cache after an update")
    for ref in sorted(set(stamped)):
        print(f"  versioned {ref}")
    print(f"{len(set(stamped))} assets stamped")


if __name__ == "__main__":
    main(sys.argv)
