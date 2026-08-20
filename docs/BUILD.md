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

Run the flasher for your platform — it finds the image itself, searching
`firmware/`, its own directory, Downloads and the Desktop and accepting only a
file whose checksum matches the image it was generated for, so a browser
download works where it landed. `Program218e_v3_Rewired_macOS.command` on macOS and
`Program218e_v3_Rewired_Windows.bat` on Windows. Both do the same sequence, and the build rewrites the expected
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

**This software is not code-signed**, on either platform. On macOS that costs
nothing if the package is obtained with `git clone`: Gatekeeper only refuses
files carrying `com.apple.quarantine`, which a browser download sets on every
file and a clone sets on none. A downloaded ZIP does hit it, and the flasher
clears it before running anything.

That ordering is not fussiness. A quarantined unsigned binary does not fail
when launched: macOS suspends it behind a modal dialog and it waits
indefinitely, so a script that tries to *detect* the problem by running the
tool hangs on it instead. Reading the extended attribute answers the same
question instantly and without executing anything, so the flasher checks
`com.apple.quarantine` on `dfu-programmer` and `sendmidi` first, offers to
clear it, and only then runs them. A fifteen-second deadline on that first call
is the backstop if macOS holds them anyway.

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
  -import build/218eV3_v369_Rewired_DFU.hex \
  -processor "avr32:BE:32:default" \
  -scriptPath src \
  -postScript RecoverPressurePatch.java \
  -postScript ExportAnalysis.java build/verify/export
```

## What the tests cover, and what they cannot

| | |
|---|---|
| `tools/test.py` | 97 assertions on the generated tables — pitch curve monotonic and inside the DAC, Scala files parse and are rejected when malformed, tuning tables exact |
| `tools/test.py --golden` | the default build still reproduces its pinned image |
| `tools/avr32/sweep.py` | 13 representative configurations, built by both toolchains and compared byte for byte |
| `web/test_configs.py` | the browser build matches `build.py` for 10 configurations |
| `web/test_matrix.js` | **all 192 option combinations** built through the guarded path |

Every build, in either toolchain, has to pass four structural checks before it
produces an image: no two patches overlap, no patch lands on a factory entry
point (2,665 control transfers are traced), every byte differing from the
factory image lies inside a declared patch, and the rendered hex re-parses to
the same bytes. `web/test_matrix.js` runs all 192 combinations through those
checks in about 40 seconds:

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

**2. Find where it returns.** Somewhere the worker fetches GitHub Pages and
returns the response - a line like `return fetch(upstream, request)` or
`const res = await fetch(...)` followed by `return res`.

**3. Wrap that return.** A `Response` from `fetch` has immutable headers, so
the headers cannot be set on it directly; construct a new one around the same
body. Replace the return with:

```js
const res = await fetch(upstream, request);

// The page is the one file that cannot carry a version in its URL, so it is
// the one that has to be revalidated.  Everything else is fetched under a
// name containing its own hash and can be cached as long as anyone likes.
if ((res.headers.get('content-type') || '').includes('text/html')) {
    const out = new Response(res.body, res);
    out.headers.set('cache-control', 'no-cache');
    return out;
}
return res;
```

`no-cache` rather than `no-store`: the browser still keeps the page and still
revalidates it, so an unchanged page costs a 304 rather than a download.

**4. Deploy**, and check it took:

```
curl -sSI https://triglavmodular.hu/mods/218e-Rewired/ | grep -i cache-control
```

`cache-control: no-cache` means it is in place. Still `max-age=600` means the
branch did not match - check that the response really is `text/html` and that
you edited the return the page actually takes.

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
curl -LO https://github.com/libusb/libusb/releases/download/v1.0.27/libusb-1.0.27.tar.bz2
tar xf libusb-1.0.27.tar.bz2 && cd libusb-1.0.27
for A in arm64 x86_64; do
  mkdir -p build-$A && (cd build-$A && ../configure --host=$A-apple-darwin \
    --prefix=$PWD/../out-$A --disable-udev --enable-shared --disable-static \
    CFLAGS="-arch $A -mmacosx-version-min=10.13" \
    LDFLAGS="-arch $A -mmacosx-version-min=10.13" && make -j4 && make install)
done

# dfu-programmer, per architecture, against the matching libusb
git clone https://github.com/dfu-programmer/dfu-programmer && cd dfu-programmer
git checkout v1.1.0 && ./bootstrap.sh
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
