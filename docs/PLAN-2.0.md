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
- **The add-to-pitch toggle is left alone.**  It keeps selecting octaves /
  preset voltage / none exactly as the factory does.  (Revised 2026-08-27,
  third time: it was going to become record/play/off, which is what forced
  the adder-source chord that replaced it, which is what needed a forcing
  point in the pitch adder.  None of that exists now.  The pitch adder is
  untouched, and so is every preset behaviour built in phase 2.)
- **The sequencer lives on the pad 4 hold.**  Hold pad 4 for three seconds
  to arm - pad 4's light blinks - then, still holding it, press:
    - pad 1: RECORD
    - pad 2: PLAY
    - pad 3: OFF (stop)
  Pad 3 is not something the owner named; it is here because a sequencer
  needs a way to stop that is not "wait for a power cycle", and 1/2/3 in
  panel order is the reading that needs no explaining.  Say so if it should
  be something else.
- The chord machinery is unchanged from the adder-source version it
  replaces, only the meaning of the three pads is different: the pad touch
  array at RAM 0x46f0 is read every scan by the preset editor cave, so this
  is a hold counter plus an edge test - pad 1/2/3 going 0->2 while pad 4 has
  been at 2 for 600 scans.  Scans are ~5 ms, so three seconds is ~600 of
  them: a HALFWORD counter, not a byte.
  Rules: the arm lives only while pad 4 stays held - releasing it disarms and
  zeroes the counter, so an arm can never outlive the gesture that made it.
  The selecting press is EATEN: it must not also select an octave or a pad.
  Pad 4's own press is an ordinary selection throughout, with nothing armed
  for the first three seconds.  A pad-4 hold that has MOVED ITS KNOB does not
  arm - the editor already flags that pad as following, and a careful preset
  edit is exactly what a long hold looks like.  None of these is the factory
  pads-2+3 latch, which our-latch builds remove anyway.
  Acknowledgement, on pad 4's own light (the pads are lit, one channel each -
  see LEDs above):
    - Held, under three seconds: nothing.  The light is simply on, the way
      any selected pad's is, and nothing has been promised yet.
    - Armed at three seconds: pad 4's light BLINKS - LED channel 3, bit 6 of
      the same scan counter that timed the hold, so it toggles every 64 scans
      and reads as roughly 1.6 Hz.  The counter free-runs and is allowed to
      wrap: 65536 is a whole multiple of 64, so the phase survives the wrap,
      and the armed flag is latched separately rather than re-derived from the
      count - which is what stops a five-minute hold from disarming itself.
    - Selected: the blink stops and the light goes steady for the rest of the
      hold, so the press is seen to have been taken.
    - RELEASED: the blink clears at once.  Counter zeroed, armed and selected
      cleared, and the light put back to what the radio group says - lit if
      state+0x2ef is still pad 4, dark otherwise.  Derived, not snapshotted,
      so an eaten pad press cannot leave it wrong.  Nothing repaints the pad
      lights per scan (unlike rem-en/trn, which the tuning applier
      re-asserts), so this restore is the only thing that puts the light
      right: it has to happen on every exit from the hold, the knob-moved
      refusal included.
    - Never the factory's own blink at 0x80003b1c: it busy-waits 4x150 ms and
      would stall the scan for six tenths of a second.  Ours is a bit test and
      a set/clear, and led_flush is free when nothing changed.
- **The running mode flashes its own pad, for as long as it runs.**  Pad 1
  flashes while recording, pad 2 flashes while playing; off is dark.  Same
  law as the arm blink - bit 6 of a free-running scan counter, about 1.6 Hz -
  so every light this firmware adds blinks at one rate and means "something
  is engaged".  The arm blink is on pad 4 and can run at the same time as a
  mode flash on pad 1 or 2; different channels, no interaction.
  This one needs re-asserting EVERY SCAN, which the arm blink does not.  The
  arm blink lives inside a pad hold, and nothing else writes the lights
  during it; a mode flash outlives the gesture and has to survive
  `select_pad` (`0x8000698c`), which clears channels 0-3 and lights one on
  every single pad press.  So: each scan, if the mode is not off, write the
  mode's channel to the flash phase.  That is the same thing the tuning
  applier already does for rem-en and trn, and for the same reason.
  The cost, stated plainly: **while a mode runs, its pad's light stops
  reporting pad selection.**  Recording with pad 1 selected as the octave,
  pad 1's light means "recording", not "pad 1 is the active preset".  Every
  other pad still reports selection normally - select pad 3 while recording
  and channel 2 lights steady while channel 0 flashes, which reads correctly.
  The lost indication comes back the moment the mode ends: on leaving, the
  channel is restored from `state+0x2ef` the same way the arm blink restores
  pad 4.
  OPEN: whether play's flash should follow the STEP instead of free-running,
  which would make it a tempo indicator as well as a running light.  Nicer,
  and free - we already have the tick - but it blurs at fast tempos, and the
  owner asked for flashing rather than for a tempo light.  Free-running
  unless told otherwise.
- Precedence over the arp switch, including latch-exit clearing.
- Record: BUILT.  Entering wipes; note-ons append PITCHES - the halfword the
  key table holds for that key, the same value the arp would have sounded -
  so a later change of tuning slot moves the keyboard without moving anything
  already recorded.  64 steps, then it stops taking them.  Hangs off the
  existing note-on wrapper, where the key is still in R12.
- Play: BUILT.  Entering resets to step 0.  It is not a note engine of its
  own: it answers the arp's own note selection, so the arp's clock, gate,
  MIDI and the pad octave transpose all carry it.  An empty sequence answers
  -1, which the arp already reads as nothing this step.  The pitch is swapped
  in at the value hook, after the octave randomiser, so what plays is what was
  recorded plus whatever the pads transpose.
  CONSEQUENCE, and it needs saying in the page copy: play rides the ARP
  CLOCK, so the arpeggiator has to be running for the sequence to advance.
  Forcing the arp engine on from our side was considered and rejected - the
  factory sets state+0x34c inside a start/stop sequence with its own setup
  calls, and skipping that setup is not something to do blind.
- Rest and tie: BUILT.  Push the bend strip hard one way for a REST, the
  other way for a TIE, while recording.  Both are edge-triggered, so a held
  push enters one step and letting the strip back towards the middle re-arms
  it; a small push is still just a bend.
  One hook does both jobs the plan asked for.  The strip's own pool word at
  0x8000335c goes through our cave, which reads how far it has been pushed
  AND passes zero on to the factory while recording - so the strip does not
  bend the pitch while you are entering rests.  Outside record it passes the
  value through untouched.
  Rest and tie are kept in the step store where a pitch cannot reach - 0x7ffe
  and 0x7fff against a 12-bit pitch.  Both answer the selector with -1, so
  neither retriggers and the pitch already sounding stays put.  What
  separates them is the GATE: seq_gate answers the arp's gate-off compare
  with 3 as the factory does, or with -0x8000 - a count the countdown can
  never reach - when the step about to play is a tie, so the gate never falls
  and the note carries across.  That reuses the knob-2 machinery that already
  owned that compare rather than adding any.
  THE PORTAMENTO KNOB MEANS TIME WHILE PLAYING.  Everywhere else on a
  pressure-blend build it means pressure-needed-to-bend and the glide rate is
  forced to zero - but the sequencer's keyboard is silent, so there is no
  pressure to blend and the knob would otherwise mean nothing at all.  In
  play it reads the factory's own glide table, exactly as a build without the
  blend does.
  A tie SLIDES into the note after it, 303 fashion, rather than stepping to
  it.  The tie arms a two-step count; the step that actually moves the pitch
  spends it, and while it is unspent the glide rate handed to the factory
  slew is ours rather than the knob's - which matters most on a
  pressure-blend build, where the knob's answer is zero and notes otherwise
  snap.  The store went out of line into its own cave to make room for the
  test: the clamp's block ends where pulse_defer_set begins.
  THRESHOLD NOT CONFIRMED ON HARDWARE: strip_end_units is 48, in the DAC
  units the bend is added to the pitch in (~1.2 semitones at 484/octave).
  The strip's absolute range depends on the factory's bend-depth setting at
  state+0x1f8, which was not chased down.  It is a build number so it can be
  moved once a real strip has been pushed.  tie_glide_rate is 60 on the
  factory's own 0..1024 glide scale - the same scale its knob table runs on,
  where 0 snaps and 1024 is the longest glide.  How long 60 actually is was
  not measured; it is a build number for the same reason.

## Strip archaeology (2026-08-27)
- `bend(R12 = value)` `0x80002e30`, reached through the pool word at
  `0x8000335c`.  Early-exits when the value has not changed, so hooking it
  gives every change and nothing else.
- It writes `state+0x216`, which the pitch update adds to the pitch at
  `0x800031f4`; the strip's own computed value is mirrored at `state+0x35e`.
- The raw halves are `state+0x202` / `state+0x204`, scaled against
  `state+0x1f8` (the bend depth) by `0x8000c150`, with `state+0x206` and
  `state+0x20c` choosing between them.  Not needed once `bend()` is hooked.
- Entering and leaving are our own chord handler now, not the toggle's change
  callback - simpler, and entirely under our control.
- RAM: the hold counter (halfword), one byte for armed/selected, one byte for
  the sequencer mode, and the step store.  Declared in RAM_REGIONS when built.
- RAM only (lost at power-off) - no new flash machinery.  If the settings
  record has ~130+ spare bytes, persistence can ride along later.

### 5. External clock divide (option) - BUILT

**Archaeology and build both done 2026-08-27.**

- The arp step function `0x8000210c` takes the interval in R12, and **-1
  means tick now, do not reload** - which is how every external trigger in
  the factory already advances it.  A divider therefore does not need to
  invent a tick path; it needs to drop N-1 of the ticks that already arrive.
- `state+0x34a` is the internal tempo.  It is written at `0x80002c06` from
  the ARP RATE knob: `0x3ff - knob` indexes a table at pool `0x80002d00`.
  That is the site the knob's second meaning would take over.
- Two sites already call the step with -1:
  `0x80005c4e`, from the key handler `0x80005b6c` and gated on `state+0x2da`
  (a key in 11..24 advances the arp), and `0x80004e72`, inside the event
  dispatcher and gated on `state+0x341` (the arp switch).
- **The clock pulse is dispatcher EVENT 10.**  The dispatcher is
  `0x80004c64`: it pulls an event, refuses anything above 0x27, and jumps
  through the table at `0x80014818` (pointer at `0x800051d0`).  Entry 10
  lands at `0x80004e58`, which checks the arp switch (`state+0x340` or
  `+0x341`) and ticks the arp with -1.  That body is 34 bytes,
  `0x80004e58..0x80004e7a`, and is the whole hook: a divider does not need
  to find the pin, only to decide whether each event 10 gets through.
- **The rate knob reads at RAM `0x2ee6`**, 0..0x3ff, written by the rate
  handler `0x80002b28` as `state+0x308` (the CV input) plus half of
  `state+0x2f2` (the pot), clamped.  Taking it over needs no patch there at
  all: the divider just reads the mirror, and the value it also writes to
  the internal tempo does no harm while an external clock is running.

### How MARF does it, and what we take (read 2026-08-27)

MARF locks when pulses arrive with "steady, plausible spacing", roughly
**20 ms to 2 s** apart (0.5-50 Hz), and locks after **two** consistent
pulses.  Its Time Multiply knob then becomes a clock ratio centred on 1:
clockwise multiplies up to x8 by subdividing, counter-clockwise divides to
one stage every 2-8 clocks, with hysteresis at the boundaries.  Stop the
clock and after **2 s** it returns to free-running.

Taken as-is: the 20 ms - 2 s window, the two-pulse lock, the 2 s release.
At a 5 ms scan those are 4..400 scans and a 400-scan timeout, so the
existing free-running scan counter measures all of it - no new timer.

NOT taken, at least first: **multiplication**.  MARF subdivides between
pulses, which means predicting where the next edge will fall; the original
sketch here ruled that out for the same reason and the Clockwork Card has
none either.  So the knob divides only, /1../8 across its travel by the
Clockwork law - fast end /1, slow end /8 - rather than centring on 1.
That is the one place this will feel different from MARF, and the obvious
thing to improve later once locking has proved itself on hardware.
Also not taken: MARF's humanize.  The arp's own rhythm knob already owns
that ground with swing and randomness.

### BUILT 2026-08-27

Verified out of the built image: a 20-scan clock locks on the second
interval; the knob then divides by 1, 2, 3, 5 and 8 at the positions the
law says; an unevenly spaced clock never reaches lock and every pulse
plays; 2-scan and 500-scan spacings are refused as clocks; and the lock
releases between 399 and 404 scans of silence, which is the two seconds
MARF gives itself.

The knob law is `1 + ((1023 - rate) * 8 >> 10)`.  The multiplier is 8
rather than the card's 7 because the shift divides by 1024 and not 1023;
with 7 the eighth division was unreachable and the top of the travel spent
two hundred counts on /1.

### Shape of the build (as built)

- Hook event 10's body at `0x80004e58`; our cave decides pass or swallow.
- Per pulse: dt = scan counter - last stamp.  In 4..400 and close to the
  previous dt -> lock count up to 2; otherwise back to 0.
- Locked: N = 1 + 7*(1023 - `0x2ee6`)/1023; pass one pulse in N.
- Per scan: no pulse for 400 scans -> unlock, and the arp is free-running
  again on its own countdown, which never stopped.
- RAM: last stamp (halfword), previous interval (halfword), lock count,
  divide count.  The scan counter has to exist whenever this option does,
  so it moves out of the sequencer's cave into a shared per-scan one.

Original sketch:
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

## LEDs (read out of the factory binary 2026-08-27)

Ten channels, each one bit of a 16-bit shadow word at RAM `0x2ef4`, with a
dirty flag at `0x2ef6`:

| ch | bit | what |
|----|-----|------|
| 0-3 | 7, 9, 10, 11 | the four preset pads, one each |
| 4 | 2 | unidentified |
| 5 | 13 | rem-en |
| 6 | 15 | unidentified |
| 7 | 3 | unidentified |
| 8 | 12 | trn |
| 9 | 14 | unidentified |

- `led_set(R12 = ch)` `0x80006808`, `led_clear(R12 = ch)` `0x800068cc`.  Both
  set the dirty flag; a channel above 9 falls through and only dirties.
- `led_flush()` `0x8000673c` pushes the word to a shift register over SPI
  (peripheral `0xFFFF2400`, two bytes, high first) and strobes GPIO pin 19,
  then clears the dirty flag.  It returns immediately when nothing is dirty,
  so calling it per scan is free; the two SPI bytes are microseconds.  The
  pad selector does not call it, so a caller that wants its change on the
  panel now should.
- `select_pad(R12 = 0..3)` `0x8000698c` is the whole pad-light story: clear
  channels 0-3, set the one that matches, store the pad at `state+0x2ef`.
  The lights are a radio group and are re-asserted only on a pad press -
  nothing repaints them per scan, unlike rem-en/trn which the tuning applier
  re-asserts.
- `blink(R12 = ch, R11 = currently_on)` `0x80003b1c` blinks twice and restores
  the state R11 claims.  It is BLOCKING: four `delay(150 ms)` calls, 600 ms
  of busy-wait.  Fine where the factory uses it; unusable from the scan loop.

## Sequencer archaeology (read out of the binary 2026-08-27)

**The add-to-pitch toggle** - NOT used by the sequencer any more, kept
because it is the only clean three-position input on the panel and the next
option that wants one should not have to find it again.  Scanned at
`0x80003980` off panel inputs 0xc and 0xd (`read_panel(ch)` `0x8001105a`),
and published three ways:

| cell | kind | meaning |
|------|------|---------|
| `state+0x342` | byte | top position: octaves |
| `state+0x343` | byte | middle position: active pad's preset voltage |
| `state+0x344` | word | the position itself: 0 bottom, 1 middle, 2 top |

`0x344` is the one to read - one cell, three values, no decoding.  The scanner
calls `0x800098c8(R12 = new position)` ONLY when it changes; the factory body
just emits MIDI CC 18 on channel 16.  That is the entering-record-wipes and
entering-play-resets hook, already isolated and already only firing on edges.
The toggle is applied inside the pitch adder `0x80003590`: `0x8000379a` reads
`0x342` for the octave branch, `0x800037ba` reads `0x343` for the preset
branch, both adding to the base pitch at `state+0x350`.  Nothing of ours
touches either one.

**The arp step engine** `0x8000210c`, R12 = the step interval:

- `state+0x34c` gates the whole engine (1 = running).
- `state+0x38e` is the countdown; the rhythm randomiser already writes it, and
  the gate-off compare at `0x800021a0` (== 3) is our existing hook.
- At zero it fires a step.  **`state+0x21a` (held count) must be non-zero or
  no note is chosen at all** - `0x800022ca` skips the selection outright.
  This is the one thing standing between play mode and a silent keyboard.
- The selector is called at `0x800022d4` through pool `0x80002420` - the pool
  we already repoint - with R12 = `&state+0x21b`, the held-flags array, and
  returns a key index or -1.  **-1 means no note this step**, which is exactly
  what a rest needs, and what the pattern gate already returns.
- The chosen key becomes a pitch at `0x800022f2` (`table[key]`, the table
  being RAM `0x854` that the tuning applier fills), stored at `0x800022f6` -
  **our `arp_octave_hook` already intercepts precisely this value** - and from
  there into `state+0x352` and `state+0x350`, the base the pitch adder reads.

So play mode does not need a note engine of its own.  Force the held count,
answer the selector with any valid index, and substitute the step's stored
pitch at the value hook: clock, gate, MIDI, the pad octave transpose and the
calibration remap all come along unchanged.  Rest is a -1 from the selector.

## Archaeology still to do
- Pitch-bend strip: touch/position state, and where its bend enters the
  pitch path (to suspend in record and read ends for rest/tie).
- Tie: how to hold the gate across a step, given gate-off is the `== 3`
  compare on the countdown.
- Arp rate knob: mirror address + rate handler (for the divider takeover).
- External clock input: where pulses arrive (interval measurement site).
- ~~Pad lighting~~ DONE, see below.
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
