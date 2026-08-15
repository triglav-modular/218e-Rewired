# Building and flashing

## Requirements

- Python 3.11 or newer (the build uses `tomllib` from the standard library)
- Ghidra 12.x — the AVR32 assembler that encodes every patch

Point the build at Ghidra either in `config/218e.toml` under `[tools]`, or with
an environment variable:

```bash
export GHIDRA_HOME=/path/to/ghidra_12.1.2_PUBLIC
```

## Build

```bash
python3 tools/build.py
```

This reads `config/218e.toml`, generates the tables, assembles every patch,
applies them to the factory image, and writes
`mac/firmware/218eV3_v369_PressureFix_DFU.hex`. It also rewrites the updater's
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
- each Scala file has 12 degrees and a true 2/1 octave.

Any failure stops the build without writing firmware.

## Flashing

Run `ProgramLEM218_PressureFix.command`.

The updater verifies the image checksum, confirms `BOOTPROT=3` (the 8 KiB
bootloader region is protected), programs, and validates by read-back. If the
keyboard does not enumerate in DFU, it aborts **before erasing anything** — a
failed attempt leaves the instrument as it was.

## Changing the firmware's behaviour

Everything that is a choice lives in `config/218e.toml`.

**Retune.** Drop a Scala file into `tunings/`, list it in `[tuning].slots`, and
rebuild. Files must have 12 degrees and a 2/1 octave — the key table repeats
every octave across the 32 keys, so anything else would put the octave switches
out of tune, and the build rejects it. Slot 0 is the power-on default. The
special value `"factory"` copies the instrument's original temperament
bit-exact out of the base image.

**Recalibrate the pitch CV.** All of it — the octave scaling that used to live
in the uTune and each key's own tracking error — is one table,
`calibration/218e-pitch-calibration.csv`: one row per semitone above the
208p's 0 V pitch, with `Offset_Cents` giving how far the output is pushed from
an ideal 1 V/octave ramp.

Corrections are **cumulative**, because you measure an instrument that is
already applying the current table. So don't hand-edit the offsets from a
tuner reading — record the readings and fold them in:

```bash
# your-readings.csv:  Key,Measured_Cents   (or Semitone,Measured_Cents)
#                     positive = the note played sharp
python3 tools/build.py --fold-measurement your-readings.csv
python3 tools/build.py
```

The fold converts each reading into a voltage change using the octave width
the table itself reports at that pitch — a cent costs more voltage where the
208p's scaling is stretched, which it is by about 21 % near the top — and
marks those rows `measured`. Rows you don't measure are left alone.

For a calibration run, point slot 2 at `tunings/12TET.scl` rather than
`"factory"` first: the factory temperament is up to 1.65 cents off exact
12-TET, and measuring against it folds that error into your readings.

**Change the pressure feel.** `[pressure.calibration]` sets the floor and
ceiling used when there is no stored calibration — the state after every flash.
A lower ceiling reaches full pressure sooner but amplifies sensor noise, since
the floor-to-ceiling window is mapped onto the whole output range. Both must
stay in 256..1023 so the immediates keep their encoding width; the build
rejects anything else.

**Hand a control back to the factory.** Set any knob to `"factory"`, or
`[arp].switch = "factory"`. The patch that activates the new behaviour is then
simply not applied, so the original code runs untouched — verified by
rebuilding and diffing the hook bytes against the factory image.

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
  assembled (the common-mode subtraction, the latch toggle test, the vibrato
  call in the per-scan chain).

Code caves and the hooks that reach them are gated together, so a disabled
feature is never reachable — it is not dead code that might still run.

## Verifying a build against the hardware image

`src/RecoverPressurePatch.java` and `src/ExportAnalysis.java` disassemble a
built image back to instructions, which is how each patch was checked against
its intended design:

```bash
$GHIDRA_HOME/support/analyzeHeadless build/verify checkbuild \
  -import mac/firmware/218eV3_v369_PressureFix_DFU.hex \
  -processor "avr32:BE:32:default" \
  -scriptPath src \
  -postScript RecoverPressurePatch.java \
  -postScript ExportAnalysis.java build/verify/export
```
