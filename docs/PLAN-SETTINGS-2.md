# Settings over MIDI, stage 2

The options that are *code* today - which blocks are in the image and what
each knob does - set from the page over Web MIDI like the tables are, so
one image serves every option set and a change of mind costs a MIDI push
and a power cycle, not a DFU round trip. [PLAN-SETTINGS.md](PLAN-SETTINGS.md)
is stage 1 and stays authoritative for everything it lays out: the record,
the mirror, the boot chain, the wire protocol and the identity block. This
file adds to it and changes nothing in it except the layout version.

**Status (2026-09-23): phases A, B and C built; D next.** The owner's calls at
the end were answered the same day: the whole scope, layout 2, and options
that take effect at once through a restart the page sends. Every address and byte count
below was read out of the assembler, the golden build's manifest and log,
the union of block extents across the five logged configurations, the
factory control-flow table and the factory hex; nothing was measured on the
instrument. What still has to be read per site during the build is marked
*(verify)*.

## The principle, and what it costs

Stage 1 left one image per option set because a disabled option is left as
*factory bytes*: `block.<name>=0` skips the patch, `feature.<name>=0` leaves a
section out of a cave, and the page counts what it "left factory". Stage 2
ends that for the options it moves: **the image always carries every block,
and "off" is a behaviour, decided at boot from a cell in the settings
mirror.** The factory-bytes guarantee survives only for what stays
build-time (below). That is a change of contract the page's copy has to
say, and it is the first of the owner's calls at the end.

Three things are true of this firmware that make the move cheaper than it
sounds, and they were checked rather than assumed:

- **Most options are already dormant until a gesture engages them.** The
  sequencer's nine hooks in factory code all ask `seq_clock_enabled`
  (`0x8001d600`), which answers the physical arp switch unless the mode at
  `0x6158` is 2, playing; the mode only becomes 2 through the pad-4 chord in
  `seq_chord` (`0x8001b180`). The latch engages only when `latch_v2`
  (`0x8001a280`) sees switch position 1; the divider only acts once
  `clock_capture` has locked; the jack transposer shifts nothing at zero
  degrees. So for these, "off at runtime" is one test at the point of
  engagement, and the consequence code downstream sees the same zero state
  it sees today in a build with the option on and the gesture unused.
- **Every cave is reached through a pool word or a hook the assembler
  wrote**, and there are 99 sites in factory code: 32 pool words, 23
  inserted hooks of 6..54 bytes, 42 in-place instructions of 2 or 4 bytes,
  and two 128-byte caves in unused factory space.
  A pool word becomes a dispatcher; an inserted hook's cave already
  reproduces the factory test it displaced (the assembler's comments say so
  hook by hook, and the caves were designed that way) so it grows a flag
  test at its entry; a 4-byte in-place instruction has room for an `MCALL`
  to a shim. Only the 2-byte sites have no room, and there are six that
  matter: four become 6-byte hooks and two become always-on (below).
- **Flash and RAM are not the constraint.** The caves total 24,662 bytes
  in the union of configurations; `0x8001fa00..0x8003cfff` is erased, 117 KB.
  The dispatchers and shims below come to about a kilobyte. Sixteen bytes of
  RAM hold the live option bytes.

The cost is in the caves themselves: 87 of the golden's 142 caves have no
slack at all, and where there is padding it is under 16 bytes in all but a
handful; and cave labels are absolute addresses written into
instruction strings. **Any cave that has to grow moves**, and moving a cave
means rewriting its labels - the trap recorded in
`~/SDIY/Tooling/practices/embedded.md` under "Sequential label rewrites
collide". The settings caves from `settings_target` on were written with
`long` label constants for exactly this and move as one edit; the older
caves do not, and each one that grows is a careful relocation. The plan
below is ordered so that the options whose gates fit in existing slack or
in new caves come first, and the two that force relocations in old caves
(the pressure path and the clock) come last and can be left build-time.

## What moves, and what does not

| Option (page) | Today | Stage 2 | What "off" means at runtime, and where it differs from a build with it off |
|---|---|---|---|
| `knob1` order / orders / factory | `arp_selector_pool` word names the blend cave, the zones cave or the factory selector `0x800029a8`; `arp_gate_hook` out when all knobs are factory | dispatcher on the pool word; the gate hook always in | identical; the gate hook latches knobs 1-3 into our own RAM whatever the roles, which nothing else reads |
| `knob2` spacing / quantized / swing / patterns / factory | `arp_rhythm_hook` (10 B) calls the pool at `0x80019d40`, which names the randomiser, swing or quantized; patterns puts `arp_pattern_gate` in front of the selector and drops the hook | the rhythm hook always in, its pool word a dispatcher; the pattern gate a dispatcher stage of the selector word; the bank always mirrored | identical; a factory knob 2 reproduces the displaced reload in the hook's cave *(verify: the 10 displaced bytes)* |
| `knob3` octaves / factory | `arp_octave_hook` (8 B: `MCALL` + the displaced `ST.H R7[-0x8],R8`) | cave entry tests the byte; off returns R8 unchanged | identical |
| `knob4` vibrato / trn / factory | `vibrato_engine`+`vibrato_sine`+`pressure_vibrato_*` for vibrato, `knob4_octave_switch`+`knob4_early_pool`+`knob4_owned_transpose` for trn; the two exclude each other at build time over RAM `0x6028` | both in; the per-scan vibrato call and `knob4_early_pool` become one dispatcher; `0x6028` is shared by time instead of by build | identical; the octave-switch cave zeroes `0x6028` on entry so a role change over a power cycle cannot inherit a vibrato offset *(verify: which of the two writes it first after boot)* |
| `latching_arp` | 8 blocks and `feature.arp_latch` in 10 cave sections; `factory_pad_latch_off` NOPs the factory latch call; `poly_arp_independence` suppresses the long-hold poly toggle; `release_count_guard` fixes a factory count underflow | one test where position 1 becomes the latch (`latch_v2`, the applier's edge watch, `latch_pitch_toggle`'s caller); `factory_pad_latch_off` becomes an `MCALL` to a shim that calls the factory timer when the latch is off; `poly_arp_independence` branches on the byte | the switch is ascending / random / off again; `release_count_guard` stays in - it is a bug fix, and keeping it is a difference from today's off build that is strictly better; the 10 `arp_latch` sections are inert with nothing latched *(verify each; list below)* |
| `sequencer` | ~40 blocks, 9 hooks in factory code, and the pool words whose target depends on it (`strip_pool`, `pulse_drop_pool`, `pad_select_pool`, `key_note_pool`, `key_restore_note_pool`, `midi_transpose_arp_pool`) | the arm in `seq_chord` refuses while the byte is off; nothing else | identical for the player: every hook already answers the factory way while the mode is 0; the persistence record's take is restored but cannot be played |
| `clock_divide` | ~25 blocks, the ISR hook, `clock_edge_mode`, `clock_spike_units`, five pool words onto the 1 kHz beat path | acquisition refused in `clock_service` while the byte is off; `pulse_pool_*` and `dac_flush_pool` dispatch back to `pulse_defer_set` and the factory flush; the ISR cave runs the factory body and drops the falling edge it now receives | **differs**: `trigger_spike_units` stays 5 (the owner's 4 ms spike) instead of the factory 3, and the two-phase beat's timing goes back to the scan grid only if the pulse pools dispatch (they do, above). The ISR's off path is 52 bytes of factory code with five pool calls and two branches reproduced in the cave *(verify: the whole body, instruction by instruction)* |
| `pressure_fix` | four pool words back to factory, `pressure_gain_nop` (2 B), `pitch_clamp_skip_1/2` (2 B each), `pressure_target_redirect`, `dac_interpolate` | the pool words dispatch; the three 2-byte sites each become a 6-byte hook (the instruction after each is 4 bytes and nothing branches into the pair) whose cave does the factory work or ours; the target redirect stays and the interpolator copies straight through when off | identical if the three hooks are right; the pressure store reaches the DAC one 1 kHz tick later than the factory's direct store *(verify: audible?)* |
| `pressure_portamento` | `feature.pressure_blend` in 3 sections, `glide_rate_hook`'s clamp cave, `pitch_hook_pool` routed around the conditioner | route word dispatches; `glide_rate_clamp` grows a test (it has no slack: relocation); the three sections branch on the byte | identical *(verify the three sections)* |
| `quantize_presets` | `preset_quantize_pool` (float-to-int pointer), `feature.preset_rotate`, the shared key-table rotation | the pool word dispatches to the quantiser or the factory `0x80013434`; `preset_degrees` publishes zero degrees when off | identical *(verify: nothing rotates at zero degrees with the jack also off)* |
| `portamento_in` portamento / transpose | `glide_cv_addend` (4 B `MOV R8,0x28` over `LD.UH R8,R8[0x2f0]`), `feature.cv_jack` in 3 sections, `cv_transpose` reading the jack | the addend becomes an `MCALL` to a shim that loads or returns 0x28; `cv_transpose` reads zero degrees when off | identical; the jack's filter pole (`cv_filter_shift`, off by default) stays build-time |
| `alternate_tunings` slots present or not | `feature.alternate_tunings`, `edit_key27/28` (54 and 48 B over the factory transpose-mode and remote-enable toggles), `remote_guard_1..3` | **stays build-time**: both key blocks overwrite factory code that has another job when no tuning is installed, and the tables are already runtime |
| non-octave tunings | eleven `octave_units` sites, five in the factory octave-switch block | **stays build-time** (stage 1's rule) |
| `volts_per_octave`, `pitch_offset`, `pitch_correction` | in the pitch table | already runtime (stage 1) |
| `persist` | required on | unchanged |
| `diagnostics.*`, `midi.poly_default`, the `pressure.*` and `timing.*` internals | frozen `INTERNAL_DEFAULTS` | **stay build-time** |

`transpose_force_1..3` (three sites forcing the factory transpose mode to 1,
emitted whenever a tuning or a knob role is on) become runtime with the
knobs: two are 4-byte loads that an `MCALL` shim replaces, the third is a
2-byte `MOV R8,0xa` followed by a 4-byte `ST.B R9[0x1c6],R8` and becomes a
6-byte hook like the pressure ones *(verify: LR live across the stretch?)*.
With tunings still build-time, an image with a tuning keeps forcing it as
today.

## The sites, by class

The 99 sites in factory code, from the union of extents across the golden,
`allon`, `featoff`, `nomulti` and the JavaScript build (the golden's own
manifest has 77; the rest belong to configurations it does not build).

**Pool words (32 in factory code, more inside our caves).** Each names a
factory routine today or one of ours; about twenty, in factory code and in
our own caves, name *one of several* of ours by option (`arp_selector_pool`,
`0x80019d40`, the four `pulse_pool_*`, `dac_flush_pool`, `knob4_early_pool`,
`pitch_hook_pool`, the boot and housekeeping chain links, the sequencer's
note and pad words). Stage 2 gives each option-dependent word a dispatcher:
`MOV R8,0x6d28; LD.UB R8,R8[k]; CP.W; BR; LDDPC PC,...` - about 20 bytes
and one pool word each, in new caves above `0x8001fa00`. A dispatcher
preserves nothing and tail-jumps, so the register plan at the call site is
untouched; the factory targets are read from the factory hex and written
into the plan as data (`arp_selector_pool` `0x800029a8`, the pulse pools
`0x800077f8`, `dac_flush_pool` `0x80004f66`, `pressure_fn_pool` `0x80013350`,
the float helpers `0x80013434`, `knob1_pool` `0x80004188`, `knob3_pool`
`0x800040c8`, `knob4_pool` `0x80004070`, `knob4_early_pool` `0x80004a00`,
`pad_select_pool` `0x8000698c`, `strip_pool` `0x80002e30`, `pulse_drop_pool`
`0x80007540`, `clock_irq_pool` `0x80002440`, `clock_ms_pool` `0x800076b0`,
`profiler_pool` `0x80004c64`, `clock_init_pool` `0x80007340`, the five
`midi_transpose_*`/`key_*_note` words `0x800057a8`, `noteoff_pool_1/2`
`0x80005a50`, `note_on_pool` `0x80005a04`, `active_key_pool` `0x8000596c`).

**Inserted hooks (23).** All but four are `MCALL` into a cave plus a
re-emitted fragment of what was displaced; the assembler's comment at each
says what the cave reproduces. The control-flow table shows which displaced
ranges contained factory branches (the cave already re-targets them):
`clock_gate_hook` (2), `seq_noteoff_hook` (1), `seq_gate_clear_hook` (1
pool call), `arp_select_hook` (1 + 1 pool call), the three `seq_clock_*`
hooks (2 each), `pitch_store_hook` (1), `seq_clock_midi_hook` (2),
`seq_trigger_led_hook` (1), `seq_clock_tick_hook` (2), `poly_arp_independence`
(1 pool call), `clock_hook` (1 pool call), `clock_irq_hook` (7). The runtime
"off" path for each is the cave's existing factory-equivalent branch,
selected by the option byte instead of by absence; where the cave has no
slack for the test (most), the test goes in a new stub cave that the hook
calls instead, and the stub calls on. `release_count_guard` (36 B) and
`edit_key27/28` are full rewrites of a factory stretch with no cave: the
guard stays unconditional (a fix), the keys stay build-time.

**In-place 4-byte instructions (33).** Twenty-five are the decoupled preset
reads/outs, the octave-switch redirects, the `octave_*` arithmetic and the
remote-enable guards, all build-time or unconditional already, and
`poly_persistence_marker` is internal. The seven that carry an option:
`factory_pad_latch_off` (over the factory's own `MCALL`, so LR is already
spent), `transpose_force_1/2` (over `LD.UB R8,R9[0x2db]`), `glide_cv_addend`
(over `LD.UH R8,R8[0x2f0]`), `clock_tempo_hook` (already an `MCALL` into
`clock_tempo`, which grows the test), `pressure_target_redirect` and
`strip_dac_redirect` (stores redirected into our cells, which stay redirected
- the consumers already hand the value on when idle). An `MCALL PC[pool]`
reaches ±128 KB, so every site reaches a pool word above `0x8001fa00`. For
the three over plain loads, whether LR is live across the site is the thing
to read first *(verify)*.

**In-place 2-byte instructions (9).** Three are internal or numbers and stay
as they are (`scan_period`, the two `poly_*` defaults); two become always-on
(`clock_spike_units` at 5, `clock_edge_mode` at both edges with the ISR cave
dropping the falling one when the divider is off). The four that carry an
option and have no room: `pressure_gain_nop` (over a `BR{eq}` that
skips the factory gain), `pitch_clamp_skip_1/2` (over the `LDDPC R8` that
begins the factory 16-tap filter), `transpose_force_3` (over `MOV R8,0xa`),
Each of the four is
followed by a 4-byte instruction and the control-flow table shows nothing
landing between them, so each becomes a 6-byte hook: `MCALL` plus `NOP`, the
cave replaying both instructions (an `LDDPC` replays as a `MOV` of the
literal it loaded) and then either continuing or jumping to the skip target.
Four caves of about 24 bytes; the LR question again at each *(verify)*.

**Cave sections under `feature(...)`.** `arp_latch` (10 sites, lines 372,
1097, 1123, 2197, 2648, 7079, 7538, 7708, 7762, 10084 of the assembler),
`cv_transpose` (7), `pressure_blend` (3), `cv_jack` (3), `multi_key_pressure`
(2), `preset_rotate`, `knob4_vibrato`, `alternate_tunings`, and the
diagnostics. The rule is **gate the engagement, not the consequence**: a
section that only acts on state the gesture writes needs no test of its own
once the gesture is refused. Line 1123 is the exception in kind - a data
layout (`MOV R8,0x6540` against `0x6100`) rather than behaviour - and the
latch layout simply becomes the only one. Each of the 26 sections is read
during its phase and classified as inert-when-idle or needing a branch; the
count of branches decides how many old caves move. The estimate from reading
the comments is under six, all in phases E and F.

## The option cells

The record and the mirror keep their size. **Number cells 16..27 become the
option cells**, one halfword each, absolute values, bounded like the numbers:

| Cell | Mirror | Option | Values |
|--:|---|---|---|
| 16 | `0x6820` | `latching_arp` | 0 off, 1 on |
| 17 | `0x6822` | `knob1` | 0 order, 1 orders, 2 factory |
| 18 | `0x6824` | `knob2` | 0 spacing, 1 quantized, 2 swing, 3 patterns, 4 factory |
| 19 | `0x6826` | `knob3` | 0 octaves, 1 factory |
| 20 | `0x6828` | `knob4` | 0 vibrato, 1 trn, 2 factory |
| 21 | `0x682a` | `sequencer` | 0, 1 |
| 22 | `0x682c` | `clock_divide` | 0, 1 |
| 23 | `0x682e` | `pressure_fix` | 0, 1 |
| 24 | `0x6830` | `pressure_portamento` | 0, 1; refused on load and on receive when 23 is 0, as the page refuses it |
| 25 | `0x6832` | `quantize_presets` | 0, 1 |
| 26 | `0x6834` | `portamento_in` | 0 portamento, 1 transpose |
| 27 | `0x6836` | reserved | 0 |
| 10..15, 28..31 | | reserved | 0 |

The values are the page's own option encodings in the order `options()` in
`web/app.js` lists them, so `settingsFields` names them without a second
table. Cells 10..15 stay reserved so the ten numbers can grow without
moving the options.

**The build's config values are the baked defaults**, exactly as the tables
are: `settings_defaults` writes all 32 cells (it writes ten today), so a
fresh flash behaves as its config says and stage 2, like stage 1, changes
nothing about what a fresh flash does. `settings_valid`'s bounds table
grows from 10 to 32 pairs and moves out of `settings_valid`'s extent into
its own cave; `settings_target`'s number branch bounds at 0x20 instead of
0xa. The map gains one section: **`0x0020..0x002f`, the live option bytes,
read-only** - the dump sends them right after the 32 cells, so the cursor's
first gap moves from 0x0a to 0x30, and a write to them is ignored.

**Layout version 2.** The map changed, so the identity block's `0x3f7f`
answers 2, the record header carries 2, and both `settings_valid` and the
page refuse 1. Nothing has ever committed a layout-1 record: stage 1 has
not been flashed to any instrument, so there is no record to migrate and no
reason to carry the three-state "zero means as built" encoding that a
compatible extension would need. If a layout-1 record were ever found
(a bench image flashed before this lands), the firmware ignores it and boots
baked, which is stage 1's own rule for any record it refuses.

**When an option applies: at boot.** `settings_boot`, after the record load,
calls `settings_live`, which copies the low byte of cells 16..31 to
**`0x6d28..0x6d38`, the live option bytes** (a `RAM_REGIONS` entry after the
commit staging, which ends at `0x6d28`). Every dispatcher, shim and gate reads a live byte, never the
mirror, so an NRPN write changes nothing until the next power-up, however
much state the option owns. That is the whole reason for the snapshot: a
latch holding notes, a take mid-play, a locked divider, a knob role changed
under a moving knob - none of those has to be unwound live, and none of the
suites has to prove a transition. The page tells the two apart because both
are in the dump: the mirror's cells 16..27 and the live bytes at
`0x0020..0x002f`. Where they differ it says which options take effect at
the next power-up, and the send's copy says a power cycle is part of the
gesture.

`0x3f01` (reload the mirror from flash) and `0x3f02` (the image's own
settings back) fill the option cells like any other; neither touches the
live bytes.

**And the restart that makes it immediate.** The owner's call (2026-09-23):
settings take effect at once, and a restart is an acceptable way to get
there. So the map gains `0x3f04` with data `0x2a2a`: **restart**, through the
watchdog - the cave enables the WDT with a short timeout and spins, which is
a power cycle by other means (SRAM survives it, and the boot already treats a
warm reset like power-up). The page's send becomes: identity, push, dump,
compare, commit, identity, and then, when the committed option cells differ
from the live bytes, restart; it then waits for the port to come back (the
USB re-enumerates) and asks for the identity block once more, whose live
bytes must now equal the cells. A send that changes only tables does not
restart. What the settings regression can prove is that the command writes
the watchdog's control register in the documented two-key sequence and
nothing else; the reset itself, and that the bootloader hands a
watchdog-reset chip back to the application, are the bench's to confirm
*(verify on the instrument, first thing)*.

## The boot chain, and what always runs

Today the startup pool word chain (`clock_init_pool` `0x80007d8c` ->
`settings_boot` -> `persist_boot` -> `clock_init` / `seq_boot` -> the
factory's GPIO setup) is built per configuration, three of its links
chosen by `block(...)` (assembler lines 6629, 6631, 10495), and the
housekeeping chain likewise (11106). In stage 2 **every link is always in
and always runs**: `first_use_initializer`, `seq_restart_init`, `seq_boot`,
`clock_init`, `persist_boot`. Each of them clears its own state
unconditionally already, which is what makes a dormant feature safe: the
practices file's "New state is outside the old clear" is exactly the failure
a conditional initialiser would reintroduce here. `clock_init` configures
the GPIO edges and the timer whether or not the divider may acquire; the
ISR then runs for both edges and the cave ignores the falling one when the
live byte is off *(verify: that this is what the factory body needs, and
that a second interrupt per pulse costs nothing it cannot afford)*.

## The build, the page and the record

**`tools/build.py`.** `resolve_flags` forces every runtime block and feature
on and writes the config's runtime options into `_numbers` under the cell
names above; `FEATURE_MAP` keeps only what stays build-time. The fingerprint
that makes `init_marker` no longer varies with the runtime options, so two
builds that differ only in them produce **the same image and the same
marker**, and the record the page sends is right for both - which is the
point. `settings.bin` carries the cells. The JavaScript build mirrors it, as
`WEBBUILD.build` mirrors everything else.

**`web/buildlib.js`, `web/settings.js`.** `settingsRecord` writes the cells;
`nrpnParamsOf`, `nrpnRecordOf`, `settingsFields` know the section and the
live bytes; `SETTINGSMIDI.read` returns the options beside the tables and
the live bytes beside them; `install` is unchanged in shape. Layout 2 in
`NRPN_IDENTITY` and the refusals.

**`web/app.js`, `web/index.html`.** Step 2 keeps every control where it is.
What changes is which controls invalidate the build: only the build-time
inputs do (tunings and their period, scaling and offset through the table,
the calibration). The runtime options no longer clear `state.result`; they
mark the settings dirty, and step 5's *Send settings* pushes the record the
controls describe against the image the keyboard reports. The read-back
loads the options into the controls - stage 1 could show them only, because
they were baked - and lists, per option, whether the keyboard's live byte
already matches. The send's gate stays the exact image marker. The copy for
all of this is the owner's, as always.

**The beacon.** It counts builds with the options the build was made with.
Under stage 2 a build's options are its *defaults*, and the option set the
player ends up running is sent over MIDI and never counted. Leaving the
beacon alone means the stats describe fewer real choices than they do today;
counting sends would change what the beacon collects, which needs the
owner's yes on its own (memory: worker-deploy-authorisation). Default here:
unchanged.

**`docs/`.** This file becomes part of `SETTINGS.md` with stage 1's when
built; `HANDOFF.md`'s "Settings over MIDI" item points here.

## Tests

The suites today build one image per variant and assert each variant's
behaviour against its own image. Stage 2's acceptance is the same
assertions against **one image**, the variant chosen by planting the option
cells - which is also the proof that "off at runtime" is "off at build
time" for everything the suites already check, variant by variant. The
regressions gain a fixture that writes a record with given cells into slot 0
(as `SettingsRegression` plants records now) and boots; `test_controls.py`'s
seven variants and `test_clock.py`'s become records, and `test_configs.py`'s
matrix compares fewer distinct images and the same number of records. The
build-suite cost falls with the image count (memory:
ghidra-emulation-is-the-suite-cost).

Each phase ends with one of these, and the golden is repinned once per phase,
not per fix (memory: batch-firmware-fixes-into-one-tail):

1. **Cells and layout (phase A).** `test_settings_record`, `test_nrpn.js`,
   `test_settingsmidi.js`: 32 numbers, the option bounds, the live section
   read-only, layout 2 refused as 1 and accepted as 2, the restart after a
   commit that changed a cell and not otherwise. `SettingsRegression`:
   the cells load, the live bytes are the booted cells and stay put across a
   write and a reload, `0x3f02` restores the config's values, a cell out of
   range falls the record back, `pressure_portamento` without `pressure_fix`
   is refused on both paths, `0x3f04` with the key writes the watchdog's
   two keys. No behaviour moves yet: the image differs from the golden only
   in the settings caves.
2. **Knob roles (phase B).** `ControlRegression` `roles`, `swing`, `patterns`
   and `default` against one image with the cells planted; `knobs_off` from
   the parity matrix as a record. The dispatchers' factory targets checked
   against the factory hex by `tools/test.py`.
3. **Latch (phase C).** `ControlRegression` `default` and `lean` on one
   image; the pads 2 & 3 chord reaches the factory timer with the byte off
   and the toggle with it on; the poly long-hold likewise; the count guard
   fires in both.
4. **Sequencer (phase D).** `SequenceEditRegression` and `ControlRegression`
   `lean` on one image: the chord never arms with the byte off, every hook
   answers the factory way, a restored take does not play.
5. **Jack and presets (phase E).** `ControlRegression` `jack` and `tuned`:
   zero degrees rotates nothing; the glide addend is the factory load with
   the byte off and 0x28 with it on.
6. **Pressure (phase F).** `pressure_off` and `portamento_off` as records;
   the three 6-byte hooks' both paths; the interpolator's pass-through
   timing measured in the harness.
7. **Clock (phase G).** `ClockRegression` on one image: no lock with the
   byte off, the factory ISR body's effects on a rising edge, nothing on a
   falling one; the pulse pools' dispatch; the jitter target under both.
8. **Nothing else moved**, after each phase: `test.py --golden`,
   `test_configs.py`, the sweep, `test_persistence.py`, the node tests, the
   browser matrix, six fake keyboards through the built page.
9. **On the instrument**, by the owner, after phase B at the earliest: send
   knob 2 as swing, power-cycle, hear it; send it back; and the pending
   report on the page between the two.

## Order of work

Each phase is one image, one repin, one commit, in this order because each
leaves the previous ones' proof standing and because the relocation risk
rises along it:

- **A. Cells, live bytes, layout 2, the map, the restart, the page codec.**
  Settings caves only, all movable by constant; no old cave touched.
  Built 2026-09-23: `settings_valid` holds 32 cells to a bounds table of
  its own (`settings_bounds`, `0x8001fa80`), `settings_defaults` copies the
  32 cells out of `settings_numbers` (`0x8001fb00`), `settings_live`
  (`0x8001fb40`) copies the option bytes at boot, `settings_restart`
  (`0x8001fb60`) is the watchdog write, and everything from
  `settings_target` on moved up by the growth (`settings_scan` is at
  `0x8001f820` now). The page's step 5 sends the restart after a commit
  that changed an option and waits through the re-enumeration; a read
  lists the options, says which are not yet running, and loads them into
  the controls. In this phase a record's cells always equal its image's
  baked ones, because the image marker still varies with every option, so
  the page never actually restarts a keyboard until phase B moves the
  first option out of the marker. Verified: both toolchains at
  `fab1de5c`, historical `2cc3c881` (sweep: match + known image), the
  parity matrix 44/44, `test.py --golden` 289 checks, the corpus
  regenerated (11,337 instructions), `SettingsRegression` in all four
  persistence modes (680..683 assertions, the restart's two watchdog
  writes read back), controls 12/12, clock 6/6, the node codec and
  transport tests, and the built page against a fake keyboard in the
  preview: send, restart, port away and back, the confirmation; a read
  listing the options with one not yet running and loading them into
  the controls; and a keyboard that never comes back reported after the
  20 s limit. `run()` had to be split: the settings caves live in an
  `Emitter` lambda (Java's 64 KB method limit), which the transpiler
  nests as a function.
- **B. Knob roles.** Built 2026-09-23. Six dispatchers and two latch
  helpers at `0x8001fb80..0x8001fd20`, each spending only R8 (dead at every
  call site: the caves dispatched to clobber it first): the key selector's
  word (`0x80002420`, and the sequencer's copy) goes to
  `knob_selector_dispatch`, which puts the pattern gate in front when knob
  2 is on patterns and otherwise, like the gate's own inner word, to
  `knob1_dispatch` (blend, zones, or the factory selector `0x800029a8`);
  the rhythm hook's word `0x80019d40` goes to `knob2_rhythm_dispatch`
  (randomiser, quantized, swing, or for patterns and factory the factory's
  own reload, leaving R8 the state base and R9 the tempo as the
  randomiser's deadzone does); the per-scan chain's vibrato word, the ADC
  event's pool word `0x800051f0` and the preset editor's late word go to
  the three knob-4 dispatchers (vibrato engine or return; `knob4_early` or
  the factory handler `0x80004a00`; the octave switch or return). Knob 3
  needed no dispatcher and could not have one without a scratch register
  (its randomiser keeps R9..R12 live): its latch write in the housekeeping
  became a call to `knob3_latch`, which stores zero when knob 3 is
  factory, the randomiser's own deadzone. Knob 1's latch write likewise
  became `knob1_blend_latch`, which writes `0x60f2` as before and a blend
  latch at `0x6d38` only while knob 1 blends; the sequencer's shuffle reads
  the blend latch, so a recorded order is kept under the zones or a factory
  knob (it used to follow knob 1 whenever any knob was live). Every knob
  cave is in every image; `knob1_orders`, `knob2_*` and `knob4_octaves`
  are gone from the numbers; the four knob cells, `pattern_count` and the
  pattern bank are out of the image marker (`MARKER_EXCLUDES`, both
  builders), so two builds that differ only in knob roles share a marker -
  checked: 52604 for the default and for orders/swing/factory/trn - and the
  page's send reaches a keyboard flashed with either. Two behaviours differ
  from a build with the option off: the three transpose forcing patches
  stay in every image (the build no longer knows whether the knobs are all
  factory), so four factory knobs and no tuning over MIDI keep the factory
  transpose mode forced off; and knob 1 factory with knob 2 on patterns now
  plays the patterns, where the build used to leave the factory selector
  in place with the gate unreachable. Verified: both toolchains at
  `8438b900`, historical `e945d1b0` (sweep: match + known image), parity
  44/44, `test.py --golden`, the corpus (11,500 instructions),
  `SettingsRegression` in all four modes (704..707 assertions: every cell
  value of every knob resolved to its cave, the plain reload's registers,
  both latch helpers under every role, the seven words naming the
  dispatchers), controls 12/12 with the roles now decided by the
  dispatchers, clock 6/6.
- **C. Latch.** Built 2026-09-23. Three dispatchers, a helper and a shim
  at `0x8001fd20..0x8001fdf0`, on the live byte at `0x6d28`: the note-on
  wrapper's word for `latch_owner` goes to `latch_noteon_dispatch`, which
  with the latch off writes the identity ownership a non-latch position
  keeps (`current[key] = key + 1`, `owner[key] = key + 1`, so the blend's
  slot map is the raw cache by key) and hands the key back; both note-off
  pools go to `latch_noteoff_dispatch`, which with the latch off clears the
  key's ownership as the latch's wrapper does in every position and goes on
  to the factory note-off; `transpose_capture`'s word for `latch_hold` goes
  to `latch_hold_dispatch`, which with the latch off hands the pitch back.
  `latch_state`'s pads 2 & 3 test reads pad 2 through `latch_pad_test`,
  which answers "not held" while the latch is off (a 6-byte
  load-compare-branch became call-branch, so the cave did not move), and
  the NOP over the factory's own latch chord call became an `MCALL` to
  `latch_pad_shim`, which swallows the call while the latch is live and
  lets the three-second timer run otherwise, so the factory chord is back
  with the latch off. Downstream, the blend's stamp add, the slot map and
  the switch-edge release in the housekeeping see zero stamps and the
  identity ownership with the latch off, which is what a non-latch switch
  position already gave them. Two of the ten `arp_latch` sections needed
  more than that, and the lean variant of the controls suite found the
  first: the pitch rank added stamps at switch position 1 whatever the
  option said, and SRAM survives the restart that turns the latch off, so
  stamps from a latch-on session would have reordered the regular arp. The
  rank now tests the live byte too (it had 14 bytes of slack), and
  `option_boot` (`0x8001fdf0`, which took over `settings_live`'s live copy
  and blend latch) zeroes the 29 stamps, the toggle's term and both
  ownership maps at every boot with the latch off - the first-use
  initialiser does not run again for a restart. Two things differ from a
  build with the option off: `release_count_guard` stays in (a factory
  fix), and `poly_arp_independence` stays in, so the factory's long-hold on
  the arp switch no longer toggles polyphonic MIDI in any image - giving it
  back at runtime would mean relocating 32 bytes of factory code with a
  pool call inside, and edit mode as the single owner was the intent. The
  latch spacing refusal now applies to every build, since the latch may be
  live. The latch cell is out of the image marker (checked: 44606 with the
  latch on and off). Verified: both toolchains at `06258c88`, historical
  `45cb758f` (sweep: match + known image), parity 44/44, `test.py
  --golden` (which caught the chord call assembled without a listing
  entry, an MCALL the audit could not count), the corpus (11,614
  instructions), `SettingsRegression` in all four modes (732..735
  assertions: every gate under both states, the registers each target
  takes preserved - `latch_hold` takes R8 and R9, and the first version
  of its dispatcher spent R8, which the clock modes caught as the pitch
  moving between notes - the words naming the dispatchers, and the
  latch's RAM cleared at a latch-off boot and kept at a latch-on one),
  controls 12/12 with both lean variants running the latch off through
  the dispatchers, clock 6/6.
- **D. Sequencer.** One test at the arm.
- **E. Jack and presets.** One shim, two zero-degree gates, up to three
  branches in `cv_transpose`/`preset_degrees` (both have 4 bytes of slack;
  a third branch relocates one of them).
- **F. Pressure.** Four pool dispatchers, three 6-byte hooks and their caves,
  `glide_rate_clamp` relocated, the interpolator's pass-through.
- **G. Clock.** The ISR cave's factory body, the acquisition gate, the
  pulse-pool dispatchers, the edge filter.
- **H. Page, copy (owner's), docs, `SETTINGS.md`.**

F and G are the ones that can be left build-time if the ISR body or the
pressure stretches turn out to cost more than they are worth; nothing in A-E
depends on them, and the option table above then says so for those two.

## Decisions taken here, and the calls that are the owner's

Taken, cheap to change:

- Options apply at boot from a snapshot, never live. Auditioning is for
  tables; an option owns state, and a power cycle is what the page asks for.
- Absolute values in number cells 16..27 and a layout bump to 2, rather than
  a zero-means-as-built encoding in the reserved bytes. Nothing has been
  committed under layout 1 anywhere.
- The dispatchers read the factory targets from the factory hex at build
  time and the build checks them, so a wrong constant is a build failure,
  not a pool word into the wrong routine.
- `release_count_guard` becomes unconditional. It fixes a factory bug and
  has no option semantics.
- The beacon is unchanged.

The owner's:

- **The scope.** Decided 2026-09-23: everything in the table marked
  "Stage 2", in the order above, F and G last.
- **The end of "left factory".** Accepted 2026-09-23: for the options that
  move, a build with an option off no longer leaves the factory bytes at its
  sites, and the page's "left factory" count shrinks to the build-time
  options.
- **The layout bump** to 2 with 1 refused. Accepted 2026-09-23.
- **A reset command.** Decided 2026-09-23: yes, `0x3f04`, and the page
  sends it after a commit that changed an option.
- **The page's shape and copy** for a step 2 whose controls no longer all
  invalidate the build, the pending report, and the send's power-cycle line.
- **The version.** 3.0.0 stays until told otherwise; the identity block's
  layout version is what tells a page the map changed.
