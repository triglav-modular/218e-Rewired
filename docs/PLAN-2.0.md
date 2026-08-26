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
- Latch match tolerance guard scaled to the smallest degree spacing
  (24-tet needs <= 10 units; config allows 30 today).
- Page UI: one optional .kbm beside each .scl slot.

### 2. Decoupled preset voltages
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
  mirror] OR [1.x continuous press->random blend].
- Knob 2: swing | randomness (1.x) | patterns.  Pattern engine: gate mask
  + length (1..32), stepped at the arp clock.  Preload: 22 CLIX fills from
  ~/SDIY/208_Clockwork_Card/Clockwork_Code/clix.h.  Page: grid editor,
  settable length, plain-text x.x.. import/export.
- Knob 3: stays arp octave span, untouched.
- Knob 4: vibrato (1.x) | octave switching.

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

## Archaeology (all in the factory binary)
- Add-to-pitch toggle: state address + how the source selection is applied
  (forcing point for octave mode).
- Pitch-bend strip: touch/position state, and where its bend enters the
  pitch path (to suspend in record and read ends for rest/tie).
- Arp rate knob: mirror address + rate handler (for the divider takeover).
- External clock input: where pulses arrive (interval measurement site).
- Settings record at 0x0968: length and spare bytes (preset storage).
- Stack low-water mark vs RAM plan (sequence buffer past 0x613a).

## Infrastructure
- Branch 2.0 (pushed).  Add it to firmware.yml + windows workflow
  triggers; NOT pages.yml — the site deploys from main only.
- Version 2.0.0 on this branch; goldens diverge per branch, repin on
  every cherry-pick.
- Order: 0 infra, 1 kbm, 2 presets, 3 knobs, 4 sequencer, 5 clock divide.
  Test hexes per phase on the owner's config, as in 1.x.
