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

## Flashing

Not done here, and deliberately so. The page builds the image; the flasher for
your platform installs it, because those already verify the checksum, confirm
`BOOTPROT`, and gate the exit from DFU on read-back validation. Reimplementing
that over WebUSB would mean rewriting the one part of this project that is
already proven, in a browser, for no gain.

The flashers locate the built image themselves — `firmware/`, their own
directory, then Downloads — and accept only a file matching the checksum they
were generated against, so there is nothing for the user to move.
