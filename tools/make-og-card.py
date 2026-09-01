#!/usr/bin/env python3
"""Draw web/images/og-card.png - the link preview for the builder page.

Without one, Facebook, Slack, iMessage and the rest are left to guess: they
take the only image the page has, which is the 56px banana in the header, and
blow it up to fill a 1200x630 card.  What that produces is a blurred crop of
one curve of the peel, on white, with no words on it.

So the card is drawn here instead, from the same parts the page is made of -
the palette, the wave, the mark, the banana, the version - so that it cannot
say something the page has stopped saying.

    pip install pillow "fonttools[woff]" cairosvg
    python3 tools/make-og-card.py

Run by hand, like tools/make-icons.py, and the PNG is committed: the page
workflow runs on a machine with none of those three installed, and a card that
only exists when a rasteriser does is a card that eventually does not exist.

The type is IBM Plex Mono, which is in this repository under the OFL.  The
page's own display face, Euclid Circular A, deliberately is not - it is
licensed to Triglav Modular, not redistributable, and served from
triglavmodular.hu.  Whoever holds that licence can pass their own copy:

    python3 tools/make-og-card.py --display-font ~/fonts/EuclidCircularA-Bold.otf

and the headline is set in it.  Everything else is unchanged, and the default
run needs nothing that is not already here.
"""
import argparse
import io
import re
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from fontTools.ttLib import TTFont
import cairosvg

REPO = Path(__file__).resolve().parent.parent
WEB = REPO / "web"

# 1200x630 is Facebook's recommended size and the 1.91:1 it crops everything
# else to; Twitter/X, LinkedIn, Slack and iMessage all read the same card.
W, H = 1200, 630
PAD = 72

# From web/style.css, which tools/sync-site.py keeps in step with the theme.
# Read out of the sheet rather than repeated here: a card in last season's
# colours is exactly the drift that block exists to prevent.
PALETTE_KEYS = ("bg", "panel", "line", "ink", "muted", "accent", "accent2")


def palette():
    css = (WEB / "style.css").read_text(encoding="utf-8")
    block = css.split("/* sync-site:begin */", 1)[1].split("/* sync-site:end */", 1)[0]
    out = {}
    for key in PALETTE_KEYS:
        m = re.search(rf"--{key}:\s*(#[0-9a-fA-F]{{3,8}})\s*;", block)
        if not m:
            raise SystemExit(f"--{key} is not in the synced block of web/style.css")
        out[key] = m.group(1)
    return out


def version():
    """The version the page prints beside the title: major.minor, no patch."""
    toml = (REPO / "config" / "218e.toml").read_text(encoding="utf-8")
    m = re.search(r'^\s*version\s*=\s*"([^"]+)"', toml, re.M)
    if not m:
        raise SystemExit("no firmware version in config/218e.toml")
    return ".".join(m.group(1).split(".")[:2])


class Faces:
    """IBM Plex Mono out of web/fonts, plus an optional display face.

    Pillow reads TrueType and OpenType and not woff2, and the repository ships
    woff2 because that is what the page loads.  fontTools unwraps one into the
    other in memory, so nothing has to be committed twice.
    """

    def __init__(self, display=None):
        self._raw = {}
        for weight in ("Regular", "SemiBold", "Bold"):
            src = WEB / "fonts" / f"IBMPlexMono-{weight}.woff2"
            font = TTFont(src)
            buf = io.BytesIO()
            font.flavor = None          # woff2 in, plain sfnt out
            font.save(buf)
            self._raw[weight] = buf.getvalue()
        self._raw["Display"] = (Path(display).read_bytes() if display
                                else self._raw["Bold"])
        if display and Path(display).suffix.lower() == ".woff2":
            font = TTFont(io.BytesIO(self._raw["Display"]))
            buf = io.BytesIO()
            font.flavor = None
            font.save(buf)
            self._raw["Display"] = buf.getvalue()

    def __call__(self, weight, size):
        return ImageFont.truetype(io.BytesIO(self._raw[weight]), size)


def svg(path, width, fill=None, rotate=0.0):
    """An SVG from the repository, rendered to RGBA at 4x and brought down.

    cairosvg antialiases well enough on its own, but the rotation does not:
    turning a 210px bitmap leaves visible steps on the peel's outline, and
    turning an 840px one and then halving it twice does not.
    """
    source = Path(path).read_text(encoding="utf-8")
    if fill:
        source = source.replace('fill="#000000"', f'fill="{fill}"')
    png = cairosvg.svg2png(bytestring=source.encode("utf-8"),
                           output_width=width * 4)
    art = Image.open(io.BytesIO(png)).convert("RGBA")
    if rotate:
        art = art.rotate(rotate, resample=Image.BICUBIC, expand=True)
    ratio = art.height / art.width
    return art.resize((width, max(1, round(width * ratio))), Image.LANCZOS)


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
    # The page runs it at .5, across a viewport far wider than the 820px
    # column of text.  A card is all column: at .5 the strokes read as marks
    # on the headline rather than as a ground behind it.
    alpha = wave.getchannel("A").point(lambda a: round(a * 0.32))
    wave.putalpha(alpha)
    card.alpha_composite(wave)
    return card


def tracked(draw, xy, text, font, fill, tracking):
    """Draw with letter-spacing, which Pillow has no notion of."""
    x, y = xy
    for ch in text:
        draw.text((x, y), ch, font=font, fill=fill)
        x += draw.textlength(ch, font=font) + tracking
    return x - tracking


def runs(draw, xy, parts, font, sep, sep_fill):
    """A "a · b · c" line where the separators carry their own colour."""
    x, y = xy
    for i, (text, fill) in enumerate(parts):
        if i:
            draw.text((x, y), sep, font=font, fill=sep_fill)
            x += draw.textlength(sep, font=font)
        draw.text((x, y), text, font=font, fill=fill)
        x += draw.textlength(text, font=font)
    return x


def build(out, display_font=None):
    c = palette()
    face = Faces(display_font)
    card = ground(c["bg"])
    draw = ImageDraw.Draw(card)

    # The banana sits where it sits on the page: top right, turned -12 degrees,
    # and about the same share of the width it has there (140 of 820).
    banana = svg(WEB / "images" / "banana.svg", 214, rotate=12)
    card.alpha_composite(banana, (W - PAD - banana.width + 6, 46))

    # The brand row, set like the site navigation: the mark, then the name
    # uppercase at 2px of tracking.
    mark = svg(WEB / "icons" / "safari-pinned-tab.svg", 34, fill="#fffdfb")
    card.alpha_composite(mark, (PAD, 56))
    tracked(draw, (PAD + 50, 62), "TRIGLAV MODULAR",
            face("SemiBold", 19), c["muted"], 2.6)

    # The headline, and under it the sentence the page opens with.
    draw.text((PAD - 4, 176), "218e Rewired", font=face("Display", 104),
              fill=c["ink"])
    draw.text((PAD, 316), "Custom firmware for the Buchla 218e V3",
              font=face("Regular", 33), fill=c["muted"])

    # What it does, in the words the page uses for them.  Two lines rather
    # than one: at the size a feed actually shows a card - about 500px wide,
    # less on a phone - a single line of six features is unreadable.
    features = face("SemiBold", 27)
    runs(draw, (PAD, 396), [("step sequencer", c["ink"]),
                            ("scala tunings", c["ink"]),
                            ("pressure portamento", c["ink"])],
         features, "  ·  ", c["accent2"])
    runs(draw, (PAD, 438), [("arpeggiator", c["ink"]),
                            ("per-note calibration", c["ink"]),
                            ("volts per octave", c["ink"])],
         features, "  ·  ", c["accent2"])

    draw.line([(PAD, 512), (W - PAD, 512)], fill=c["line"], width=2)

    # The call to action, drawn as the page's own build button.
    label, size = "Build it in your browser", 29
    font = face("SemiBold", size)
    text_w = draw.textlength(label, font=font)
    pill = (PAD, 546, PAD + text_w + 62, 546 + 60)
    draw.rounded_rectangle(pill, radius=30, fill=c["accent"])
    draw.text((PAD + 31, 546 + 30), label, font=font, fill=c["bg"],
              anchor="lm")

    # And the version, right-aligned on the same line, as the page prints it.
    draw.text((W - PAD, 546 + 30), f"v{version()}",
              font=face("Regular", 27), fill=c["muted"], anchor="rm")

    card.convert("RGB").save(out, "PNG", optimize=True)
    print(f"  wrote {Path(out).relative_to(REPO)} "
          f"({Path(out).stat().st_size // 1024} KB)")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", default=str(WEB / "images" / "og-card.png"))
    ap.add_argument("--display-font", default=None,
                    help="a .otf/.ttf/.woff2 to set the headline in; "
                         "IBM Plex Mono Bold otherwise")
    args = ap.parse_args()
    build(args.out, args.display_font)


if __name__ == "__main__":
    main()
