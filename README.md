# 218e v3 Rewired

A patched build of the stock **v36.9** firmware for the Buchla 218e V3 touch
keyboard. 

## Features

- **Reworked pressure response.**
- **Pressure-based portamento.**
- **Reworked arpeggiator with latching.**
- **Arpeggiator controls** (random octave walk, random tempo, random note order)
- **Global pressure-responsive vibrato**

- **Alternate tunings** switched from edit mode. 
- **Per-note pitch calibration.**
- **Selectable volts per octave**


## Build it in your browser

### **<https://triglav-modular.github.io/218e-v3-Rewired/>**

Then run the flasher for your platform from this repository:

| Platform | Flasher | Stuck in DFU |
|---|---|---|
| macOS | `Program218e_v3_Rewired_macOS.command` | `ExitDFU_218e_v3_macOS.command` |
| Windows | `Program218e_v3_Rewired_Windows.bat` | `ExitDFU_218e_v3_Windows.bat` |



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

Licensed under the [Unlicense](UNLICENSE); the bundled flashing tools keep
their own — see [THIRD-PARTY.md](THIRD-PARTY.md).
