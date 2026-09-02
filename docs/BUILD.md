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
`build/218eV3_v369_Rewired_DFU.hex` — deliberately outside `firmware/`, which
is where the flashers look. A build lands where it cannot be picked up by
accident; put an image in `firmware/` only when you mean to flash it. It also
rewrites the updater's
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
- each Scala file has strictly ascending degrees.  Twelve of them repeating
  at a 2/1 need nothing else; any other count, or a period that is not the
  octave, needs a .kbm keyboard map beside it saying which degree each key
  plays.  The octave controls then step that period rather than an octave,
  and every slot has to agree about it, since there is one set of them;
- the pitch calibration covers every semitone the firmware reads (0..78), so
  a short table cannot leave assembler padding to be read as pitch.

Any failure stops the build without writing firmware: the image is rendered
and checked in memory — including `--expect-sha` — and only written once every
check has passed, so a failed build never leaves an unexpected image or a
rewritten updater behind.

`tools/test.py` covers the generators and validators without needing Ghidra;
`tools/test.py --golden` also rebuilds and compares against
`[firmware].golden_sha256`.

`python3 tools/test_clock.py` builds clock-only and clock+sequencer variants
and emulates the actual ISR, divider and pitch/trigger hooks. It requires
Ghidra and never flashes a device. See [CLOCK.md](CLOCK.md) for the input
contract, regression coverage and remaining hardware checks.

`python3 tools/test_persistence.py` builds all four persistence/sequence/clock
variants and executes the actual save/load/startup code and factory flash
wrapper with controller failure and power-cut injection. It also reruns the
clock suite with an unfinished preset held and tests saves during playback.
With `persist = true`, changed sequences save on record exit/CLEAR and
changed presets on pad release, without an idle or arp-off requirement.
Flash saves can briefly disrupt playback. See [PERSISTENCE.md](PERSISTENCE.md)
for the gesture contract, failure handling and legacy-format limits.

## macOS tool compatibility

| Tool | Architectures | Minimum macOS |
|------|---------------|---------------|
| `lem218-pressure-readout` | universal (arm64 + x86_64) | 11.0 |
| `sendmidi` | universal | vendor build |
| `dfu-programmer` | universal (arm64 + x86_64) | 10.13 |

`dfu-programmer` is built from source — see *Rebuilding dfu-programmer* below —
and runs natively on both architectures, so nothing needs installing to flash.
The readout tool is built from `LEM218PressureReadout.swift` in this repo;
rebuild it universal with:

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

The flasher opens with a choice — flash firmware, or get a keyboard out of DFU
mode after an interrupted flash. On macOS the site hands out
`218e Rewired Flasher.app`, built and signed by `tools/make-app.sh`; from a
checkout, `mac/Program218e_v3_Rewired_macOS.command` is the same program.
`windows/218e_Rewired_Flasher.bat` on Windows.

It finds the image itself. From a checkout that means `firmware/` beside the
script; from a download it means the `firmware` folder next to the app, which
takes some finding — see *App Translocation* above.

**Any structurally valid 218e image is accepted**, not only the one the flasher
shipped with. The checksum the build writes into each script is a label, so the
default build can be named in a list of candidates rather than shown as a bare
hash; it is not a gate. What is a gate is `tools/validate_hex.py`, which mirrors
`dfu-programmer`'s own parser — it stops at the end-of-file record, it lets type
4 and type 5 both set the address offset, and it counts addresses rather than
declared record lengths, because each of those is a way for a file to be
approved and something else to be written.

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

**The macOS app is signed and notarised**; Windows is not. The app the site
hands out is built by `tools/make-app.sh`, signed with Developer ID, notarised
and stapled, so it opens with nothing to click through and works offline. A
checkout is not signed and does not need to be: Gatekeeper only refuses files
carrying `com.apple.quarantine`, which a browser download sets on every file
and a clone sets on none. A ZIP taken from a browser does hit it, and the
flasher clears it before running anything.

That ordering is not fussiness. A quarantined unsigned binary does not fail
when launched: macOS suspends it behind a modal dialog and it waits
indefinitely, so a script that tries to *detect* the problem by running the
tool hangs on it instead. Reading the extended attribute answers the same
question instantly and without executing anything, so the flasher checks
`com.apple.quarantine` on `dfu-programmer` and `sendmidi` first, offers to
clear it, and only then runs them. A fifteen-second deadline on that first call
is the backstop if macOS holds them anyway.

`tools/make-app.sh` is the release path for macOS: it builds the app, signs
every Mach-O inside it before the bundle around them, notarises, staples, and
writes `mac/Flasher.zip` for the site to publish. `tools/sign-macos.sh` signs
and notarises a disk image of the loose package instead, which is the older
shape and is not what the site ships.

Both need a **Developer ID Application** certificate — the only type Apple
accepts for notarisation. It can only be created by the Account Holder of a
team, and development certificates (`Mac Developer`, `Apple Development`) do
not work; the scripts say so if pointed at the wrong one. Windows signing is
separate again and needs an Authenticode certificate.  Nothing on the Windows
side is signed: the `.bat` is the double-click, and Windows Defender may want
**More info → Run anyway**.  A batch file has no slot for an icon or a
signature, and a small unsigned launcher exe was tried and dropped — a tiny
static exe that spawns `cmd.exe` is the exact shape Defender's dropper
heuristics hard-block, and it did.

**Getting an instrument out of DFU by hand.** If a run stopped before the erase
the application is intact, but a power cycle will not boot it: reading the fuses
sets `ISP_FORCE`, so the bootloader keeps returning to DFU until it is told to
start.

```bash
mac/support/dfu/bin/dfu-programmer at32uc3b1256 start
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

## The options

Everything that is a choice lives in [`config/218e.toml`](../config/218e.toml)
under `[options]`. Every other setting is fixed at the value this firmware was
built and tested with; those constants are in `tools/options.py`, which expands
the options into the full internal settings the build has always used.

| Option | Default | What it does |
| --- | --- | --- |
| `latching_arp` | `true` | Arp switch becomes latch / regular / off. Latched notes are *pitches*, so a key held in three octaves stacks three notes. `false` restores the factory switch. |
| `remap_knobs` | `true` | Remaps knobs 1–4 to arpeggiator and vibrato controls: arp order, arp rhythm, random octaves, vibrato. `false` hands all four back. Edit-mode knobs 1 and 4 are unaffected. |
| `pitch_correction` | `false` | Path to a per-semitone correction CSV. `false` emits an ideal ramp with no per-key trim. |
| `alternate_tunings` | `false` | One to three Scala files, switchable from edit mode. `false` leaves the edit keys and their LEDs entirely alone. |
| `volts_per_octave` | `1.2` | The standard Buchla scaling. `1.0` rescales the ramp for 1 V/oct gear. |
| `pitch_offset` | `true` | The pitch CV starts three semitones above the 208's 0 V pitch, which puts the bottom C in tune on a 208, 208r or 208p — they start from A. `false` is for the 208c, which starts from C: the bottom key sounds the 0 V pitch. |
| `pressure_fix` | `true` | The reworked pressure path — 218r curve, pressure combined across held keys, proximity rejection, interpolated output. `false` returns all of it to factory. |
| `pressure_portamento` | `true` | Pitch moves between held notes as their relative pressure moves. `false` restores the factory time-based glide. |
| `knob1`, `knob2`, `knob3`, `knob4` | per knob | With `remap_knobs` on, names one knob's role instead of taking its default: `knob1` `order`/`orders`, `knob2` `spacing`/`swing`/`patterns`, `knob3` `octaves`, `knob4` `vibrato`/`trn`. Any may be `factory` to hand that knob back alone. |
| `arp_patterns` | CLIX bank | Only read when `knob2 = "patterns"`. Up to 32 step patterns, each a string where a dot is a rest, or a `[pattern, length]` pair. Left out, the bank is the 22 CLIX fills. |
| `sequencer` | `true` | A 64-step sequencer: hold pad 4 about one second, then pad 1 records, pad 2 plays/stops, pad 3 clears. The strip enters rests and ties. PLAY/STOP control its clock independently of the arp switch. |
| `persist` | `true`, required | Saves changed sequences on record exit/CLEAR and changed presets on pad release. Flash saves can briefly disrupt playback; see [PERSISTENCE.md](PERSISTENCE.md). `false` is refused: it is a diagnostic shape, built only by the harnesses that characterise it. |
| `clock_divide` | `true` | The arp RATE knob divides an external clock /1–/8 after five consistent measured intervals. Target: 0.5–200 Hz; releases after >2.6 s without input. Conditioned MCU low phase must exceed 250 us. See [CLOCK.md](CLOCK.md). |

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

Each must have 12 degrees unless a `.kbm` says otherwise, and may repeat at
whatever interval it declares — the table steps that period and the octave
controls are rebuilt to match it, so a scale that never reaches a 2/1 still
plays in tune with its own switches. All three slots must agree about the
period, because there is one set of octave controls. Slot 0 is the power-on default; in edit mode
key 28 toggles slot 0 against slot 2 and key 27 toggles slot 1 against slot 2.
Slots you do not fill keep the factory temperament, and a slot left empty
between two filled ones stays empty rather than collapsing.

**With no Scala file at all, none of that is installed.** The two edit keys
keep their factory jobs — key 27 the transpose-mode toggle, key 28 the
remote-enable toggle — and the rem-en and trn LEDs are left alone, because the
applier that drives them is not called. There is nothing to switch between, so
nothing is taken over.

Transpose *mode* needs one more thing to survive: the knobs. It is driven from
the knobs the remap takes over, so `remap_knobs` retires it as surely as a
tuning does. With both off, the three `transpose_force_*` patches are skipped
and transpose works as it shipped; with either on, they are applied and it does
not. That is the only place two options combine to decide a third thing.

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

## Version identity

Builds are named `Rewired <version> (<sha8>)` — for example
`Rewired 1.0.0 (9474624b)`. The version is declared in `[firmware].version`;
the eight hex digits are the front of the image's own SHA-256, so they are
derived and cannot be set to something the image is not.

The build prints it, writes it to `build/VERSION`, and rewrites it into both
flashers alongside the checksum, so a flasher cannot announce one build and
install another. Each flasher prints it before erasing and, on success, writes
`firmware/INSTALLED.txt` recording the version, the image hash and when it was
flashed. The browser derives the identical string from the image it just built.

That answers "which build is this?" from the package. It does not yet answer
"which build is on the instrument?" — the firmware does not report a version
over MIDI. Adding one means extending the telemetry frame the readout
validates strictly and rebuilding the readout binary, which is worth doing
after the firmware has been flashed and played, not before.

## Checking a change

```bash
python3 tools/test.py --golden     # generators, validators, and the golden image
python3 tools/avr32/sweep.py       # every option both ways, both toolchains
python3 tools/test_controls.py     # emitted knob roles and strip-gesture ownership
```

`test_controls.py` runs default, six-order/transpose, tuned-transpose, and
lean (factory arp, no sequencer or divider) images with persistence on and
off. It checks all six note orders, preset-4
isolation through the actual ADC-event pitch target and DAC path from the
first knob movement, release-triggered saves, released/unlatched press
history, and pitch ordering with octave-stacked notes and equal pitches.
It also checks strip touches across preview and RECORD boundaries.
Like `test_persistence.py`, it requires Ghidra, models
peripherals without flashing hardware, and restores shared build metadata.

`sweep.py` builds 26 configurations twice — once through Ghidra, once
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
  -import build/218eV3_v369_Rewired_DFU.hex \
  -processor "avr32:BE:32:default" \
  -scriptPath src \
  -postScript RecoverPressurePatch.java \
  -postScript ExportAnalysis.java build/verify/export
```

## What the tests cover, and what they cannot

| | |
|---|---|
| `tools/test.py` | 126 assertions on the generated tables — pitch curve monotonic and inside the DAC, Scala files parse and are rejected when malformed, tuning tables exact |
| `tools/test.py --golden` | the default build still reproduces its pinned image |
| `tools/avr32/sweep.py` | representative configurations, including all four persistence variants, built by both toolchains and compared byte for byte |
| `web/test_configs.py` | the browser build matches `build.py` across its option/interaction matrix |
| `tools/test_persistence.py` | emitted persistence and factory copy code, fault injection, power cuts, same-scan gestures, unfinished-edit isolation, and clock continuation after saves |
| `web/test_matrix.js` | **1,536 option combinations**, including persistence on/off, built through the guarded path |

Every build, in either toolchain, has to pass four structural checks before it
produces an image: no two patches overlap, no patch lands on a factory entry
point (2,665 control transfers are traced), every byte differing from the
factory image lies inside a declared patch, and the rendered hex re-parses to
the same bytes. `web/test_matrix.js` runs all 1,536 combinations through those
checks:

```bash
jsc web/generated.js web/sha256.js web/buildlib.js web/assembler.js \
    web/build.js web/test_matrix.js -- \
    firmware/218eV3_v369_DFU.hex calibration/218e-pitch-calibration.csv
```

**None of this says the firmware plays correctly.** It says no combination of
options produces a malformed image — the class of fault that would matter
before an instrument has even booted. Behaviour on the instrument has been
confirmed for the default build and for one custom build (three tunings,
measured calibration, 1 V/oct); the rest rest on the structure being sound and
on each option being independent by construction.

## Publishing the page, and caches

The page is on GitHub Pages, behind a Cloudflare worker that maps
`triglavmodular.hu/mods/218e-Rewired` onto it. Cloudflare caches by file
extension, and it is generous about it: `style.css` came back
`cf-cache-status: HIT` with `max-age=14400`, so a change to the stylesheet or
to `generated.js` stayed invisible for four hours.

`tools/version-assets.py` runs at publish time and rewrites every local asset
reference to carry a hash of the file it points at:

    <link rel="stylesheet" href="style.css?v=97d4ff27">

A changed file gets a different URL, which no cache has, so it is fetched. The
same pass rewrites `url()` inside the stylesheet, so the fonts and the
background follow the same rule; stylesheets are done first, since versioning a
font inside `style.css` changes `style.css` and its own hash has to be taken
afterwards. The asset check that follows fails the build if any reference
lost its stamp, because an unstamped file is one that will go stale silently.

### The remaining ten minutes

`index.html` cannot be versioned - it is the URL people type - and GitHub Pages
serves it with `max-age=600`. A browser that already has the page waits up to
ten minutes before it sees the new asset URLs at all. Pages offers no way to
set headers, so the worker has to.

**1. Find the worker.** In the Cloudflare dashboard, pick the
`triglavmodular.hu` zone, then **Workers Routes** in the sidebar. The route
covering `triglavmodular.hu/mods/218e-Rewired*` names the worker it runs.
Click that name to open it, then **Edit code**. (The same worker is also under
**Workers & Pages** at account level.)

**2. Replace the code** with `deploy/worker.js` from this repository, which is
kept as the source of truth for what is pasted in there.

It differs from the plain proxy in three ways.

*The page is marked `no-cache`.* Not `no-store`: the browser still keeps it and
still revalidates, so an unchanged page costs a 304 rather than a download.

*Anything with `?v=` is marked `immutable` for a year.* Those URLs carry a hash
of the file's own contents, so that exact URL can never mean different bytes
later, and there is nothing to gain by ever checking again.

*Conditional request headers are forwarded.* Without them the origin cannot
answer 304, and every revalidation downloads the page in full - which would
make `no-cache` cost something on each visit instead of nothing. `Host` stays
out of that list on purpose: GitHub Pages routes on it, and passing
`triglavmodular.hu` asks it for a site it does not have.

**3. Deploy**, and check it took:

```
curl -sSI https://triglavmodular.hu/mods/218e-Rewired/ | grep -i cache-control
```

`cache-control: no-cache` means it is in place. Still `max-age=600` means the
worker did not deploy, or the route is not the one being edited. An asset
should answer differently:

```
curl -sSI "https://triglavmodular.hu/mods/218e-Rewired/style.css?v=97d4ff27" \
  | grep -i cache-control
```

That one should say `max-age=31536000, immutable`.

### Why four hours

`max-age=14400` on `style.css` is not from Pages, which sends `max-age=600`.
It is Cloudflare's **Browser Cache TTL**, which defaults to 4 hours and
overrides the origin for anything it caches. **Caching - Configuration -
Browser Cache TTL - Respect Existing Headers** turns that off. The version
stamps make it moot, since a changed file arrives under a name nothing has
cached, but it explains why a stylesheet went stale for an afternoon.

Assets already cached under their old unversioned names stay in the edge cache
until they expire. They are no longer referenced by anything, so they do no
harm; **Caching - Configuration - Purge Everything** clears them if you would
rather not wait.

## The link preview card

A page with no `og:` tags does not get no preview - it gets a guessed one.
Facebook took the only image the markup offered, the 56px banana in the
header, stretched it to 1200x630 and posted a blurred crop of the peel on
white, under the bare `<title>` and nothing else. Every other scraper guesses
from the same markup, so the same card turned up in Slack, iMessage and
LinkedIn.

The tags are in the `<head>` of [../web/index.html](../web/index.html) and the
card is `web/images/og-card.png`, drawn by
[../tools/make-og-card.py](../tools/make-og-card.py):

```bash
pip install pillow cairosvg
python3 tools/make-og-card.py
```

The card carries no words. The `og:title` and `og:description` sit directly
under the image in every feed that shows one, so anything written on it is
said twice; what the picture is for is being recognised at a glance, which is
the banana's job and not a paragraph's. So it is the banana, whole, at two
thirds of the card's height, centred on the ground the page itself stands on -
the background colour out of the `sync-site` block in `style.css` and the wave
out of `web/images`, so a card cannot show a colour the page has stopped
using.

Centred on the drawing, not on the file it arrives in: `banana.svg` is an
Illustrator export with air on one side, and centring its frame put the banana
visibly right of centre. The tool trims to the ink first.

The wave is held at the `.5` the page holds it at, but scaled half again past
`cover`. A card is about a sixth of the area of the viewport the drawing was
made to fill, so at `cover` the strokes come out small, and at `.5` they then
hardly read at all. Zooming in trades a great many small marks for fewer at a
size that carries. Not much further, though: past about 1.7 the crop runs out
of drawing and half the card goes empty.

It does not run in the workflow: the deploy runner has no rasteriser, and a
card that exists only where one is installed eventually does not exist. So it
is committed, like `mac/Flasher.zip`, and redrawn by hand after a change to
the palette, the background or the banana.

### Two hostnames, one page

The page answers on `triglav-modular.github.io` as well, and a scraper
resolves nothing relative, so `og:image` has to name a host. It names the
domain, and `og:url` and `<link rel="canonical">` name it too: a post of
either URL is then a post of the same page, and the likes and comments land
in one place rather than two. The worker maps that path onto Pages, so the
card is served through it like everything else.

`tools/version-assets.py` stamps the card along with the rest, taking the
canonical link as the base rather than keeping a second opinion about where
the site lives. That is what eventually retires a cached card - a scraper
holds one for weeks, and Facebook holds one until something makes it look
again. To make it look now, put the URL into the [Sharing
Debugger](https://developers.facebook.com/tools/debug/) and press **Scrape
Again**; LinkedIn has the [Post
Inspector](https://www.linkedin.com/post-inspector/).

## Counting builds

The page reports one thing, once, when someone downloads: which options were
chosen, which platform, which version. It is a `POST` to `beacon` beside the
page, handled by the worker in [../deploy/worker.js](../deploy/worker.js) and
written to a Cloudflare Analytics Engine dataset.

What is deliberately **not** in it: any identifier, any header (no IP, no
user-agent), the factory image, the calibration numbers, and the names of any
Scala files — a slot can hold a tuning someone wrote themselves, so the beacon
carries how many slots were filled and not which. The pattern bank is treated
the same way: its size, never its patterns. Each knob's role is sent by name,
since the names are the page's own. The build still happens entirely in the
browser; these values are all that leave it.

The URL is relative on purpose. Only the deployment behind the worker has
anywhere to put this, so a clone served from somewhere else, or the page opened
from a `file:` URL, reports nowhere rather than reporting to us. That also means
the counts are a floor: builds from the `github.io` URL, from a local clone, or
from anyone blocking beacons are not in them.

**Deploying it.** From the repository root:

```bash
npx wrangler deploy
```

The dataset, the route, the name and the observability settings all come from
[../wrangler.toml](../wrangler.toml) — the dashboard cannot add the dataset
binding at all. It offers the form, accepts `BUILDS` / `builds`, enables Save,
and the binding is simply not there after a reload. A deploy is the only way.

**Analytics Engine has to be enabled on the account first**, once, at
Storage & databases → Analytics Engine → Enable. Until it is, `wrangler deploy`
fails with `code: 10089` and changes nothing — which is the safe direction, but
it does mean a first deploy can fail for a reason that has nothing to do with
this repository.

> **A Workers Builds connection took the page down once.** It was connected
> while the repository still had no `wrangler.toml`. The first build ran
> `npx wrangler deploy` with nothing to read, reported success, and replaced
> the proxy with a guessed configuration that served the site's files at `/`
> and 404ed everything else — including `/mods/218e-Rewired`, the whole public
> page. The route was never removed and the dashboard looked healthy; the
> deployed version simply was not this worker, and it sat at 0% traffic.
>
> Worse, it left the service flagged as *"a Worker that only has static
> assets"*, which blocks bindings, variables, triggers and metrics — and the
> flag survives a version rollback. Only deploying a real script clears it.
>
> Recovered by rolling back (Deployments → Version History → ⋯ → Rollback) to
> restore service, then `npx wrangler deploy` to put the script back. The Git
> connection is not in use now.
>
> **After any deploy, check two things:** `/mods/218e-Rewired/` answers 200,
> and `GET .../beacon` answers 405 rather than 404. A 404 on the beacon means
> whatever is deployed is not this worker.

Two consequences worth knowing. **The dashboard will not add a binding any
more** — with a build connection owning the worker, the Add binding button
appears to do nothing, because a deploy would overwrite whatever it saved.
And **whatever is not declared in `wrangler.toml` is not promised to survive a
deploy**, which is why that file spells out `workers_dev` and `observability`
even though both would otherwise be defaults.

It also has to be a *binding* rather than a runtime variable. `BUILDS` added
under Settings → Runtime variables gives the worker the string `"builds"`,
which is truthy and has no `writeDataPoint` on it. The worker checks for the
method rather than for the name, so that mistake reads as "no dataset" and
costs nothing; without a usable binding the route answers 204 and writes
nothing. A missing or misconfigured binding must never break the page, so that
direction is the safe one.

Each download is written twice: to the Analytics Engine dataset, and as one
key in the `COUNTS` KV namespace whose metadata carries the same values.
An option the page did not always offer — the clock divider, the sequencer,
the knob roles, the pitch offset, the pattern bank — is recorded as
*unreported* (`-1`, or an empty role) when a page older than the option sends
nothing for it, so an old build is never read as having turned it down. The
Analytics Engine columns are positional, so the newer ones follow the older.
The second exists because an Analytics Engine binding can only write — reading
one back means the SQL API and an API token to go with it, while a namespace
can simply be bound to whatever reads it. One key per download rather than a
counter, because KV has no atomic increment and two downloads at once would
read the same number and write it back twice. Keys age out after 400 days.

**Reading it back.** Not from here. What reads the counts is not in this
repository.

The counts say how many people run this firmware and which parts they use,
which is the owner's business rather than the repository's, so the reader lives
outside it. What stays here is the half that has to: the beacon in
`web/app.js` and the route in `deploy/worker.js`, both of which anyone can read
to see exactly what is collected — which is the only part of this that owes
anyone an explanation.

A build is `blob1` of `mac` or `win`. Anything else — `other` — is not a
download: the endpoint is public, and two points were posted through it while
proving the path worked. One of those reports `mac`, because a probe that
proves a real download would arrive has to look like one; it carries version
`9.9.9`, which no build will have. So:

```sql
WHERE blob1 IN ('mac','win') AND blob2 != '9.9.9'
```

So "how many builds turned each option on, this month" is:

```sql
SELECT count() AS builds, sum(double1) AS latching, sum(double2) AS knobs,
       sum(double3) AS pressure, sum(double4) AS portamento,
       sum(double6) AS calibrated
FROM builds
WHERE timestamp > now() - INTERVAL '30' DAY
  AND blob1 IN ('mac','win') AND blob2 != '9.9.9'
```

Nothing on the route is authenticated — it cannot be, since the page is public
— so anyone who finds it can add to a count. Every field is validated against
what the page can actually send, so the worst case is noise in the numbers
rather than arbitrary strings in the dataset.


## Watching buchla.com

A new stock firmware means rebasing this patch, so buchla.com is watched daily
for one. None of that lives here: it runs from
[buchla-firmware-watch](https://github.com/triglav-modular/buchla-firmware-watch),
a private repository, which is also where the code and its documentation sit.

Private for a reason worth repeating here, because it is the kind of thing that
gets undone by someone tidying up: GitHub disables a scheduled workflow on a
**public** repository after 60 days with no repository activity. A watch that
exists for the quiet years cannot be one of the things that goes quiet.


## Giving CI the factory image

Everything that builds firmware needs Buchla's stock image, and it is not in
this repository. Without it the workflow still runs, but the golden build, the
reproducibility check, the 1,536-build option matrix and the browser/Python
comparison all skip — so a green tick covers the flashers, the validators and
the packaging, and none of the firmware. The notice in the log says so.

It cannot be a secret directly: the image is 255 KB and a GitHub secret caps at
48 KB, gzipped and base64-encoded or not. So the secret holds a credential, and
the image lives somewhere the credential can reach.

**1. Put the image in a private repository.** A new one holding a single file
is enough — `218eV3_v369_DFU.hex` at the root. Private, because the file is
Buchla's.

**2. Make a token that can read only that.** github.com → your avatar →
Settings → Developer settings → Personal access tokens → Fine-grained tokens →
Generate new token. Resource owner: the account or organisation that owns the
private repository. Repository access: *Only select repositories*, and pick
just that one. Permissions → Repository permissions → **Contents: Read-only**,
which is the only one needed. Set an expiry you are willing to renew; when it
lapses the workflow fails with `could not fetch the factory image` rather than
going quiet.

**3. Add two secrets to this repository.** Settings → Secrets and variables →
Actions → New repository secret.

| Name | Value |
|---|---|
| `FACTORY_HEX_URL` | `https://api.github.com/repos/OWNER/REPO/contents/218eV3_v369_DFU.hex?ref=main` |
| `FACTORY_HEX_TOKEN` | the token from step 2 |

The API is asked for the raw bytes rather than the JSON description of them;
the workflow sends `Accept: application/vnd.github.raw` for an
`api.github.com` URL, and `application/octet-stream` if the URL names a release
asset instead. Any other host — a presigned S3 link, say — is fetched as-is,
and `FACTORY_HEX_TOKEN` can be left unset if the URL needs no credential.

**4. Re-run the workflow.** The log should say `Factory image fetched and
verified.` and the skipped steps should run. If something else comes back the
step says what it was rather than leaving a checksum mismatch to explain a JSON
error page:

```
::error::what came back is not an Intel HEX file - it starts with {"message":"Not Found",
```

The image is checked against `factory_sha256` from `config/218e.toml` before
anything uses it, so a wrong or truncated file fails there rather than becoming
the thing every later step verifies against.

## App Translocation, and finding the firmware

A download is the app with a `firmware` folder beside it, and the app looks in
that folder. macOS makes that harder than it sounds: a quarantined app is run
from a read-only copy of itself under `/AppTranslocation/<uuid>/d/`, and from
inside that copy the folder it was unzipped into cannot be reached. Every path
the flasher knows about itself points into the copy.

The system knows the mapping and will give it up.
`SecTranslocateCreateOriginalPathForURL` has been in Security.framework since
10.12, and `mac/support/resolve-translocation` is a few lines around it:

```
clang -O2 -target arm64-apple-macos11  -framework CoreFoundation \
      -o /tmp/rt-arm64 mac/support/ResolveTranslocation.c
clang -O2 -target x86_64-apple-macos11 -framework CoreFoundation \
      -o /tmp/rt-x86   mac/support/ResolveTranslocation.c
lipo -create -output mac/support/resolve-translocation /tmp/rt-arm64 /tmp/rt-x86
```

It is reached through `dlsym` rather than a header: the symbol has been
exported for years but `SecTranslocate.h` is not in the SDK, so there is
nothing to compile against. It prints the original path, or exits non-zero -
including for a path that was never translocated, which is not an error but has
no answer either.

`tools/make-app.sh` signs it along with every other Mach-O under `support/`,
so it needs no special handling there.

If it ever fails to answer, the flasher falls back to a guess: a folder in
Downloads or on the Desktop holding both this app and a `firmware` folder is a
download of ours, and the most recent one is the one just opened. A folder of
loose `.hex` files never matches, which is the point - the search used to take
in the whole of Downloads and listed every image on the machine.

## Rebuilding dfu-programmer

The bundled `dfu-programmer` is **1.1.0**, built from
[the upstream repository](https://github.com/dfu-programmer/dfu-programmer) as a
universal binary with its own universal `libusb`, so it runs natively on Apple
silicon and Intel with nothing installed. Buchla's own kit ships an x86_64-only
0.6.2 that needs Rosetta; this replaces it.

Both libraries and the tool are built once per architecture and joined with
`lipo`, because neither autotools project cross-builds two architectures in one
pass:

```bash
brew install autoconf automake pkg-config

# libusb, per architecture
curl -LO https://github.com/libusb/libusb/releases/download/v1.0.29/libusb-1.0.29.tar.bz2
tar xf libusb-1.0.29.tar.bz2 && cd libusb-1.0.29
for A in arm64 x86_64; do
  mkdir -p build-$A && (cd build-$A && ../configure --host=$A-apple-darwin \
    --prefix=$PWD/../out-$A --disable-udev --enable-shared --disable-static \
    CFLAGS="-arch $A -mmacosx-version-min=10.13" \
    LDFLAGS="-arch $A -mmacosx-version-min=10.13" && make -j4 && make install)
done

# dfu-programmer, per architecture, against the matching libusb
git clone https://github.com/dfu-programmer/dfu-programmer && cd dfu-programmer
git checkout c204739 && ./bootstrap.sh
for A in arm64 x86_64; do
  mkdir -p b-$A && cp -r update-bash-completion.sh dfu_completion b-$A/
  (cd b-$A && ../configure --host=$A-apple-darwin \
    CFLAGS="-arch $A -mmacosx-version-min=10.13 -I<libusb>/out-$A/include" \
    LDFLAGS="-arch $A -mmacosx-version-min=10.13 -L<libusb>/out-$A/lib" && make -j4)
done

# join, repoint at the bundled library, sign
lipo -create b-arm64/src/dfu-programmer b-x86_64/src/dfu-programmer -output dfu-programmer
for A in arm64 x86_64; do
  install_name_tool -change <libusb>/out-$A/lib/libusb-1.0.0.dylib \
    @executable_path/../Frameworks/libusb-1.0.0.dylib dfu-programmer
done
codesign --force -s - dfu-programmer
```

`install_name_tool -change` must run once per architecture: each slice records
its own absolute path, and changing one leaves the other pointing into the build
tree.

Three build notes, each of which stops the build outright:

- 1.1.0's post-build step runs `./update-bash-completion.sh`, which an
  out-of-tree build cannot find — copy it and `dfu_completion` into the build
  directory.
- 1.1.0 includes `<libusb-1.0/libusb.h>`, so the include path is the `include`
  directory, not `include/libusb-1.0`.
- 0.6.2, if you build it instead, needs `-std=gnu99`: it defines `true` and
  `false` as enum members, which are keywords under the C23 default.
- `dfu-programmer` has to be rebuilt whenever `libusb` is, not just relinked in
  place. 1.0.29 carries compatibility version 6.0.0 where 1.0.27 carried 5.0.0,
  and the executable records the version it was built against; dropping the new
  library under a binary that still asks for 5.0.0 happens to load today and is
  a coincidence, not an arrangement.

### What was checked before changing version

0.6.2 → 1.1.0 is a large jump, so every command and string the flashers depend
on was compared against the source and against the running binary:

| | 0.6.2 | 1.1.0 |
|---|---|---|
| `at32uc3b1256` target | yes | yes |
| `erase`, `start` | accepted | accepted |
| `flash --suppress-bootloader-mem` | accepted | accepted (undocumented in `--help`) |
| `get`/`getfuse` labels | `Bootloader Version`, `Bootloader protected area`, `ISP Force` | identical |
| value format | `"%s%s0x%02x (%d)\n"` | identical |
| `no device present.` | yes | yes |
| exit status, success | 0 | 0 |
| exit status, no device | 1 | **3** |
| `bytes used` on flash | printed | **gone** |

The last two are the only differences. Neither matters here: the flashers key
the device probe on the message rather than the status, and `bytes used` is
logged, never parsed. `erase` in 1.1.0 also skips an already-blank chip unless
`--force` is given, which is harmless — a chip with firmware on it is not blank,
and a blank one needs no erase.

**Not verified without hardware:** the success paths. Everything above is either
source-level or observable with no instrument attached; the actual erase, write,
read-back and restart under 1.1.0 need a real flash to confirm.
