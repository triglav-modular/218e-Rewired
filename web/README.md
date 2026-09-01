# Browser firmware builder

Builds the firmware entirely client-side. There is no build server, and the
factory image never leaves the machine — it is read with `FileReader`, verified by
SHA-256, patched in memory and handed back as a download.

```bash
python3 -m http.server 8123 --directory web
```

Then open <http://localhost:8123>. Any static host works; nothing is fetched
from outside the page.

One thing does leave, and only on a download: which options were chosen, which
platform, and which version, POSTed to `beacon` beside the page. No identifier,
no header kept, and never the image or the calibration. The URL is relative, so
it reports only where something is listening for it — a clone served anywhere
else, or the page opened from a file, reports nowhere. See "Counting builds" in
[../docs/BUILD.md](../docs/BUILD.md).

## How it fits together

| File | Role |
|---|---|
| `index.html`, `app.js` | the interface |
| `build.js` | the whole build: options + factory image → flashable image |
| `buildlib.js` | ported from `tools/build.py` — tables, hex, flags, properties |
| `sha256.js` | synchronous SHA-256 (SubtleCrypto is async and absent from jsc) |
| `generated.js` | **generated** — frozen defaults, feature map, control flow, assembler source |
| `assembler.js` | **generated** — the AVR32 assembler, bundled from `tools/avr32/` |
| `images/og-card.png` | **generated** — the link preview, by `tools/make-og-card.py` |

The card is what Facebook, LinkedIn, Slack, Discord and iMessage show when
somebody posts the page: the banana, whole and centred, on the page's own
ground. It is committed rather than built in the workflow — drawing it needs
Pillow and cairosvg, and the deploy runner has neither. Redraw it after a
change to the palette, the background or the banana:

```bash
pip install pillow cairosvg
python3 tools/make-og-card.py
```

Regenerate both generated files after changing `tools/options.py`,
`tools/build.py`, `src/AssemblePressureFix.java` or
`tools/factory_control_flow.txt`:

```bash
python3 web/generate.py
```

## Why it can be trusted

The browser runs the same assembler the command-line build does, and that one
is checked against Ghidra instruction by instruction. On top of that:

```bash
python3 web/test_configs.py
```

builds ten configurations with `tools/build.py` and again with this pipeline,
and compares **both** the generated `build.properties` and the final image
SHA-256. All ten match, including `historical` — the most complex
configuration, with measured calibration and three tunings. It is an anchor
for that combination rather than a reproduction of any older image: the
power-up marker hashes the assembler source, so no build made today can be
byte-identical to one made before the source changed.

A build takes about 200 ms.

## Flashing

Not done here, and deliberately so. The page builds the image; the flasher for
your platform installs it, because those already validate the file against
`dfu-programmer`'s own parser, confirm `BOOTPROT`, and gate the exit from DFU
on read-back validation. Reimplementing that over WebUSB would mean rewriting
the one part of this project that is already proven, in a browser, for no gain.

A download carries two images: the build the page just made, and the stock
v36.9 image it was made from — the file that was uploaded a moment earlier,
handed back so that going back to stock does not mean going and finding it
again. Both sit in `firmware/` beside the flasher, which lists them with what
each one is and lets the choice be made.

Any structurally valid 218e image is accepted, not only those two. The checksum
each flasher is built with is a label, so the default build can be named in that
list rather than shown as a bare hash; it is not a gate. The gate is the
validator, and what it refuses is whatever `dfu-programmer` would refuse after
the erase has already run.
