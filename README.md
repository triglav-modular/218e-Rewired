# 218e v3 Rewired — custom firmware for the Buchla 218e v3

> **⚠ For the Buchla 218e version 3 only, running firmware v36.9.** Not the
> 218, not the 218r, not the 218e v1 or v2.
>
> **Using this tool and firmware is entirely at your own risk.** It is
> experimental and unofficial, not made or supported by Buchla, and probably
> voids your warranty. It has been tested on *one* instrument and it can brick
> your keyboard. No warranty of any kind. Neither the authors nor Buchla is
> liable for damage, loss of use, or a keyboard that no longer works.

A patched build of the stock **v36.9** firmware for the Buchla 218e touch
keyboard, driving a 208 (Easel). It reworks the pressure response, adds
in-firmware tuning and per-key pitch calibration, and repurposes the four
preset-voltage knobs and the arpeggiator switch.

## Build it in your browser

### **<https://triglav-modular.github.io/218e-v3-Rewired/>**

**This is the way to build a firmware.** Supply your own factory image, pick
the options, and the page hands you a firmware file — no toolchain, no clone,
nothing uploaded. It also walks through flashing, including the one-off
Windows driver step and what to do if a flash is interrupted.

Then run the flasher for your platform from this repository:

| Platform | Flasher | Stuck in DFU |
|---|---|---|
| macOS | `Program218e_v3_Rewired_macOS.command` | `ExitDFU_218e_v3_macOS.command` |
| Windows | `Program218e_v3_Rewired_Windows.bat` | `ExitDFU_218e_v3_Windows.bat` |

**No firmware image ships here** — the factory image is Buchla's and the
patched one is that firmware with these changes in it, so neither is ours to
redistribute. Take `218eV3_v369_DFU.hex` from
[Buchla's flashing kit](https://buchla.com/firmwarefiles/218ev3-Firmware-Flashing.zip);
it is verified by SHA-256 before anything is built or flashed.

## What it does

- **Reworked pressure response** — a gentler curve for more expressive play,
  blended by edit knob 4 from linear (the default) to the full curve, with a
  faded onset so releases do not step off a cliff.
- **Spatial proximity rejection** — estimates the hand field beside each held
  key independently, so two distant hands do not share the wrong correction.
- **Bounded pressure interpolation** — spreads each 5 ms scan update over five
  1 ms DAC ticks and reaches the target exactly, without a long pressure tail.
- **In-firmware tuning** *(opt in)* — up to three tuning tables from your own
  Scala files, switched from edit mode with LED indication.
- **Per-key pitch calibration** *(opt in)* — corrects your 208's tracking error
  per semitone rather than per octave (≤1.5 cents residual on the instrument it
  was measured on). Measure your own; a tracking curve belongs to one
  oscillator.
- **Selectable volts per octave** — 1.2, the standard Buchla scaling, or 1.
- **Pressure-based portamento** — notes snap, and pitch moves between held
  notes as their relative pressure moves. Fully off at zero.
- **Knobs 1–4 remapped** to arpeggiator and vibrato controls: note order,
  rhythm randomness, random octaves, and pressure-responsive global vibrato.
- **Latch mode** on the arpeggiator switch. Latched notes are pitches, not
  keys — the same key in three octaves stacks three notes.
- **Trigger timing fix** — gates no longer fire with the previous note's pitch.

Anything not listed keeps its factory behaviour, and any single change can be
handed back in [`config/218e.toml`](config/218e.toml). Full details, with
addresses: [`docs/FIRMWARE_CHANGES.md`](docs/FIRMWARE_CHANGES.md).

## Controls

**Playing**

| Control | What it does |
|---|---|
| Knob 1 | Arp note order: press order → random. |
| Knob 2 | Arp rhythm: even pulses → randomly spaced. |
| Knob 3 | Random octave displacement per arp note. |
| Knob 4 | Global vibrato, depth and rate together, up to 33 cents at ~6 Hz. Pressure scales it from half to full. |
| Portamento | How much pressure a second held note needs to pull pitch toward it. Off at zero; no time-based glide at any setting. |
| Arp switch | latch / regular / off. In latch, keys are toggles. |

**Edit mode**

| Control | What it does |
|---|---|
| Knob 1 | Scales the whole pressure window, 0.50x–1.14x. Lower reaches full output sooner. |
| Knob 4 | Pressure curve amount, linear through to the full 218r curve. |
| Key 28 / Key 27 | Tuning slot select. LEDs: *rem-en* = slot 0, *trn* = slot 1, both dark = slot 2. |

## Building from source

Only for changing the firmware itself — adding options, editing the assembler
source, or reproducing an image independently. To *use* the firmware, build it
on [the page](https://triglav-modular.github.io/218e-v3-Rewired/) instead;
both toolchains produce identical images from the same options.

```bash
cp /path/to/218eV3_v369_DFU.hex firmware/   # your own copy, once
python3 tools/build.py --no-ghidra          # writes build/
python3 tools/test.py --golden              # rebuild and compare
```

Python 3.11+; Ghidra 12.x only if you drop `--no-ghidra`. The seven options
live in [`config/218e.toml`](config/218e.toml), and `tools/avr32/` holds a
second, Ghidra-free toolchain that assembles the same firmware in JavaScript —
`tools/avr32/sweep.py` builds every option both ways and checks the images
match. See [`docs/BUILD.md`](docs/BUILD.md).

## Layout

```
config/       the seven options — the only file you normally edit
tunings/      Scala files (12 degrees, 2/1 octave)
calibration/  measured per-key tracking error — instrument-specific
src/          Ghidra scripts: the AVR32 assembler and verification tools
tools/        build.py, and avr32/ — the Ghidra-free build
web/          the builder page
mac/ windows/ the flashing kits
docs/         what changed, and how to build
```

## Notes

- The pressure CV tops out at 10 V; the 208 wants ~13.5 V for a fully open
  gate. The DAC has no gain bit, so that one needs the analog booster mod.
- Never repurpose the protected DFU bootloader region.

Licensed under the [Unlicense](UNLICENSE); the bundled flashing tools keep
their own — see [THIRD-PARTY.md](THIRD-PARTY.md).
