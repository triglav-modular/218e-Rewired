# Building and flashing

## Requirements

- Python 3.11 or newer (the build uses `tomllib` from the standard library)
- A JavaScript engine — macOS already has one (`jsc`, part of
  JavaScriptCore); otherwise Node
- Ghidra 12.x is **optional**, and only needed without `--no-ghidra`

```bash
python3 tools/build.py --no-ghidra     # no Ghidra, no JDK
```

The AVR32 assembler in `tools/avr32/` encodes every patch itself, and is
checked against Ghidra's own output instruction by instruction and image by
image — see [`../tools/avr32/README.md`](../tools/avr32/README.md). Ghidra
remains the reference for verification, and for the disassembly work in
`src/RecoverPressurePatch.java` and `src/ExportAnalysis.java`.

Point the build at Ghidra either in `config/218e.toml` under `[tools]`, or with
an environment variable:

```bash
export GHIDRA_HOME=/path/to/ghidra_12.1.2_PUBLIC
```

## Build

You supply the factory image. None ships with this repository: the stock
firmware is Buchla's and the patched image is that firmware with these changes
in it, so neither is ours to redistribute. Copy your own — it comes with the
official flashing kit — to the path in `config/218e.toml`:

```bash
cp /path/to/218eV3_v369_DFU.hex firmware/
```

The stock image is in Buchla's own flashing kit:
<https://buchla.com/firmwarefiles/218ev3-Firmware-Flashing.zip> — the file
inside is `218eV3_v369_DFU.hex`.

**This is for the 218e version 3 only, running v36.9.** Not the 218, not the
218r, not the 218e v1 or v2. The checksum below is what enforces that.

The build checks it against
`565f2d0c3466edfd13ddc1626cb7a74204723ff3a01f65eac34a9db99901dd47` before
anything is applied, so a wrong or altered file is rejected rather than
patched.

```bash
python3 tools/build.py
```

This reads `config/218e.toml`, generates the tables, assembles every patch,
applies them to the factory image, and writes
`firmware/218eV3_v369_Rewired_DFU.hex`. It also rewrites the updater's
`EXPECTED_SHA256`, so the flasher always matches the image you just built.

The first run imports the factory hex into a Ghidra project under `build/` and
takes a couple of minutes; later runs reuse it and take a few seconds.

Useful flags:

```bash
python3 tools/build.py --tables-only        # regenerate tables, skip Ghidra
python3 tools/build.py --config other.toml  # build a variant
python3 tools/build.py --expect-sha <sha>   # fail unless the image matches
```

Everything the build produces lands in `build/` and is not tracked:

| File | Contents |
|------|----------|
| `build/build.properties` | the settings handed to the assembler |
| `build/tables.txt` | every generated table, in decimal |
| `build/patch_manifest.txt` | address, size and description of each patch |
| `build/assemble.log` | full Ghidra output, including the disassembly |

## What the build checks

- the factory image matches its recorded SHA-256 before anything is applied;
- no two patches overlap;
- no patch lands outside the application image (nothing can reach the
  protected DFU bootloader);
- the written hex reads back byte-for-byte;
- every difference from the factory image lies inside a declared patch;
- generated pitch tables are monotonic and inside the 12-bit DAC range;
- each Scala file has 12 strictly ascending degrees and a true 2/1 octave;
- the pitch calibration covers every semitone the firmware reads (0..78), so
  a short table cannot leave assembler padding to be read as pitch.

Any failure stops the build without writing firmware: the image is rendered
and checked in memory — including `--expect-sha` — and only written once every
check has passed, so a failed build never leaves an unexpected image or a
rewritten updater behind.

`tools/test.py` covers the generators and validators without needing Ghidra;
`tools/test.py --golden` also rebuilds and compares against
`[firmware].golden_sha256`.

## macOS tool compatibility

| Tool | Architectures | Minimum macOS |
|------|---------------|---------------|
| `lem218-pressure-readout` | universal (arm64 + x86_64) | 11.0 |
| `sendmidi` | universal | vendor build |
| `dfu-programmer` | **x86_64 only** | vendor build |

`dfu-programmer` ships as a vendor binary with no source here, so **flashing
on Apple silicon needs Rosetta** (`softwareupdate --install-rosetta`). The
readout tool is built from `LEM218PressureReadout.swift` in this repo; rebuild
it universal with:

```bash
swiftc -O -target arm64-apple-macos11  -o /tmp/ro-arm64 mac/support/LEM218PressureReadout.swift
swiftc -O -target x86_64-apple-macos11 -o /tmp/ro-x86   mac/support/LEM218PressureReadout.swift
lipo -create /tmp/ro-arm64 /tmp/ro-x86 -output mac/support/lem218-pressure-readout
codesign --force --sign - mac/support/lem218-pressure-readout   # required
```

The `codesign` step is not optional: `lipo` produces a mixed state — the
arm64 slice keeps its linker-generated ad-hoc signature while the x86_64
slice has none — and the result fails `codesign --verify` and Gatekeeper
assessment. Re-signing ad-hoc (`--sign -`) makes it verify and run locally;
distributing it to other people would need Developer ID signing and
notarisation.

## Flashing

Run the flasher for your platform — it finds the image itself, searching
`firmware/`, its own directory, Downloads and the Desktop and accepting only a
file whose checksum matches the image it was generated for, so a browser
download works where it landed. `Program218e_v3_Rewired.command` on macOS and
`Program218e_v3_Rewired.bat` on Windows. Both do the same sequence, and the build rewrites the expected
checksum and printed instructions in each, so neither can describe or install a
build it was not generated for.

**Windows needs the DFU device bound to WinUSB**, or `dfu-programmer` cannot
open it. The flasher deals with this itself, in the only order that works:
Zadig can only bind a device it can see, and the DFU device exists only while
the instrument is in DFU — so the request goes out first, and if the device
still does not appear, Zadig is launched right there and the run continues once
the driver is in. Once per machine.

That failure is also the common one, so it is treated as the expected path
rather than an error. If it still cannot be reached afterwards, the script says
so, confirms nothing was erased, and points out that the keyboard is in DFU and
a power cycle brings it back. 

**macOS on Apple silicon needs Rosetta**, because `dfu-programmer` is an
x86_64 binary: `softwareupdate --install-rosetta`.

**This software is not code-signed**, on either platform. On macOS that costs
nothing if the package is obtained with `git clone`: Gatekeeper only refuses
files carrying `com.apple.quarantine`, which a browser download sets on every
file and a clone sets on none. A downloaded ZIP does hit it, and the flasher
handles the part it can reach.

`tools/sign-macos.sh` exists and will sign, notarise and staple a disk image
if a **Developer ID Application** certificate is ever available — that is the
only certificate type Apple accepts for notarisation, it can only be created
by the Account Holder of a team, and development certificates (`Mac
Developer`, `Apple Development`) do not work for it. The script says as much
if you point it at the wrong one. Windows signing is separate again and needs
an Authenticode certificate.

Until then:

**Without that, the first run is blocked on both platforms**: macOS reports the flasher and
then `dfu-programmer` as being from an unidentified developer, each cleared
once from System Settings → Privacy & Security → Open Anyway; Windows Defender
may need More info → Run anyway. A package downloaded through a browser is also
quarantined, which the macOS flasher detects and offers to clear.

**Getting an instrument out of DFU by hand.** If a run stopped before the erase
the application is intact, but a power cycle will not boot it: reading the fuses
sets `ISP_FORCE`, so the bootloader keeps returning to DFU until it is told to
start.

```bash
mac/support/dfu-programmer at32uc3b1256 start
```

If the erase had already happened there is nothing to start — run the flasher
again and let it finish. Both scripts now say which case you are in.

**Connecting.** A standalone LEM218 takes USB-C to the computer with its own
power connected and switched on. A 218e module is reached over USB-B through
the 5xIO module that carries its USB and MIDI. Either way, connect directly
rather than through an unpowered hub. Buchla's own
`ProgramLEM218.bat` is **not** a substitute: it verifies no checksum, checks
neither `BOOTPROT` nor `ISP_FORCE`, does not gate on read-back, and flashes
whichever `.hex` it finds first.

The updater verifies the image checksum, confirms `BOOTPROT=3` (the 8 KiB
bootloader region is protected), programs, and validates by read-back. If the
keyboard does not enumerate in DFU, it aborts **before erasing anything** — a
failed attempt leaves the instrument as it was.

## The seven options

Everything that is a choice lives in [`config/218e.toml`](../config/218e.toml)
under `[options]`. Every other setting is fixed at the value this firmware was
built and tested with; those constants are in `tools/options.py`, which expands
the seven options into the full internal settings the build has always used.

| Option | Default | What it does |
| --- | --- | --- |
| `latching_arp` | `true` | Arp switch becomes latch / regular / off. Latched notes are *pitches*, so a key held in three octaves stacks three notes. `false` restores the factory switch. |
| `remap_knobs` | `true` | Remaps knobs 1–4 to arpeggiator and vibrato controls: arp order, arp rhythm, random octaves, vibrato. `false` hands all four back. Edit-mode knobs 1 and 4 are unaffected. |
| `pitch_correction` | `false` | Path to a per-semitone correction CSV. `false` emits an ideal ramp with no per-key trim. |
| `alternate_tunings` | `false` | One to three Scala files, switchable from edit mode. `false` leaves the edit keys and their LEDs entirely alone. |
| `volts_per_octave` | `1.2` | The standard Buchla scaling. `1.0` rescales the ramp for 1 V/oct gear. |
| `pressure_fix` | `true` | The reworked pressure path — 218r curve, pressure combined across held keys, proximity rejection, interpolated output. `false` returns all of it to factory. |
| `pressure_portamento` | `true` | Pitch moves between held notes as their relative pressure moves. `false` restores the factory time-based glide. |

The options combine freely, with one exception the build enforces:
**pressure-based portamento needs the pressure response fix**. The blend
weights pitch by per-key pressure, and only the reworked pressure path
measures it — without it the option would build and then silently do nothing,
so the build refuses the pair instead.

**Per-key pitch correction.** `calibration/218e-pitch-calibration.csv` was
measured on one specific instrument **with its 208 trimmed to 1 V/oct** — the
correction reaches **+280 cents** at the top, where that oscillator needs
6.23 V for a nominal 6 V.

Both of those make it an example rather than a default. A tracking-error curve
belongs to the oscillator it came from, and it belongs to the scaling it was
taken at: at a different `volts_per_octave` the oscillator sits at a different
operating point and its error is not the same curve. Set `volts_per_octave` to
match your 208 first, then measure against that. To calibrate your own,
measure each key against 12-TET with a tuner and fold the readings in:

```bash
# your-readings.csv:  Key,Measured_Cents   (positive = the note played sharp)
python3 tools/build.py --fold-measurement your-readings.csv
```

Corrections are **cumulative** — you are measuring an instrument that already
applies the current table — so record readings and fold them, rather than
hand-editing offsets. Measure against an exact scale: set
`alternate_tunings = ["tunings/12TET.scl"]` while you do it, since the factory
temperament is up to 1.65 cents off exact 12-TET and measuring against it would
fold that error in.

**Alternate tunings.** Drop Scala files into `tunings/` and list them:

```toml
alternate_tunings = ["tunings/Sabat II (C-rooted).scl",
                     "tunings/5-Limit JI with Septimal 7th.scl",
                     "tunings/12TET.scl"]
```

Each must have 12 degrees and a true 2/1 octave — the key table repeats every
octave across the 32 keys, so anything else puts the octave switches out of
tune, and the build rejects it. Slot 0 is the power-on default; in edit mode
key 28 toggles slot 0 against slot 2 and key 27 toggles slot 1 against slot 2.
Slots you do not fill keep the factory temperament, and a slot left empty
between two filled ones stays empty rather than collapsing.

**With no Scala file at all, none of that is installed.** The two edit keys
keep their factory jobs — key 27 the transpose-mode toggle, key 28 the
remote-enable toggle — and the rem-en and trn LEDs are left alone, because the
applier that drives them is not called. There is nothing to switch between, so
nothing is taken over.

**Anchoring.** Each scale is shifted so that **A** lands on the 12-TET grid,
which keeps the note you tuned the 208 to in the same place in every slot;
switching tuning never sends you back to the trimmer. The shift is computed
from the file — Sabat II needs −5.87 cents, the septimal JI +15.64, 12-TET none — and
is not read out of it, so any scale you supply gets the same treatment. The
reference note is fixed at A (`reference_key = 9` internally).

What *is* a property of the file is which degree sits on the bottom C. A scale
published rooted on A, as Sabat II is, has to be rotated before its ratios land
on the keys you expect — which is what `tunings/Sabat II (C-rooted).scl` is.

**Volts per octave.** `1.2` is the standard Buchla scaling and the default.
`1.0` rescales the whole ramp uniformly for 1 V/oct gear, changing the octave
span while leaving every relative pitch where it was; a `pitch_correction`
table stays valid across the change, because it is stored in cents rather than
volts. **The 208 will need retrimming after a change.**

The underlying table places one octave 400.59 counts apart at 1 V/oct, since
4096 / (2.5 x 4.09) = 400.59 counts per volt at the jack; the setting scales
that.

**Pressure response fix.** `false` returns every pointer that reaches the
reworked pressure path to its factory value: the original curve, filter and
single-key sourcing run exactly as they shipped. The code caves are still
assembled into unused flash, but nothing reaches them.

> The pressure CV tops out at 10 V and the 208 wants ~13.5 V for a fully open
> gate. The DAC has no gain bit, so **no software fix exists** — that one needs
> the analog booster mod, whatever this option is set to.

## Checking a change

```bash
python3 tools/test.py --golden     # generators, validators, and the golden image
python3 tools/avr32/sweep.py       # every option both ways, both toolchains
```

`sweep.py` builds fourteen configurations twice — once through Ghidra, once
through the JavaScript toolchain — and compares the images. It also asserts
that every configuration produces a *distinct* image, so a variant that
silently stopped taking effect cannot pass as agreement.

## How a patch becomes firmware

`src/AssemblePressureFix.java` is the authority for every instruction. It runs
inside Ghidra, assembles each code cave and hook, and prints records:

```
PATCH 8001a480 ebcd4080…      ; scan_housekeeping
SKIP  vibrato_engine (disabled by build config)
```

`tools/build.py` collects those records and applies them. Nothing hand-encodes
an instruction, and no patch bytes are stored in the repository — they are
re-derived on every build, which is why changing a table or a toggle is safe.

Feature gating works at two levels, both driven by `build/build.properties`:

- **`block.<name>`** — whether a whole patch is emitted. Disabling one leaves
  the factory bytes at that address.
- **`feature.<name>`** — whether an optional section *inside* a cave is
  assembled (the per-key proximity correction, the latch toggle test, the vibrato
  call in the per-scan chain).

Code caves and the hooks that reach them are gated together, so a disabled
feature is never reachable — it is not dead code that might still run.

## Verifying a build against the hardware image

`src/RecoverPressurePatch.java` and `src/ExportAnalysis.java` disassemble a
built image back to instructions, which is how each patch was checked against
its intended design:

```bash
$GHIDRA_HOME/support/analyzeHeadless build/verify checkbuild \
  -import firmware/218eV3_v369_Rewired_DFU.hex \
  -processor "avr32:BE:32:default" \
  -scriptPath src \
  -postScript RecoverPressurePatch.java \
  -postScript ExportAnalysis.java build/verify/export
```
