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
  THE KEYBOARD IS NOT SILENCED, which an earlier draft of this section
  claimed it would be.  What actually holds: while playing, held keys no
  longer choose the arp's notes - the sequence's pitches replace the
  selector's answer - but note-ons still run their bookkeeping (MIDI note
  messages, the latch, pressure).  Audit note 2026-08-27: silencing was
  never built, and the sections below that leaned on "silent" have been
  reworded to lean on "the keys no longer choose the notes", which is the
  true statement.
- Rest and tie: BUILT.  Touch the bend strip while recording and let go:
  below halfway a REST, above halfway a TIE.  One touch is one step, and
  every touch is a step - three taps at the bottom enter three rests.  The
  position is read where the strip is when it is let go, not from which way
  it was pushed, so a slide from the top to the bottom enters what the
  bottom says.
  Two pieces share the strip's cave.  The strip's own pool word at
  0x8000335c goes through the first, which passes zero on to the factory
  while recording - so the strip does not bend the pitch while you are
  entering rests - and the value through untouched everywhere else.  The
  second is called once per scan by the pad chord's cave, which is the one
  thing in the sequencer that runs every scan whatever else is happening: it
  keeps the position while the touch flag is set and enters a step when the
  flag drops.  Waiting for a release, rather than for the bend value to
  change, is why every touch counts.
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
  forced to zero - but while the sequencer plays, the keys no longer choose
  the notes, so the pressure blend has nothing to steer and the knob would
  otherwise mean nothing at all.  In
  play it reads the factory's own glide table, exactly as a build without the
  blend does.
  A tie SLIDES into the note after it, 303 fashion, rather than stepping to
  it.  The tie arms a two-step count; the step that actually moves the pitch
  spends it, and while it is unspent the glide rate handed to the factory
  slew is ours rather than the knob's - which matters most on a
  pressure-blend build, where the knob's answer is zero and notes otherwise
  snap.  The store went out of line into its own cave to make room for the
  test: the clamp's block ends where pulse_defer_set begins.
  strip_halfway_units is 2048, the middle of the 0..4095 the factory clamps
  the position to, and the middle is the rule rather than a number to taste -
  it is a build number so that a strip reading off centre can be told where
  its own middle is.  tie_glide_rate is 60 on the
  factory's own 0..1024 glide scale - the same scale its knob table runs on,
  where 0 snaps and 1024 is the longest glide.  How long 60 actually is was
  not measured; it is a build number for the same reason.

## Rests and ties from an absolute strip position (BUILT 2026-08-27)

The owner's own model of the hardware, which is better than the one the
first version was built on.  The pitch strip has TWO modes: one that springs
back to the middle, and one that behaves like a mod wheel and stays where it
is left.  The second is the default.

What it does instead of thresholding a bend direction:

- Entering RECORD forces the strip into the mod-wheel mode, and leaving
  record puts it back to whatever the player had before - so somebody who
  works in spring mode is not silently switched.
- Rest and tie are read from the ABSOLUTE position at the moment of release:
  **below halfway is a rest, above halfway is a tie.**  No threshold, no
  direction, no re-arm rule - the position is simply what it says when you
  let go.

### The strip is not a knob, and its position is `state+0x1fe`

The strip has no ADC channel of its own.  It is a **seven-segment capacitive
sensor**, read through the same array as the keys, and the factory turns it
into a position at `0x8000aa98`:

```
total    = sum(seg[0..6])
weighted = sum(seg[i] * (i+1) * 1000)
centroid = weighted / total                    # 1000..7000
```

The centroid is then mapped and clamped by the factory's own range mapper
(`0x8000704a`, `out = clamp((x-in_lo)*(out_hi-out_lo)/(in_hi-in_lo)+out_lo)`):

```
state[0x1fe] = map(centroid, 1250, 6750, 0, 4095)      # 0x8000ad00
```

So **`state+0x1fe` is the strip's absolute position, 0..4095**, and the range
is not inferred - the firmware clamps it there itself.  4095 is also the
0-10 V the manual promises out of the blue banana.

Two things make it the right cell to read:

- It is written in **exactly one place**, and only while `state+0x206` (the
  touch flag) is up.  After a release it still holds where the finger left,
  so the release scan can simply read it - no snapshot of our own is needed.
- It is the RAW position in **both** strip modes.  What `state+0x20c`
  changes is `state+0x1f8`, the strip's OUTPUT: in absolute mode
  `0x1f8 = 0x1fe`; in relative mode `0x1f8 = 0x1fe - 0x200 + 0x7ff` clamped,
  where `state+0x200` is where the gesture started, and on release it snaps
  to `0x7ff` - the centre of 0..4095.  That is the pitch-wheel behaviour the
  manual describes, and it never touches `0x1fe`.

`strip_halfway_units` is 2048.  It is a build number only so that a strip
reading off centre can be told where its own middle is; the rule is the
middle.

### The knobs, since this was got wrong once

There are six knobs on the panel and six conditioned analog channels, and
they line up one to one.  `0x80007ad8` runs all six through the deadband
follower at `0x800079e0`:

| mirror | knob |
|---|---|
| `state+0x306` | PORTAMENTO |
| `state+0x308` | ARPEGGIATION RATE (through a table) |
| `state+0x30a` | preset voltage 1 |
| `state+0x30c` | preset voltage 2 |
| `state+0x30e` | preset voltage 3 |
| `state+0x310` | preset voltage 4 - the "trn" knob, decoded into transpose zones at `0x80004a0e` |

An earlier reading of this session had `0x306` down as the strip position,
on the strength of the factory snapshotting it into `state+0x312` the moment
the touch flag goes up (`0x8000ac5e`).  That snapshot is real, but it means
the opposite: it is gated on `state+0x39`, **edit mode**, and it exists so
that the two edit gestures in the manual can see a knob MOVE while the strip
is held -

- *"touch and hold a finger on the strip while turning the portamento knob"*
  sets the strip slew rate → `state+0x202` / `state+0x204` (`0x80003ea6`),
  the second being the same value with its bottom lifted into 150..330, which
  is the minimum slew the relative mode's snap-back runs at;
- *"Hold strip and turn ARP knob"* sets the pitchbend range →
  `state+0x214` (`0x80003f62`), which the factory defaults to `0xc` at
  `0x800070f2`.

Two more defaults set on the same line confirm the whole reading:
`state+0x218 = 1` and `state+0x219 = 2` at `0x800070e2` are the strip's CC1
and the preset knobs' CC2 that the manual names.

### The mode

`state+0x20c` is a word, loaded from the persisted settings record at
`0x8000a396`, and **0 = absolute, 1 = relative pitchbend**:

- The strip pass itself says so: mode 0 copies `0x1fe` straight to `0x1f8`;
  mode 1 offsets it against the gesture's start and centres it on `0x7ff`,
  and on release forces `0x1f8 = 0x7ff`.
- The two-ended-hold toggle at `0x8000af8a` agrees.  1 -> 0 sends pitch bend
  CENTRE and zeroes `state+0x216`, the bend added to the pitch; 0 -> 1 zeroes
  the strip's mod-wheel CC (controller `state+0x218`) and sets `state+0x1f8`
  to `0x7ff`, the centre.
- The two-ended hold is detected at `0x8000abaa`: segments 2, 3 and 4 quiet
  (sum <= 0x63) while segments 0 and 6 both read above 0xc8.  That is what
  puts 2 in `state+0x210`, and it is why the bend path wants `0x210 == 1`.

Record borrows the mode and gives it back.  The saved value is kept plus one
at RAM `0x622e`, so that zero means nothing is being held and a restore
cannot fire twice; the swap runs at the single point where the sequencer's
mode byte changes, so every way into and out of record goes through it.

Two things worth knowing about the borrow:

- **Reading the position never needed it.**  `state+0x1fe` is the raw
  position whatever the mode says.  The borrow is for the player's sake, so
  the strip behaves the same way in record as out of it, which is what was
  asked for.
- **A settings save during a take would persist the borrowed 0.**  The save
  path reads `state+0x20c` like any other setting (`0x80009e94`,
  `0x8000a142`).  Nobody is likely to save settings mid-take, and nothing
  guards it.

### Two things the mode change has to do besides change the mode

Both found by audit, both confirmed against the image before being fixed.

**Stop and clear have to end the NOTE.**  The sequencer's note is started and
ended by the arp's step function: the MIDI note-off, the gate and the trigger
light all live inside it at `0x80002218`-`0x800022c2`.  Stop and clear only
changed the mode and left the next step to tidy up - and the case where stop
matters most is exactly the case where there is no next step.  Set RATE to
zero and advance from an external pulse, or lock the divider to a clock and
then take the clock away, and the arp is not stepping: the gate sits at its
5 V sustain (`state+0x354` = `0x7ff`) and the MIDI note stays on until the
power does not.

`seq_release` now does what that step would have, with the factory's own
routines in the factory's own order: the 208 bus note-off when the bus is
carrying the note, both MIDI note-offs, the active-note flag, the gate
through `0x80002440` (which zeroes `state+0x354` and flushes it), the trigger
light, and our own deferred pulse at `0x60ee` so none outlives the stop.  It
runs whenever PLAY is what is being left - stop, clear, and record too.

**Record has to put away a bend before it borrows the mode.**  `bend()` only
writes `state+0x216`, the offset the pitch adds, on the relative side of its
own test at `0x80002edc`.  Once record has forced absolute, no value passed to
`bend()` can reach that cell - so a bend standing when record started was
added to every note of the take, for the whole take.  Entering record from
relative mode now runs the factory's own `1 -> 0` cleanup from `0x8000afee`:
zero `state+0x216`, and send pitch bend centre on both ports.

### The gate is a trigger, and the tie is what holds it (2026-08-27)

A Buchla pulse is a short 10 V spike that drops to a 5 V sustain only while
the note is HELD, and to 0 when it is let go.  The factory builds exactly
that: `0x800077f8` writes `0xfff` to `state+0x354`, and a timer scheduled
three counts later at `0x8000788a` runs `0x80007540`, which drops it to
`0x7ff`.

A sequencer step that is not tied into the next one is not held by anything,
so it should go to 0 there rather than sit at the sustain for the rest of the
step.  `seq_pulse_drop` is chained in front of that callback through its pool
word at `0x800078bc`: playing, and the step is not carrying a tie, it calls
`0x80002440` instead - gate to zero and flushed.  Everything else, including
recording and ordinary keyboard playing, gets the factory drop untouched.
Which is which is `seq_gate`'s decision, asked rather than repeated, so the
gate and the pulse can never disagree about the same step.

**A note after a tie retriggers.**  It used to be held - the 303 slide - and
is not any more.  The tie makes the note before it longer; the note after the
tie is a new note and gets its own spike, which is what the SH-101 does and
what "regular notes are pulses" means.

**Nothing slides with the portamento knob at zero.**  `seq_glide` asks the
knob first, and its deadzone now covers the tie's slide as well as the
ordinary glide.  With the knob up a tie still slides at `tie_glide_rate`.

**Entering a note in record sends a trigger.**  Recording silences the arp,
and with the arp on the keyboard has no pulse of its own, so a bar of notes
went in silent and unlit.  `seq_record` now sets the same deferred-pulse
countdown at `0x60ee` that every other pulse in this firmware uses, so the
trigger still waits for the pitch to reach the DAC.

**The pad-4 hold is a second**, not a second and a half: `chord_hold_scans`
200 at a 5 ms scan.

### The clock divider on a millisecond timebase (2026-08-27)

It counted 5 ms scans, so the shortest interval it could measure was 20 ms.
A 208 pulser at its top rate is **780 Hz - 1.28 ms** - which no scan counter
can see at all.  The counter at `0x61e6` is now incremented by
`clock_ms_tick`, chained in front of the factory's own 1 ms task callback
through the pool word at `0x80007da0` (the task is registered at
`0x80007c1c` with a period of 1).  Everything the divider measures is
milliseconds: the plausible window is `clock_min_ms`..2000, the release is
2000, and the countdown refresh no longer converts.

**An uneven clock is not divided - every pulse passes through.**  That is the
honest answer when there is no steady rate to take a fraction of, and it is
what the lock has always been for.  What was wrong was how easily it locked:
two agreeing intervals, and a clock with no rate at all still throws the odd
matching pair.  Measured against random spacings, a run of 2 divided **every**
such clock; 3 divided 90% of them; 5 divides 8%.  `clock_lock_pulses` is 5,
and the cost is settling time - a steady clock passes that many pulses at 1:1
before it starts dividing.  No run length makes a mistake impossible.

### A knob does one thing at a time (2026-08-27)

Holding a preset pad and turning its knob sets that pad's voltage, and while
that is happening the knob's OTHER job has to stand still.  It did not:
setting preset voltage 2 wound the arp's rhythm randomness up with it.

The editor already says when it is happening - `0x614a + pad` is set for
exactly as long as that pad's voltage is following its knob - so the arp knob
latches at `0x80019d44` and the vibrato latch at `0x8001a350` now test it,
per knob rather than all at once, so holding pad 1 does not freeze knobs 2
and 3.  Edit mode still suspends all four, as before.

### Hearing what goes into a take (2026-08-27)

Recording silences the arp, which is right - an arpeggiator chewing on what
you hold is not what you are listening for - but silence was not right
either: a bar of notes went in with no pitch, no gate and no light, and
nothing said which key had landed.

The note-on leaves its key at RAM `0x6230` (plus one, so the cleared state is
"nothing waiting"), and the per-scan cave steps the arp once for it with
R12 = -1: step now, do not reload.  The selector answers that step with the
waiting key and spends it, so the arp's own steps after it still sound
nothing.  Everything else - the pitch, the gate, the trigger, the MIDI note -
comes from the factory's own note machinery, already paired, rather than from
a pulse fired on its own.

### The randomisers reach the sequence (2026-08-27)

**Random octaves.**  `seq_pitch` called the octave randomiser first and then
replaced its answer with the step's pitch, so knob 3 displaced played notes
and never sequenced ones.  The order is the other way round now: the step's
pitch is chosen first and the randomiser runs on it, so knob 3 means the same
thing whichever is sounding.  The recording keeps the pitch it was played at
- the displacement is per playback, as it is for the arp.

**Random note order.**  While playing, the next step used to be the next one,
always.  Both advance sites in `seq_select` now go through `seq_next_step`,
which applies knob 1's BLEND law to the sequence: the knob is the chance, out
of 128, that the next step is any step rather than the one after this.  At
zero it is the recorded order exactly.  The draw is scaled by
`(draw * count) >> 8` rather than divided, so it needs no `DIVU`.

Knob 1's other setting - the six note-order zones - is the **keyboard's
alone**.  A recorded sequence keeps the order it was played in whatever the
zone says, and the arpeggiator's own selector is untouched; the zones build
emits `seq_next_step` as a plain walk.

Rests and ties come along unchanged and still mean what they meant: a tie
holds whatever is sounding and a rest silences it, whichever note the shuffle
has put them beside.

### Three from audit, and the hole that let two of them through (2026-08-27)

**Playback repeated its first step.**  Both advance sites in `seq_select`
called `MCALL PC[0x8001b438]`, and that pool word is the RECORDING answer,
not `seq_next_step`: the helper returned -1 in R12 and left R9 exactly as it
found it, so the index was written back unchanged and step one played for
ever.  The advances read `0x8001b43c` now; the recording call keeps
`0x8001b438`.

**Entering record from relative strip mode did not enter record.**  The bend
centre sends destroy R8..R12, and R9 was carrying the mode being entered - so
`ST.B R12[0x4],R9` wrote rubbish and the sequencer never went into record at
all.  R10, the MIDI port, was destroyed by the first send and the second one
went wherever that left it.  R9 goes on the stack across both sends now, and
the port is kept in R0, which a callee has to preserve.

**A 780 Hz clock lost most of its notes.**  The arp step tests the countdown
against the gate-off threshold BEFORE it chooses a note, and returns there.
At a 1 ms interval with a divisor of one the refresh lands on exactly 3,
which is that threshold, so the forced pulse was swallowed.  `clock_pulse`
now lifts a countdown below 4 before it steps.

**What let the first two through** was the emulator, not the review: calls to
routines outside the dumped window were modelled as leaving every register
alone.  They are calls, and R8..R12 are caller-saved - a cave that keeps a
live value in one of them across a call is broken.  The emulator fills them
with rubbish now, which fails that immediately.  The scenarios that missed
these also tested the routine rather than the call site; playing a whole
sequence THROUGH `seq_select`, and entering record all the way through
`seq_enter`, is what the suites do now.

### The divider drops chatter instead of playing it (2026-08-27)

A pulse arriving too soon after the last one used to fall through to "no rate
here, so pass everything" - which STEPPED THE ARP on it.  Every burst of
chatter therefore became a burst of notes, and that is what made a fast clock
glitch and flash lights that had nothing to do with it.  It is dropped now:
not counted, not stamped, not stepped.

The 208's pulser puts out a falling sawtooth, and whatever thresholds it
chatters as the slope crawls back through the trip point - which is why a
square wave behaved better than the sawtooth it is meant to be driven with.

The window is FLAT rather than a fraction of the measured rate.  Scaling it
was tried and reverted: an uneven clock is allowed here, and a short gap
after a long one is a real pulse rather than a bounce, so a rate-scaled
window swallowed pulses the divider is supposed to pass through.  A flat
window is only as wide as the chatter it exists to kill.

`clock_min_ms` is that window, 1 ms by default - the floor that still keeps up
with the pulser's top rate of 780 Hz, or 1.28 ms.  A slow slope that still
gets through wants it raised, and the cost is the fastest clock the divider
will follow.

Beyond that there is nothing the firmware can do about the difference between
a bounce and a pulse: they are the same signal.  A CV-to-pulse converter is
what the 218e's own manual recommends for the pulser, and it remains the
right answer for a slope this slow.

**Still unexplained: unrelated LEDs.**  The chatter bursts account for lights
flashing at a fast clock, and dropping them should take it with them.  If it
survives, note that divide-by-one at 780 Hz asks the arp to step 780 times a
second - every step is a note-off, a note-on, a gate, a MIDI pair and a
rescheduled 3 ms timer - and that is the instrument being driven far past
anything the factory expected, not a bug in the divider.  Dividing down is
what the knob is for.

### Full audit (2026-08-27, after the shuffle register bug)

Run over BOTH built images - the blend build and the zones build - since they
emit different code.  Mechanical where a sweep could be written; each hit
eyeballed against the source.

- **Register liveness across every `MCALL`.**  Fourteen candidates in the
  blend build, twelve in the zones build.  One real - the shuffle holding the
  blend, index and count across the PRNG, fixed at `1fabc2a` - and the rest
  are return values or callees that push what they preserve
  (`first_use_initializer` keeps R8..R12 by design, the proximity estimator
  pushes R9, the vibrato helper takes and returns R11, `seq_release` and
  `seq_next_step` keep their caller's registers by documented contract).
- **Stack pairing: every `STM` against its `LDM`.**  All balanced.  Two
  scanner flags were the scanner's own: `first_use_initializer` pops its
  seven registers at a tail it reaches by `RJMP`, which address order
  misattributes to the migration cave; and the arp gate hook's pop-to-LR is
  the documented flags-preserving pattern.
- **Every branch target in the caves lands on an instruction.**  All do.
- **The first-use clear's reach, from the image:** 0x99 halfwords from
  `0x6100`, through `0x6231` - both cells this week added are inside.
- **One latent bug found and fixed:** the key a note-on leaves at `0x6230`
  for the record-sound scan survived a mode change, because the pad loop runs
  before the record-sound call in the same scan.  A press ending a take could
  leave the last note pending, and the NEXT take opened by sounding a note
  nobody played into it.  `seq_enter` clears the cell with the other take
  transients now.
- **Noted, not fixed:** the divider's millisecond counter is a halfword, so
  after ~65.5 s of silence a first pulse landing in a ~1 ms wrap window can
  be eaten by the dead time - once, with probability about 1 in 65,000, and
  the next pulse recovers.  Not worth a word of RAM.

### The divider against the pulser's own recordings (2026-08-27)

Four recordings off the instrument - left the pulser, right the trigger out,
RATE knob at each end, pulser slow (~0.85 Hz) and fast - and they told one
story.  The divider NEVER LOCKED: at knob 0 the output fired on every input
pulse with a phantom note midway between them, and at knob max it was a
continuous ~670 Hz spray with brief dips after each real pulse.  The phantom
and the spray are the same thing - the arp's INTERNAL timer free-running at
the knob's own tempo, because standing it down was gated on the lock the
chatter never allowed.

Two causes, two fixes:

**The dead time now scales with the clock.**  A falling sawtooth's slow edge
drags through the trip point for longer the slower it runs, so the chatter
scales with the period, and the flat 1 ms window killed none of it at
0.85 Hz.  The window is a 64TH of the clock's own measured interval, floored
at `clock_min_ms`: 18 ms against the slow pulser, still 1 ms at 780 Hz.  An
uneven clock still passes - only a gap under a 64th of the last one is
treated as a bounce, and that is not a clock anyone patches.

**The internal timer stands down while a clock is PRESENT, not locked.**
`clock_gate` (`0x8001baf0`) hooks the factory arp step's own is-it-time test
at `0x800021ce` - the sixteen replaced instructions are exactly that test,
both halves reproduced: a pulse-driven step (interval -1) always proceeds, a
running countdown always holds.  What changes is a countdown that has RUN
OUT: it proceeds only when no clock is about (the divider's presence byte at
`0x61ec`, held by any plausible pulse, cleared by the two-second release).
While one is, the beat belongs to the pulses and the countdown is pushed one
clock interval ahead - gate-off still rides it down, so notes still end.
The cave is a leaf that reads the caller's own frame (`R7[-0x10]`) for the
interval argument; the buried-entry-point check confirms nothing else in the
factory jumps into the replaced range.

**What the sawtooth still does, and firmware cannot help:** the slow edge's
threshold crossing is a real event to a comparator, so a sawtooth presents
TWO pulses per period - the reset and the crossing - and the divider now
locks onto that doubled, slightly-lopsided clock and divides it steadily.
Everything aligns to the input and nothing free-runs, but the division base
is twice the pulser's rate.  The 218e manual's own advice stands for
perfection: a CV-to-pulse converter on the pulser's sawtooth.  A square wave
in is exact.

Verified by driving the built image's bytes with a model of the recordings:
the sawtooth-with-crossing at 0.85 Hz locks and divides with not one
internal step once the clock appears, at both knob ends; the clean square
divides by eight; 780 Hz still passes its own dead time; the uneven set
passes through undivided; with no clock the internal timer beats exactly as
before, and it returns two seconds after a clock stops.

### The divisor reads the knob, not the jack; and clocked triggers fire sooner (2026-08-27)

Two more recordings (the ARP pair, pulser swept, both knob ends) showed
triggers dropping at BOTH ends, knob-independent.  The divisor was reading
`state+0x2ee6` - and the factory writer at `0x80002b62` shows what that is:
`table[knob] + state[0x2f2]/2`, clamped.  **Neither half is the knob
position.**  The knob half is the tempo table's OUTPUT - nonlinear, and not
in the direction a divisor wants - which is why the division looked
knob-independent.  The CV half is the arp-rate CV input: the owner corrected
an earlier reading here - it is NOT the pulse jack but a separate input (the
218K+ has its own CV jack beside the orange pulse one; on a stock V3 it is
only reachable by the Appendix IV reassign or resistor mod), near zero when
nothing is patched there.  The divisor now reads **`state+0x2fc`, the rate
knob's own raw channel** - the knob as a position, polluted by nothing.

**And the nine milliseconds.**  Measured from clock pulse to trigger out,
and it is the deferred-pulse design, not the divider: a trigger waits for
the next 5 ms scan to put the pitch in the DAC, then `gate_settle_scans`
(default 1) more for the output RC - 5..10 ms, ~7.5 mean.  Right for a key
under a finger; most of the latency under a clock.  While a clock is about
(the presence byte again), the deferred trigger now fires AT the pitch
store: the DAC already holds the new note and only the RC (tau 0.9 ms) is
still moving.  Lag from pulse to trigger is the scan alignment alone,
0..5 ms.  `clock_settle_scans` (default 0) puts the wait back if octave
jumps under a clock turn out to slew audibly.  The keyboard path is
untouched, and a countdown already in flight is still never restarted.

### The DFU re-enumeration failure, diagnosed and worked around (2026-08-27)

Caught in the act with the two commands the flasher's failure asked for:
after the DFU SysEx, `ioreg -p IOUSB` showed the XHCI controllers with **no
children at all** - no `218e`, no `AT32UC3B` - and dfu-programmer said "no
device present".  So the instrument detaches from USB when it jumps to the
bootloader and sometimes never re-attaches as anything; the keyboard is dead
until a power cycle, which brings it back in application mode with nothing
written.  Intermittent, and reported as getting more frequent with heavy
flashing.

**The device side is factory code and stays untouched.**  The DFU entry path
is the recovery path: a bug introduced there could cost the ability to enter
DFU at all, this repo's rule is that nothing is ever tested against the
instrument by the assistant, and the failure is recoverable.  Do not patch
it blind.  For whoever picks this up with the owner's hands on the hardware:
the watchdog helpers live at `0x8000c1a8` (arm, keyed 0x55/0xAA writes to
WDT CTRL at `0xFFFF0D30`) and `0x8000c1d8` (disable), with a
timeout-computing caller at `0x8000c260`; the FLASHC sits behind
`0xFFFE1400` immediates around `0x80010552`.  The SysEx manufacturer id is
parsed byte-at-a-time, not stored, so the handler wants finding through the
MIDI dispatch, not a byte search.

**The macOS flasher now recovers by itself.**  A failed re-enumeration used
to be a dead end: power-cycle, rerun the whole script.  The DFU step is a
loop now, up to four attempts: when the DFU device fails to appear and the
218e has vanished from USB entirely, it says to power-cycle, waits up to
five minutes for the instrument to come back on MIDI (or for the DFU device
to appear on its own), and re-sends the request.  Staying in application
mode after the request, and a missing MIDI port at the start, still fail
immediately as before.  The Windows flasher is left alone: its failure flow
is built around WinUSB driver binding and already pauses for the user, and
there is no way to test a change to it from here.

### The hang, and the two holes that let it ship (2026-08-27)

`d0554ef` bricked the running instrument: it booted and enumerated on USB,
and nothing on the panel did anything.  The cause was one instruction.

`pulse_defer_set` is a LEAF - it returns with `MOV PC,LR`, having never
saved LR.  Making it call `clock_settle` put an `MCALL` inside it, and
`MCALL` writes LR.  The call returned, and then `MOV PC,LR` jumped to the
address right after the `MCALL`... which is the `MOV PC,LR` itself.  A
two-instruction infinite loop, entered by the first trigger request any key
press makes, inside the main loop.  USB is interrupt-driven, so the
instrument still appeared on MIDI while the panel was dead - exactly the
symptom.

The fix: `pulse_defer_set` is a leaf again, unchanged from before; the
clock-aware settle choice lives in `clock_settle` as its own leaf holding
the whole defer decision, and in a divider build the four pulse-caller pools
point THERE instead.  One indirection, no nesting, both ends leaves.

**Hole one: the emulator treated a hang as a pass.**  `run()` looped up to
its instruction limit and then fell off the end and returned normally, so an
emulated infinite loop was indistinguishable from a clean return.  It raises
now, naming the entry and where the PC got stuck - and the limit went from
400 to 2000 so a long legitimate path cannot trip it.

**Hole two: the audit sweep had no check for this shape.**  It now walks
every `MOV PC,LR` in the caves, scans back to the nearest boundary, and
flags any `MCALL` reached with LR unsaved.  Zero hazards across the image
after the fix - and it catches the exact bug that shipped, which is the only
evidence that it works.

### The double rate: it was watching a level, not an edge (2026-08-27)

The owner asked the right question - "why are we watching crosses? we need
the rising edge" - and the image says that is exactly the trouble.  The
pulse input is a GPIO **pin-change** interrupt on line 5, and the factory
handler at `0x800072e4` decides which edge it was by SAMPLING THE LEVEL:

```
if (IFR bit 5)            // a transition, either way
    if (PVR bit 5)        // and the pin reads high right now
        gate off; post(event 10)
```

That is right for a clean edge and cannot be right for a slow one.  A 208
pulser's falling sawtooth crawls back down through the trip point and
chatters there; the ISR keeps sampling HIGH, and the crossing posts a second
event.  Measured off the instrument: a 181 ms pulser, steps at +80 ms and
+164 ms - dead on double rate, one real beat and one crossing.

**The edge is reconstructed in firmware.**  `clock_ms_tick` reads PVR once a
millisecond (GPIO port 0 at `0xFFFF1000`, PVR at `+0x60`, straight out of the
factory's own accessor) and counts the milliseconds the pin has read LOW.
The divider accepts a pulse only when that count has reached
`clock_rearm_ms`, and clears it on acceptance.  The crossing never has a
count - the pin is high from the beat until the crossing, and the dip inside
the crossing's own chatter is over in far less than the run.

The count is a LATCH, cleared only by an accepted pulse - never reset when
the pin reads high.  Resetting on high looks right and races the beat: the
pin goes high exactly when the beat arrives, the event waits its turn in the
dispatcher, and a tick landing in between would zero the count and throw the
beat away.  The emulation caught that, with the pin modelled and the tick
landing on the edge.

The period test stays as the other half: at a fast enough clock the low
stretch is shorter than the millisecond that watches for it, so a whole
period elapsing (`clock_hysteresis_eighths`, seven eighths) is the other way
of knowing a beat arrived.  It needs a rate, so it only applies once locked.
Between them the two cover each other's blind spot - the pin for slow clocks,
the period for fast ones - and an unlocked, uneven clock still passes
everything on the pin alone.

**Verified against the built image with the pin modelled**: a sawtooth
crossing twice a cycle gives exactly one step per cycle at 60, 181, 500 and
1170 ms, and at every duty from 10% to 75%.

**The honest limit** is the counter's resolution.  At the pulser's very top
rate, 780 Hz, both edges of a 1.28 ms cycle land inside the same millisecond
and cannot always be told apart; it runs, and may run doubled.  Everything
from 60 ms up is exact.

## Strip archaeology (2026-08-27)
- `bend(R12 = value)` `0x80002e30`, reached through the pool word at
  `0x8000335c`.  Early-exits when the value has not changed, so hooking it
  gives every change and nothing else.
- It writes `state+0x216`, which the pitch update adds to the pitch at
  `0x800031f4`; the strip's own computed value is mirrored at `state+0x35e`.
- The chain is: seven segments -> centroid (`0x8000aa98`) -> `state+0x1fe`,
  the position 0..4095 -> `state+0x1f8`, the output, absolute or centred by
  `state+0x20c` -> slewed against `state+0x202`/`0x204` by `0x8000c150` ->
  `bend()`.  `state+0x202` and `0x204` are the SLEW RATE, set by the
  portamento knob in edit mode, not raw halves of anything.
- The touch flag `state+0x206` goes up at `0x8000ac4a` and down at
  `0x8000acb6`, both inside the factory's strip pass at `0x8000aa74`.
  Watching that flag every scan is what turns a release into an event;
  `bend()` cannot, since it only ever fires on a change of value.
- Entering and leaving are our own chord handler now, not the toggle's change
  callback - simpler, and entirely under our control.
- RAM: the hold counter (halfword), one byte for armed/selected, one byte for
  the sequencer mode, the step store, and a halfword for the borrowed strip
  mode.  Declared in RAM_REGIONS.
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
  (As built, this did not happen: the divider keeps its OWN counter at
  0x61e6 in clock_scan, gated on clock_divide, and the sequencer's blink
  counter stays at 0x615e.  Two counters, each owned by its option -
  simpler than sharing, at the cost of two bytes of RAM.)

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
- ~~Pitch-bend strip~~ DONE: position at `state+0x1fe` over 0..4095, output
  at `state+0x1f8`, touch at `state+0x206`, mode at `state+0x20c`, bend
  entering the pitch at `state+0x216`.  See the rests-and-ties section above.
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
