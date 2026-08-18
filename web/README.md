# Browser firmware builder

Builds the firmware entirely client-side. There is no server, and the factory
image never leaves the machine — it is read with `FileReader`, verified by
SHA-256, patched in memory and handed back as a download.

```bash
python3 -m http.server 8123 --directory web
```

Then open <http://localhost:8123>. Any static host works; nothing is fetched
from outside the page.

## How it fits together

| File | Role |
|---|---|
| `index.html`, `app.js` | the interface |
| `build.js` | the whole build: options + factory image → flashable image |
| `buildlib.js` | ported from `tools/build.py` — tables, hex, flags, properties |
| `sha256.js` | synchronous SHA-256 (SubtleCrypto is async and absent from jsc) |
| `generated.js` | **generated** — frozen defaults, feature map, control flow, assembler source |
| `assembler.js` | **generated** — the AVR32 assembler, bundled from `tools/avr32/` |

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
SHA-256. All ten match, including `historical`, which reproduces the image the
firmware shipped with before the config was reduced to seven options.

A build takes about 200 ms.

## Not done yet

Flashing. Entering DFU is one Web MIDI SysEx (`F0 00 02 55 02 01 01 F7`), but
programming means reimplementing Atmel's DFU protocol over WebUSB — including
the fuse checks that make the current flasher safe — and WebUSB is Chrome and
Edge only. Until then the page produces the image and
`ProgramLEM218_PressureFix.command` flashes it.
