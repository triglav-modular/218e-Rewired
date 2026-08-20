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
# URI, a fragment, or already carrying a query is left alone.
HTML_REF = re.compile(r'\b(href|src)=(["\'])([^"\']+)\2')
CSS_REF = re.compile(r'url\((["\']?)([^)"\']+)\1\)')
# The flashing tools are fetched by script, so their URLs are string literals
# rather than markup, and nothing above would ever see them.  Only kit/ paths:
# a sweep of every string in a JavaScript file would eventually stamp something
# that only looked like a filename.
JS_REF = re.compile(r'(["\'])(kit/[^"\']+)\1')
SKIP = re.compile(r'^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|//|#|/)')


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
