#!/usr/bin/env python3
"""Draw web/images/og-card.png - the link preview for the builder page.

Without one, Facebook, Slack, iMessage and the rest are left to guess: they
take the only image the page has, which is the 56px banana in the header, and
blow it up to fill a 1200x630 card.  What that produces is a blurred crop of
one curve of the peel, on white, with no words on it.

So the card is drawn here instead: the same banana, whole and at a size it was
meant to be seen at, on the same ground the page stands on.  Nothing is
written on it - the title and the description in the <head> are what carry the
words, and a card that repeats them says everything twice.

    pip install pillow cairosvg
    python3 tools/make-og-card.py

Run by hand, like tools/make-icons.py, and the PNG is committed: the page
workflow runs on a machine with neither installed, and a card that only exists
where a rasteriser does is a card that eventually does not exist.
"""
import argparse
import re
from io import BytesIO
from pathlib import Path

from PIL import Image
import cairosvg

REPO = Path(__file__).resolve().parent.parent
WEB = REPO / "web"

# 1200x630 is Facebook's recommended size and the 1.91:1 it crops everything
# else to; Twitter/X, LinkedIn, Slack and iMessage read the same card.
W, H = 1200, 630

# How tall the banana itself stands in that frame, ink to ink.  Two thirds:
# enough to be the subject at the size a feed shows a card, with enough ground
# left around it that it reads as placed rather than cropped.
SUBJECT = round(H * 0.66)

# The page's background, out of the block tools/sync-site.py keeps in step
# with the theme rather than repeated here - a card in last season's colours
# is exactly the drift that block exists to prevent.
def background():
    css = (WEB / "style.css").read_text(encoding="utf-8")
    block = css.split("/* sync-site:begin */", 1)[1].split("/* sync-site:end */", 1)[0]
    m = re.search(r"--bg:\s*(#[0-9a-fA-F]{3,8})\s*;", block)
    if not m:
        raise SystemExit("--bg is not in the synced block of web/style.css")
    return m.group(1)


def svg(path, height, rotate=0.0):
    """An SVG from the repository, turned, trimmed to its ink and sized to it.

    Rendered at 4x and brought down, because cairosvg antialiases well enough
    on its own but the rotation does not: turning a 400px bitmap leaves
    visible steps on the peel's outline, and turning a 1600px one and then
    quartering it does not.

    Trimmed, because the artwork does not fill its own viewBox - it is an
    Illustrator export with air on one side - and centring the frame it came
    in put the banana visibly right of centre.  What is centred has to be the
    drawing, so the frame goes and `height` means the banana.
    """
    png = cairosvg.svg2png(url=str(path), output_height=height * 4)
    art = Image.open(BytesIO(png)).convert("RGBA")
    if rotate:
        art = art.rotate(rotate, resample=Image.BICUBIC, expand=True)
    art = art.crop(art.getbbox())
    width = max(1, round(height * art.width / art.height))
    return art.resize((width, height), Image.LANCZOS)


def ground(colour):
    """The page's background: the flat colour, then the wave over it at .5."""
    card = Image.new("RGBA", (W, H), colour)
    wave = Image.open(WEB / "images" / "wave_bg.png").convert("RGBA")
    # background-size: cover - scale to fill, then crop the overflow centred.
    scale = max(W / wave.width, H / wave.height)
    wave = wave.resize((round(wave.width * scale), round(wave.height * scale)),
                       Image.LANCZOS)
    left, top = (wave.width - W) // 2, (wave.height - H) // 2
    wave = wave.crop((left, top, left + W, top + H))
    alpha = wave.getchannel("A").point(lambda a: a // 2)     # opacity: .5
    wave.putalpha(alpha)
    card.alpha_composite(wave)
    return card


def build(out):
    card = ground(background())
    # Turned the way it is turned on the page, where it sits at -12 degrees.
    # Rotating grows the bitmap into transparent corners, so centring the
    # bitmap is centring the banana.
    banana = svg(WEB / "images" / "banana.svg", SUBJECT, rotate=12)
    card.alpha_composite(banana, ((W - banana.width) // 2,
                                  (H - banana.height) // 2))
    card.convert("RGB").save(out, "PNG", optimize=True)
    print(f"  wrote {Path(out).relative_to(REPO)} "
          f"({Path(out).stat().st_size // 1024} KB)")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", default=str(WEB / "images" / "og-card.png"))
    build(ap.parse_args().out)


if __name__ == "__main__":
    main()
