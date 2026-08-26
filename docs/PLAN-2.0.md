# Rewired 2.0 — plan

Decisions below were settled with the owner (2026-08-26); the archaeology
items are the remaining unknowns.  Nothing here is user-facing copy.

## Features and settled decisions

### 1. .kbm support (build-side only)
- Parsers in `tools/build.py` AND `web/buildlib.js` (test_configs.py keeps
  them in lockstep).  Table expression generalised from 12/1200 to map
  size / formal octave — the firmware key table is 32 flat entries and the
  tracking correction interpolates by pitch, so no firmware change.
- Unmapped keys → nearest degree.  Reference NOTE honoured (maps to
  reference_key); reference FREQUENCY ignored (CV instrument — absolute
  pitch belongs to the 208's trimmer).
- DONE, and the 2/1 requirement went with it: the table steps the period
  the file declares, and the factory's own octave arithmetic (panel switch
  at 0x80003776/88/92, stored octave at 0x800035e4/fa) plus our arp octave
  are rebuilt to that period.  One period per build - the slots must agree,
  because there is one set of octave controls.
- Latch match tolerance guard scaled to the smallest degree spacing
  (24-tet needs <= 10 units; config allows 30 today).
- Page UI: one optional .kbm beside each .scl slot.

### 2. Decoupled preset voltages  [BUILT, except persistence]
- Factory: preset OUTPUT (single jack, active pad selects) and the
  add-to-pitch MIDDLE mode both read the live knob mirrors
  (getters at 0x80003624/366e/36b8/3702 switch on knob index).
- New: four stored values, restored at boot, BOTH consumers read the store.
  Hold pad N + turn knob N to edit (output follows while editing);
  release stores.  Persist in the factory settings record (RAM 0x0968,
  loader 0x8000a264, saver 0x80009fb8 — already called by
  poly_settings_migration; verify spare bytes in the record).
- While a pad is held and its knob moves, freeze that knob's arp/vibrato
  latch.  Pads 2+3 TOGETHER is the factory latch chord; single holds are
  free.  Remove the factory pad-latch in builds with latching_arp = true.

### 3. Configurable knobs (page options)
- Knob 1: [six-order zones: asc, desc, random, press, reverse-press,
  mirror] OR [1.x continuous press->random blend].  BUILT, off by default.
  [arp_order].knob1_orders = 1 turns the zones on.  The cave at 0x8001aec0
  takes the selector's pool word, keeps the same stack frame as the selector
  it replaces, and jumps into that selector's own code for the random and
  press-order zones rather than reimplementing them.  Mirror turns at the
  outermost HELD key, not the end of the keyboard, and its direction lives
  at RAM 0x614e.
- Knob 2: randomness (1.x) | swing | patterns.  ALL THREE BUILT, randomness
  by default; [knob2].mode = "swing" or "patterns".
  Patterns: the 22 CLIX fills are in tools/clix.py, taken from the Clockwork
  Card, and are the bank when the config names none.  The gate at 0x8001b050
  takes the selector's pool word and calls the real selector through, so it
  composes with knob 1: a rest answers -1, which is what the caller already
  reads as "nothing to play", and the note sequence does not advance on a
  rest.  Bank and lengths live in the gap the relocated sine left at
  0x80019f20; the step is RAM 0x6150.  The rhythm randomiser comes out in
  this mode - it reads the same knob latch, and a pattern is unreadable if
  its hits are also unevenly spaced.
  Swing: the cave at 0x8001b100 takes the randomiser's own hook and output
  cell, because both answer the same question - how long is this step.  It
  lengthens one step of each pair by as much as it shortens the next, so the
  pair keeps its total and the arpeggio does not drift in tempo; up to a
  third either way, which is 1.97:1 at full travel.  Parity is RAM 0x6152,
  and the same deadzone (knob < 0x30) and clamps (0x8..0xfff) as the
  randomiser.  Verified by emulating the shipped bytes.
  The page has a grid editor for the pattern bank, with plain-text x.x..
  import/export and the CLIX fills one click away.
- Knob 3: stays arp octave span, untouched.
- Knob 4: vibrato (1.x) | octave switching.  BUILT, off by default;
  [knob4].octaves = 1.  It drives the factory's OWN trn transpose rather
  than inventing one: knob 4 is the knob trn was always on, which is why
  remap_knobs retires it.  The cave writes state+0x6b (the amount) and
  state+0x6a (the mode) once per scan, after the tuning applier, which is
  what makes it stick - with a tuning installed the applier zeroes 0x6a
  every scan.
  The factory reads that as: value <= 2 means no transpose, then (v-2)
  periods UP - never negative, which is why no clamp is needed.  Nine
  positions on a 2/1, and fewer on a wider period so the reach stays about
  six octaves' worth rather than the knob's top half pushing everything
  past the DAC.  The arithmetic is the octave_scale_mul/bias pair, already
  period-aware, so trn steps tritaves on a tritave build.

### 4. SH-101-style sequencer (option)
- Takes the add-to-pitch toggle: top=record, middle=play, bottom=off.
  Factory toggle: top=octaves (4 pads select), middle=active pad's preset
  voltage, bottom=none.  With the option on, the pitch
  adder is hard-stuck to octave mode, so pads transpose live during play.
  Only the adder's preset-voltage SOURCE is forfeited: the preset
  voltages themselves stay live at their own banana output, pad-selected
  and pad+knob-editable as ever (document the distinction).
  Bottom = factory none.
- Precedence over the arp switch, including latch-exit clearing.
- Record: entering wipes; note-ons append PITCHES (like latch stamps, so
  tuning-slot switches do not shift recorded notes); pitch-bend strip
  ends enter rest (left) / tie (right); strip's normal role suspended in
  record.  64 steps.  Play: entering resets to step 0; clocked by the
  same source as the arp; keyboard silent; pads = live octave transpose.
- RAM only (lost at power-off) — no new flash machinery.  If the settings
  record has ~130+ spare bytes, persistence can ride along later.

### 5. External clock divide (option)
- On the ARP RATE knob (not knob 2): internal clock -> rate as today;
  steady external clock detected (interval variance under threshold over
  N pulses) -> knob becomes divider /1../8, the Clockwork Card's own law
  (map(1023-pot, 0, 1023, 1, 8)).  No multiplication — predicting edges
  is out of scope (card has none either).  Falls back when the clock stops.

## Noted for later

- **Oscillator ceiling, not a firmware limit.** On a Bohlen-Pierce build the
  top octave sounds nothing: the 208p tops out around 2670 Hz and the table
  runs past it.  Later 208 revisions go higher, so this is per-instrument.
  Worth a builder-side warning naming the highest key's frequency for the
  configured volts/octave, and possibly an option to cap the table at a
  stated ceiling.  Confirmed on the owner's instrument 2026-08-26.

## Archaeology (all in the factory binary)
- Add-to-pitch toggle: state address + how the source selection is applied
  (forcing point for octave mode).
- Pitch-bend strip: touch/position state, and where its bend enters the
  pitch path (to suspend in record and read ends for rest/tie).
- Arp rate knob: mirror address + rate handler (for the divider takeover).
- External clock input: where pulses arrive (interval measurement site).
- Settings record at 0x0968: SETTLED, see below.  What remains is what sits
  next to it in flash.
- Stack low-water mark vs RAM plan (sequence buffer past 0x613a).

## Phase 2 findings (2026-08-26)

The persisted settings record, end to end:

- RAM 0x0968 holds a POINTER to the record in flash; the payload is staged in
  RAM at 0x46c8 and committed to (pointer + 2).
- The record is 31 bytes, 0x00..0x1e, and every one of them is in use - the
  saver at 0x80009fb8 writes each from a live setting, byte 0x02 being our
  own poly-MIDI marker.  There is no spare byte inside it.
- Length is the immediate `MOV R10,0x1f` at 0x8000a244 (and again at
  0x80009f96 in a second saver with the same shape).  Both are two bytes, so
  0x27 would patch in place with no layout change.
- The loader at 0x8000a264 does NOT take a length: it dereferences the
  pointer and reads bytes by offset.  So new bytes need no loader patch -
  our own code reads 0x1f.. directly.
- The commit is a general bounds-checked flash writer at 0x800108fc taking
  (dest, src, len, flag).

So four 16-bit preset voltages want bytes 0x1f..0x26 and length 0x27.

SETTLED - use the User Page instead.  The AT32's separate 512-byte User Page
at 0x80800000 is untouched by the application: the only real references to it
in the whole image are eight instructions between 0x800109b8 and 0x80010e1e,
all inside the flash driver itself, alongside MOV Rn,0x200 for the page size.
(A byte scan appears to find 119 references; every one is a false positive -
the LDM SP++,R7,PC epilogue encodes as e3cd8080, and a word-aligned read of
its tail plus two zero bytes looks exactly like 0x80800000.  Check the
instruction boundary before believing any of them.)

So our own storage goes in the User Page, written through Buchla's driver at
0x800108fc, and their 31-byte record is left alone - no length patch, no
wear on their structure, and room for the sequence later if it ever wants
persisting.

The old option, for the record: extending their record would have meant
knowing what follows it in flash.  The record's address is only known at run time (RAM 0x0968, whose
writer is not any of the obvious constant-loads), so writing eight bytes past
it could land on something else.  Options, cheapest first:

1. RAM-only presets for now - works, lost at power-off, contradicts the spec.
2. Establish the flash layout around the record (read 0x0968 on a running
   instrument, or find its initialiser) and extend if there is room.
3. Use the AT32's separate 512-byte User Page, which exists for exactly this -
   needs checking whether the factory already uses it.

## Phase 2, still open: the pad gesture

Decoupling itself is ready to write - four RAM cells, the four getters at
0x80003624/366e/36b8/3702 repointed off the knob mirrors, seeded at first
use.  What is missing is the gesture.

- state+0x2ef holds which of the four pads is SELECTED (0..3).  It is what
  the octave switch reads at 0x8000375a, and what the add-to-pitch middle
  mode means by "the currently active pad".
- The press handler is at 0x800069b0: a switch on a pad index that writes
  0x2ef and calls 0x80006a18 (LED, most likely).
- That is a latch, not a hold.  The momentary state is a SEPARATE array:

    **RAM 0x46f0, one byte per pad** - 0 released, 1 touched, 2 held.

  Written at 0x8000a5be (0), 0x8000a574 (1) and 0x8000a598 (2); the 2 path
  also sets state+0x2eb+pad and calls the press handler at 0x8000a784, which
  is what writes 0x2ef and moves the LED.  Found by walking backwards from
  the pad-select function at 0x8000698c through its only non-restoring
  caller.  Same shape as the keys' array at 0x3490, so "held" is the same
  test our pressure pass already makes: CP.W ...,0x2.

  Clear of everything we use: the settings payload ends at 0x46e7 and our
  own RAM starts at 0x6000.

  So the gesture is now buildable: hold = 0x46f0[n] == 2, release = the
  transition back to 0, knob moved = the mirror against a remembered value.

## Phase 2, as built (2026-08-26)

- The four getter reads become `LD.SH R8,R8[0x2bda]` and neighbours: the
  displacement is sixteen bits and the base is the state block, so our RAM at
  0x613a is reachable from it.  Same instruction, same four bytes, no pool
  word needed - which sidesteps the shared-pool problem below entirely.
- RAM: 0x613a store, 0x6142 knob snapshots, 0x614a flags.  The first-use
  clear of the pressure cache runs to 0x25 instead of 0x1c so it covers them,
  which is why a flash cannot leave the presets reading old RAM.
- The editor is a cave at 0x8001ae1c, called once per scan from the
  housekeeping cave's own slack.  Hold a pad, turn its knob, the store
  follows until release.  Following waits for real movement (more than 8
  counts) so touching a pad with the knob parked elsewhere does not snatch
  the stored voltage - and while a pad is up its snapshot tracks the knob, so
  movement is always measured from where the knob stood when the pad went
  down.
- Verified by emulating the shipped bytes: eight cases including the pickup
  problem and jitter under a held pad.

Still to come: persistence, and it is blocked on one fact.

WRITE ON RELEASE, never on the follow path: the store tracks the knob in RAM
while the pad is down, so one gesture is one write.  That is what keeps flash
wear irrelevant - roughly a hundred thousand erase cycles against a handful of
edits a day.

NOT the User Page after all, despite it being free.  On this part the DFU
bootloader keeps its configuration words at the END of that page, so anything
that erases the page to write it risks taking the bootloader's configuration
with it - and that is the one failure this project cannot recover from
without JTAG.  Our own page in the main array is the safer target: the image
ends at 0x80018bf4 and our caves at 0x8001aec0, so a page at 0x8001c000 is
empty, ours alone, and a mistake there costs a reflash rather than an
instrument.

The open fact is the driver at 0x800108fc: whether it erases the target page
before writing, or assumes the target is already erased.  It matters because
the two need different code - a driver that erases can rewrite one slot
forever, while a driver that does not needs a fresh slot each time and an
erase when the page fills.  Static analysis has not settled it: the FLASHC
registers are never reached through a pool word or an ORH pair that a scan
can find, so the controller access is built some other way.

Settle it empirically instead, with a throwaway diagnostic build: write a
known pattern to 0x8001c000, read it back, write a DIFFERENT pattern to the
same place, read again.  If the second write takes, the driver erases.  If it
returns the AND of the two, it does not.  Ten minutes on the instrument
answers what an afternoon of disassembly has not, and it cannot hurt anything
- the page belongs to nobody.

## Phase 2, the wrinkle in the getter repoint (avoided in the end)

The four preset getters read the mirror as `LD.SH R8,R8[0x30a]` (and 0x30c,
0x30e, 0x310), four bytes each, off a state base loaded from the pool at
0x80003900.  Repointing that pool would move all four at once - but the same
pool word is also read at 0x80003758 for the octave selection at state+0x2ef,
so it cannot simply be aimed somewhere else.

Options: replace each load with `LDDPC R8,<pool>` + `LD.SH R8,R8[0x0]`, which
is the same four bytes but needs a free aligned pool word in range of each
site; or take over the getter wholesale by repointing whatever pool reaches
it, the pattern used everywhere else here.  The second is more in keeping and
avoids hunting for pool space.

## Infrastructure
- Branch 2.0 (pushed).  Add it to firmware.yml + windows workflow
  triggers; NOT pages.yml — the site deploys from main only.
- Version 2.0.0 on this branch; goldens diverge per branch, repin on
  every cherry-pick.
- Order: 0 infra, 1 kbm, 2 presets, 3 knobs, 4 sequencer, 5 clock divide.
  Test hexes per phase on the owner's config, as in 1.x.
