# Duophonic keyboard

Two voices from the main keyboard: the pitch, pressure and pulse jacks carry
voice 1, and three of the five secondary banana outputs carry voice 2.

**Status (2026-10-24): design, nothing built.** Every address below was read
from the factory image (`firmware/218eV3_v369_DFU.hex`) and the Ghidra dump in
`build/disasm/dump1.txt`; nothing has been measured on the instrument or run
in the emulator. What still has to be read or run is marked *(verify)*. The
owner's open questions are at the end, each with the default the build would
use.

**Decided by the owner (2026-10-24):**

- The jack mapping below.
- One key held sounds one voice. A lone key does not play in unison.

## What the hardware gives

**All eight outputs are channels of one octal 12-bit DAC** (LTC2620 format).
The transfer at `0x8000b9ac` loops over slots 0..7 and sends each as a
24-bit word, `0x300000 | address[slot] | value << 4`, with the slot-to-address
map at the pool word `0x8000ba90`. Every transfer writes all eight channels.
The 1 kHz flush (event 17) transfers, and so do the pulse routines, directly
(see [CLOCK.md](CLOCK.md)). All of them enter at `0x8000ba9c` with the
buffer in `R12` *(verify: every caller, including the clock's phase A)*.

**The pulse jacks are DAC levels, not GPIO.** The factory writes `0xfff` for
the spike and `0x7ff` for the sustain. In software any jack can carry a
pitch, a pressure or a pulse.

The buffer is eight halfwords from `state+0x354` (RAM `0x38b4`):

| Slot | Cell | Jack | Factory writers |
|---|---|---|---|
| 0 | `state+0x354` | Pulse | 13 sites, plus the deferred pulse, clock and sequencer caves |
| 1 | `state+0x356` | Pressure | the scan, redirected to the interpolator target at `0x6036` |
| 2 | `state+0x358` | Pitch | the pitch remap |
| 3 | `state+0x35a` | Preset value | pad handler `0x8000a9ce` (the stored preset at `0x6140` × 4), MIDI in `0x80008438` |
| 4 | `state+0x35c` | Preset pulse | pad press `0x8000a82e`, pulse routine `0x800078e8`/`0x800078f4` (timer task 6) |
| 5 | `state+0x35e` | Strip value | `seq_strip_led` only; the strip lamps follow it |
| 6 | `state+0x360` | Strip pulse | strip touch `0x8000aca8`, `0x80006632`, `0x8000765c`, `0x80007990` (timer task 7) |
| 7 | `state+0x362` | Velocity | keyboard note-on `0x80006522` and MIDI note-on `0x800081da`, both velocity × 32 |

No Rewired cave stores to slots 3, 4, 6 or 7.

**Only two jacks have been measured.** Pitch is 1 V/octave at 400.59
counts/V (2.5 V × 4.09), settling as a single pole with τ ≈ 0.9 ms. Pressure
is about 10 V at 4095. The pulse reaches about 10 V at `0xfff`, which
suggests the same output gain, but the stage behind each secondary jack is
unknown: its full scale, offset, filtering and noise. There is no 218e v3
schematic here; `~/SDIY/218r-Pressure-Smoothing/218_schematics.pdf` is a
different instrument (STM32F405).

## Jack mapping (decided)

| Voice 2 | Jack | Slot | Lost while duo is on |
|---|---|---|---|
| Pitch 2 | Preset value | 3 | the preset voltage output; add-to-pitch still works, it reads the stored preset, not the jack |
| Pressure 2 | Velocity | 7 | the velocity output |
| Pulse 2 | Preset pulse | 4 | the pad pulse |

The strip outputs stay as they are. Strip value is the wrong jack for a
pitch: it drives the strip's three lamps, and the sequencer's rest and tie
lamp feedback writes it.

## Phase 0: measure the three jacks

This decides whether the mapping holds, and it needs no new firmware.

| Jack | Set it with | Read |
|---|---|---|
| Preset value | hold a pad, turn its knob fully clockwise (4092) and fully counter-clockwise (0); set two presets to the two ends and switch pads for a step | DC at both ends and mid-travel; 10 to 90% rise and fall on the scope; noise AC-coupled at a steady level, beside the pitch jack |
| Velocity | MIDI note-on at velocity 127 (4064) and 1 (32), alternating | DC at both; rise and fall |
| Preset pulse | press a pad | spike height, sustain level, fall |

What pitch 2 needs from the preset value jack:

- **Range.** At least the 6.6 octaves of the 80-entry table. Below that, the
  table clamps the top notes.
- **Settling** comparable with the pitch jack's, about 2 ms 10 to 90%. A
  slower stage makes pitch 2 glide on every note, and no table fixes that.
- **Noise** comparable with the pitch jack's.

Gain and offset errors do not matter: voice 2's own calibration table
absorbs them together with the second oscillator's tracking.

If the preset value stage fails, the fallback is pitch 2 on strip value and
lamps that show voice 2's pitch.

## The factory's note choice

The keyboard is fully multi-touch, and each scan already has what two voices
need. Per-key touch state is at `0x3490+k` (2 means a finger is on it), the
press time of each key is at `0x325c+4k` against the clock at `0x2efc`, the
press-order list is at `0x6000`, and the per-key corrected pressure is at
`0x6100` (§1 of `docs/internals/FIRMWARE_INTERNALS.md`, which is local and
not in the repository).

The mono chain plays **last-note priority, returning to the most recent held
key**:

- **A press** makes the new key the sounding note. `0x800062b4` reads the
  key table at `0x854` into `state+0x350`/`0x352` and publishes the live
  note at `state+0x2e1`.
- **A release** that is not the sounding note changes nothing. That is the
  comparison against `state+0x2e1` at `0x800060e4`.
- **Releasing the sounding note** with other keys down calls `0x8000596c`,
  which picks the key in touch state 2 with the smallest `now − press time`.
  That key goes to `state+0x256` and is re-sounded through `0x800062b4`
  (`0x800061f0`..`0x8000622e`). With no key down, the gate drops.

Whether that return fires a new pulse is not established *(verify in the
emulator)*.

## Why the cheap allocation does not survive one key = one voice

The first proposal was the cheap one: voice 1 is the factory's own note,
voice 2 the oldest other held key. That left the factory chain untouched, but
only because a lone key played in unison. Press A and both voices sound A;
press B and voice 1 moves to B while voice 2 keeps A. Nothing hops.

With one key, one voice, the same rule moves notes between oscillators. A
alone sounds on voice 1. Pressing B moves voice 1 to B, and A has to appear
on voice 2: oscillator 2 attacks a note that was already sounding. Releasing
B hops A back to voice 1 and drops voice 2.

So a voice has to keep its key for as long as that key sounds. The jacks
can no longer follow the factory's choice of note.

## Allocation (default, open question 1)

**The two voices play the two most recently pressed keys still held, and a
key keeps its voice for as long as it is one of the two.**

- A press takes a free voice, voice 1 first. With both busy it takes the
  voice playing the older of the two notes. That key stays held, silently.
- Releasing a sounding key frees its voice. The freed voice takes the most
  recently pressed held key that is not sounding: the factory's own return
  rule, applied per voice. With none, the voice falls silent.
- A lone key stays on the voice it was on (open question 2).

Sequences, with the voices as (1, 2):

| Event | Voices |
|---|---|
| press A | (A, -) |
| press B | (A, B) |
| press C | (C, B): A was older |
| release C | (A, B): A is the most recent unsounding key |
| release A | (-, B): B stays on voice 2 |
| press D | (D, B) |

## Design

### One choke point for the jacks

A duo hook at `0x8000ba9c` hands the transfer a **duo output buffer**
instead of the factory's: a copy of `state+0x354..0x362` with the duo slots
(0, 1 and 2 for voice 1; 3, 4 and 7 for voice 2) laid over it.

- No factory writer has to be patched. The pad handler, the MIDI-in
  writers, the note-on velocity store, the pulse routines and the 13 gate
  writers keep writing the factory buffer, and every factory and Rewired
  read of that buffer sees what it sees today.
- With duo off, the hook passes the factory buffer through, so the outputs
  are byte-identical to today's.
- What has to hold *(verify)*: no transfer bypasses `0x8000ba9c`, and the
  clock's phase A staging, which captures and restores slot 2, is not active
  in duo (it is not, if duo requires the arp off; see Interactions).

This replaces redirecting the factory writers one by one, the way
`pressure_target_redirect` and `strip_dac_redirect` do.

### Pitch: both voices as offsets from the factory's note

The factory chain keeps choosing and composing its note: the tuning slot's
key table, the octave pads, the trn transpose, preset add-to-pitch, the
transposer jack, the strip bend and the vibrato. Each voice's pitch is that
pitch plus `table[key_v] − table[key_factory]`, applied after the glide
engine, which is the technique `blend_offset_apply` already uses for
pressure portamento. Every transposition reaches both voices for free.

- **Glide is forced to snap in duo.** Otherwise the factory's note gliding
  under an instant offset would bend both voices on every press.
  `glide_rate_clamp` already forces it with the blend enabled.
- **Each voice has its own pass through the remap:** voice 1 with the
  existing table to slot 2, voice 2 with a second 80-entry table to slot 3.
  The second pass must enter past the remap's head call to `0x8001A2E8`,
  which advances the vibrato, the tuning applier and the per-scan
  housekeeping. Entering at the head runs those twice a scan. The clock's
  fast path already enters at `0x8001998E` for this reason *(verify that
  the remap can run on a supplied pitch and table and return its value
  instead of storing slot 2)*.
- The pitch floor, `-remapOffset`, applies per voice.

### Gates

Each voice's pulse rises when that voice takes a key and falls when it falls
silent. It uses the factory's shape: spike, then the `0x7ff` sustain while
held. The duo cave owns both.

- The factory's own gate keeps running in the factory buffer and simply
  never reaches the jack in duo.
- Both pulses are deferred like pulse 1: the voice's pitch is in the buffer,
  then `gate_settle_scans` pass. Pulse 2's settle depends on phase 0's step
  response.
- A voice taking a held key on a release fires a new spike by default (open
  question 3).

### Pressure

- **Voice 1** reads only its own key. In duo, `pressure_cache` selects the
  voice-1 key's corrected sample instead of the `multi_key` aggregate, which
  would mix the two voices.
- **Voice 2** gets a second growing-average ring, the same curve, and a
  second 1 kHz interpolator in the event-17 wrapper, writing the duo buffer's
  slot 7. Its ring resets when voice 2 takes a key, as the note-on wrappers
  reset voice 1's.
- MIDI channel pressure keeps playing voice 1 while no key is touched.

### State and cost

About 100 bytes of RAM: two voice keys, two gate states, a second filter ring
of up to 24 halfwords plus its sum and count, a second interpolator target
and counter, and the duo buffer. The declared map ends at `0x6e12`, and the
deepest stack measured (2026-09-13) reached 140 bytes below `0x7800`. Flash
is not a constraint: about 100 KB is erased above `0x80024260` in the last
image built here (2026-10-06).

CPU per scan adds a second remap pass, the allocator's walk of 29 keys and a
second filter and curve; each millisecond adds a second interpolator step.
The 68.6% main-loop load in [BUILD.md](BUILD.md) predates most of the caves,
so `scan_profiler` has to measure this again before and after.

### Settings and the page

Duo is an option like the others: data in `settings_numbers`, one code image
([SETTINGS.md](SETTINGS.md)).

- The record gains the duo byte and voice 2's 80-entry pitch table *(verify
  the layout's room; this is a layout version change)*.
- The page's tuning run needs a voice choice, so calibration mode
  (`0x6d40`/`0x6d44`) plays its entry on voice 2's jack.
- The page gains a duo setting. Its copy goes to the owner for approval
  before it is written.

## Interactions (defaults)

| Feature | In duo |
|---|---|
| Arpeggiator, latch | Duo applies only with the arp switch off; in latch or regular the keyboard is mono, as today |
| Sequencer | Mono while a take plays or records, as today |
| Pressure portamento | Bypassed; the portamento knob does nothing |
| Glide | Forced to snap |
| Strip bend, vibrato, octave pads, trn, transposer jack, preset add-to-pitch, tuning slots | Both voices, through the offset |
| `multi_key` | Ignored; each voice reads its own key |
| Preset editing | Still edits the stored presets; the preset jacks carry voice 2 |
| MIDI out | Unchanged (open question 7) |
| Clock divide | Not involved: it drives the arp, which is off |

## Verification

- **A duo regression on the real touch handlers**, in the manner of
  `ControlRegression`. For press and release sequences it reads the duo
  buffer at each transfer: the allocation table above, every held key keeping
  its voice, one key sounding one voice, each pulse rising only for its own
  voice and after its pitch, and each pressure following its own key. It must
  be able to fail, so it runs first on an image with the hook passing the
  factory buffer.
- **Duo off is today's firmware**: the factory buffer reaches the transfer
  under every existing configuration, and the existing suites pass
  unchanged.
- One repin tail for the whole feature, with the suite list taken from the
  workflows.
- On the instrument: both voices tuned by the page, then two-key playing.

## Order of work

1. Phase 0 measurements (owner). Go or no-go on the mapping.
2. The transfer hook, the duo buffer and the setting, with the off path
   proven identical.
3. Allocator and both pitches.
4. Gates.
5. Pressures.
6. The second table, the settings layout and the page.
7. Interactions, the regression, the repin tail.

## Open questions for the owner

Each has the default the build uses if it is left unanswered.

1. **Stealing a voice for a third key.** Default: take the voice playing the
   older note, so the voices are always the two most recent held keys.
   Alternatives: always take voice 1, as the factory does; or ignore a third
   key.
2. **A lone key after a release.** Default: it stays on the voice it was on.
   Alternative: it moves to voice 1, which is a hop.
3. **A voice taking a held key on a release.** Default: a new spike. The
   alternative is sustain without one; the factory's mono return should be
   matched once it is known *(verify)*.
4. **Duo with the arp or sequencer running.** Default: suspended, mono as
   today.
5. **Switching duo on.** Default: a page setting. Alternative: an edit-mode
   gesture.
6. **Tuning voice 2.** Default: its own table, tuned by the page's tuning
   run through the preset value jack.
7. **MIDI out.** Default: unchanged. Alternative: voice 2 on the next channel.
