# Buchla 218e V3 — custom firmware

A patched build of the stock **v36.9** firmware for the Buchla 218e touch
keyboard, driving a 208p (Easel). It reworks the pressure response, replaces an
external uTune with in-firmware tuning and per-key calibration, and repurposes
the four preset-voltage knobs and the arpeggiator switch.

| | |
|---|---|
| **Base image** | `mac/firmware/218eV3_v369_DFU.hex` (AT32UC3B1256, AVR32) |
| **Output** | `mac/firmware/218eV3_v369_PressureFix_DFU.hex` |
| **Settings** | [`config/218e.toml`](config/218e.toml) |

## What it does

- **Delicate pressure response** — the 218r's gentle 10 dB exponential curve
  with an immediate onset step, in place of the factory curve that stayed
  nearly silent until 85 % of travel. Calibration is hardcoded to sensible
  defaults; knobs 1 and 3 became centred trims.
- **Common-mode rejection** — cancels the two-hand proximity lift that
  otherwise pinned pressure at maximum.
- **In-firmware tuning** — three tuning tables built from Scala files
  (Sabat II, ADDAC JI, factory), switched from edit mode with LED indication.
- **Per-key pitch calibration** — the 208p's measured tracking error corrected
  per semitone, finer than the uTune's per-octave scheme (≤1.5 cents residual).
- **Pressure-weighted portamento** — Haken Continuum style, on the portamento
  knob, fully off at zero.
- **Arpeggiator controls on the four knobs** — note order, rhythm randomness,
  random octaves, and global vibrato.
- **Latch mode** on the arpeggiator switch: latch / regular / off.
- **Trigger timing fix** — gates no longer fire with the previous note's pitch.

Full details, with addresses: [`docs/FIRMWARE_CHANGES.md`](docs/FIRMWARE_CHANGES.md).

## Build

```bash
python3 tools/build.py
```

Needs Python 3.11+ and Ghidra 12.x (set `GHIDRA_HOME` or `[tools].ghidra_home`).
Everything that is a *choice* — tuning files, calibration data, and whether
each knob and the arp switch use the new or the factory behaviour — lives in
`config/218e.toml`. See [`docs/BUILD.md`](docs/BUILD.md).

## Flash

**Enable polyphonic MIDI first** (edit mode, key 29) — the DFU handshake needs
it, and this firmware forces it off at every power-up. Then run
`ProgramLEM218_PressureFix.command`.

The updater verifies the image checksum and `BOOTPROT=3` before programming and
validates by read-back. If the keyboard does not enter DFU it aborts **before
erasing anything**.

## Layout

```
config/       build settings — the only file you normally edit
tunings/      Scala files (12 degrees, 2/1 octave)
calibration/  measured per-key tracking error
src/          Ghidra scripts: the AVR32 assembler and verification tools
tools/        build.py — config to firmware
mac/          firmware images and the macOS flashing kit
docs/         what changed and how to build
```

## Notes

- The pressure CV tops out at 10 V; the 208p wants ~13.5 V for a fully open
  gate. The DAC has no gain bit, so that one needs the analog booster mod.
- Never repurpose the protected DFU bootloader region.
