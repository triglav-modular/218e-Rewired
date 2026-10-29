# Handoff — 218e Rewired 2.0

Updated 2026-08-28. Branch `2.0`, including working-tree changes. Everything
below is either in the repo or reproducible from it; nothing here is a memory
of a conversation.

## Where it stands

**Current clock fix:** see [CLOCK.md](CLOCK.md). The interrupt-timestamped
capture/FIFO replaces the sampled-low and rejection-budget experiments
described below. It explicitly enables both GPIO edges, fixes literal-pool
fallthrough, latches division independently of interval confidence, and
serializes note/pitch/trigger output. The requirement is 0.5–200 Hz.
Run `python3 tools/test_clock.py` against fresh produced firmware.
The owner confirmed that clock fix working on hardware, and on 2026-08-29
that the divider timeout is fixed there too. Persistence was validated on
the instrument the same day. Independent sequencer transport still needs a
hardware check.

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
sends a trigger, and the pad-4 hold is a second. Earlier clock experiments
targeted 780 Hz; the current interrupt-captured implementation targets
0.5–200 Hz. It passes inputs through until five consistent measured
intervals establish division, then keeps its divide phase even if intervals
vary. See [CLOCK.md](CLOCK.md) for the implemented contract.

Then: a knob no longer does its other job while a preset pad is using it, and
recording sounds what you play into it - the note-on leaves its key for the
per-scan cave, which steps the arp once so the pitch, gate, trigger and MIDI
note all come from the factory's own machinery.

And the randomisers reach the sequence: knob 3 displaces sequenced notes by
an octave the way it does played ones, and knob 1's blend shuffles which step
comes next. Knob 1's six note-order zones stay the keyboard's alone - the
owner's call - so a recorded sequence keeps the order it was played in.

**Hardware validation.** The owner flashes and reports. Persistence was
validated on the instrument on 2026-08-29, including its dropped edges
during a save, which the owner accepts. The transport changes have still
only been tested in emulation.
`strip_halfway_units` is 2048 because 2048 is the middle of the
range the firmware clamps to, and the middle is the rule; it is a build
number so a strip reading off centre can be told where its own middle is.

**User-facing instructions are updated.** `web/index.html` describes the
strip's two halves and independent sequencer transport: PLAY starts its
clock even with arp OFF; STOP/CLEAR end sequence playback and return the
ordinary arpeggiator to the physical switch. RATE retains its normal role.

## Other open items

- **DFU re-enumeration sometimes fails** - the instrument detaches on the
  DFU SysEx and the bootloader never re-attaches (ioreg shows nothing);
  power-cycle recovers, nothing written.  The macOS flasher now retries
  through a power cycle by itself, up to four attempts.  The device side is
  factory code and is deliberately NOT patched - it is the recovery path,
  and nothing here is ever tested against hardware.  Archaeology pointers
  are in the plan's DFU section if the owner ever wants it chased.

- **USB MIDI is read while the host leaves a packet untaken** - fixed in
  the image (2026-10-29: `usb_task_every_pass`, `usb_flush_gate`,
  `usb_receive_keep`), audited (`docs/audits/USB-STALL-AUDIT-2026-10-29.md`,
  kept locally like every audit), not yet on the instrument.
  *The factory fault.* LUFA's endpoint wait (`0x8000d9c4`), which the stream
  write (`0x8000d214`) and the stream read (`0x8000d394`) open with and the
  flush (`0x8000c670`) calls once it has committed a bank, sets `0x4718`
  when the host leaves an IN packet untaken for 100 ms and clears it when
  any wait succeeds, IN or OUT (`0x8000da34`, `0x8000da54`); while it
  stands, every wait gives up at the
  next frame. The factory main loop runs its USB task (`0x80007428`: the
  MIDI receive, the flush, and `USB_USBTask`, the only place control
  requests are answered) only while `0x4718` is clear (`0x80007c5a`), and
  the extra call it makes every 8 s (`0x80007d40`) is gated the same way.
  So the receive never ran while the byte stood, and only a send reached the
  wait again: after one untaken packet the keyboard reads no MIDI, answers
  no control request and cannot enumerate after a replug (a bus reset
  leaves the byte) until it sends something the host takes, or boots
  (startup zeroes `0x2ed8..0x4748`). It sends unprompted: the
  arpeggiation-rate reading (`state+0x308`, `(v+5)>>3`) as CC 16 on channel
  16 and PORTAMENTO as CC 5 on its own channel whenever the 7-bit value
  changes (`0x80003284..0x8000333a`). Every send, the receive and the flush
  return at once unless the device is configured (`0x4739` = 4), so the
  byte can only be set after SET_CONFIGURATION.
  *The fix.* The gate's branch (`0x80007c60`) is a NOP, so the task runs on
  every pass, and two of the task's words keep the byte's factory meaning.
  Its flush word (`0x80007504`) names `usb_flush_gate` (`0x80024310`),
  which tail-jumps to the factory MIDI task (`0x8000c55c`, R12 the
  interface, LR the task's return) while `0x4718` is clear and returns
  while it stands. The factory MIDI task flushes without asking whether the
  endpoint is ready, and the flush recommits whatever the bank's byte count
  shows - a committed packet the host has not taken still counts, by the
  UC3B manual's account of the USBB byte count (not measured) - so with the
  byte standing an ungated flush would wait out a frame on every pass,
  pacing the main loop to 1 kHz for as long as the host leaves the packet.
  Its receive word (`0x800074fc`) names `usb_receive_keep` (`0x80024328`),
  which calls the factory receive (`0x8000c74c`; R12 and R11 passed on, its
  answer back in R12) and puts `0x4718` back as it found it. Without it a
  received packet's read would clear the byte, and with a host that sends
  without reading, the next flush and every send after it would wait out
  100 ms again: a main loop held up 100 ms per incoming packet in place of
  a deaf one. The keeper has no branch, so every instruction of it runs on
  every pass from the first, inside the boot guard's window. Neither the
  receive nor `USB_USBTask` waits on the IN endpoint; the control requests
  wait on the host's own control stages, as on every factory pass. Holding
  the flush loses nothing: with the byte standing no send writes (its first
  wait either clears the byte or gives up), so nothing uncommitted waits in
  a bank, and the first send the host takes clears it. The 8 s call keeps
  its factory gate. Clearing the byte at bus reset and SET_CONFIGURATION,
  the other candidate, was not taken: the byte is only ever set after
  configuration, so it would not reach the power-on case; with the task on
  every pass a re-enumeration is answered whatever the byte; and after one
  the banks are free, so the first send clears it anyway.
  *Evidence.* `SettingsRegression.usbStall` (run by
  `tools/test_persistence.py`) drives the loop from its gate with the byte
  clear and standing. The factory receive, MIDI task, `USB_USBTask` and
  received-byte handler are recorded at their entries, and the receive
  clears the byte for each packet it hands over, as the factory's read
  does. A host's note reaches the parser and the control requests are
  served either way, the flush runs only with the byte clear, and a
  received packet leaves a standing byte standing. The pre-fix image
  (`2db956fd`) fails the check with the byte standing (the pass calls
  nothing), and the flush gate alone (`9fcf58df`, the code of `e682f3e8`)
  fails it the same way the audit predicted: the host's note clears the
  standing byte and the same pass flushes. A Fable 5.1 audit at extra-high
  effort, in two rounds, found nothing that could stop a reflash; its first
  round's P2, the receive clearing the byte, is what `usb_receive_keep`
  answers. The gate-2 rig's keeper (`compute:~/rig208/midikeeper.log`, the
  port held open and drained throughout) saw both lost cold power-ons open
  the port 11-12 s after power-on and receive nothing at all for 25 and
  28 min (2026-10-28 13:14:01.8 to 13:39:15, 2026-10-29 08:25:12.0 to
  08:53:15), while short power cycles opened within about a second and
  carried traffic. Consistent with the byte set right after configuration
  and the host's own requests then going unanswered; not established: what
  the keyboard sent that went untaken, and why only after a long time off.
  *Bench, factory USB code (2026-10-29, the instrument's build before the
  fix, the Intel Mac rig, keeper stopped, dumps by
  `compute:~/rig208/gate2/alive.sh`, a settings dump round trip run as the
  gui-domain job `alive.plist`).* macOS's MIDI server keeps two reads
  pending on the IN endpoint whenever it runs, client or none (kernel:
  `abortGated ... endpoint 0x82: aborting 2 requests` as it exits), so with
  Audio MIDI Setup open an ARP RATE nudge left the keyboard answering
  (12:22). With no server running, one key touch, then a dump: no answer
  (12:26, 0 parameters), and none at 12:28:32 or at 12:28:53, the last with
  the server up and the keeper reading. Each server start meanwhile took
  about a minute while its control requests timed out at the keyboard
  (kernel: endpoint 0x00 `0xe00002d6` at 12:25:45, 12:26:37, 12:27:30,
  12:28:21). A key touch at 12:29:29.375 cleared the byte - its note-on
  reached the keeper - and froze the panel: nothing more was sent and the
  dump requests waiting on the OUT endpoint were never taken, until a power
  cycle at 12:29:59, after which the keyboard read MIDI. The freeze is the
  factory control code answering the last timed-out request 70 s late. Its
  control write's status wait (`0x8000d71c..0x8000d75e`) waits for the
  host's status packet and leaves only on detach or suspend - no timeout,
  no abort on a new SETUP - so a reply the host has given up on holds the
  main loop until power-off. So on the factory code, recovering a deaf
  keyboard by making it send can freeze it whenever anything asked it a
  control request meanwhile (a MIDI server start, Audio MIDI Setup); a power
  cycle is the safe recovery. The fix runs `USB_USBTask` on every pass, so
  no control request waits on the byte. The power cycle re-enumerated in
  2.6 s and the keyboard sent CC 18 = 127 on channel 16 1.7 s after the
  port opened, a send at boot the list above lacks. After the flash, the
  same sequence should answer the first dump, start the server in seconds
  with no endpoint 0x00 timeouts, and not freeze; then cold power-ons
  should read MIDI every time.

- **Preset/sequence persistence** — on since 2026-08-29, required since
  2026-09-01, and validated on the instrument. The post-audit version
  uses a CRC32 musical-data-only record, verified body followed by a no-erase marker
  commit, and bounded retries that never erase the newest valid page.
  Changed sequences save on record exit/CLEAR and changed presets on their
  pad release, with no idle or arp-off gate. A completed-edit snapshot
  excludes other edits still in progress. Flash saves can briefly disrupt
  live clock/output timing; they are not gapless background operations.
  Startup always restores stopped and clears transients, including on warm
  reset. The reserved main-array ring is `0x8003e000..0x8003efff`; the User
  Page and factory settings are untouched. DFU erases these records, and
  old experimental records are not migrated. See [PERSISTENCE.md](PERSISTENCE.md)
  for the save gesture/timing contract, record layout, failure states and
  `tools/test_persistence.py` regression coverage. Remaining work is bench
  validation of save/power-cycle behavior and physical flash failures.
- **Settings over MIDI** — stage 1's firmware is built (2026-09-22), laid
  out in [PLAN-SETTINGS.md](PLAN-SETTINGS.md): a RAM mirror at `0x6800`
  that every table reader and the ten numbers below address, filled at boot
  from the image's own tables and then from the newer valid record in the
  two slots at `0x8003d000`/`0x8003d800`; NRPN on channel 16 at the
  factory's Control Change branch (`0x8000838e`) writes the mirror live,
  `0x3f00`/`0x2a2a` commits it from the per-scan chain, `0x3f03` dumps it
  back paced, `0x3f7f` answers with the identity block. `tools/settings.py`
  and `BUILDLIB.settingsRecord` write the record, `BUILDLIB.nrpn*` is the
  codec. The page's step 5 sends a build's record (`SETTINGSMIDI.install`:
  identity, push, dump, compare, commit) and reads what the keyboard holds
  back into the page (`SETTINGSMIDI.read`: the patterns and the pitch
  table, with the scaling and offset read off the table). The identity
  block leads with the firmware version (`0x3f76`, frozen numbers) and the
  page's verdict tells an older, a newer and a same-version-other-options
  keyboard apart. Nothing here has run on the instrument yet. Stage 2 -
  the blocks and knob roles as option cells 16..27 of the same record,
  applied at boot from live bytes at `0x6d28`, with a restart command
  (`0x3f04`, the watchdog) so the page can apply them at once - is
  planned in [PLAN-SETTINGS-2.md](PLAN-SETTINGS-2.md). Phase A (the
  cells, layout 2, the live bytes, the restart, the page codec), phase B
  (the four knob roles decided at boot by dispatchers on their pool words,
  out of the image marker), phase C (the latching arp, three dispatchers,
  a pad test and the factory chord given back by a shim), phase D (the
  sequencer, one gate at the pad-4 chord's arm; every other sequencer cave
  already asks the mode) and phase E (the jack and the presets: the
  rotation idles at zero degrees in every image, and four helpers decide
  where the degrees come from and give the factory its glide addend and
  its straight preset add back) and phase F (the pressure path: three
  dispatchers on the factory's pool words, three hooks where the 2-byte
  patches stood, the interpolator's pass-through, the blend's route word
  and glide value) and phase G (the clock divider: the ISR hook reshaped
  to six bytes so the factory body stays for the off path, event 10's
  handler and the four pulse pools dispatched) and phase H (the docs,
  the page's copy) are built (2026-09-23). The reference is
  [SETTINGS.md](SETTINGS.md). The owner's three approval criteria
  (2026-09-23): (1) everything as in 2.4 - open: the evidence still owed
  is an emulator A/B of the new image against the shipped 2.4 image
  (`build/Rewired_marton_2.4.0_DFU.hex`, untracked) through the factory
  entry points both share, scripted gestures in, DAC slots and MIDI sends
  out, diffed with the same options; (2) every setting over MIDI - done
  the same evening: the image marker leaves out the pitch table, the
  tuning tables, the keys per period and the ten timing numbers (checked:
  builds with a tuning, a pitch correction, no pitch offset and 1 V/oct
  share the default's marker), and cell 27 (`alternate_tunings`, live
  byte `0x6d33`) decides the two edit keys, the applier and the
  remote-enable guards, so a keyboard built without a tuning takes tables
  over MIDI, and since the same evening the period too, as number cell
  10 read by every octave site (PLAN-SETTINGS-2.md, record J), so no
  setting change needs a flash;
  (3) no side effects - the residue test (`ControlRegression.residue`: a
  session with every option on, a warm all-off restart, custom RAM
  compared against a cold boot with the same record and ring) ran
  2026-09-23 and found three cells read with their option off - the
  vibrato offset the remap adds, the jack transposer's tagged state word,
  the blend's re-base history - now cleared by `option_boot_state`; the
  22 other runs are allowlisted in the test with their readers
  (PLAN-SETTINGS-2.md, record I), the test runs both directions in both
  builds since `seq_restart_clear` (record L); (1) works as in 2.4 - the A/B trace
  (`tools/ab_trace.py`, record K) finds 62 of 63 steps identical with
  2.4's tables planted, the one difference a transpose of -2 before the
  first key: the transposer's refresh standing the bottom key in for
  nothing played, inaudible and gone at the first key (record K). The
  volatile build's sequencer runtime surviving a warm restart is closed
  by `seq_restart_clear` (record L). Still open: bench validation of
  both stages, the wide-map refusal (phase E) and the added page copy
  (phases A and H). (Dispatcher event 32 at `0x80004fc2`
  is the factory remote-note handler, not the CC path.)
- **Numbers never measured on hardware**: `tie_glide_rate` (60),
  `strip_halfway_units` (2048), `clock_min_ms` (4), `clock_rearm_us` (250),
  `clock_lock_pulses` (5), and the jack transposer's `cv_counts_per_volt`
  (204.75, i.e. 4095 counts over 20 V — INFERRED, not measured: the factory's
  own glide-index arithmetic at `0x80003142` spans its 0x3ff clamp with this
  jack only at about 205 counts/V. It was 102.3, then 409.5, both read off an
  observation of where the pitch OUTPUT saturates), `cv_zero` (0) and
  `cv_hysteresis` (12) in tools/options.py - read the raw count at TWO known
  voltages into PORTAMENTO IN against state+0x2f0 before trusting the first
  two; one point cannot separate a slope from an intercept. All are build numbers
  precisely so they can move. `chord_hold_scans`
  is 200 because the owner asked for a second. For current clock timing
  values and their digital-input constraints, use [CLOCK.md](CLOCK.md).
- **The trigger spike is measured and settled.** The factory schedules it
  with 3 at `0x80007888` (R10; the adjacent R11=2 selects the timer ID) and
  the owner measured that spike at 2 ms, so the units are (n − 1) ms. Clock
  builds set it to `trigger_spike_units` (5); the owner measured the result
  at 4.2 ms on the jack — the model holds, the 0.2 ms being the analog
  edge. Bounded at 5: the attack-age guards cover exactly four milliseconds.
- **A factory settings save during a take would persist the borrowed strip mode.**
  Record forces `state+0x20c` to 0 and the save path reads it like any other
  setting. This is separate from musical persistence, which excludes that
  field and snapshots sequence data only on record exit/CLEAR. A preset
  release can save during record without saving its borrowed strip mode or
  unfinished sequence.
- **Clock multiplication** — deliberately not built; MARF gets it by
  subdividing between pulses, which needs edge prediction. See the plan.
- **208p oscillator ceiling** — a builder-side warning was noted for later.

## How to work on this

`docs/BUILD.md` covers the pipeline. What follows is only the things that
have actually bitten, repeatedly.

**Verify against the built image, not the source.** Every firmware claim in
this repo was checked by disassembling the image and emulating those bytes.
`tools/avr32/sweep.py` builds representative configurations through both toolchains;
`web/test_configs.py` compares its option matrix against the browser build;
`web/test_matrix.js` builds 1,536 combinations, including persistence on/off.
`tools/test_persistence.py` adds firmware-level power-cut and independent
sequencer-transport regressions. Run all of them.

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

**Word accesses must be word-aligned.**  A word or doubleword access off a
4-byte boundary, or a halfword at an odd address, raises an address
exception on the chip. The emulator just moves the bytes. `seq_restart_clear`
cleared two halfword cells at `0x622e` with one `ST.W` and hung the first
3.0 image at boot, before USB, with only JTAG to bring it back
(2026-09-26). `check_alignment` in `tools/build.py` refuses a misaligned
constant-address access, and `src/AlignGuard.java` traps a misaligned
access in every harness. Clear adjacent narrow cells at their own width.

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
loop from `0x6100`; its count is `0xff`, covering everything up to `0x62ff`
— the preset block, the arp cells, the sequencer, the clock divider and its
FIFO, the borrowed strip mode and the knob pickup stamps.

**One harness at a time.**  Every regression driver and every hand-run
build.py shares `build/` - the images, the logs, and the SHIPPED FLASHERS,
which each build rewrites with its own image's hash.  Two consequences, both
of which have actually happened: running the sweep concurrently with another
harness hashes an image mid-overwrite and reports a phantom duplicate
("a variant may not be taking effect" - re-run serially before believing
it); and any hand-run non-default build leaves the flashers pointing at a
test image, which test.py's flasher guard catches at commit time - rebuild
the DEFAULT config last, always.

**Repin after every change.** The init marker hashes the settings and the
assembler source, so any edit — a comment included — moves every image.
`config/218e.toml`'s `golden_sha256` and `sweep.py`'s `historical_config`
both need updating, `tools/avr32/make_corpus.py` re-run (it needs Ghidra),
and `web/generate.py` re-run. The build rewrites both flashers with the new
hash, and a rewritten `mac/Program218e_v3_Rewired_macOS.command` leaves
`mac/Flasher.zip` stale — the signed app seals that script inside itself and
records its hash in `SOURCES.sha256` — so `tools/make-app.sh --notarize` runs
too and the zip is committed with the rest. Do it once, after the last source
edit.

## Standing instructions from the owner

- Never flash hardware; never point a DFU command at the instrument.
- Never ask for or handle credentials or API tokens.
- Version bumps are the owner's call alone.
- `private/` and `docs/internals/` stay gitignored; history is not scrubbed.
- Commit and push to origin without asking. Force-pushes need approval.
- Propose new user-facing copy and wait; edits that were asked for do not
  need approval.
