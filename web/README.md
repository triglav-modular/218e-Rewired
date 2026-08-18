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

`flash.js` does the **pre-flight only**: it asks the running firmware to reboot
into DFU over Web MIDI (`F0 00 02 55 02 01 01 F7`, the same SysEx the
`.command` flasher sends), attaches to the bootloader over WebUSB, and reads
its state. Every request it makes is either a standard DFU 1.1 class request or
a read. **Nothing in it writes flash, fuses or security bits.**

WebUSB is Chrome and Edge only — Safari and Firefox have both declined it.
Windows additionally needs Zadig to bind WinUSB to the DFU device, and Linux a
udev rule.

Erase and programming are deliberately absent. Two reasons:

- **It cannot be tested here.** Shipping an untested chip-erase sequence is not
  the same kind of risk as shipping an untested build step. The failure is
  bounded — any accepted ISP command sets `ISP_FORCE`, and only a successful
  `start` clears it, so a botched flash powers back up in DFU and the
  `.command` flasher can recover it — but "bounded" is not "verified".
- **Licence.** The protocol byte sequences are Atmel's documented interface and
  are facts, but a transliteration of dfu-programmer's implementation would be
  a derivative of GPL code, which this repository's Unlicense does not cover.
  An independent implementation from the protocol is fine; a port is not.

The commands themselves are short and known — select memory unit
`06 03 00 <unit>`, select page `06 03 01 <hi> <lo>`, erase `04 00 FF`, read
`03 00 <start> <end>`, blank check `03 01 <start> <end>`, launch `04 03 00`,
with memory units flash 0, security 2, config 3, bootloader 4, user 6 — so
finishing this is a bounded job, but it needs an instrument in front of it.
