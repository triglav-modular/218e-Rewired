# Handoff — 218e Rewired 2.0

Written 2026-08-27. Branch `2.0`, last commit on that branch. Everything
below is either in the repo or reproducible from it; nothing here is a memory
of a conversation.

## Where it stands

Phases 0–5 of [PLAN-2.0.md](PLAN-2.0.md) are built and on the owner's
instrument: `.kbm` keyboard maps and period-aware octave controls, preset
voltages decoupled from the knobs, the four knob roles, the step sequencer,
and the external clock divider. The plan document is the source of truth for
what each one does and why; it also carries the archaeology, which is worth
reading before touching anything near the arpeggiator.

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

- **Preset persistence** — blocked on an empirical probe: does the factory
  flash driver at `0x800108fc` erase before writing? Specified in the plan,
  never run.
- **Settings over MIDI** — feasibility done (dispatcher event 32 at
  `0x80004fc2` carries an incoming message with both data bytes). The cost is
  that build numbers are compiled as immediates, so each one moved to
  runtime needs a RAM cell and a load. Good candidates are the numbers below.
- **Numbers never measured on hardware**: `tie_glide_rate` (60),
  `chord_hold_scans` (300), and `strip_halfway_units` (2048, principled but
  unplayed). All are build numbers precisely so they can move.
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
