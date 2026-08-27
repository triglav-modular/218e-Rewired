# Handoff — 218e Rewired 2.0

Written 2026-08-27. Branch `2.0`, last commit on that branch. Everything
below is either in the repo or reproducible from it; nothing here is a memory
of a conversation.

## Where it stands

**Current clock fix:** see [CLOCK.md](CLOCK.md). The interrupt-timestamped
capture/FIFO replaces the sampled-low and rejection-budget experiments
described below. It explicitly enables both GPIO edges, fixes literal-pool
fallthrough, latches division independently of interval confidence, and
serializes note/pitch/trigger output. The requirement is 0.5–200 Hz.
Run `python3 tools/test_clock.py` against fresh produced firmware.
This revision still needs the owner's hardware check.

Phases 0–5 of [PLAN-2.0.md](PLAN-2.0.md) are built and on the owner's
instrument: `.kbm` keyboard maps and period-aware octave controls, preset
voltages decoupled from the knobs, the four knob roles, the step sequencer,
and the external clock divider. The plan records the feature decisions and
archaeology; [CLOCK.md](CLOCK.md) supersedes its older clock experiments.
Read the history before touching anything near the arpeggiator.

The half-done thing from the last handoff is done: **rests and ties are read
from an absolute strip position.** Touch the strip while recording and let
go — below halfway a rest, above halfway a tie, one step per touch. Record
borrows the strip's own mode (`state+0x20c`) for the length of a take and
gives it back. What the old version did — push one way for a rest, the other
for a tie, thresholded on the bend value — is gone; it is in the history at
`034cdec` if it is ever wanted back.

The blocker the last handoff named was the range of the strip position, and
it is settled — but the cell it named, `state+0x306`, was the wrong one.
`0x306` is the PORTAMENTO knob, exactly as `pressure_blend` and `seq_glide`
always had it. The strip has no ADC channel at all: it is seven capacitive
segments, and the factory turns them into a centroid and maps that to
**`state+0x1fe`, 0..4095**, clamped there by its own mapper. Written in one
place, only while the touch flag is up, and raw in both strip modes. The
evidence, and the panel's six knobs against the six conditioned channels, is
in PLAN-2.0.md under "Rests and ties from an absolute strip position". Read
that before touching the strip; a session already lost a day to it.

Two audit findings landed on top of it, both confirmed against the image
before being fixed: stop and clear left the sequencer's note sounding when
the arp was not stepping (external clock, or RATE at zero), and entering
record froze a standing pitch bend into every note of the take.

Then a round of playing notes from the owner, all written up in PLAN-2.0.md:
the gate is a Buchla trigger now (a 10 V spike that drops to 0 unless a tie
is holding it), a note after a tie retriggers instead of being slurred into,
nothing slides with the portamento knob at zero, entering a note in record
sends a trigger, the pad-4 hold is a second, and the clock divider counts
milliseconds instead of 5 ms scans so it keeps up with a 780 Hz pulser. An
uneven clock is passed through undivided, and the run of agreeing intervals
it takes to believe a rate went from 2 to 5 - measured, not guessed: a run of
2 divided every uneven clock thrown at it.

Then: a knob no longer does its other job while a preset pad is using it, and
recording sounds what you play into it - the note-on leaves its key for the
per-scan cave, which steps the arp once so the pitch, gate, trigger and MIDI
note all come from the factory's own machinery.

And the randomisers reach the sequence: knob 3 displaces sequenced notes by
an octave the way it does played ones, and knob 1's blend shuffles which step
comes next. Knob 1's six note-order zones stay the keyboard's alone - the
owner's call - so a recorded sequence keeps the order it was played in.

**Not yet on hardware.** The owner flashes and reports; nothing below has
been played. `strip_halfway_units` is 2048 because 2048 is the middle of the
range the firmware clamps to, and the middle is the rule; it is a build
number so a strip reading off centre can be told where its own middle is.

**Waiting on the owner: the web page copy.** `web/index.html` around line 154
still describes pushing the strip one way and the other. It is now wrong, and
it was left alone deliberately — user-facing wording gets proposed, not
changed. A replacement is proposed in the session that built this; if that is
lost, propose a fresh one and wait.

## Other open items

- **DFU re-enumeration sometimes fails** - the instrument detaches on the
  DFU SysEx and the bootloader never re-attaches (ioreg shows nothing);
  power-cycle recovers, nothing written.  The macOS flasher now retries
  through a power cycle by itself, up to four attempts.  The device side is
  factory code and is deliberately NOT patched - it is the recovery path,
  and nothing here is ever tested against hardware.  Archaeology pointers
  are in the plan's DFU section if the owner ever wants it chased.

- **Preset persistence** — blocked on an empirical probe: does the factory
  flash driver at `0x800108fc` erase before writing? Specified in the plan,
  never run.
- **Settings over MIDI** — feasibility done (dispatcher event 32 at
  `0x80004fc2` carries an incoming message with both data bytes). The cost is
  that build numbers are compiled as immediates, so each one moved to
  runtime needs a RAM cell and a load. Good candidates are the numbers below.
- **Numbers never measured on hardware**: `tie_glide_rate` (60),
  `strip_halfway_units` (2048), `clock_min_ms` (4), `clock_rearm_us` (250)
  and `clock_lock_pulses`
  (5). All are build numbers precisely so they can move. `chord_hold_scans`
  is 200 because the owner asked for a second. For current clock timing
  values and their digital-input constraints, use [CLOCK.md](CLOCK.md).
- **The trigger spike is the factory's own**, scheduled with 3 at
  `0x80007888` (R10; the adjacent R11=2 selects the timer ID). The owner puts
  a Buchla trigger at ~4 ms; if a measurement
  says the unit is not what 3 assumes, that immediate is the one to move.
- **A settings save during a take would persist the borrowed strip mode.**
  Record forces `state+0x20c` to 0 and the save path reads it like any other
  setting. Nothing guards it, and nothing is likely to hit it.
- **Clock multiplication** — deliberately not built; MARF gets it by
  subdividing between pulses, which needs edge prediction. See the plan.
- **208p oscillator ceiling** — a builder-side warning was noted for later.

## How to work on this

`docs/BUILD.md` covers the pipeline. What follows is only the things that
have actually bitten, repeatedly.

**Verify against the built image, not the source.** Every firmware claim in
this repo was checked by disassembling the image and emulating those bytes.
`tools/avr32/sweep.py` builds 22 configurations through both toolchains;
`web/test_configs.py` compares 16 against the browser build;
`web/test_matrix.js` builds 768 combinations. Run all of them.

**Ghidra will disassemble the whole image for you.** `src/DumpDisassembly.java`
takes a start, an end and an outfile; the two code runs are
`80002000..80014288` and `80014400..80018bf4`. Grepping that dump for a state
offset — every reader and every writer of one cell at once — is how
`state+0x1fe` was found. `src/DisasmRange.java` does the same for a handful
of instructions.

**Read the manual before naming a control.** `state+0x306` was identified as
the strip position off a chain of real evidence, all of it consistent, and it
was wrong: the panel has six knobs, and the User's Guide says which gestures
set what. One look at the front panel would have stopped it. The instrument
is the ground truth the disassembly is being interpreted against.

**A leaf must stay a leaf.**  A cave that returns with `MOV PC,LR` has not
saved LR, and `MCALL` writes LR - so a call added inside one turns its return
into a jump to itself.  That shipped once, in `pulse_defer_set`, and bricked
the running instrument: dead panel, still enumerating on USB, because the
hang was in the main loop and USB is interrupt-driven.  `tools/test.py`-style
sweeps in the plan's audit section cover it now; if a leaf needs to make a
decision, give the decision its own leaf and repoint the callers.

**A call destroys R8-R12.**  They are caller-saved, and a cave that holds a
live value in one of them across a call is broken.  Two shipped that way in
one afternoon - the mode being entered, and a MIDI port - because the
emulator left registers alone for routines outside its dumped window.  It
fills them with rubbish now.  Scenarios should drive the CALL SITE, not the
routine: a pool word pointing at the wrong helper is invisible to a test that
calls the right one directly.

**Emulating a cave does not exercise its hook.** A clock hook that jumped to
an invalid address passed every emulation and the whole parity matrix,
because the emulations called caves directly and the matrix compares two
toolchains that were both told the same wrong thing. `tools/test.py` now
walks every `MCALL` in the image and checks the word it reads holds a code
address — that guard has since caught two more of the same. Trust it.

**`MCALL PC[x]` is memory-indirect.** It calls whatever the *word* at x says.
It needs a pool word, never the routine's own address. It also writes LR, so
a cave that calls another must have pushed LR first — and an emulator that
does not model that will report a bug that is not there.

**`LDDPC` only reads forward.** Its displacement is unsigned, so a pool word
behind the load will not encode. Put the pool at the end of the block.

**Two ways to accept is one too many.**  The clock divider grew three
independent acceptance routes - a qualified pin, an elapsed period, and
being unlocked - and each handed back what the others rejected: the period
route admitted any spurious event at seven eighths of the rate, and removing
the pass-through route stalled the divider dead (nothing accepted, so no
interval measured, so never locked, so nothing ever accepted).  When a
decision has one question, give it one answer path and make the exceptions
explicit state, not extra routes.  Beware especially of an exemption phrased
as a time window: BOTH directions were tried and both let the same artefact
through, because the thing being rejected always arrives sooner than the
thing being kept.

**Do not assume a timebase.** The arp countdown is decremented by a 1 ms task
(event 17, registered at `0x80007c1c`), not the 5 ms scan (event 2, at
`0x80007c0c`). A refresh written in scan units was five times too short and
survived three review rounds because the emulator faithfully modelled the
wrong rate it was told.

**Both toolchains must agree.** Ghidra accepts forms the browser encoder has
no rule for — `SUB Rd,Rs,-imm`, `BR{al}` — and only `--no-ghidra` catches it.

**Cave layout is manual.** `padTo` labels have to be computed with
`tools/avr32/encoder.js` rather than estimated; the build's "code crossed
target" and block-overlap errors are the backstop. When relabelling by text
substitution, never use a marker that is a prefix of another marker.

**One edit per script.** A Python edit script that asserts several times and
writes at the end loses *every* change when one assertion fails — and the
build then still succeeds, on the old source. That has silently discarded a
completed fix more than once. Write the file after each edit.

**New RAM must be declared** in `RAM_REGIONS`, and the first-use clear in the
initialiser cave must reach it. SRAM survives a DFU, so anything left out
starts as whatever the previous image left behind. The clear is a counted
loop from `0x6100`; its count moved to `0x97` for the borrowed strip mode.

**Repin after every change.** The init marker hashes the settings and the
assembler source, so any edit — a comment included — moves every image.
`config/218e.toml`'s `golden_sha256` and `sweep.py`'s `historical_config`
both need updating, `tools/avr32/make_corpus.py` re-run (it needs Ghidra),
and `web/generate.py` re-run. Do it once, after the last source edit.

## Standing instructions from the owner

- Never flash hardware; never point a DFU command at the instrument.
- Never ask for or handle credentials or API tokens.
- Version bumps are the owner's call alone.
- `private/` and `docs/internals/` stay gitignored; history is not scrubbed.
- Commit and push to origin without asking. Force-pushes need approval.
- Propose new user-facing copy and wait; edits that were asked for do not
  need approval.
