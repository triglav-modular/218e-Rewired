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

Run `ProgramLEM218_PressureFix.command`.

The updater verifies the image checksum, confirms `BOOTPROT=3` (the 8 KiB
bootloader region is protected), programs, and validates by read-back. If the
keyboard does not enumerate in DFU, it aborts **before erasing anything** — a
failed attempt leaves the instrument as it was.

## Changing the firmware's behaviour

Everything that is a choice lives in `config/218e.toml`. Every setting is
commented in place with the reasoning behind its value; this is the index, so
you can see what exists without reading the whole file. Defaults are the
shipped ones.

| Setting | Default | What it does |
| --- | --- | --- |
| `[knobs].knob1`–`knob4` | `arp_order`, `arp_rhythm`, `arp_octaves`, `vibrato` | Panel knobs outside edit mode. `"factory"` on any one hands just that knob back. |
| `[arp].switch` | `latch` | Three-position switch becomes latch / regular / off. |
| `[arp].latch_match_tolerance` | `8` | How close a press must come to a latched note's pitch to release it, in units of 2.48 cents. The shared transpose term rounds, so `0` (exact) misses and the press stacks a note instead. A semitone is ~40 units. |
| `[midi].poly_default` | `off` | Polyphonic MIDI off on first boot and after a factory reset; the edit-mode choice is then saved and restored. |
| `[pressure].multi_key` | `max` | Pressure source with several keys held: `max`, `mean` or `factory` (last key touched). |
| `[pressure].common_mode` | `true` | Subtract the per-key hovering-hand proximity lift. |
| `[pressure].proximity_reference` | `300` | Raw count above which a neighbouring key's reading counts as proximity. Lower = stronger rejection. |
| `[pressure].black_key_scale` | `1.2` | Multiplies black-key readings so both colours share one calibration window. `1.0` disables. |
| `[pressure].output_smoothing` | `5` | Length in 1 ms ticks of the DAC output ramp. `0` gives the plain zero-order-hold staircase. |
| `[pressure].resolution_bits` | `4` | Fractional bits carried through the pressure chain. `0` restores integer arithmetic. |
| `[pressure].smoothing_taps` | `8` | Growing-average depth, 8..24 taps (40..120 ms). |
| `[pressure.calibration].floor` / `ceiling` | `592` / `893` | Post-flash pressure window in raw counts. Its width sets the noise gain. |
| `[pressure.calibration].trim_mode` | `scale` | `scale` = edit knob 1 multiplies both endpoints; `independent` = knobs 1 and 3 trim ceiling and floor separately. |
| `[pressure.calibration].trim_span` | `512` | How far those knobs can trim, in raw counts. |
| `[pressure.curve].span` / `onset_db` | `913` / `-10.0` | The 218r response curve: its working range, and the output level at the first count above the floor. |
| `[pressure.curve].default_level` | `0` | Curve amount after a flash, 0 (linear) to 31 (full 218r). Edit knob 4 still sets it live. |
| `[pressure.curve].onset_fade` | `60` | Ramps the onset step in over this many curve counts so soft notes do not cut off abruptly. `0` = pure step. |
| `[portamento].pressure_blend` | `true` | Pressure-weighted portamento: pitch is pulled toward the harder-pressed of the held keys. |
| `[portamento].zero_snap` | `true` | Forces a true zero glide time at the bottom of the portamento knob. |
| `[tuning].slots` | Sabat II, ADDAC JI, 12TET | The three tuning tables. Slot 0 is the power-on default; `"factory"` copies the original temperament. |
| `[tuning].base_units` / `units_per_octave` | `485` / `484` | Factory key-table constants. Changing them retunes the whole instrument. |
| `[pitch].calibration_csv` | `calibration/218e-pitch-calibration.csv` | The single pitch-CV correction table (octave scaling plus per-key tracking). |
| `[pitch].dac_counts` / `dac_vref` / `dac_gain` | `4096` / `2.5` / `4.09` | Pitch DAC scaling — 400.59 counts per volt at the jack. |
| `[diagnostics].scan_profiler` | `false` | Times every event handler with the cycle counter and reports through the telemetry. |
| `[diagnostics].pressure_ab_switch` | `false` | Repurposes the octave switch to A/B our pressure law against the factory one. Freezes the octave setting at its power-on position. |
| `[diagnostics].telemetry_smoothing` | `false` | Reports the live filter depth and interpolation length in the scan-component telemetry fields. |
| `[diagnostics].factory_gain_shift` | `3` | The factory law's gain as a power of two, for the A/B above. |
| `[timing].scan_period_ms` | `5` | The instrument's master update clock. Also scales glide, vibrato and attack rates — measure before lowering. |
| `[timing].gate_settle_scans` | `1` | Extra scans the trigger waits after the pitch reaches the DAC, so the CV (single pole, τ ≈ 0.9 ms) can arrive. Costs that much trigger latency; set `0` if a fast arp drops notes. |

`scan_profiler` and `telemetry_smoothing` share the same two telemetry fields,
and the build refuses to enable both at once. `[firmware]` and `[tools]` hold
paths and checksums rather than behaviour. `golden_sha256` records the image
this configuration is meant to produce; `tools/test.py --golden` rebuilds and
compares against it. The build does not rewrite it — when you change something
that legitimately alters the image, update it by hand from the SHA the build
prints, which is what makes every other change show up as a test failure.

**Retune.** Drop a Scala file into `tunings/`, list it in `[tuning].slots`, and
rebuild. Files must have 12 degrees and a 2/1 octave — the key table repeats
every octave across the 32 keys, so anything else would put the octave switches
out of tune, and the build rejects it. Slot 0 is the power-on default. The
special value `"factory"` copies the instrument's original temperament
bit-exact out of the base image.

A Scala file says nothing about which note its degree 0 is, and degree 0 always
lands on the bottom key, which is a C. A scale published against some other
reference has to be rotated before it will sit on the intended keys — `Sabat
II.scl` is Marc Sabat's own A-rooted listing, and `Sabat II (C-rooted).scl` is
the same tuning rotated by 32/27 so that its Pythagorean chain falls on
F-C-G-D-A-E-B-F# as designed. Slot 0 uses the rotated one; the original is kept
for reference.

`[tuning].reference_key` is the note you tune the instrument to, as a semitone
above that bottom C (0 = C, 9 = A, the default). Each scale is shifted so this
key lands on the 12-TET grid, which pins it to the same pitch in every slot —
tune the 208p once, at that note, and switching slots no longer moves it. The
shift is derived from each scale, not configured: Sabat II needs −5.87 cents
and the ADDAC scale +15.64 to bring their A's together. The build log prints
the offset it used for each slot. A `"factory"` slot is copied verbatim and is
not shifted.

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

Measure against an exact scale: slot 2 is `tunings/12TET.scl` for this reason.
The `"factory"` temperament is up to 1.65 cents off exact 12-TET, and
measuring against it would fold that error into your readings.

**Change the pressure feel.** `[pressure.calibration]` sets the floor and
ceiling used when there is no stored calibration — the state after every flash.
A lower ceiling reaches full pressure sooner but amplifies sensor noise, since
the floor-to-ceiling window is mapped onto the whole output range. Both must
stay in 128..2000 so the immediates keep their encoding width; the build
rejects anything else. `trim_mode = "scale"` gives knob 1 the whole window
using a build-computed range that keeps the ceiling valid (currently
0.50x..1.14x), and returns knob 3 to the factory.

**Change the update rate.** `[timing].scan_period_ms` is the period of the
task that drives the key/pressure/pitch scan — 5 ms (200 Hz) from the factory,
and a single `MOV R10,imm` at `0x80007C0C`. This is the target-update cadence.
With `[pressure].output_smoothing = 0` it is also exactly the zero-order-hold
step width; with smoothing enabled, the 1 kHz DAC flush divides each target
change into the configured number of finite one-millisecond steps.

It is the instrument's master clock, not a pressure setting. The glide engine,
the vibrato phase and the pressure attack ramp each advance once per scan, so
at 4 ms they all run 1.25x faster and the scan's CPU cost rises by the same
factor. The scan is ADC-completion driven, so there is also a hardware floor
that no amount of static analysis can predict.

**Can a too-short period lock you out of flashing?** In principle yes, so it
is worth understanding exactly how before you try one.

Entering DFU over USB needs the *running* firmware to receive the updater's
SysEx and reboot into the bootloader. There is no button or jumper that forces
DFU on a valid application: with `ISP_FORCE=0` the bootloader starts the
application on every power-up. So an application that runs but never answers
the SysEx cannot be updated over USB, and recovery means JTAG.

What limits the damage:

- **Overrun degrades, it does not hang.** The scheduler's event queue is a
  32-entry ring (`0x800103BC`); when it is full an enqueue rolls its head back
  and returns failure, so the event is dropped. A scan that cannot keep up
  simply gets scheduled less often, settling at whatever rate the CPU sustains
  — no hang, no corruption.
- **Once in DFU you cannot fall out of it.** Any accepted ISP command sets
  `ISP_FORCE=1`, and only `dfu-programmer start` clears it — which the updater
  issues solely after read-back validation passes. An interrupted or failed
  flash therefore powers back up into DFU, still updatable.

The residual risk is the middle case: an application busy enough that the
SysEx is dropped rather than answered. Retrying the updater helps, since
dropping is a matter of queue space rather than a permanent state.

**So measure the headroom before shortening the period, not after.** That is
what `[diagnostics].scan_profiler` is for. Set it true, rebuild and flash at
the stock 5 ms — a build that changes no timing and so cannot lock you out —
then read the numbers with a key held in edit mode:

```bash
# config/218e.toml:  [diagnostics] scan_profiler = true
#                    (and telemetry_smoothing = false — they share the same
#                     telemetry fields, and the build refuses both at once)
python3 tools/build.py
./ReadLEM218_Pressure.command      # scan_component_a/b carry the profiler
```

`scan_component_a` is the worst single dispatch in cycles/32 (multiply by 32,
divide by 60e6 for seconds) and `scan_component_b` is the main-loop CPU load
in tenths of a percent. A worst dispatch of 1.5 ms and 30 % load means 4 ms
has room; 3.8 ms and 80 % means it does not. See
[PressureReadout_Protocol.md](../PressureReadout_Protocol.md).

It works by wrapping the main loop's event dispatcher (`0x80004C64`, reached
through the pool at `0x80007DC0`) with reads of the AVR32 cycle counter, which
free-runs at the CPU clock and which nothing else in the firmware writes. Turn
it back off for playing: it repurposes the two telemetry fields and adds a
little overhead to every dispatch.

**Measure whether the instrument keeps up** rather than assuming it: the
pressure telemetry readout emits one frame per key per scan, so its cadence is
a direct read of the achieved rate. At 5 ms a full 32-key cycle takes 160 ms.
If a 4 ms build shows ~128 ms, it is keeping up; if the cadence stays at 160 ms
or turns erratic, the scan is overrunning its period — go back to 5 ms.

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
  -import mac/firmware/218eV3_v369_PressureFix_DFU.hex \
  -processor "avr32:BE:32:default" \
  -scriptPath src \
  -postScript RecoverPressurePatch.java \
  -postScript ExportAnalysis.java build/verify/export
```
