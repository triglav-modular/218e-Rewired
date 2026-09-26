# Browser firmware builder

Builds the firmware entirely client-side. There is no build server, and the
factory image never leaves the machine — it is read with `FileReader`, verified by
SHA-256, patched in memory and handed back as a download.

```bash
python3 -m http.server 8123 --directory web
```

Then open <http://localhost:8123>. Any static host works. The only thing
fetched from outside the page is its typeface — four faces from
triglavmodular.hu, which the licence covers there and does not allow copying
here. A clone served anywhere else is refused them and falls back to the
system sans; the build is identical either way.

Two things leave. On a download: which options were chosen, which platform,
and which version, POSTed to `beacon` beside the page. And when a read or a
send of the settings over MIDI ends: which button, how it ended, the page's
version and the firmware the keyboard reported, POSTed to `settings-beacon`.
No identifier, no header kept, and never the image, the calibration or the
settings themselves. The URLs are relative, so they report only where
something is listening — a clone served anywhere else, or the page opened
from a file, reports nowhere. See "Counting builds" in
[../docs/BUILD.md](../docs/BUILD.md).

Some things now stay. The options, the tunings, the pattern bank, the
calibration and the verified factory image are kept in `localStorage`, so a
second visit carries on where the first stopped. That is per-origin storage and
none of it is sent anywhere. Two keys because the image is 261 KB and changes
once while the settings change on every click, and the key name carries the
page's own directory because the staging build under `/dev/` is the same origin
one path down — a single key would let a test there overwrite what the
released page remembered.

The feature is deliberately invisible: nothing on the page explains it, and the
only control is a small **Reset** beside the step 2 heading, which is hidden
until something has actually moved off its default. Reset puts the choices back
and nothing else — it walks the same ordered appliers a restore does, and the
Scala files, the pattern bank, the measured calibration and the dropped factory
image are not among the defaults, so none of them is touched. Re-ticking a box
brings what was loaded back with it. The deviations being empty afterwards is
what empties the save.

The options are stored as *deviations* from the page's defaults rather than as
a snapshot. Those defaults are a recommendation, re-tuned per release and
deliberately separate from `config/218e.toml`, so a snapshot would freeze a
returning visitor on whichever version they first saw, and an option added
default-on later would come back off. `BUILDLIB.SETTINGS_ORDER` fixes the order
a restore applies things in, which is a dependency rather than a preference:
the pitch offset renumbers every semitone and drops a loaded calibration by
design, so it has to go back first. `web/test_settings.js` asserts on that
array and reads `app.js` to catch an applier that was never added to it.

## How it fits together

| File | Role |
|---|---|
| `index.html`, `app.js` | the interface |
| `build.js` | the whole build: options + factory image → flashable image; `test_readback.js` builds, reads the record back into table slots as the page's read does, and builds again (local only: it needs the factory image) |
| `calibrate.js` | the automatic measurement: drives the keyboard over MIDI, measures the 208 on an audio input, fills in the calibration offsets |
| `settings.js` | the settings transport: pushes a build's record to the keyboard as NRPN, reads it back, commits it; `test_settingsmidi.js` runs it against a fake instrument, `test_nrpn.js` the codec in `buildlib.js` |
| `buildlib.js` | ported from `tools/build.py` — tables, hex, flags, properties, and the fold that accumulates readings onto a flashed table |
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

builds thirty configurations with `tools/build.py` and again with this
pipeline, and compares **both** the generated `build.properties` and the final
image SHA-256; eleven more are option sets both toolchains have to refuse, and
refuse for the same reason. A clean run reports every configuration matching,
`historical` included — the most complex one, with measured calibration and
three tunings. It is an anchor for that combination rather than a reproduction
of any older image: the power-up marker hashes the assembler source, so no
build made today can be byte-identical to one made before the source changed.

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
