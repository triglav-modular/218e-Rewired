# Settings over MIDI, stage 1

One image, and the numbers that people actually iterate on set from the page
over Web MIDI, with no DFU round trip: the pitch calibration, the volts per
octave and pitch offset that live inside it, the three tuning slots, the
pattern bank, and the build numbers nobody has measured yet. Everything that
is *code* - which features are in, which role each knob has - stays a build
option; that is stage 2, and it is not laid out here.

Nothing in this document is built. Every address below was read out of the
disassembly in `build/disasm/dump1.txt` and the assembler on 2026-09-22; the
facts that were inferred rather than read are marked as such.

## What moves to runtime, and what does not

| Setting | Today | Stage 1 |
|---|---|---|
| `pitch_correction`, `volts_per_octave`, `pitch_offset` | all three fold into one 79-halfword table, `pitch_remap` at `0x80019bc0` | the table, over MIDI |
| `alternate_tunings` (octave-period only) | three 32-halfword tables at `0x80019af8` and the 3-entry `tuning_period_keys` at `0x8001e2d0` | the tables, over MIDI |
| `arp_patterns` | 32 masks at `0x80019f20`, 32 lengths at `0x80019fa0` | the tables, over MIDI |
| the unmeasured numbers below | immediates in caves | RAM cells, over MIDI |
| non-octave tunings | change `octave_units`, which is compiled into eleven sites, five of them in the factory octave-switch block at `0x8000336c` | **stay build-time** - a runtime tuning must repeat at the period the image was built with |
| `pressure_curve` (1828 bytes at `0x80018d80`), `black_key_excess` | generated from frozen `INTERNAL_DEFAULTS`, identical in every build | stay baked |
| every `block.*` and `feature.*`, the knob roles | code present or absent | stage 2 |

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
| `0x004` | 2 | Layout version, `1` |
| `0x006` | 2 | Payload length, `0x298`; the record is `0x2a8` with its header |
| `0x008` | 4 | Generation, nonzero, wrapping to 1 |
| `0x00c` | 4 | CRC-32/ISO-HDLC over `0x004..0x00b` and `0x010..end`, as persistence |
| `0x010` | 2 | Image marker: the low 16 bits of `init_marker`, so a record written against one image is refused by another |
| `0x012` | 2 | `octave_units` the record was generated for; refused unless it matches the image |
| `0x014` | 12 | Reserved, zero |
| `0x020` | 64 | 32 numbers, halfwords; the ten above, the rest zero |
| `0x060` | 158 | `pitch_remap`, 79 halfwords |
| `0x0fe` | 2 | Pad |
| `0x100` | 192 | Tuning slots 0..2, 32 halfwords each |
| `0x1c0` | 8 | `tuning_period_keys`, 3 halfwords and a pad |
| `0x1c8` | 128 | 32 pattern masks, 32 bits each |
| `0x248` | 64 | 32 pattern lengths, halfwords |
| `0x288` | 32 | Reserved, zero |
| `0x2a8` | | end |

Load validates marker, version, length, generation, CRC, image marker,
period, then every field's bounds: pitch and tuning entries inside the
12-bit DAC range and monotonic where the build validators demand it, lengths
`1..32`, numbers inside their ranges. Any failure means the record is ignored
and the image's own tables are used; a bad record is never repaired in place.

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
| `0x6a68` | 8 | NRPN state: parameter MSB/LSB, data MSB, a "have MSB" flag, the dump cursor |
| `0x6a70` | 8 | commit state: `0` clean, `1` requested, `2` written, `3` failed; the loaded slot index; its generation |
| `0x6a80` | 0x2a8 | staging for a commit, marker erased, as persistence stages at `0x6300` |

Everything ends below `0x6d30`; the stack keeps over 12 KB.

Every reader reaches its table through a pool word today, so repointing is a
word change, not a code change:

| Table | Pool words to repoint |
|---|---|
| `pitch_remap` | `0x80019bc0` in `pitch_remap_calibration` |
| tuning slots `0x80019af8` | the applier's `0x80019aec`, and the pools of `cv_transpose`, `preset_degrees`, `preset_entry` |
| `tuning_period_keys` `0x8001e2d0` | the same three caves |
| pattern bank `0x80019f20` / `0x80019fa0` | `arp_pattern_gate` |

The ten numbers become `LD.UH` from `0x6800 + 2n`. Each is a cave, so the two
extra bytes per site are found by re-laying the cave out, not by squeezing.
The `cv_transpose` pool words are read with `LDDPC` and used as values; those
three sites become a `MOV Rn,0x6800; LD.UH Rn,Rn[2m]` pair.

`MOV Rd,0x6800` is the same 4-byte instruction persistence already uses for
`0x6300` and `0x6640`.

## Boot

`settings_boot`, called first from the `persist_boot` chain (a new pool word
ahead of `0x8001d5f0`'s first entry):

1. Copy the baked tables and the build's numbers into the mirror. This is the
   fallback and it always runs, so a corrupt record can only lose edits,
   never the instrument's own tuning.
2. Find the newer valid slot. If there is one, copy its payload over the
   mirror.
3. Zero the NRPN state and the commit state, record the loaded slot and
   generation, zero the applier's guard at `0x60e4` so the first scan copies
   the (possibly new) slot table into `0x854`.

Order matters: `persist_boot` then restores `0x6090`, and the first scan's
applier copy reads the mirror.

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

The hook is the branch at `0x80008366` that sends a Control Change to
`0x8000838e`: repointed to a cave that reads the channel from the parser's
own frame (`R7[-0xa]`), the controller and value from the ring the same way
the factory does at `0x800083a2..0x800083ce`, handles the four NRPN
controllers on channel 16 itself, and jumps to `0x8000838e` for everything
else. The factory's three controllers keep working on the instrument's
channel; if that channel is set to 16, data entry on it is ours. That is the
one behaviour change and it is documented rather than avoided.

### Parameter map

| Parameter | Meaning | Value |
|---|---|---|
| `0x0000..0x001f` | number *n* | bounded on receive; out of range is ignored, not clamped |
| `0x0080..0x00ce` | `pitch_remap[0..78]` | DAC units |
| `0x0100..0x011f`, `0x0120..0x013f`, `0x0140..0x015f` | tuning slot 0, 1, 2 | DAC units |
| `0x0160..0x0162` | period keys | |
| `0x0180 + 3p + 0..2` | pattern *p*'s mask, bits 0..13, 14..27, 28..31 | |
| `0x01e0..0x01ff` | pattern lengths | `1..32` |
| `0x3f00` | commit | data `0x2a2a`; anything else ignored |
| `0x3f01` | reload the mirror from flash, dropping live edits | |
| `0x3f02` | reset the mirror to the image's baked tables (flash untouched until a commit) | |
| `0x3f03` | dump: send every parameter back | data selects a section, `0` for all |
| `0x3f7f` | identity: reply with layout version, image marker, generation, CRC, commit state | |

Table writes go live in the mirror at once, so a calibration entry can be
auditioned before it is committed. A tuning entry additionally zeroes the
applier's guard so the next scan re-copies the slot; a single halfword store
is atomic and the pitch is recomputed every scan, so a table mid-push is
merely a half-new table, never a torn value.

Commit is a request, not a write: the handler sets the commit state to `1`
and the write happens in the next scan from `persist_scan_shim`, the same
context and the same driver procedure as a preset save - stage the record
with its marker erased, erase-and-write the four pages of the *other* slot,
read back and compare, then write the marker without erase, read back and
validate. The state goes to `2` on success and `3` on any mismatch, and the
identity reply carries it, so the page learns the outcome instead of
assuming it. A failed commit leaves the previous slot intact; the next commit
request tries again.

Replies use the same four CCs on channel 16 in the other direction, at two
values (eight packets) per scan, so a full dump is about 0.8 s and never
outruns the telemetry's proven rate. The page's decoder is the firmware's
encoder mirrored.

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

## Changes, file by file

**`src/AssemblePressureFix.java`** - new blocks in a fresh cave page from
`0x8001f000`: `settings_boot`, `settings_load` (find and validate a slot),
`settings_nrpn` (the CC hook and the parameter state machine),
`settings_commit` (called from `persist_scan_shim`), `settings_dump` (the
paced sender, driven from the same shim). The reader pool words repointed and
the ten number sites converted. The init marker changes, so this is one repin
tail for the whole batch; keep it to one commit.

**`tools/build.py` / `tools/options.py`** - the record serializer, shared
with the page in spirit and checked against it by a parity test. The build
still bakes the tables into the image exactly as now; the serializer only
adds `build/settings.bin` and its CRC for the tests and for the page.

**`web/buildlib.js`** - the same serializer, and the NRPN codec: value to
four CCs, four CCs to value, plus the section layout so the page can address
a single entry.

**`web/app.js`, `web/index.html`** - a step after the download: pick the
MIDI port (the calibration's port list already exists), *Push*, *Read back*,
and a comparison readout. The copy for it needs the owner's approval before
it is written; this document names the controls, not the wording.

**`docs/`** - this file becomes `SETTINGS.md` once built, the way
`PERSISTENCE.md` did; `HANDOFF.md`'s "Settings over MIDI" item points here.

## Tests, each stage ending with one

1. **Record and codec, no Ghidra.** `tools/test_settings.py` and
   `web/test_settings_record.js`: serialize the default config through both
   toolchains and compare bytes; NRPN encode/decode round trips every
   parameter; bounds refuse every out-of-range value.
2. **Load and fallback.** `src/SettingsRegression.java` on the persistence
   harness (it already models FLASHC and the page buffer): no record boots
   the baked tables; a valid record boots the mirror from it; each of the
   validation failures - marker, CRC, version, image marker, period, bounds -
   falls back; the newer of two slots wins, including across generation
   wrap.
3. **Receive.** Feed packets into the ring at `0x34b4` and run the
   dispatcher's drain: a table write lands in the mirror and re-arms the
   applier guard; a number write is bounded; a foreign CC still reaches the
   factory's mod-wheel path; an NRPN on channel 3 is ignored.
4. **Commit and dump.** Commit writes the other slot, verifies, sets state
   `2`; a modelled write failure sets `3` and leaves the old slot loadable; a
   power cut between body and marker leaves the old slot newest. Dump emits
   every parameter through the modelled sender at the paced rate, and the
   page decoder reads them back to the same record.
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
  flashes with its settings already in place? Default here: no - the page
  pushes after the flash, one procedure instead of two. It is a few hex
  records at `0x8003d000` if wanted later.
- **Non-octave tunings at runtime** need `octave_units` moved to RAM, five
  of whose sites are in the factory octave-switch block. Default here:
  build-time only in stage 1.
- **The user-facing wording** for the page step.
