# 218e v3 Rewired — custom firmware for the Buchla 218e v3


> ## ⚠ Read this first
>
> **This is for the Buchla 218e version 3 only, running firmware v369.** Not
> the 218, not the 218r, not the 218e v1 or v2, not any other touchplate
> controller. The build refuses any file that is not the exact v369 image.
>
> **You do this entirely at your own risk.** This is experimental, unofficial
> firmware, not made or supported by Buchla. It has been tested on *one*
> instrument. **It can brick your keyboard.** Recovering a bricked unit may need
> JTAG hardware and opening the instrument, and may not be possible at all.
>
> A failed flash usually leaves the keyboard in DFU mode, where the flasher can
> try again — but nobody is promising that. If losing the use of your 218e would
> be a problem, stop here and keep the factory firmware.
>
> No warranty of any kind. Neither the authors nor Buchla are liable for damage,
> loss of use, or a keyboard that no longer works.

A patched build of the stock **v369** firmware for the Buchla 218e touch
keyboard, driving a 208 (Easel). It reworks the pressure response, adds
in-firmware tuning and per-key pitch calibration, and repurposes the four
preset-voltage knobs and the arpeggiator switch.

## Start here

### → **<https://triglav-modular.github.io/218e-v3-Rewired/>**

The builder runs in your browser. Drop in your factory image, pick the seven
options, and it hands you a firmware file — no toolchain, no Python, no clone.
Nothing is uploaded: the build happens on your machine, and the page has no
server behind it.

Then get this repository for the flasher, and run the one for your platform:

| Platform | Flasher | Stuck in DFU |
|---|---|---|
| macOS | `Program218e_v3_Rewired_macOS.command` | `ExitDFU_218e_v3_macOS.command` |
| Windows | `Program218e_v3_Rewired_Windows.bat` | `ExitDFU_218e_v3_Windows.bat` |

The flasher finds the image itself — it looks in `firmware/`, its own folder,
Downloads and the Desktop, lists everything flashable it finds newest-first,
and asks which one. It verifies the file before erasing anything, and it will
not touch the instrument until you have agreed to the warning above.

The rest of this page is for building from source, or changing what the seven
options do.

| | |
|---|---|
| **Base image** | stock v369, **you supply it** (AT32UC3B1256, AVR32) |
| **Output** | `build/218eV3_v369_Rewired_DFU.hex`, built locally |
| **Settings** | [`config/218e.toml`](config/218e.toml) — seven options |
| **Licence** | [Unlicense](UNLICENSE) for this repository's own work; the bundled flashing tools keep their own — see [THIRD-PARTY.md](THIRD-PARTY.md) |

> **No firmware image ships here.** The factory image is Buchla's, and the
> patched one is that firmware with these changes spliced in, so neither is
> ours to redistribute. What this repository is, is the recipe.
>
> Get the stock image from Buchla's own flashing kit —
> <https://buchla.com/firmwarefiles/218ev3-Firmware-Flashing.zip> — and take
> `218eV3_v369_DFU.hex` out of it. The build verifies it by SHA-256 before
> anything else, so a wrong file is rejected rather than flashed.

## What it does

- **Reworked pressure response** — a more gentle pressure curve allowing for
  more expressive play, blended by edit knob 4 from linear (the default) to the
  full curve, with a faded onset so releases do not step off a cliff.
  Calibration is hardcoded to defaults that survive a flash; edit knob 1
  scales the whole window live.
- **Spatial proximity rejection** — estimates the hand field beside each held
  key independently, so two distant hands do not share the wrong correction.
- **Bounded pressure interpolation** — spreads each 5 ms scan update over five
  1 ms DAC ticks and reaches the target exactly, without a long pressure tail.
- **In-firmware tuning** *(opt in)* — up to three tuning tables built from your
  own Scala files, switched from edit mode with LED indication.
- **Per-key pitch calibration** *(opt in)* — corrects your 208's measured
  tracking error per semitone rather than per octave (≤1.5 cents residual on
  the instrument it was measured on). Measure your own: the table in
  `calibration/` came from one oscillator trimmed to 1 V/oct, and a
  tracking-error curve belongs to both.
- **Selectable volts per octave** — 1.2, the standard Buchla scaling, or
  1 V/oct.
- **Pressure-based portamento** — notes snap, and pitch moves between held
  notes as their relative pressure moves. The
  portamento knob sets how much pressure a second note needs to bend, and is
  fully off at zero.
- **Knobs 1–4 remapped to arpeggiator and vibrato controls** — note order, rhythm randomness,
  random octaves, and pressure-responsive global vibrato (one-half to full
  effective knob value).
- **Latch mode** on the arpeggiator switch: latch / regular / off. Latched
  notes are pitches, not keys — the same key in three octaves stacks three
  notes, each releasable from any octave where its pitch still maps.
- **Trigger timing fix** — gates no longer fire with the previous note's pitch.

Full details, with addresses: [`docs/FIRMWARE_CHANGES.md`](docs/FIRMWARE_CHANGES.md).

## Controls

Everything below is remapped. Anything not listed — including the
octave/preset/none switch — behaves as it did from the factory. Any single
entry can be handed back in `config/218e.toml`.

**Playing**

| Control | What it does |
|---|---|
| Knob 1 | Arp note order: fully left = strict press order, fully right = random. |
| Knob 2 | Arp rhythm: fully left = even pulses, fully right = randomly spaced. |
| Knob 3 | Random octave displacement per arp note, rising with the knob. |
| Knob 4 | Global vibrato, one-knob law (depth and rate together), up to 33 cents at ~6 Hz. Pressure scales it from half its value at rest to full at maximum pressure. |
| Portamento | How much pressure a second held note needs to pull the pitch toward it. **At zero it is fully off**, and there is no time-based glide at any setting — notes snap. |
| Arp switch | latch / regular / off. In latch, keys are toggles; leaving the position releases everything. Latched notes are *pitches*, so one key held in three octaves stacks three notes, each releasable from any octave where its pitch still maps. |

**Edit mode**

| Control | What it does |
|---|---|
| Knob 1 | Scales the whole pressure window, both endpoints together, 0.50x–1.14x. The built-in 592/893 calibration sits at about ¾ of travel; the feet-up condition is ~⅓. Lower = reaches full output sooner. |
| Knob 4 | Pressure curve amount, linear through to the full 218r curve. Fresh from a flash it starts linear until you move it. |
| Key 28 / Key 27 | Tuning slot: key 28 toggles slot 0 against slot 2, key 27 toggles slot 1 against slot 2. LEDs: *rem-en* lit = slot 0, *trn* lit = slot 1, both dark = slot 2. |
| Knobs 2, 3 | Factory behaviour. (Knob 2 briefly carried a smoothing control; it was removed because its ADC mirror never tracked the physical knob. Filter depth and interpolation length are build settings.) |

## Build

> Most people want the builder page instead:
> <https://triglav-modular.github.io/218e-v3-Rewired/>. This section is for
> building from a clone — which is what you need to change the assembler
> source, add options, or reproduce an image independently.


```bash
cp /path/to/218eV3_v369_DFU.hex firmware/   # your own copy, once
python3 tools/build.py --no-ghidra              # build the firmware
python3 tools/test.py      # regression tests (add --golden to rebuild and compare)
```

Needs Python 3.11+ and Ghidra 12.x (set `GHIDRA_HOME` or `[tools].ghidra_home`).

There are **seven options**, all in [`config/218e.toml`](config/218e.toml):
latching arpeggiator, knob remapping, per-key pitch correction, alternate
tunings, volts per octave, the pressure response fix, and pressure-based
portamento. Everything else is fixed at the tested value. See
[`docs/BUILD.md`](docs/BUILD.md).

Pitch correction and alternate tunings are **off by default**: the shipped
calibration was measured on one specific 208, and would push another
instrument toward the wrong curve.

`tools/avr32/` holds a second, Ghidra-free toolchain that assembles the same
firmware in JavaScript; `tools/avr32/sweep.py` builds every option both ways
and checks the images match.

## Flash

**Connect the instrument** directly rather than through an unpowered hub:

- **Standalone LEM218** — USB-C to the computer, power connected, switched on.
- **218e module** — USB-B from the 5xIO module that carries its USB and MIDI.

Then run the flasher for your platform. If a flash is ever interrupted the
keyboard stays in DFU — reading the safety fuses sets ISP_FORCE, so a power
cycle brings it straight back — and `ExitDFU_218e_v3_macOS.command` or
`ExitDFU_218e_v3_Windows.bat` sends the START that releases it.


| Platform | Flasher |
|---|---|
| macOS | `Program218e_v3_Rewired_macOS.command` |
| Windows | `Program218e_v3_Rewired_Windows.bat` |

### Windows only — one driver step

Windows must bind the keyboard's DFU mode to the **WinUSB** driver or the
flasher cannot see the instrument. The flasher opens **Zadig** at the moment
the device appears; in it:

1. List empty? **Options → List All Devices**
2. Select **AT32UC3B DFU** (`03EB 2FF6`)
3. Set the right-hand box to **WinUSB**, press the button

**If the button reads "Replace Driver", press it anyway.** An older Buchla kit
will have left `libusb0` bound, which the current flashing tool cannot use.
Leaving it is the usual reason the keyboard goes into DFU and is then never
found.

Once per machine. **macOS needs none of this** — just run the flasher.

### Both platforms

- **Unsigned software warnings.** On macOS, `git clone` this repository
  instead of downloading the ZIP and there is no warning at all: a browser
  download marks every file as quarantined, a clone does not. If you did
  download it, macOS blocks the flasher and then `dfu-programmer` as being from
  an unidentified developer — **System Settings → Privacy & Security → Open
  Anyway** for each; the flasher catches the second and offers to clear it.
  Windows Defender may warn about the `.bat`: **More info → Run anyway**.

There is nothing to move or rename: each flasher looks in `firmware/`, its own
directory, Downloads and the Desktop, and takes whichever file matches the
checksum it was generated against — so a fresh download works where it landed,
even if the browser renamed it. It then copies the image into `firmware/`.

Both are generated against the image they will install: the build rewrites
their expected checksum and the instructions they print, so a flasher can never
describe or install a build it was not made for.

The updater verifies the image checksum and `BOOTPROT=3` before programming and
validates by read-back. If the keyboard does not enter DFU it aborts **before
erasing anything**.

## Layout

```
config/       the seven options — the only file you normally edit
tunings/      Scala files (12 degrees, 2/1 octave) — opt in
calibration/  measured per-key tracking error — opt in, instrument-specific
src/          Ghidra scripts: the AVR32 assembler and verification tools
tools/        build.py — config to firmware; avr32/ — the Ghidra-free build
mac/          the macOS flashing kit; put your factory image in firmware/
docs/         what changed and how to build
```

## Notes

- The pressure CV tops out at 10 V; the 208 wants ~13.5 V for a fully open
  gate. The DAC has no gain bit, so that one needs the analog booster mod.
- Never repurpose the protected DFU bootloader region.
