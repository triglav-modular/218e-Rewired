# Handoff — 218e Rewired 2.0

Written 2026-08-27, at the end of a long session. Branch `2.0`, last commit
on that branch. Everything below is either in the repo or reproducible from
it; nothing here is a memory of a conversation.

## Where it stands

Phases 0–5 of [PLAN-2.0.md](PLAN-2.0.md) are built and on the owner's
instrument: `.kbm` keyboard maps and period-aware octave controls, preset
voltages decoupled from the knobs, the four knob roles, the step sequencer,
and the external clock divider. The plan document is the source of truth for
what each one does and why; it also carries the archaeology, which is worth
reading before touching anything near the arpeggiator.

The owner flashes and reports. Several rounds of that have already landed —
the trigger LED, the arm time, record appending rather than wiping, the pad
chord staying armed. Expect more.

## The one thing that is half-done

**Rests and ties from an absolute strip position.** The owner's model:

> The strip has two modes. One snaps back to the middle; one is like a mod
> wheel with absolute positions and remembers where you left it. The latter
> is the default. In sequencer write mode it should be in the latter mode,
> reverting to whatever the user was in before. Then read absolute values at
> release rather than a bend direction — **below halfway a rest, above
> halfway a tie**.

What is settled, with evidence, in PLAN-2.0.md:

- The mode is `state+0x20c`, **0 = absolute, 1 = relative pitchbend**. Proved
  from what the two-ended-hold toggle at `0x8000af8a` sends in each
  direction, not inferred.
- Forcing and restoring it is one store each way. That half is ready.

What is **not** settled, and must not be guessed:

- The threshold. `state+0x202`, which the pitch path reads in absolute mode,
  is not the position — it is `table[state+0x306]` through the shared curve
  at `0x80015150`, which is steeply non-linear (index 512 → 166 of 1024).
  Half its range is nowhere near the middle of the strip. `state+0x306` looks
  like the raw position but is also read by the glide path and compared
  against `0x1d` in the scanner, so its range wants establishing first.
  Halfway is the entire rule here.

What works today, and should keep working until the replacement is verified:
tap an end of the strip while recording — one rest or tie per touch, re-armed
by releasing (`state+0x206`, checked every scan).

## Other open items

- **Preset persistence** — blocked on an empirical probe: does the factory
  flash driver at `0x800108fc` erase before writing? Specified in the plan,
  never run.
- **Settings over MIDI** — feasibility done (dispatcher event 32 at
  `0x80004fc2` carries an incoming message with both data bytes). The cost is
  that build numbers are compiled as immediates, so each one moved to
  runtime needs a RAM cell and a load. Good candidates are the numbers below.
- **Three numbers never measured on hardware**: `strip_end_units` (48),
  `tie_glide_rate` (60), `chord_hold_scans` (300). All are build numbers
  precisely so they can move.
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

**Emulating a cave does not exercise its hook.** A clock hook that jumped to
an invalid address passed every emulation and the whole parity matrix,
because the emulations called caves directly and the matrix compares two
toolchains that were both told the same wrong thing. `tools/test.py` now
walks every `MCALL` in the image and checks the word it reads holds a code
address — that guard has since caught two more of the same. Trust it.

**`MCALL PC[x]` is memory-indirect.** It calls whatever the *word* at x says.
It needs a pool word, never the routine's own address.

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
starts as whatever the previous image left behind.

**Repin after every change.** The init marker hashes the settings and the
assembler source, so any edit — a comment included — moves every image.
`config/218e.toml`'s `golden_sha256` and `sweep.py`'s `historical_config`
both need updating, and `web/generate.py` re-run.

## Standing instructions from the owner

- Never flash hardware; never point a DFU command at the instrument.
- Never ask for or handle credentials or API tokens.
- Version bumps are the owner's call alone.
- `private/` and `docs/internals/` stay gitignored; history is not scrubbed.
- Commit and push to origin without asking. Force-pushes need approval.
- Propose new user-facing copy and wait; edits that were asked for do not
  need approval.
