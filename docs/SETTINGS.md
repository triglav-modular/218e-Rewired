# Settings over MIDI

One image serves every option set. The eleven options the page offers in
step 2, the ten timing numbers, the pattern bank, the pitch table and the
three tuning tables all live in a settings record the firmware reads at
boot, and the page's step 5 writes that record over Web MIDI and reads it
back. Changing an option costs a MIDI push and a restart the page sends,
not a DFU round trip.

This is the reference for what was built. The design and the reasons for
it are in [PLAN-SETTINGS.md](PLAN-SETTINGS.md) (stage 1, 2026-09-22: the
record, the mirror, the wire protocol) and
[PLAN-SETTINGS-2.md](PLAN-SETTINGS-2.md) (stage 2, 2026-09-23: the options
as cells, decided at boot), with a verification record per phase. Nothing
here has run on an instrument yet; see the end.

## What moves over MIDI, and what still needs a flash

The page sends a record only to the image it was made for: the record
carries the image marker, and the keyboard refuses any other. The marker
hashes the code that is in the image, its build-time tables (the pressure
curve, the black-key excess) and the assembler source. It leaves out the
option cells, the pattern bank, the pitch table, the tuning tables, the
keys per period and the ten timing numbers - everything the record itself
bounds-checks at load - so two builds that differ only in those produce
one marker and one record fits both. Checked: builds with a tuning
installed, with a pitch correction, without the pitch offset and at 1 V
per octave all share the default build's marker.

| Changed on the page | Reaches the keyboard by |
|---|---|
| Any option in step 2 (the eleven below), and whether tunings are in use | Send settings; the keyboard restarts itself to run it |
| The arpeggiator's pattern bank, the three tuning tables, the pitch table (calibration, volts per octave, pitch offset) | Send settings; tables go live in the mirror at once |
| The ten timing numbers | Carried in the record and editable by any NRPN sender; the page has no controls for them |
| A scale that repeats at something other than the octave | Send settings: the period travels as number cell 10, `octave_units`, which the octave controls read, the add-to-pitch octave included; knob 4's octave-switch step count stays the build's |

`Read settings` works against any 3.0 keyboard: it lists what the keyboard
holds, loads the patterns and the options into the page, and loads the
pitch table into the calibration as the table already on the instrument,
so a new measurement accumulates on it.

## The options as cells

Number cells 16..27 of the record are the option cells, one halfword each.
The build writes its config's choices into them as the image's defaults,
exactly as it bakes a table; `settings_defaults` writes all 32 cells.

| Cell | Mirror | Live byte | Option | Values |
|--:|---|---|---|---|
| 16 | `0x6820` | `0x6d28` | `latching_arp` | 0 off, 1 on |
| 17 | `0x6822` | `0x6d29` | `knob1` | 0 order, 1 orders, 2 factory |
| 18 | `0x6824` | `0x6d2a` | `knob2` | 0 spacing, 1 quantized, 2 swing, 3 patterns, 4 factory |
| 19 | `0x6826` | `0x6d2b` | `knob3` | 0 octaves, 1 factory |
| 20 | `0x6828` | `0x6d2c` | `knob4` | 0 vibrato, 1 trn, 2 factory |
| 21 | `0x682a` | `0x6d2d` | `sequencer` | 0, 1 |
| 22 | `0x682c` | `0x6d2e` | `clock_divide` | 0, 1 |
| 23 | `0x682e` | `0x6d2f` | `pressure_fix` | 0, 1 |
| 24 | `0x6830` | `0x6d30` | `pressure_portamento` | 0, 1; refused when cell 23 is 0 |
| 25 | `0x6832` | `0x6d31` | `quantize_presets` | 0, 1 |
| 26 | `0x6834` | `0x6d32` | `portamento_in` | 0 portamento, 1 transpose |
| 27 | `0x6836` | `0x6d33` | `alternate_tunings` | 0 off, 1 on: whether any slot holds a scale; the page sets it |

**An option applies at boot.** `settings_boot` loads the record, then
`option_boot` copies the low byte of cells 16..31 to the live option
bytes at `0x6d28..0x6d38`. Every gate in the firmware reads a live byte,
never the mirror, so an NRPN write changes nothing until the next restart,
and no running state - a latch holding notes, a take mid-play, a locked
divider - ever has to be unwound live. The dump carries both the cells and
the live bytes (`0x0020..0x002f`), so the page can say which options are
saved but not yet running.

**The restart.** `0x3f04` with data `0x2a2a` enables the watchdog with a
short timeout and spins; the boot treats the reset like power-up. The
page's send restarts the keyboard only when the committed option cells
differ from the live bytes, waits for the port to re-enumerate, and asks
for the identity block again to confirm the live bytes now match. A send
that changes only tables or patterns does not restart.

**What "off" is, per option.** Every cave is in every image; the byte
decides at the point where the option engages, and everything downstream
sees the state it sees today when the gesture is not made:

| Option off | What the byte does |
|---|---|
| `latching_arp` | Three dispatchers at the note-on, the note-off and the hold give the key back the way a non-latch switch position does; the pads 2 and 3 test says "not held"; a shim lets the factory's own latch chord run. `option_boot` clears the latch's stamps, term and ownership at every boot, whichever way the byte points: nothing is held when the keyboard comes up |
| `knob1..knob4` | Dispatchers on the pool words the knob caves stand behind name the blend, the six orders, the factory selector, the randomiser, the grid, swing, the pattern gate, the vibrato engine, the octave switch or the factory handler; a knob left factory latches into RAM nothing else reads. `option_boot_state` zeroes the vibrato engine's phase, depth and output offset at every boot, since the pitch remap adds that offset every scan whatever knob 4's role |
| `sequencer` | The pad-4 chord never arms, so the mode byte stays 0 and every sequencer cave answers the factory way; a take restored from the persistence record is kept but cannot be played. `seq_restart_clear` zeroes the sequencer's runtime (the mode, the cursor, the chord, the audition) at every boot in every image, so no restart resumes a take, in a build without persistence too |
| `clock_divide` | The GPIO interrupt runs the factory's own body instead of the capture cave, event 10 reaches the factory's arp step again, and the four pulse pools go back to the scan-grid pulse; the divider never sees an edge, so it never acquires. The 4 ms trigger spike stays either way |
| `pressure_fix` | The curve, knob 1 and knob 4 pool words go back to the factory's routines; the two clamp skips and the gain branch replay the factory's own instructions; the pressure store passes straight through the interpolator to the DAC slot |
| `pressure_portamento` | The pitch hook goes straight to the remap around the blend's conditioner; the glide clamp keeps the classic portamento with its zero-snap. The conditioner's last offset and the re-base history, which `transpose_capture` consults in every configuration, are reset at every boot: nothing sounds when the keyboard comes up |
| `quantize_presets` | The preset adder's float-to-int is the factory's own again, so the voltage adds as it is, and the rotation is asked for zero degrees from the preset |
| `portamento_in` = portamento | The transposer reads no jack (zero degrees from it) and the factory's glide-rate addend reads the jack again. The transposer's state word, whose shift the MIDI note conversion honours, is unseeded at every boot and recomputed by the first scan |
| `alternate_tunings` | The per-scan applier word returns at once, so the slot LEDs and the transpose-mode byte stay the factory's; edit keys 27 and 28 replay the factory's transpose-mode and remote-enable toggles instead of selecting slots; the three remote-enable reads read the flag instead of zero |

Two things differ from a build that never had the option: the factory's
long hold on the arp switch no longer toggles polyphonic MIDI in any image
(edit mode is its single owner), and `release_count_guard`, a factory
fix, stays in. A keyboard map wider than 32 positions is refused in every
build, since either input to the key-table rotation can be turned on over
MIDI.

## The record

Two slots, `0x8003d000` and `0x8003d800`, 2 KB each, immediately below the
persistence ring. Writes alternate; the loader takes the newer generation.
Big-endian.

| Offset | Bytes | Meaning |
|---|---:|---|
| `0x000` | 4 | Commit marker `0x32313853` (`"218S"`); erased until the final commit |
| `0x004` | 2 | Layout version, `2` |
| `0x006` | 2 | Payload length, `0x298`; the record is `0x2a8` with its header |
| `0x008` | 4 | Generation, nonzero, wrapping to 1 |
| `0x00c` | 4 | CRC-32/ISO-HDLC over `0x004..0x00b` and `0x010..end` |
| `0x010` | 2 | Image marker: the low 16 bits of `init_marker` |
| `0x012` | 2 | `octave_units` the record was generated for; informational, cell 10 is what the keyboard reads |
| `0x014` | 12 | Reserved, zero |
| `0x020` | 64 | 32 numbers: the ten timing numbers, cell 10 the period, cells 16..27 the options (27 the tunings' switch), the rest zero |
| `0x060` | 158 | `pitch_remap`, 79 halfwords |
| `0x0fe` | 2 | Pad |
| `0x100` | 192 | Tuning slots 0..2, 32 halfwords each |
| `0x1c0` | 8 | `tuning_period_keys`, 3 halfwords and a pad |
| `0x1c8` | 128 | 32 pattern masks, each as two halfwords, low first |
| `0x248` | 64 | 32 pattern lengths, halfwords |
| `0x288` | 32 | Reserved, zero |
| `0x2a8` | | end |

The ten timing numbers, cells 0..9: `tie_glide_rate`,
`strip_halfway_units`, `clock_min_ms`, `clock_rearm_us`,
`clock_lock_pulses`, `transpose_cv_period`, `transpose_cv_zero`,
`transpose_cv_hysteresis`, `chord_hold_scans`, `latch_state_hold_scans`.
Cell 10 is `octave_units`, the period the octave controls step, in DAC
units: 484 for a scale that repeats at the octave, 767 for a tritave;
the page sends the period its tuning declares.

Load validates the marker, version, length, generation, CRC and image
marker, then every field's bounds: table entries inside the 12-bit
DAC range, keys per period `1..32`, lengths `0..32`, numbers and option
cells inside their ranges, and `pressure_portamento` only with
`pressure_fix`. Any failure means the record is ignored and the image's
own settings are used; a bad record is never repaired in place. A DFU
update erases both slots.

## The mirror

The record's payload is mirrored in RAM at `0x6800..0x6a68`, in the
record's own layout: the 32 numbers at `0x6800`, the pitch table at
`0x6840`, the tuning slots at `0x68e0`, the period keys at `0x69a0`, the
patterns at `0x69a8` and `0x6a28`. Every reader in the firmware addresses
the mirror. The NRPN state sits at `0x6a68`, the loader's state at
`0x6a70` (commit state, slot loaded, generation), a commit's staging at
`0x6a80`, and the live option bytes at `0x6d28`.

At boot, `settings_boot` copies the image's own tables and numbers into
the mirror, then the newer valid record over them, then zeroes the NRPN
and commit state and the tuning applier's guard so the first scan copies
the slot table. `option_boot` then copies the live bytes and clears the
state an option that is off must not inherit across the restart.

## The wire protocol

NRPN on **channel 16**, whatever the instrument's own channel is set to:
CC 99 parameter MSB, CC 98 parameter LSB, CC 6 data MSB, CC 38 data LSB.
A value is applied on the data LSB; the parameter number is not
auto-incremented, so every value carries its own address. Values are 14
bits. The hook sits at the factory's Control Change branch; the factory's
own controllers keep working on the instrument's channel, and if that
channel is 16, data entry on it is the settings'.

| Parameter | Meaning | Value |
|---|---|---|
| `0x0000..0x001f` | cell *n* | its bounds; out of range is ignored |
| `0x0020..0x002f` | the live option bytes | read-only; a dump sends them |
| `0x0080..0x00ce` | `pitch_remap[0..78]` | `0..0xfff` |
| `0x0100..0x011f`, `0x0120..0x013f`, `0x0140..0x015f` | tuning slot 0, 1, 2 | `0..0xfff`; a write clears the applier's guard |
| `0x0160..0x0162` | keys per period | `1..32` |
| `0x0180 + 3p + 0..2` | pattern *p*'s mask, bits 0..13, 14..27, 28..31 | each third replaces its own bits |
| `0x01e0..0x01ff` | pattern lengths | `0..32`, zero unused |
| `0x3f00` | commit on the next scan | data `0x2a2a` |
| `0x3f01` | reload the mirror from flash, dropping live edits | |
| `0x3f02` | the image's own settings back in the mirror; flash untouched until a commit | |
| `0x3f03` | dump: every parameter, then the identity block | |
| `0x3f04` | restart through the watchdog | data `0x2a2a` |
| `0x3f7f` | the identity block alone | |

Table and cell writes go live in the mirror at once; an option cell's
write takes effect at the next restart. Commit is a request: the write
happens from the per-scan chain, into the slot the loaded record is not
in, with a read-back, and the identity block reports the outcome.

The identity block, the last thing in every dump:

| Parameter | Value |
|---|---|
| `0x3f76` | the firmware version, major.minor.patch as 6, 4 and 4 bits: `0x300` is 3.0.0 |
| `0x3f77` | the image marker's top two bits |
| `0x3f78` | cell 10, the period the octave controls step |
| `0x3f79` | the slot loaded, `0xff` for none |
| `0x3f7a` | commit state: `0` clean, `1` requested, `2` written, `3` failed |
| `0x3f7b`, `0x3f7c`, `0x3f7d` | the loaded record's generation, bits 28..31, 14..27, 0..13 |
| `0x3f7e` | the image marker's low fourteen bits |
| `0x3f7f` | the layout version, `2` |

The block's parameter numbers are frozen; the layout version changes only
when the map changes. Replies go out at two parameters per scan, so a full
dump takes about 0.8 s.

## The page

**Send settings**: identity (the layout must be 2 and the image marker
must match the build here), the values in bursts of 16, a dump compared
against what was sent, a commit on a match, identity again for the commit
state, and, when an option cell differs from its live byte, the restart
and a wait for the keyboard to come back. **Read settings**: one dump,
listed, then the patterns and the options loaded into their controls and
the pitch table into the calibration. The page's verdict tells apart a
keyboard on the settings built into its firmware, one holding saved
settings, this build with *n* differences, the same version from another
build, an older build and a newer one.

The codec is `BUILDLIB.nrpn*` in `web/buildlib.js`, the transport
`web/settings.js`, and `tools/settings.py` writes the same record from the
CLI (`build/settings.bin` beside every image).

## Verification, and what the bench still owes

`src/SettingsRegression.java`, under `tools/test_persistence.py`, boots
every image the persistence suite builds, plants records in the slots and
checks the load, the refusals, the receive, the commit, the dump, the
identity block, and every stage 2 gate under both states of its byte with
the registers its callers keep; the controls and clock suites run the
options both ways through the real scans. The restart command is checked
to write the watchdog's control register in its two-key sequence and
nothing else.

Not yet done on an instrument: flashing a stage 2 image at all; the send
and read from the page against a real keyboard; that the watchdog reset
comes back through the bootloader into the application; and that a
falling edge on the clock jack, which the GPIO interrupt now also
receives with the divider off, costs nothing audible.
