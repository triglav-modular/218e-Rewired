# Settings over MIDI, stage 1

One image, and the numbers that people actually iterate on set from the page
over Web MIDI, with no DFU round trip: the pitch calibration, the volts per
octave and pitch offset that live inside it, the three tuning slots, the
pattern bank, and the build numbers nobody has measured yet. Everything that
is *code* - which features are in, which role each knob has - stays a build
option; that is stage 2, laid out in [PLAN-SETTINGS-2.md](PLAN-SETTINGS-2.md)
(planned and built 2026-09-23).

**Built, both stages (2026-09-23).** The reference for what was built is
[SETTINGS.md](SETTINGS.md); this file and stage 2's keep the design, the
reasoning and the verification records. For a few hours the image marker
still hashed the pitch table and the tuning tables, so a record made for a
changed table was refused as another image; since the same evening the
marker leaves out everything the record bounds-checks itself, and cell 27
carries whether tunings are in use, so the tables, the numbers and the
tuning switch all move over MIDI as this file intended.

**Built (2026-09-22):** the whole firmware side and the page's codec. The
record and both serializers (`tools/settings.py`, `BUILDLIB.settingsRecord`);
the RAM mirror and its loader; every reader repointed at the mirror and the
ten number sites reading their cells; the NRPN receive at the factory's
Control Change branch, the commit from the per-scan chain, the paced dump
and the identity block; the caves at `0x8001f000..0x8001f9d4`; the codec in
`buildlib.js`; and `src/SettingsRegression.java` under
`tools/test_persistence.py`. Not yet built: the page's step, which waits on
its wording. Every address below was read out of the disassembly in
`build/disasm/dump1.txt` and the assembler; the facts that were inferred
rather than read are marked as such.

## What moves to runtime, and what does not

| Setting | Today | Stage 1 |
|---|---|---|
| `pitch_correction`, `volts_per_octave`, `pitch_offset` | all three fold into one 79-halfword table, `pitch_remap` at `0x80019bc0` | the table, over MIDI |
| `alternate_tunings` (octave-period only) | three 32-halfword tables at `0x80019af8` and the 3-entry `tuning_period_keys` at `0x8001e2d0` | the tables, over MIDI |
| `arp_patterns` | 32 masks at `0x80019f20`, 32 lengths at `0x80019fa0` | the tables, over MIDI |
| the unmeasured numbers below | immediates in caves | RAM cells, over MIDI |
| non-octave tunings | change `octave_units`, which is compiled into eleven sites, five of them in the factory octave-switch block at `0x8000336c` | **stay build-time** - a runtime tuning must repeat at the period the image was built with |
| `pressure_curve` (1828 bytes at `0x80018d80`), `black_key_excess` | generated from frozen `INTERNAL_DEFAULTS`, identical in every build | stay baked |
| every `block.*` and `feature.*`, the knob roles | code present or absent | stage 2: [PLAN-SETTINGS-2.md](PLAN-SETTINGS-2.md) |

The numbers, all of them in caves and all of them 2- or 4-byte `MOV` or `CP`
immediates today, with the range the assembler already enforces:

| # | Number | Default | Range | Site |
|--:|---|--:|---|---|
| 0 | `tie_glide_rate` | 60 | 1..1024 | `seq_step` cave, near `0x8001b63c` |
| 1 | `strip_halfway_units` | 2048 | 128..3968 | `seq_record`, near `0x8001b5e0` |
| 2 | `clock_min_ms` | 4 | 1..4 | `clock_init`, cycles/ms product |
| 3 | `clock_rearm_us` | 250 | 1..1000 | `clock_init` |
| 4 | `clock_lock_pulses` | 5 | 2..32 | `clock_capture`, near `0x8001c878` |
| 5 | `transpose_cv_period` | 123 | 1..1023 | already a pool word in the `cv_transpose` pool, plus one `MOV` in `preset_degrees` |
| 6 | `transpose_cv_zero` | 0 | 0..1023 | pool word |
| 7 | `transpose_cv_hysteresis` | 2 | 0..64 | pool word |
| 8 | `chord_hold_scans` | 300 | 20..2000 | `seq_chord`, near `0x8001b1ae` |
| 9 | `latch_state_hold_scans` | 200 | 20..2000 | `latch_state_toggle`, near `0x8001e6b0` |

Bounds are enforced twice: on receive, and again on load from flash, because
a halfword off flash is untrusted input like every other stored field.

## What the firmware already has

**A CC parser in main-loop context.** The USB receive handler at `0x80008db4`
(inferred from its callers at `0x80007496..0x800074b8`; not traced to the
interrupt) copies each 4-byte USB-MIDI packet into a 32-entry ring at `0x34b4`
(write index `0x34b0[0x84]`, wrapping at `0x7f`, no overflow check - a
burst longer than the ring silently overwrites). The dispatcher at
`0x80004c64` drains it one packet per pass through `0x800051b0`, calling the
parser at `0x8000831c`. The parser switches on status at `0x80008362`; a
Control Change on the instrument's own channel (`state+0x2e7`) reaches
`0x8000838e`, which handles three controllers - the mod wheel `state+0x218`,
`state+0x219`, and controller 5 - and discards everything else at
`0x80008628`. So an NRPN handler runs where persistence already runs: in the
main loop, between events, never in an interrupt.

**A CC sender.** `0x80008034(cc, value, channel)` builds one USB-MIDI packet
and sends it. `send_usb_midi_14bit` at `0x80019940` already wraps it as an
MSB/LSB pair, and the edit-mode telemetry sends seventeen packets per 5 ms scan on
channel 16 without trouble, which bounds how fast a dump can go.

**A flash writer.** `0x800108fc(dest, src, len, erase)` clamps to the
application array, walks the range one 512-byte page at a time with
read-modify-write per page, and persistence already uses it with the
two-step body-then-marker commit from UC3B section 14.4.7. See
[PERSISTENCE.md](PERSISTENCE.md).

**Free flash.** The image ends at `0x80018bf4`, the caves at `0x8001ed53`,
the persistence ring is `0x8003e000..0x8003efff` and factory settings
`0x8003f000`. Everything from `0x8001f000` to `0x8003dfff` is `0xff`.

**Free RAM.** Our highest cell is `0x674b`; the stack starts at `0x8000` and
the deepest chain through our code is 300 bytes.

**A startup hook.** `persist_boot` at `0x8001d540` runs before GPIO interrupts
from the `clock_init_pool` word at `0x80007d8c`, and restores the tuning slot
into `0x6090` before the first scan so the applier copies that slot's table to
`0x854`. The settings load has to run *before* it, so the table it copies is
the runtime one.

## The record

Two slots, `0x8003d000` and `0x8003d800`, 2 KB each, four pages, immediately
below the persistence ring. Writes alternate; the loader takes the newer
generation, as `persist_newest` does for the ring. Big-endian, like the
musical record.

| Offset | Bytes | Meaning |
|---|---:|---|
| `0x000` | 4 | Commit marker `0x32313853` (`"218S"`); erased until final commit |
| `0x004` | 2 | Layout version: `1` for stage 1; `2` since stage 2 phase A (2026-09-23), see [PLAN-SETTINGS-2.md](PLAN-SETTINGS-2.md) |
| `0x006` | 2 | Payload length, `0x298`; the record is `0x2a8` with its header |
| `0x008` | 4 | Generation, nonzero, wrapping to 1 |
| `0x00c` | 4 | CRC-32/ISO-HDLC over `0x004..0x00b` and `0x010..end`, as persistence |
| `0x010` | 2 | Image marker: the low 16 bits of `init_marker`, so a record written against one image is refused by another |
| `0x012` | 2 | `octave_units` the record was generated for; refused unless it matches the image |
| `0x014` | 12 | Reserved, zero |
| `0x020` | 64 | 32 numbers, halfwords; the ten above, then the option cells at 16..27 (stage 2), the rest zero |
| `0x060` | 158 | `pitch_remap`, 79 halfwords |
| `0x0fe` | 2 | Pad |
| `0x100` | 192 | Tuning slots 0..2, 32 halfwords each |
| `0x1c0` | 8 | `tuning_period_keys`, 3 halfwords and a pad |
| `0x1c8` | 128 | 32 pattern masks, each as two halfwords, low first, as the gate reads them |
| `0x248` | 64 | 32 pattern lengths, halfwords |
| `0x288` | 32 | Reserved, zero |
| `0x2a8` | | end |

Load validates marker, version, length, generation, CRC, image marker,
period, then every field's bounds: pitch and tuning entries inside the
12-bit DAC range, keys per period `1..32` where the key-table rotation is
built and `1..127` otherwise, lengths `0..32` (zero is an
unused pattern), numbers inside their ranges. Monotonicity is not checked:
a non-monotonic curve plays wrong notes, which is the player's to hear, and
nothing downstream can be harmed by it. Any failure means the record is
ignored and the image's own tables are used; a bad record is never repaired
in place.

The bank is only in the image when knob 2 plays patterns, and only mirrored
then; any other build boots a zero bank, and the serializers write zeros
for it too, so a fresh boot and the build's own record agree byte for byte
- which is the first thing the regression checks.

Reserved bytes are zero and future fields are laid out so that zero is the
old behaviour, which is what lets a record written before a field existed
load without a version bump.

## RAM

A mirror the readers point at, filled at boot from the record or from the
baked tables, and edited live by the NRPN handler:

| Address | Bytes | Contents |
|---|---:|---|
| `0x6800` | 64 | the 32 numbers |
| `0x6840` | 160 | `pitch_remap` and pad |
| `0x68e0` | 192 | tuning slots |
| `0x69a0` | 8 | period keys |
| `0x69a8` | 128 | pattern masks |
| `0x6a28` | 64 | pattern lengths |
| `0x6a68` | 8 | reserved for the NRPN state: parameter MSB/LSB, data MSB, a "have MSB" flag, the dump cursor |
| `0x6a70` | 8 | loader state: commit state byte (`0` clean, `1` requested, `2` written, `3` failed), the slot loaded (`0xff` none), a pad, the generation word at `+4` |
| `0x6a80` | 0x2a8 | reserved for a commit's staging, marker erased, as persistence stages at `0x6300` |

Everything ends below `0x6d30`; the stack keeps over 12 KB. The mirror is
declared in `RAM_REGIONS` in `tools/build.py`.

Every reader reaches its table through a pool word today, so repointing is a
word change, not a code change:

| Table | Pool words to repoint |
|---|---|
| `pitch_remap` | `0x80019bc0` in `pitch_remap_calibration` |
| tuning slots `0x80019af8` | the applier's `0x80019aec`, and the pools of `cv_transpose`, `preset_degrees`, `preset_entry` |
| `tuning_period_keys` `0x8001e2d0` | the same three caves |
| pattern bank `0x80019f20` / `0x80019fa0` | `arp_pattern_gate` |

The ten numbers are `LD.UH` from `0x6800 + 2n` now. Where a cave had the
room, the site is `MOV Rn,0x6800; LD.UH Rn,Rn[2n]`; where it did not, a pool
word holding `0x6800` was added and the site is `LDDPC Rn,pool; LD.UH` at
the old width (`seq_glide`, whose extent grew by one word to `0x8001b660`;
`seq_strip`; `preset_degrees`). `seq_chord` shifted two labels by a
halfword into its own slack; `cv_transpose`'s three pool-word values became
loads off one mirror pool word, its labels moving with them. Cells 8 and 9
are past a compact load's 14-byte reach, so those two sites use the cell's
own address as the base. The values a build gives them still come from the
config: `settings_boot` is where the ten immediates live now, one each.

## Boot

`settings_boot` at `0x8001f1c0` is what the factory's startup pool word at
`0x80007d8c` names now, in every image; its own last pool word carries on
into what that word used to name - `persist_boot`, the clock's init, the
sequencer's, or the factory's GPIO setup at `0x80007340`:

1. Copy the baked tables and the build's numbers into the mirror. This is the
   fallback and it always runs, so a corrupt record can only lose edits,
   never the instrument's own tuning.
2. Find the newer valid slot. If there is one, copy its payload over the
   mirror.
3. Zero the NRPN state and the commit state, record the loaded slot and
   generation, zero the applier's guard at `0x60e4` so the first scan copies
   the (possibly new) slot table into `0x854`.

Order matters: `persist_boot` then restores `0x6090`, and the first scan's
applier copy reads the mirror. `settings_valid` checks the header, the CRC
through `persist_crc`, the image marker and period, then the bounds off a
table of low/high pairs in its own pool; `settings_newest` walks the two
slots with the serial-number compare `persist_newest` uses.

Warm resets reload like power-up. SRAM surviving a DFU is not a concern
here because the mirror is rebuilt from flash on every boot and the record
region is erased by a DFU update, so a fresh image always boots on its baked
tables.

## The wire protocol

NRPN on **channel 16**, the channel the telemetry already uses, so the
instrument's own MIDI channel setting is irrelevant. Standard controllers:
CC 99 parameter MSB, CC 98 parameter LSB, CC 6 data MSB, CC 38 data LSB. A
value is applied on the data LSB; the parameter number is *not*
auto-incremented, so every value carries its own address and a lost packet
costs one value, not the rest of the push. Values are 14 bits, which every
field fits in: the pitch and tuning tables are 12-bit DAC units, the numbers
are at most 4095, and a 32-bit pattern mask is split into three parameters.

The hook is at `0x8000838e`, where the factory's Control Change branch
begins: its first two instructions, which load the instrument's own MIDI
channel into R8 for the compare that follows, are replaced by an `MCALL`
into `settings_nrpn` (the branch at `0x80008366` that gets there is a
2-byte `BR`, too short to retarget). The cave reads the channel from the
parser's own frame (`R7[-0xa]`), the controller and value from the ring the
way the factory does at `0x800083a2..0x800083ce`, keeps the four NRPN
controllers on channel 16 - 99 and 98 set the parameter bytes, 6 the data
MSB, and 38 completes a value and applies it - and returns with R8 = `0xff`,
a channel no message can carry, so the factory's own compare discards what
was handled. For everything else it returns with the load the factory used
to do, and the factory carries on: its three controllers keep working on the
instrument's channel, and a channel-16 controller that is not NRPN reaches
them too (the regression checks both). If the instrument's channel is set
to 16, data entry on it is ours. That is the one behaviour change and it is
documented rather than avoided. The receive runs in the dispatcher's
main-loop pass, like the parser it sits in.

### Parameter map

| Parameter | Meaning | Value |
|---|---|---|
| `0x0000..0x001f` | cell *n* | the bounds of `settings_bounds`' table; out of range is ignored, not clamped. Stage 1 had `0x0000..0x0009`; the option cells are 16..27 (stage 2) |
| `0x0020..0x002f` | the live option bytes, read-only (stage 2) | a dump sends them; a write is ignored |
| `0x0080..0x00ce` | `pitch_remap[0..78]` | `0..0xfff` |
| `0x0100..0x011f`, `0x0120..0x013f`, `0x0140..0x015f` | tuning slot 0, 1, 2 | `0..0xfff`; a write clears the applier's guard |
| `0x0160..0x0162` | keys per period | `1..32` with the rotation built, else `1..127` |
| `0x0180 + 3p + 0..2` | pattern *p*'s mask, bits 0..13, 14..27, 28..31 | each third replaces only its own bits |
| `0x01e0..0x01ff` | pattern lengths | `0..32`, zero unused |
| `0x3f00` | commit on the next scan | data `0x2a2a`; anything else ignored |
| `0x3f01` | reload the mirror from flash, dropping live edits | |
| `0x3f02` | put the image's own settings back in the mirror (flash untouched until a commit) | |
| `0x3f03` | dump: every parameter, then the identity block | |
| `0x3f04` | restart through the watchdog, so a changed option cell takes effect (stage 2) | data `0x2a2a`; anything else ignored |
| `0x3f7f` | the identity block alone | |

The identity block, sent last in every dump so it is also the page's
end-of-dump marker, and on its own for `0x3f7f`:

| Parameter | Value |
|---|---|
| `0x3f76` | the firmware version, major.minor.patch packed as 6, 4 and 4 bits: `0x300` is 3.0.0 |
| `0x3f77` | the image marker's top two bits: a marker is sixteen bits and a value fourteen |
| `0x3f78` | `octave_units` the image was built for |
| `0x3f79` | the slot loaded, `0xff` for none |
| `0x3f7a` | commit state: `0` clean, `1` requested, `2` written, `3` failed |
| `0x3f7b`, `0x3f7c`, `0x3f7d` | the loaded record's generation, bits 28..31, 14..27, 0..13 |
| `0x3f7e` | the image marker's low fourteen bits |
| `0x3f7f` | the layout version: `1` for stage 1, `2` since stage 2 phase A |

The block's parameter numbers are frozen from 2026-09-22 on, and the layout
version is bumped only when the settings map changes: a keyboard can then
always say what it runs to any page, and a page that meets a layout it does
not know still names the version and says to reload.

Anything the map does not name - the gaps between sections, `0x0200` and
up short of the commands - is ignored on receive and skipped by the dump.
`settings_target` is the one place the map lives in the firmware; the
receive, the dump and `settings_valid`'s bounds all ask it.

Table writes go live in the mirror at once, so a calibration entry can be
auditioned before it is committed. A tuning entry additionally zeroes the
applier's guard so the next scan re-copies the slot; a single halfword store
is atomic and the pitch is recomputed every scan, so a table mid-push is
merely a half-new table, never a torn value.

Commit is a request, not a write: the handler sets the commit state to `1`
and the write happens in the next scan, from `settings_scan` - the cave the
housekeeping's pool word at `0x8001a520` names now, in front of what it used
to name (the persistence shim, or the preset editor in a volatile build) -
the same context and the same driver procedure as a preset save: stage the
record at `0x6a80` with its marker erased and the generation after the
loaded one, erase-and-write the two pages of the body into the slot the
loaded record is *not* in, read every byte back, write the marker alone
without erase, read back again, and take the slot only if `settings_valid`
accepts it. The state goes to `2` on success and `3` on any mismatch, and
the identity reply carries it, so the page learns the outcome instead of
assuming it. A failed commit leaves the previous record where it was; the
next request tries again.

Replies use the same four CCs on channel 16 in the other direction, through
the factory's own sender, at two parameters (eight packets) per scan, so a
full dump - 316 parameters and the ten of the identity block - takes about 0.8 s and
never outruns the telemetry's proven seventeen. The dump cursor walks the
parameter numbers and skips the gaps between sections without spending the
scan's budget on them; `0x4000` is idle. The page's decoder
(`BUILDLIB.nrpnDecoder`) is the firmware's receive mirrored, and
`BUILDLIB.nrpnParamsOf` lists a record's parameters in the dump's order.

### The page's push

1. Identity. The layout version must be `1` and the image marker must match
   what the page built; otherwise refuse with the reason, and say
   "no reply" distinctly from "wrong image" - they have different fixes.
2. Send the values, at most 16 packets per burst with a millisecond or two
   between bursts: the ring is 32 packets and overflow is silent.
3. Dump, compare against what was sent, then commit only on a match. This is
   the check that catches a dropped packet.
4. Identity again, to read the commit state.

The page already keeps the options, the tunings, the pattern bank and the
calibration in `localStorage`, and `buildlib.js` already generates every
table from them, so "push" is a serializer over the existing generators plus
the transport; nothing is built twice.

### The page's read

One dump, decoded by `BUILDLIB.nrpnRecordOf` into a record-shaped array
and by `settingsFields` into names, with the identity block beside it
(`SETTINGSMIDI.read`; a layout the page does not know is refused, not
guessed at). The page says which of four things it found - no saved
settings, saved settings with no build here to compare against, a
different build, or this build with *n* differences - lists the numbers,
the patterns, the pitch table's scaling and offset and the keys per period,
and then loads what it can: the patterns into the pattern list, and the
pitch table into the calibration as the table already on the instrument.
The record does not carry the scaling or the offset the table was built
with, so both are read off the table (`pitchTableSettings`: the entries
under the bottom key are zero without the offset and never with it; the
mean step is 33 counts a semitone at 1 V/oct and 40 at 1.2) and pressed on
the page before the offsets are taken (`pitchCents`, the exact inverse of
`pitchTable`: the same table builds again from them), because switching
the offset drops a loaded table by design. The ten numbers have no
controls on the page and a tuning table does not turn back into a scale,
so those two are shown only. The load invalidates the build like any
option change; the next image is made from what was read.

The firmware version the keyboard reports leads the listing, and decides
the verdict when the keyboard runs another build than the one here: the
same version with different options, or an older one, want a flash from
step 3 (an older one's settings are still loaded); a newer one wants a
reloaded page. A keyboard whose layout the page does not know is refused
before anything is decoded, and the refusal still names its version. Send
stays gated on the exact build in every case: a record is only right for
the image it was made for. Nothing before 3.0 answers NRPN at all, so a
pre-3.0 keyboard reads as no reply, and the no-reply line says so.

## Changes, file by file

**`src/AssemblePressureFix.java`** - built, at `0x8001f000..0x8001f9d4`:
`settings_copy`, `settings_valid`, `settings_newest`, `settings_boot`,
`settings_defaults`, `settings_reload`, `settings_target` (the map),
`settings_apply` (commands and cells), `settings_nrpn` (the hook's cave),
`settings_send`, `settings_value`, `settings_scan`, `settings_commit`,
`settings_verify`, the 8-byte `settings_cc_hook` at `0x8000838e` and the
pool word it calls through; the reader pool words repointed and the ten
number sites converted. Every label past `settings_target` is a `long`
constant, so a cave that grows moves as one edit - declared before any
pool word that names it, because the transpiled JavaScript hoists a later
`var` as undefined and emits a pool word of zero where Java would refuse to
compile; `tools/test.py`'s call-pool guard now plants that case.

**`tools/settings.py`** - built: the record serializer and parser, and
`build/settings.bin` written by every build. `tools/test.py` checks the
layout and bounds; `web/test_configs.py` compares it with the page's record
for every configuration in its matrix.

**`web/buildlib.js`** - built: `settingsRecord` and `crc32`, and the codec:
`nrpnMessages`, `nrpnDecoder`, `nrpnParamsOf`, `nrpnValueOf`, `nrpnApply`,
`nrpnIdentity`, with `NRPN_SECTIONS`, `NRPN_COMMANDS` and `NRPN_IDENTITY`
naming the map. `web/test_nrpn.js` round-trips every parameter.

**`web/settings.js`** - built: the transport, `SETTINGSMIDI`: `push` in
bursts of four parameters with a pause between (the ring is 32 packets and
overflows silently), `dump` and `identity` collecting replies until the
layout version arrives, `commit`, `reload`, `defaults`, `differences`, and
`install` - identity, push, dump, compare, commit, identity - which refuses
with a named reason: no reply, wrong layout, wrong image, mismatch (with
the parameters that differed), not written (with the state).
`web/test_settingsmidi.js` runs it against a fake instrument that behaves as
the firmware does under emulation.

**`web/app.js`, `web/index.html`** - built: step 5, *Send settings*
(`SETTINGSMIDI.install`, a line per refusal) and *Read settings*
(`SETTINGSMIDI.read`, then the verdict, the listing and the load above),
over the calibration's port list with the inputs added to `calibrate.js`.

**`docs/`** - this file becomes `SETTINGS.md` once built, the way
`PERSISTENCE.md` did; `HANDOFF.md`'s "Settings over MIDI" item points here.

## Tests, each stage ending with one

1. **Record and codec, no Ghidra.** Built: `test_settings_record` in
   `tools/test.py` (layout, CRC, bounds, the zero bank), the record compare
   in `web/test_configs.py` (both serializers, every configuration), and
   `web/test_nrpn.js` (every parameter through the wire and back, masks in
   thirds, the identity block, the walk order; and the way back, a dump's
   pairs into a record by name, and `pitchCents` against `pitchTable` for
   both scalings and both offsets, on a flat table and a bent one).
2. **Load and fallback.** Built: `src/SettingsRegression.java`, run by
   `tools/test_persistence.py` in every persistent mode against that image's
   own `settings.bin`: no record boots the baked tables and the mirror equals
   the build's record; a planted record loads and the applier, the pitch
   remap, `clock_init` and `seq_glide` read it from the mirror; a warm reset
   reloads; sixteen header, image, period and bound corruptions and a
   flipped CRC bit each fall back; the newer of two slots wins, across the
   generation wrap, and a corrupt newer slot yields to the older.
3. **Receive.** Built, in the same regression: packets into the ring at
   `0x34b4` and the factory parser over them. A number, a pitch entry, a
   tuning entry (clearing the applier's guard), keys per period, the three
   thirds of a mask and a length each land; each bound refuses; a gap
   changes nothing; controller 5 still reaches the factory on the
   instrument's channel and on channel 16, and not on another channel;
   NRPN on another channel is not ours; nothing is sent.
4. **Commit and dump.** Built: a commit needs its key, is requested on
   receive and done on the scan, takes slot 0 then slot 1 with the
   generation counting, leaves the previous record in the other slot, boots
   after a power cycle; a write that does not take leaves state `3` with
   the old record newest and the next request tries again; `0x3f01` and
   `0x3f02` reload and reset. A dump ends by itself, sends at most eight
   packets a scan, 316 parameters in the instrument's order with every
   value the mirror's, then the identity block; an identity request sends
   the ten alone, the generation in three parts.
5. **Nothing else moved.** `test_clock.py`, `test_controls.py`,
   `test_persistence.py`, the golden build and the browser matrix, because
   the number sites and pool words touch clock, sequencer and pitch caves.
6. **On the instrument**, by the owner: push a deliberately wrong
   calibration entry, hear it, reload, commit a right one, power-cycle, DFU a
   fresh image and confirm it boots baked.

## Decisions taken here, and the calls that are the owner's

Taken, cheap to change:

- Channel 16 and standard NRPN controllers, not SysEx. It is the same
  non-SysEx access the calibration already asks the browser for, the
  parser has no SysEx handling to find, and the telemetry already lives
  there.
- Live table edits, explicit commit. Audition is the point of the feature.
- Two 2 KB slots rather than a ring: settings change tens of times over an
  instrument's life, not thousands.
- The image's own baked tables remain the fallback, so stage 1 changes
  nothing about what a fresh flash does.

The owner's:

- **Should the flashed hex also carry a record** so a build made on the page
  flashes with its settings already in place? Decided 2026-09-22: no. The
  baked tables are what a fresh flash plays, so a record in the hex would
  change nothing audible, only make a fresh flash report saved settings, at
  the cost of a full repin. The page's step 5 is for changing settings
  without flashing again, and its copy says so; it asks for MIDI only when
  the port list is clicked.
- **Non-octave tunings at runtime** need `octave_units` moved to RAM, five
  of whose sites are in the factory octave-switch block. Default here:
  build-time only in stage 1.
- **The user-facing wording** for the page step.
