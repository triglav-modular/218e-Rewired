# External clock implementation and verification

This describes the clock-divider fix and independent sequence transport on
branch 2.0, updated 2026-08-28. The owner confirmed the clock-divider fix
working on hardware; the persistence and independent-transport additions
still require instrument validation.
Earlier clock experiments in PLAN-2.0.md are historical, not the current
implementation.

## Input contract

The target range is approximately **0.5–200 Hz**, for square pulses and
descending saws that produce valid logic levels at the conditioned MCU input.
Jack voltages alone do not establish the MCU's threshold, hysteresis or duty
cycle. Firmware cannot distinguish a noise pulse with the same digital timing
as an intended clock.

At the default settings:

- The MCU input must be continuously low for **more than 250 microseconds**
  before a rising transition qualifies. The high must last long enough for
  the interrupt to observe it.
- Accepted rising transitions must be at least **4 ms** apart. Rejected
  transitions never move the accepted-edge timestamp or touch the gate.
- Five consistent measured intervals acquire division. Before acquisition,
  qualified inputs pass at /1. After acquisition, the RATE knob selects /1
  through /8 — /1 at zero, /8 at the top — and interval disagreement cannot
  reset the divide phase.
- An absence longer than **2600 ms**, or switching the arp off while the
  sequencer is not playing, releases division. Period acquisition accepts
  intervals up to 2400 ms, leaving margin around the 2000 ms lower-frequency
  boundary.
- The normal 5 ms pitch scan permits 200 outputs/second with
  `clock_settle_scans=0`. Additional settle scans reduce that ceiling.
  Dispatcher stalls can add latency; a finite queue cannot absorb an
  indefinite stall or a sustained rate above its output capacity.
- The trigger rises a **fixed `clock_deadline_ms` (default 4 ms) after the
  accepted edge**, on the first service point at or past that target — a
  main-loop pass or the 1 kHz DAC flush, whichever arrives first — rather
  than the 5 ms pitch scan.
  The deadline buys jitter with latency: the path from edge to claim measured
  0.3-3.6 ms on the instrument and that variation used to reach the gate,
  where now it is absorbed. A beat that overruns the deadline gates at once
  and keeps the older behaviour. `clock_deadline_ms = 0` turns it off. The
  deadline is bounded at build time by half the acquired period, so a fast
  clock is not swallowed by it. Before the deadline the trigger
  **rose within 1 ms of the accepted edge**, on the same flush. Measured on the emitted firmware,
  the edge-to-rise delay was uniform over a whole scan period — 0 to 4.8 ms,
  with no fixed component — because the gate's fall was already scheduled
  from the edge itself while its rise waited for the next scan. Under the
  1 ms emulation fixture it now measures 0.8 ms peak to peak, but that
  figure is the fixture's tick rate and not a hardware bound; see
  **What the harness cannot measure** below.
- The **internal clock's beat holds the same bound**, and so does an
  external beat with a settle configured. A settle is a wait for the output
  RC, not for the scan grid, and on a deadline build it is measured **from
  the actual DAC transfer**: phase A stages the pitch, pushes it to the DAC
  through the factory transfer, and stores transfer + settle as part of the
  gate's COUNT target. It cannot begin earlier — a wait spent before the
  pitch reaches the DAC protects nothing, and an earlier build did exactly
  that (see **The COUNT target and the pending service** below). The wait
  itself is unchanged — `gate_settle_scans` and `clock_settle_scans` still
  mean the same number of scan periods. Under the fixture the internal beat
  is a fixed settle with no spread; the same fixture caveat applies.
  A key played with the arp and the sequencer both off is not a beat; its
  latency is the player's own and it stays on the scan as before.

- The RATE knob's raw channel is clamped to 0x3ff before the divisor is
  derived, as the factory clamps it at every read - the raw cell exceeds
  0x3ff at the top of the knob, and unclamped that silenced /1.
- The trigger spike is `trigger_spike_units` (default 5): the scheduler's
  units are (n - 1) ms, measured against the factory's 3 producing a 2 ms
  spike, so 5 is the ~4 ms Buchla spike.  Bounded at 5 - the attack-age
  guards cover exactly four milliseconds.

These internal settings live in tools/options.py and are exported identically
by the command-line and browser builds.

## Independent sequencer transport

With `sequencer = true`, PLAY starts the sequence clock even with the arp
switch OFF. STOP/CLEAR stop the sequence, release its note and cancel its
pending trigger/FIFO entries. The physical switch is never overwritten;
after stop, the ordinary arpeggiator follows that switch again. Switching
arp positions during playback does not stop or reset the sequence clock.

This reuses the factory clock, not a second oscillator. RATE and its CV still
set internal tempo; with clock division enabled, incoming pulses take over
and RATE sets /1–/8 as before. RATE's minimum retains the factory
external-only behavior. Playback never bypasses input qualification.

Bare pad 2 during WRITE borrows this clock for a one-shot preview. The beat
after its final step ends playback and returns to WRITE, without looping or
saving. Explicit STOP cancels preview and completes the take; see
[PERSISTENCE.md](PERSISTENCE.md) for those save boundaries.

The effective enable (`sequence playing OR arp enabled`) is used by tempo
conditioning at `0x80002b30`, the factory enable-change detector and setup at
`0x80002ac4`/`0x80002c2c`, timer dispatch at `0x80004f86`, and external input
dispatch. `0x8001d600` computes it; `0x8001d640` wraps the actual pad transport
and calls the factory tempo/setup routine after publishing the new mode.
Transport discards old FIFO entries in a short SR-preserving critical section.
The GPIO capture code and qualification thresholds are unchanged.

Persistence commits changed sequences on record exit/CLEAR and changed
presets on pad release, even during playback. These explicit flash saves
can briefly stall clock/output processing and miss incoming edges; the
normal 0.5–200 Hz clock tests do not guarantee gapless flash writes.
See [PERSISTENCE.md](PERSISTENCE.md).

## What changed

The factory configuration at 0x8000737e passes mode 1 to 0x80011238, which calls
0x80011164 and sets IMR0 while clearing IMR1: **rising edge**, not pin change.
The PVR check inside the handler did not prove that both edges were enabled.
Clock builds now explicitly select mode 0 (both edges).
[The MCU datasheet, sections 17.5–17.6](https://ww1.microchip.com/downloads/en/DeviceDoc/doc32059.pdf)
documents those modes and the write-one-to-clear interrupt flag.

The GPIO ISR clears its flag before sampling PVR and timestamps transitions
with COUNT. Every observed high spends the preceding low interval, including
rejected highs. There is no rejection-budget, period-elapsed or unlocked
bypass. The 1 ms callback only banks already-timed continuous lows so a long
idle can survive COUNT rollover; it neither starts a low run nor substitutes
sample counts for elapsed low time.

Timing uses the same CPU-frequency word as the factory COUNT-based delay:
0x800129e0 reads RAM 0x29cc through pool 0x80012a24. No 60 MHz assumption is
embedded in the filter.

Accepted edge timestamps enter a single-producer/single-consumer FIFO with
31 usable entries. The ISR does not clear the output gate, call a DAC driver,
or post event 10. Main-loop dequeue and timeout use a short critical section
that restores the caller's original SR; note selection and DAC work happen
afterwards. If full, the FIFO drops the newest edge and increments a
saturating overrun counter without overwriting unread entries or waiting.
Startup clears both queued edges and any pre-restart deferred trigger,
including a warm restart with the same initialization marker.

When a clock is present, `clock_settle` marks the step claimable by the 1 kHz
DAC flush as well as by the pitch scan, and whichever context completes it
drops the other's claim: nothing orders those two dispatcher events within a
millisecond, and without that the scan could fire and the flush fire again
behind it. The event-17 wrapper at `0x8001a600` is shared: pressure smoothing runs its
interpolation there and the clock raises its trigger there, so the wrapper is
built whenever **either** is enabled and each half is conditional. That matters
because `pressure_fix = false` sets output smoothing to zero while leaving
clock division on — gating the whole wrapper on smoothing put that build's
trigger back on the 5 ms scan with the fast-trigger cave emitted but
unreachable. `tools/test_clock.py --mode pressure-off` builds exactly that
configuration and holds it to the same 1 ms bound.

**The premise of the next paragraph is still unverified, but its conclusion
has now been tested directly.** The dequeue was moved onto the 1 ms tick and
measured; the jitter did not change, and the move was reverted. Whatever the
relative rates are, moving the dequeue does not help. See **What the harness
cannot measure** below for the measurements.

Moving the FIFO dequeue itself onto the flush was considered and is wrong.
The factory dispatcher at 0x80004c64 takes ONE event from its 32-entry ring
(0x8001030c) and returns; every jump-table arm ends in `BR{al} 0x800051b0`.
The main loop at 0x80007c5a calls `clock_service` and then that dispatcher,
once each, per iteration — so the dequeue already runs at least as often as
event 17 is dispatched, and moving it would make it rarer.

An earlier version of this paragraph went on to claim event-queue depth was
a floor either way, because "the DAC transfer itself happens in the factory
event-17 handler, so nothing staged earlier moves the edge." That was
wrong, and the 2026-08-31 audit proved it on the emitted image: the factory
pulse-high routine at 0x800077f8 performs its own DAC transfer through pool
0x800078b8 -> 0x8000ba9c -> 0x8000b9ac, one transfer and 24 SPI byte writes,
with no dispatcher event involved — and so does the pulse-drop path. Event
17 is A way the DAC gets flushed, not THE way, which is what makes the
pending-output service below possible. It also invalidates the pulse-width
argument at the end of this file as a proof of event-17 cadence: the pulse
paths transfer directly, so a narrow width distribution says nothing about
how often event 17 was serviced.

The claim byte at 0x625b says which of those the flush is holding: 1 to
fire on this tick, 2 to stage the pitch and compute the wait, 3 while that
wait runs. On a deadline build 0x60ee stays ZERO for the claim's whole
life: the wait is the absolute COUNT target phase A stores at 0x60dc, the
1 ms task never touches it, and every service point simply compares COUNT
against it. On a `clock_deadline_ms = 0` build the countdown at 0x60ee is
MILLISECONDS rather than scans under claim 3 — phase A hands it to the 1 ms
task once the pitch has been staged and transferred, and not before, so the
RC wait cannot be spent while the old pitch is still at the output. Either
way `clock_output` leaves the whole step alone so the scan cannot read the
wait as scans and spend it five times over. If the glide declines the step
the claim is dropped and 0x60ee is handed back a scan count, which is
exactly what declining asked for. Because a claimed step's countdown no
longer marks it, `clock_settle` refuses to re-claim while 0x625b is
nonzero: an arp advance faster than the pending wait keeps the outstanding
trigger instead of orphaning it.

The flush path stages the step's own pitch through the calibration
remap **entered past the per-scan chain at its head** — the tuning applier,
the housekeeping and the vibrato engine all advance once per scan and must not
be run at 1 kHz — and then raises the gate, so pitch and trigger reach the DAC
in the same flush. The staged word is the step's target plus the bend strip's
offset at `state+0x216`, clamped to 0..0xfff — the same two terms the scan
adds and the same clamp it applies, so the two paths reach the same DAC word.
Omitting the bend was a pitch defect on the instrument: the flush drove slot 2
to a bend-less note under every trigger and the scan only corrected it up to
5 ms later, which is heard as the clock bleeding into the pitch output.
It takes the step only while the glide is snapping; with a
real portamento time the scan's value and the target disagree, so the beat
goes back to the scan rather than have its pitch jump. The 4 ms attack-age
guard applies to both paths, and staying claimed retries on the next tick.

Each selected output step owns its pitch scan and deferred trigger until
completion. A later beat cannot overwrite it. Rests and ties complete their
slot without manufacturing a trigger. Gate-low occurs only when a selected
note requests a retrigger; discarded noise and divided-away inputs cannot
truncate the previous spike. Dequeue also respects the previous output's
attack time. If the factory countdown reaches gate-off while the actual
output is less than 4 ms old, it retries on the next tick. This prevents a
late pitch scan from placing its trigger directly under an old countdown's
gate-off, without removing the eventual gate-off or the factory attack-drop.

The allowed branch of the hook at 0x800021ce explicitly jumps over its literal
pool. It no longer executes the bytes at 0x800021e8 as loads/stores.

## Locations

| Code | Address |
| --- | --- |
| GPIO ISR hook | 0x800072ee |
| Main-loop clock wrapper | 0x8001b980 |
| Edge capture | 0x8001c200 |
| Masked startup initialization | 0x8001c300 |
| FIFO service / timeout | 0x8001c400 |
| Pitch-store completion / output | 0x8001c600 |
| Selected-note gate-low / defer | 0x8001c700 |
| Period acquisition / latched division | 0x8001c800 |
| Long-low qualification banking | 0x8001ca00 |
| Countdown attack-age guard | 0x8001ca80 |
| Remap without the per-scan chain | 0x8001c0e0 |
| Trigger rise, on the 1 kHz flush | 0x8001c100 |
| Deadline, sized from the accepted edge | 0x8001bd40 |

The old proposed page at 0x8001c000–0x8001c1ff is still unused. Persistence
now stores records at 0x8003e000–0x8003efff; see [PERSISTENCE.md](PERSISTENCE.md).
When enabled, its startup wrapper restores musical data before calling the
clock initializer above. Completed edit gestures can save during playback;
unfinished edits do not write.

| RAM | Meaning |
| --- | --- |
| 0x609c | Held pitch: the DAC word captured when an external step is claimed, restored by scans until its gate |
| 0x60dc | Claimed beat's gate target, absolute COUNT (deadline builds; valid only under claim 3) |
| 0x6232 | Low interval: 0 none, 1 timing, 2 qualified |
| 0x6233 | Acquired-divider latch |
| 0x6234 / 0x6235 | FIFO producer / consumer index |
| 0x6236 / 0x6237 | ISR input presence / external output step in flight; pending output uses the latter as its source |
| 0x6238 / 0x623c / 0x6240 | Low / accepted / consumed COUNT stamps |
| 0x6244–0x6253 | Cycle-based timing constants |
| 0x6254 / 0x625a | Last physical output stamp / valid flag |
| 0x6258 | Saturating FIFO-overrun count |
| 0x625b | Flush claim: 0 none, 1 fire now, 2 stage then settle, 3 settling |
| 0x6260–0x62df | Timestamp FIFO |
| 0x62f6 | Millisecond count at the last accepted edge |

With `[diagnostics].clock_latency`, `0x6032` and `0x6034` published pair —
max edge-to-claim / max edge-to-gate with a clock present, min / max
claim-to-gate without one / `0x6038` the source those cells belong to /
`0x603c` shared sample count / `0x6040` stamp of the edge last timed /
`0x6044` the current step's edge-to-claim age / `0x62f0` the internal beat's
claim stamp, all cleared by the startup initialiser. `scan_profiler` shares the original five cells; see
above.

The 2600 ms release is measured against the factory 1 ms counter (0x61e6),
not COUNT: COUNT is scaled by the CPU-frequency word at 0x29cc, which made
the release fire in under a second on the instrument.

## Measuring the delay from inside the firmware

`[diagnostics].clock_latency = true` builds a shim at `0x8001bbc0` that both
gate-raise paths reach through their existing pool words — the scan path's at
`0x8001c6b0` and the fast path's at `fastPool + 8`, both of which held
`0x800077f8` — so no instruction is added at either call site. The shim
stamps COUNT against the consumed-edge stamp at `0x6240`. `clock_settle` also
records the age when the selected external step becomes claimable by the
1 kHz flush. The shim publishes edge-to-claim and edge-to-gate MAXIMA in
cycles/32 at `0x6032`/`0x6034`, and tail-calls the real pulse-high routine
with R8–R12 saved around the measurement. It also times the INTERNAL beat,
which has no accepted edge: `clock_settle` stamps `0x62f0` when the internal
beat claims the step, and the shim publishes the minimum and maximum of that
claim-to-gate instead. At each gate, `0x6237` identifies the step
being measured; `0x6038` remembers which source the published pair describes.
A gate from a different source clears the pair — without that the bench
procedure, which has a key held and therefore arping before the clock starts,
would leave internal samples inside the external maxima that follow.

Those two cells go out on the telemetry frame's scan-component fields, and
`tools/clock_latency_report.py` decodes a readout CSV into milliseconds. It
checks the capture's validity before it reports any figure, because a running
maximum that FALLS between frames proves the cells were cleared and reseeded
mid-run. That rule is asymmetric and the asymmetry matters: an internal
capture should see no source change at all, so any reset there is
contamination and the tool refuses to report; an external capture is
*expected* to reset exactly once, when the clock arrives and clears the
internal pre-roll the bench procedure creates by having a key held first.
The tool names that frame and says how many frames follow it, which is the
population the maxima are actually drawn from. Several resets in an external
capture mean the input was dropping out and being re-acquired. `--period-ms`
adds a cross-check: a figure at or above the beat period is being charged
across beats. All of this was found by hand on the first internal capture,
which is why it is in the tool now. The
build also lifts the edit-mode gate on telemetry, since a clock running is
not edit mode; USB MIDI is still required and a key must be held, because the
frame is sent from the pressure path.

This makes two splits no external measurement reaches. Edge-to-claim contains
the FIFO wait, `clock_service` and factory note selection; the remainder
contains the wait for the flush, pitch remap and gate call. Under the maxima
build that remainder is NOT the difference of the two published cells: they
are independent maxima and need not come from the same beat. The internal
pair measures the remainder directly instead, on a source that spends nothing
before its claim.

### What it answered

**The delay is spent after the stamp, inside the firmware.** The whole path
on the scope under telemetry load was 1.6 ms mean; the firmware's own view of
its accepted edge to the gate raise was 1.42 ms mean over 509 frames. About
0.18 ms, 11%, is spent before the stamp. **Interrupt latency and input
conditioning are exonerated**: this is firmware time, not a hardware floor.

Read that pair with its limit in view. The 1.6 ms scope figure came from a
capture on image `47b7a75d` and the 1.42 ms telemetry figure from a later one
on `44ff1b4c`. The two builds differ only in the startup clearing below, so
the comparison holds, but it is not one simultaneous capture and a
simultaneous one has not yet been taken.

### What the reported figures are, and what they are not

Both fields are running MEANS over the same accepted samples. The historical
MAX was removed to make room for the boundary that localises the delay; it was
the least robust statistic and had already been invalidated twice by one bad
sample.

The shim times against `0x6240`, the stamp of the edge the dequeue is
actually acting on, **not** `0x623c`, the ISR's newest accepted stamp.
`0x623c` is whatever the input has done since, so with any queue depth at all
it charges a beat's gate raise to an edge that did not cause it.

A whole-path sample too large for the 14-bit field is DISCARDED from both
sums and their shared count. Clamping is what the older MAX diagnostic used
to publish as `16383`, exactly `0x3fff`: one beat behind a drained backlog
claimed 8.74 ms for a path whose whole span is under four. The edge is marked
timed before validation, so a discarded sample cannot retry against an
unrelated later gate.

The edge-to-claim boundary must be no greater than its edge-to-gate sample.
If it is not, neither sum nor the count moves. `latencySplitsAtClaim()` checks
both exact boundaries in the emitted image, then negative-controls that guard
by forcing the claim after the gate and proving the physical gate still rises
while both means reject the sample. `latencyIgnoresABacklog()` separately
proves a drained FIFO backlog moves neither mean.

`tools/test_clock.py --mode latency` builds the diagnostic so that test and
`latencyCellsCleared()` run against a real image. Without it both detect an
ordinary build and skip, which is not a test.

The accumulators are cleared by the startup initialiser. On the instrument an
older build came up holding old RAM and its published mean exceeded its MAX;
the harness could not have caught it, because `fresh()` zeroes RAM 0x0-0x8000
and those cells only ever started clean in emulation. `latencyCellsCleared()`
now seeds every whole-path and split cell before invoking the real startup.

`scan_profiler` claims the same five cells -- `0x6032`, `0x6034`, `0x6038`,
`0x603c`, `0x6040` -- with its own `0x3fff` clamp on `0x6032`, and its
dispatcher wrapper runs whenever it is on. A build carrying both would
publish the profiler's clamped worst dispatch through the telemetry fields
the latency reader decodes, and the reader could not tell. `tools/build.py`
refuses that build: any two of `scan_profiler`, `telemetry_smoothing`,
`latch_probe` and `clock_latency` are rejected as claiming the same two
telemetry fields. Enable one at a time.

That refusal is both toolchains'. It reached the browser build late: the
first check lived in `build.py` alone, and `web/generate.py` reads
`INTERNAL_DEFAULTS` into the bundle without passing through it, so for a
while the two assemblers disagreed about which configurations were legal --
with `sweep.py` pinning images on the premise that both verify them. The
check now sits at module scope in `tools/options.py`, which both import, so
neither can build a conflicting pair. `build.py`'s own check is left in place
as defence in depth.

It guards `INTERNAL_DEFAULTS` rather than a merged config, which is the right
scope: a diagnostic is only reachable by editing that default in the first
place. `tools/test_clock.py --mode latency` edits exactly one of them, so it
builds.

Compiled in but switched off, the option moves four bytes of the shipped
image — the initialization marker at `0x8001ab6e` and `0x8001ad1e`, which
hashes the assembler source. Nothing else changes, and both pins were moved
for those four bytes alone.

### What the split answered, and why it now reports maxima

Measured on the instrument 2026-08-30, owner configuration, 1233 of 1254
frames carrying a measurement, against a simultaneous scope mean of 1.61 ms:

| | ms | share of post-stamp |
|---|---:|---:|
| edge to claim | 0.77 | 56% |
| claim to gate | 0.60 | 44% |
| edge to gate (measured whole) | 1.37 | — |
| before the ISR stamp (scope − whole) | 0.24 | — |

The running means had converged: over the last quarter of the run
edge-to-claim moved 1433→1450 units and edge-to-gate 2568→2613, about 1%.
The discard guard sits at 8.74 ms, well above the 3.62 ms ceiling, so no tail
beat was dropped from these means.

**The mean is spent roughly evenly, and that closed the question the means
could answer.** No single dominant term: 0.77 ms of FIFO, `clock_service` and
note selection, then 0.60 ms of flush wait, remap and gate. Note that 0.60 ms
is about what pure 1 kHz flush quantization would cost on its own — an
asynchronous claim waits 0.50 ms on average for the next tick — leaving
roughly 0.10 ms of actual downstream work. That is an inference from a mean,
not a measurement: 0.6 ms of genuine work reads identically.

**It could not touch the tail, which is the whole problem.** The target is a
1–2 ms maximum and the instrument measures 3.36 ms peak to peak. A mean of
1.37 ms says nothing about where a 3.62 ms outlier came from, and every
structural suspect has already been refuted by direct experiment: the scan,
the dequeue wait, coarse-grained DAC dispatch and the attack-age guard.

One further suspect is eliminated here on the code rather than the bench. The
pitch-ordered selector at `0x8001da00` was the one thing in edge-to-claim that
could plausibly produce the doc's flat-topped, discrete-step distribution. It
cannot: it is a fixed 29-slot scan, roughly three instructions per unheld slot
plus one rank call per held key, with no sorting and no unbounded search. Even
ten keys held is a few hundred instructions — single-digit microseconds at
60 MHz, three orders of magnitude short of 3.4 ms.

So the same two boundaries are now published as maxima, and the internal beat
is timed too. The pair decides between two different fixes. If max
edge-to-claim approaches max edge-to-gate the tail is upstream of the claim,
and a settle computed from the accepted-edge stamp — rather than counted from
the claim, which is what `clock_settle_scans` does today and why the settle
experiment could not localise anything — absorbs it, sized at max
edge-to-claim. If max edge-to-claim sits well below, the tail is downstream
and no deadline computed at the claim reaches it.

Two limits on that fix, worth stating before the measurement rather than
after. `0x60ee` is counted down in whole milliseconds by the 1 ms task and the
claim lands at an arbitrary phase against it, which is why a nominal 5 ms
settle measured 5.17 ms minimum; that phase survives any deadline, so the
floor is about 1 ms rather than the 0.8 ms tier. And the deadline has to be
bounded by the acquired period: at the contract's 200 Hz ceiling a 4 ms
deadline is most of a beat.

### Where the internal beat's settle actually starts

**Superseded twice over.** The two-dispatch structure this section traces is
what the pending service removed, and the claim-2 counting that was this
section's fix is the settle-before-pitch defect the COUNT target corrected —
see **The COUNT target and the pending service**. The tracing stands as the
reason the structure was worth removing.

The internal capture above measured claim-to-gate at min 4.23 ms, max 8.54 ms
against a 5 ms nominal settle, and that looked impossible: a countdown of
whole milliseconds cannot spread 4.31 ms. The premise was wrong. **The settle
does not start at the claim.**

Three pieces of the code decide this, and they were traced rather than
assumed.

`clock_ms_tick` spends `0x60ee` only while `0x625b` reads 3. Nothing writes 3
except `clock_fast_trigger`'s phase A. So the countdown does not begin until
an event-17 dispatch has run.

`clock_settle` is reached for the internal beat from the arp advance, which
the factory runs at `0x80004f66`, and the fast trigger runs in the wrapper at
`0x8001a684` — earlier in the same dispatch. The claim is therefore made
*after* the only context that could act on it has already gone by, so phase A
is at minimum one whole dispatch away.

Event 17 is not the timer. The timer POSTS it into the 32-entry ring at
`0x8001030c`; the main loop at `0x80007c5a` pops ONE event per pass. The
countdown is punctual, but both phases queue.

So claim-to-gate contains **two dispatches, not one**:

| | |
|---|---|
| claim, in the factory half of dispatch *k* | `clock_settle` writes claim 2, `0x60ee` = 5, stamps `0x62f0` |
| dispatch *k+1* | phase A stages the pitch, claim ← 3 — the countdown starts here |
| 4.0–5.0 ms | five decrements by the punctual 1 ms task, at an arbitrary phase against it |
| next dispatch after that | phase B raises the gate |

`settleStartsAtPhaseA()` in `ClockRegression.java` proves this without a
model: it claims a beat, then runs ten milliseconds of the 1 ms task with no
dispatch at all and finds `0x60ee` untouched at 5 and the claim still 2. One
dispatch later the claim is 3 and the countdown starts falling. Withholding
the dispatches puts claim-to-gate at 22 ms for a nominal 5 ms settle.

**The nominal is 6 ms, not 5.** `internalDispatchModel()` runs the internal
beat through the same ring model `loopModel()` uses for the external one, and
every configuration that does not overflow returns a mean of 5.96–6.00 ms.

#### The specific suspicion, refuted

`clock_output` really does leave a claimed step alone on every path. The
guard is the first thing in the function after the frame — `LD.UB` of
`0x625b`, `CP.W 0x1`, `BR{hi}` to the return — so claims 2 and 3 exit before
any other branch is reached. The pitch scan cannot complete a step the flush
has claimed, and the 5 ms scan grid is not what is adding the milliseconds.
The assertion in **What changed** holds; this is not a defect in the
two-phase claim.

The glide decline is ruled out too, for this build specifically. A decline
would hand the beat back to the 5 ms scan and cost up to a scan period, but
`declinedGlideJitter()` shows that in a blend build the scan derives
`0x2eee = 0` from every portamento source, so the branch is unreachable — and
`pressure_portamento = true` is the shipped default and the owner's
configuration. The sequencer's own glide (`seq_glide`) can write a nonzero
rate, but the capture was the arp.

#### What the model does not explain

Queueing alone tops out at **1.43 ms** of spread before the ring overflows,
against 4.31 ms on the instrument. No modeled loop rate brackets both
hardware bounds. That is a shortfall of a factor of three and should be
stated as one.

It is not, however, an inconsistency. The structure spans the measured range
exactly as it stands:

| | |
|---|---|
| floor | ~4.0 ms — five decrements with phase A landing just before a tick, and both dispatch waits near zero, which a ring already holding two posted flushes gives |
| ceiling | ~5.0 ms of countdown plus the two dispatch waits |

Observed min 4.23 ms sits just above that floor. To reach the 8.54 ms
maximum the two waits need to sum to about 3.5 ms — roughly 1.75 ms each,
which is the same order as the 3.60 ms maximum the external path already
measures upstream of its claim. Nothing here is out of range.

What the model cannot do is REACH those waits, and the reason is stated in
`loopModel`'s own comment: it models the dispatch SLOT and not the handler's
run time. A real event-17 pop runs the interpolator, the fast trigger, the
factory flush and the arp advance before the next pop can happen; the model
charges none of that. So the model establishes the mean and the mechanism,
and is the wrong instrument for the tail. **This is a limit of the model, not
evidence against the mechanism** — and equally, not evidence for it.

One separate weakness, found while tracing and worth recording even though
the figures above do not require it. The external half of the shim times each
CONSUMED EDGE exactly once, using `0x6040`, because otherwise a gate raise
that no edge caused gets charged to a stale stamp — that guard was added
after the first shim reported a 5.64 ms spread where the scope saw 3.36 ms.
**The internal half has no equivalent.** It consumes `0x62f0` on ANY gate
raise, so a latched key, a rest completing, or anything else the arp does
between beats will take an outstanding claim's stamp and publish the interval
to a gate belonging to a different step. Capture 2 recorded 731 frames with a
key held and the arp running, which is the quietest case for this, but the
internal figures carry no guard that says so.

#### The measurement this was thought to contradict

The internal figures were read as irreconcilable with the scope-measured
internal gate period (min 40.77, mean 40.93, max 42.33 ms). They are not, and
the doc already contained the matching number.

**The settle experiment** below put the EXTERNAL beat on claim 2 — the same
two-phase path, the same 5 ms settle — and measured n=300: min 5.17 ms, mean
6.47 ms, max **8.56 ms**. The internal capture reads min 4.23, max **8.54**.
Those are the same distribution, on a different source, from an independent
image. The 8.5 ms maximum is a property of the claim-2 path, not an anomaly
of the internal clock, and the modeled 6 ms nominal sits right on the 6.47 ms
mean that experiment measured.

The period does not have to track claim-to-gate one for one either. Gate *n*
lands on the dispatch grid and so does gate *n+1*; their difference carries
one grid jitter differenced, while claim-to-gate carries two dispatch waits
plus the countdown's phase. Nothing requires them to be equal.

So neither hardware measurement is stale, and the reading that the internal
clock "only looked as if it met target" is not supported. What is left open
is narrower, and it is what capture 2's scope repeat (still to be taken) is
for: whether the gate-to-gate period really does stay inside 1.6 ms while
claim-to-gate spreads 4.31 ms. If it does, the two are measuring different
things exactly as argued above. If it does not, the internal clock misses
target as badly as the external one and the period figure in **What the
harness cannot measure** is the stale number after all.

## The deadline, and what it changed

The tail is upstream of the claim: max edge-to-claim 3.60 ms against max
edge-to-gate 3.61 ms. So a wait counted **from the claim** cannot reach it,
however long it is, and a wait counted **from the edge** absorbs it exactly —
a beat that arrived quickly waits longer, a beat that was held up waits less,
and both gate at edge + D.

`clock_deadline` at `0x8001bd40` does that sizing. Three changes carry it.

**The wait is computed at phase A, not at the claim, and it is an absolute
COUNT target.** `clock_settle` sets claim 2 and leaves `0x60ee` at zero;
`clock_fast_trigger` calls `clock_deadline` once the pitch is staged, and it
stores at `0x60dc` the later of `edge + (D + settle) × cpms` and
`transfer + settle × cpms` — one MUL against the cycles-per-millisecond word
at `0x6244`, no divide. The first version wrote whole milliseconds into
`0x60ee` for the 1 ms task to decrement at an arbitrary phase, which put up
to a millisecond of quantisation on every gate; the target is exact, and
every service point just compares COUNT against it, signed and wrap-safe.
Computing it at the claim instead would have had the phase-A gap added
straight back on top, which is the term being removed.

**A deadline already spent gates now.** `clock_deadline` returns the wait it
wrote; zero means it dropped the claim as well, and the caller falls through
to the gate on that same flush rather than costing another dispatch. Beats
that overran the deadline therefore keep exactly today's latency — they do
not get worse.

**The RC settle starts at the actual DAC transfer, never earlier.** An
intermediate version had the 1 ms task count under claim 2 as well as 3, to
take the phase-A dispatch out of the internal beat's mean, and the
2026-08-31 audit proved that a defect: the settle is a wait for the output
RC, and under claim 2 the pitch has not been staged — a delayed dispatch
could spend the entire configured interval while the OLD pitch was still at
the output, and the first flush then staged the pitch and raised the gate
on the same millisecond, a zero analog settle. Now phase A pushes the
staged pitch through the factory DAC transfer itself (`0x8000ba9c`, the
same routine both factory pulse paths call) and the settle leg of the
target is measured from that transfer. On a deadline build the 1 ms task
never touches a claimed step at all; on a `clock_deadline_ms = 0` build it
counts under claim 3 only, which starts the countdown at the phase-A
dispatch whose own flush sends the pitch out. The latency this re-adds to
the internal beat is then removed honestly, by the pending service below,
instead of by spending the RC wait early. Claim 1 is still not the
timer's — there `0x60ee` is a SCAN count, and spending it at 1 kHz would
fire the scan's deferred pulse five times over.

**A configured settle is ADDED to the deadline on the jitter leg, and
required from the transfer on the RC leg.** Taking `max(D, settle)` let a
settle longer than D swallow the deadline whole: measured that way,
`clock_settle_scans = 1` still ran 800-4200 us across the dequeue-starved
sweep, which is the distribution before the fix. So the edge-relative leg
is `edge + (D + settle)`, which keeps that build flat at edge + 9 ms — and
the target is the LATER of that and `transfer + settle`, so a beat so late
that its jitter budget is already gone still gives the output RC its full
wait, measured from the transfer the wait is for. Requiring both is the
audit's correction: lower latency must not be bought by spending the RC
wait while the old pitch is still at the output.

**D is bounded by half the acquired period, and does not apply at all before
one is acquired.** At the contract's 200 Hz ceiling a 4 ms deadline is most
of a beat, and a deadline longer than the beat would hold this gate under the
next edge's. The unlocked case is separate and was found on the bench of the
emulator rather than reasoned out: before acquisition the full deadline plus
`clock_service`'s 4 ms attack guard exceeds a 200 Hz period, so the FIFO
backs up while the clock is still locking -- and since acquisition happens at
the dequeue, the backlog throttles the very thing that would clear it. An
unlocked clock has no steady period to be steady against, so it keeps the
older behaviour and gates as soon as it can.

**The claim is cleared on transport changes.** `seq_transport` at
`0x8001d688` drops it beside where it drops `0x60ee`, the FIFO and the
in-flight flag. Without a deadline only a beat with a settle configured ever
held a claim; with one every external beat does, so a claim surviving a STOP
would put a trigger out after the transport had stopped. `seq_release` does
not clear it -- that cave is packed to the byte, and it was never the full
discard path anyway, leaving `0x6237` and the queue alone too.

`clock_deadline_ms` defaults to **4** — the next whole millisecond above the
3.60 ms maximum. Zero restores the previous behaviour, with one correction
kept: the block is still assembled but no pool word reaches it,
`clock_settle` goes back to `claimFor(clock_settle_scans)`, and the 1 ms
task spends the countdown under claim 3 only, so even the comparison mode
cannot spend the RC settle before the pitch has been transferred.

### The COUNT target and the pending service

Two dispatch waits used to sit inside every claimed beat, one on each side
of the wait: the claim was made in one dispatch, phase A could only run in
the NEXT event-17 pop, and when the wait expired the gate could only rise
in the pop after that. Each wait is a slot in the same 32-entry ring
everything else posts into — up to ~1.75 ms of tail apiece by the
instrument's own upstream measurements — and neither is necessary, because
the audit's direct probe showed the pulse routines transfer the DAC
themselves: event 17 is a way the DAC gets flushed, not the way.

So the main-loop wrapper at `0x8001b980` now services pending output
directly, around the dispatcher: `clock_service`, the pending service, ONE
dispatcher pop, the pending service again. The call is `clock_fast_trigger`
itself — the same attack-age guard, the same glide decline, the same claim
ownership, the same remap entered past the per-scan chain — so nothing runs
more often except a handful of claim-check instructions, and the per-scan
tuning, vibrato and housekeeping chains are untouched. Phase A therefore
runs on the very pass whose dequeue made the claim, the DAC transfer at
phase A is the wrapper's own call, and a target that expires is gated a
pass later at most, on the pulse routine's own transfer. The 1 kHz flush
still reaches the fast trigger too; whichever context arrives first takes
the step, the claim serialises them, and all of it is main-loop context —
the dispatcher was never anything else.

Model figures, like for like against the tables below (model microseconds;
the dispatch slot is charged, not handler run time, so these are lower
bounds): the dequeue-starved sweep reads min 4000 us — the deadline
exactly, since the countdown's whole-millisecond phase error is gone — with
spread 400-600 us and mean about 4.2 ms across every service rate, and the
widest spread over the whole loop-rate sweep fell from 1775 us to 654 us.
The internal beat's mean is the settle itself, 5000 us for a nominal
5000, with zero modeled spread, against 5961-6000 us before.
`settleStartsAtTheTransfer()` proves the RC interval is spent from the
transfer with every dispatch withheld, and `pendingGatesWithoutADispatch()`
proves a claimed external beat gates at edge + deadline through the bare
wrapper alone. None of this is a hardware bound; the measurement protocol
below is unchanged and still owed.

### The pitch bleed the first deadline image showed, and its two causes

The COUNT-target image (`4e857b01`) was the first deadline build to reach the
instrument. It measured 2.3 ms of jitter, down from 3.36 ms, and the owner
reported the clock audibly bleeding into the pitch signal. Two separate
faults, both found in the source and both fixed; neither is verified on
hardware yet.

**A RAM collision, and the worse of the two.** The gate's COUNT target was
put at `0x60a8`. That is not free RAM: `0x60a2-0x60dc` is the 29-entry latch
pitch-stamp array, and `0x60a8` is stamp **slot 3** — so the target word
covered slots 3 and 4. In a latch build every claimed external beat wrote a
raw COUNT value over two latched notes' pitches, and every latch stamp wrote
a pitch over the gate's target. The pitches moved with the clock because
COUNT does. The build's RAM map did not catch it because the cell was inside
a region already declared for the stamps; the target is at `0x60dc` now — the
free word between the stamps and the blend cells — and both it and the held
pitch below are declared in their own right, so the overlap check that exists
for exactly this will fire if anything lands on them again.

**The pitch ran ahead of the gate it belonged to.** A deadline holds the GATE
at edge + D. Nothing held the PITCH, by two routes:

* Phase A staged the step's new note into DAC slot 2 to size the wait, and
  the next 1 kHz flush transferred it — as much as a whole deadline before
  the trigger, with the previous note's gate still up.
* The 200 Hz scan writes slot 2 from the glide engine, and a snap glide puts
  the full new pitch there on the first scan after the step. `clock_output`
  guarded step COMPLETION under a claim, never the scan's own store.

Pre-deadline firmware fired pitch and gate on one flush (claim 1, one
transfer), so this window was about zero. Any deadline build opens it, which
makes this the deadline design's own defect rather than the COUNT target's.

Both are closed for an external beat with **no settle configured**, which is
the only case where the early pitch was never wanted:

* The fast trigger now asks the deadline BEFORE it computes or stages the
  pitch. Phase A's one duty is to size the wait; if the target is ahead it
  returns without touching the output, and the fire pass — claim 3, or the
  same pass when the target turns out to be already spent — computes and
  stages the pitch on the gate's own transfer.
* `clock_output` republishes what the DAC is showing at `0x609c` on every
  unclaimed scan, and puts it back, slot and last-sent mirror together, on a
  claimed one. The scan and the flush are separate dispatcher events, so no
  transfer can run between the scan's store and the restore.

A **configured settle is left alone**: it exists so the CV travels before the
trigger, so on those builds the pitch is still meant to go out first, and
`clock_deadline` still transfers it at phase A. `pitchWaitsForItsGate()`
asserts whichever of the two the build asked for, and tells them apart by
counting entries to the factory DAC transfer rather than being told.

The harness had never checked WHEN the pitch reaches slot 2 relative to the
gate, which is exactly the hole both routes went through.

### What the audit of that fix found

An independent audit of the fix above found three faults in it and one in the
capture validator. All four are corrected; none is verified on hardware.

**The held pitch was published from the wrong context.** The first version
republished it from every *unclaimed* scan, which made it depend on a scan
landing between one gate and the next claim. At the 200 Hz ceiling there need
not be one, and a claimed scan then restored a note OLDER than the one that
had just gated. It is captured at the CLAIM now, in `clock_settle`, which has
no phase to be caught out by: one writer, at the moment the step is claimed.

**The internal beat's settle was spent on the old pitch.** `holdPitchToGate()`
is the EXTERNAL settle's answer, and the internal beat shares the fast path.
Deciding before staging for it transferred whatever was already in the buffer
and spent the whole RC interval on the note already out, leaving the new one
none. That revision read the SOURCE at 0x6236: an external claim 2
decides first, an internal claim 2 keeps the original order — stage, transfer,
then size the wait from that transfer. `settleStartsAtTheTransfer()` could not
see this because it checked the target and gate timestamps and never the
transferred VALUE; `internalSettleTransfersTheNewPitch()` checks the value.

**An external edge could overwrite a pending internal step.** A deadline build
zeroes the countdown at the claim, and an internal step in flight sets neither
`0x6237` nor `0x60ee`, so an arriving edge passed the dequeue guard, was taken
off the FIFO, and advanced note selection over a step that had been selected
and never gated. The claim byte owns an unfinished beat now, so the dequeue
guard asks it. The edge stays queued; the deadline is capped at half the
acquired period, so the hold cannot outlast a beat.

**A run of cleared telemetry frames was counted as several clears.** A cell
stays zero until a valid sample arrives, so an honest hand-off read twice
before the first measurement was rejected as contaminated, and
`--external-start` skipped one zero row rather than the run. Entry into a
contiguous zero run counts once now, and the run is skipped whole. The
rejections the kept frames exist for are unchanged.

Two checks were widened rather than added: `pitchWaitsForItsGate()` was
passing vacuously in four of six configs because their fixture repeats the
step pitch, and the pool fall-through check matched only literal `emit("...")`
branches, which left every computed branch in the clock caves — most of them —
invisible to it.

### Pending steps keep their source across input changes

GPIO presence (`0x6236`) can change while an internal step is waiting. The
FIFO guard preserves its note, but reading presence again at phase A used
the external settle for that internal note. An edge during phase B also made
the scan restore an external held-pitch cache that the internal step had never
captured. These were two ways to lose the internal RC interval.

Pending output now uses **external step ownership at `0x6237`** throughout:
claim creation, phase A, deadline selection, scan restoration and diagnostic
attribution. `clock_pulse` sets it before selecting an external output step;
completion clears it. Internal steps leave it zero, and restart/transport
cancellation clears both it and the claim. The ISR never writes it. Input
presence still changes immediately to suppress subsequent internal advances,
but a queued edge cannot change the source of a beat already selected.

No extra RAM or instructions are needed: the source loads use the existing
owner byte. The COUNT deadline and configured RC intervals are unchanged.

`anEdgeWaitsForAPendingStep()` now tests external arrival both before phase A
and during the settle, at 25 and 60 MHz. It checks the actual transferred
pitch, intervening scans/flushes, the full internal RC wait, diagnostic source,
and eventual completion of the queued external edge.

The held-pitch regression also now compares against the actual last gate's
DAC value. Its former equality guard compared total outputs, including four
warm-up gates, against a counter starting at zero, so it could never fail.
It now runs 20-beat 200 Hz streams at three scan phases and two CPU timebases,
checks pitch at scans and pending flushes, and requires changing pitches and
claimed scans. External-settle builds deliberately send pitch early and keep
the separate configured-settle test. Both repaired regressions were run on
known-bad images to verify that their assertions fail for the original faults.

### What it measures, in the model

**These are the millisecond-countdown deadline's figures**, kept as the
baseline the COUNT target and pending service above improve on; their own
figures are in that section.

`loopModelJitter()` and `internalDispatchModel()` before and after, same
model, same configurations. These are model microseconds: `loopModel` charges
the dispatch SLOT and not the handler's run time, so the absolute figures are
a lower bound. The comparison is like for like.

The dequeue-starved sweep is the clearest of them, because its whole point
was that a slower `clock_service` widened the spread:

| clock_service | before | after |
|---|---:|---:|
| 1000 Hz | 800 us | 800 us |
| 600 Hz | 2000 | 800 |
| 400 Hz | 2200 | 800 |
| 300 Hz | 3200 | 800 |
| 250 Hz | 3600 | 800 |
| 200 Hz | 4200 | 800 |

**Flat.** The `clock_settle_scans = 1` build is flat at 800 us too, at
edge + 9 ms, once the settle is added rather than maxed. How long the dequeue took no longer reaches the gate, which is the
property the deadline was built for and the one no previous change had.

Across the loop-rate sweep the widest external spread went from 4200 us to
1775 us, and the mean settled at about 4.3 ms — the deadline itself. The
internal beat went from a 5961-6000 us mean for a nominal 5000 us settle to
4962 us, and its widest spread from 1428 us to 714 us.

**The trade is latency for jitter, and it is not free.** Every clocked
trigger now goes out about 4 ms after its edge instead of 0.3-3.6 ms after
it. That is the point — a constant offset is not jitter — but it is a real
4 ms, it is audible against a tight external sequencer, and `0` turns it off.

### What is NOT verified

No hardware. The instrument figures quoted throughout are from before this
change, and the model that says the fix works is the same model whose tail
could not reproduce the instrument in the first place. What the model does
establish is structural and holds regardless of its absolute scale: the gate
is now placed relative to the edge, so terms upstream of the claim cannot
reach it. The flat dequeue row is that property, measured.

The floor is no longer the millisecond countdown or the event-17 transfer:
the target is exact COUNT and the gate goes out on the pulse routine's own
transfer from whichever service point reaches it first. What remains under
the gate is main-loop service delay — how often the wrapper actually runs
between dispatcher work on a loaded instrument — plus the driver and
interrupt execution itself, and neither is established by the model or the
fixture. That is the number the hardware protocol below has to produce
before the default deadline is lowered.

### Two consequences worth knowing

**The scan can no longer complete an external step.** With a deadline the
beat is claim 2 throughout, and `clock_output` leaves claims above 1 alone.
That is deliberate: a scan reaching the step first would gate it on the 5 ms
grid, which is the jitter being removed. It costs nothing on the instrument —
the scan is event 3 out of the same 32-entry ring as event 17, so a
dispatcher not running the flush is not running the scan either — but four
regression fixtures were relying on it, having driven `service()` and
`scan()` without ever flushing or ticking the 1 ms timer. They now drive
whole dispatches.

**The no-dequeue window is wider.** `clock_service` refuses to dequeue while
a pulse is pending, and a deadline is pending for as long as it runs, so the
next edge waits a few milliseconds longer than it used to. The main loop
retries every pass, so this costs latency on the next beat and not a beat.
The period bound is what keeps it from mattering at the top of the range.

## Repeatable checks

```sh
python3 tools/test_clock.py            # six builds; see below
python3 tools/test_persistence.py
python3 tools/avr32/sweep.py
python3 web/test_configs.py
python3 tools/test.py --golden
```

The first command builds six images without rewriting the flashers, then
executes their bytes using ClockRegression.java. Three carry the shipped
timing — clock-only, clock+sequencer, and the `pressure_fix = false` build
that turns output smoothing off while leaving clock division on. The other
three run the jitter tests only. `settle-scans` builds
`clock_settle_scans = 1` and `no-gate-settle` builds `gate_settle_scans = 0`,
the two settings that used to decide whether the trigger rode the flush at
all; `latency` builds the `clock_latency` diagnostic so its own tests run
against a real image instead of detecting an ordinary one and skipping. Neither settle is a build option — both are
constants in tools/options.py — so the driver edits the constant around the
build and restores it in a finally, dropping tools/__pycache__ with it: the
edit changes one digit, so the file keeps its length, and a same-second
restore would otherwise leave CPython holding stale bytecode and every later
build in the run silently using the wrong constant.
It checks ISR hooks, register/stack preservation, gate ownership, chatter,
spent lows, duty/phase sensitivity, delayed dispatch, ordered pitch output,
division under jitter, timeout, FIFO overflow, COUNT wrap, warm restart and
rests/ties. Startup, main-loop and 1 ms callback pointers are exercised too,
as is the 1 kHz DAC flush through the dispatcher's own jump-table entry.
It also measures edge-to-rise jitter across a locked clock walked over the
scan grid and beat-to-rise jitter across an internal tempo walked over the
same grid, asks the firmware what settle it was built with rather than
assuming it, proves in `settleStartsAtTheTransfer()` that the RC wait is
spent from the DAC transfer and cannot begin before the pitch has gone out,
proves in `pendingGatesWithoutADispatch()` that a claimed beat gates at
edge + deadline through the bare main-loop wrapper with every dispatch
withheld, runs the internal beat
through the same ring model as the external one in `internalDispatchModel()`,
holds both to the 1–2 ms target on any build that carries a deadline —
detected from the emitted image, not a build flag — checks a key played with
the arp and sequencer off is not claimed, checks the pitch the flush stages is the one a later scan reaches,
checks the flush leaves the per-scan vibrato chain alone, and runs a whole
clock with the scan before the flush and again with it after.
Missing completion markers and emulator instruction-budget exhaustion fail
the run, even if Ghidra itself exits zero.

The persistence runner additionally executes `SequenceTransportRegression.java`
for both sequencer variants: real pad PLAY/STOP/CLEAR, all three physical
switch positions, changes during playback, factory RATE/setup, normal arp
handback and stale-queue cancellation. Its full external sweep runs with
the physical arp OFF; persistence's own clock sweep keeps the arp ON and
a changed preset held. Separate tests save on release and check clock
continuation after a modeled pause, not physical flash timing.

The clock, persistence and sequence-edit harnesses run the actual factory
pitch pass: glide-rate lookup, floating-point slew, bend, clamp, remap,
DAC-slot write and output hook. Ghidra 12.1.3's AVR32 SLEIGH inserts `BFINS`
at the wrong bit offset, so the harnesses model that instruction themselves;
the factory code around it still executes from the emitted image. They model
peripheral boundaries and do not validate analog settling, interrupt response
latency on a loaded board, or the electrical waveform at the jack. The
transport harness also models the three unchanged factory soft-float calls in
tempo conversion; its enable gates, raw-input conditioning, rate table lookup
and setup/teardown execute from the firmware image.

## What the harness cannot measure

**Everything in this section and the four below it is the instrument and the
model BEFORE the deadline.** The measurements stand — they are what the fix
was built from — but several of the conclusions were superseded by it, and
each of those is marked where it appears. See **The deadline, and what it
changed** above for the current numbers.

`riseJitter()` and `internalJitter()` drive `service()` and `flush()` every
1000 us, so the spread they report cannot exceed one tick whatever the
firmware does. They establish that the trigger takes the flush **claim**
instead of waiting for the 5 ms scan. They are not a latency measurement,
and the 0.8 ms and 0 ms figures above must not be quoted as hardware
numbers.

Measured on the instrument, image `1a5b8110`, external clock, n=1150:
min 258 us, mean 1.55 ms, max 3.62 ms, sigma 1.04 ms — **3.36 ms peak to
peak**, against a 1–2 ms target. Identical for a square wave and a
descending saw, and not Gaussian (range/sigma 3.23 where noise at that
count gives about 6.5), so the spread is neither analog nor the input
conditioning. It is flat-topped with its mean at 0.38 of the range, which
is a discrete step count, not a continuous delay.

`loopModelJitter()` models the structure this section blames for the
remaining latency: the timer POSTS event 17 at 1 kHz, the 200 Hz pitch pass
is a separate ring event, and the factory dispatcher takes ONE event per
main-loop pass. Swept over main-loop rates and added ring traffic, **none of
the sixteen models reproduces the instrument**, and the reason is
structural: below saturation the ring drains and the spread stays near one
loop period (widest seen 1.5 ms), and above it the 32-entry ring overflows,
which no playing instrument does. Event-queue depth is therefore a real
term but a small one — it cannot account for 3.36 ms, and the cause of the
measured spread is still unidentified.

That measurement has now been taken. Internal clock at ~25 Hz, gate period
over n=500: mean 40.93 ms, min 40.77 ms, max 42.33 ms, sigma **216 us** — a
tight core with rare late outliers, against the 1.47 ms sigma a trigger
carrying the external spread would have produced. The internal beat raises
its gate on the same event 17, so the shared path adds no significant
**variable** delay and the spread belongs to the external path alone. Note
the limit of that inference: period jitter cancels a constant offset, so it
says nothing about a fixed delay the shared path may add. It bounds the
jitter, not the latency.

That inference was later put under strain by the internal claim-to-gate
capture, which measured a 4.31 ms spread on a path this paragraph calls free
of significant variable delay. The two are reconcilable and neither
measurement is stale — see **The measurement this was thought to contradict**
above — but part of that spread was a real defect, the settle not starting
until phase A, and it is fixed.

`loopModelJitter()` can reproduce the scale only by artificially starving
`clock_service`: at 250–300 Hz that model produces min 200–400 us, max 3.6 ms
and spread 3.2–3.6 ms. It is a calibrated hypothesis, not a measurement, and
the dequeue move below directly refuted its proposed wait. What remains unique
to the external path is the work from FIFO entry through `clock_service` and
factory note selection until `clock_settle` claims the selected note.

**That starved sweep is exactly what the deadline flattened, and it is the
clearest evidence the fix does what it claims.** Its whole point was that a
slower `clock_service` widened the spread — 800, 2000, 2200, 3200, 3600,
4200 us as the poll rate fell from 1 kHz to 200 Hz. With a deadline built it
reads 800 us at every one of those rates. The work named in the paragraph
above is still done, and still takes as long; it simply no longer reaches the
gate, because the gate is placed from the edge stamp rather than from the
claim that work leads to.

A second blind spot turned up alongside the first. The fast path declines
when `0x2eee != 0` -- a real portamento time -- and sends the beat back to
the 5 ms scan, but no jitter test ever set that cell, so every figure here
is for a snapping glide. `declinedGlideJitter()` now states the glide's
condition alongside the jitter. In a `pressure_blend` build the scan derives
`0x2eee = 0` from every source tried and zeroes a written value on the next
pass, so the decline is unreachable there and the portamento knob does not
enter the external jitter. That is what makes the scan-rate experiment below
interpretable: only one mechanism is left to scale with the scan.

### The scan-rate experiment, and what it refuted

A build with `scan_period_ms = 10` instead of 5 (image `6113f63f`, verified
at `0x80007c0c` as `MOV R10,0xa`) was flashed and measured. Had the dequeue
polled at the scan's cadence the spread should have doubled to about 6.7 ms.
It did not move: range 3.28 ms against 3.36 ms, max 3.59 ms against 3.62 ms
(n=500: mean 1.11 ms, sigma 833 us).

The ceiling is therefore **not** derived from the scan. What did change is
the mean, down from 1.55 ms to 1.10 ms, the distribution skewing earlier
(mean at 0.24 of the range, from 0.38). Halving the event-2 post rate
removed about 450 us of average delay, which is the queueing term at the
size the model predicted — small.

Two terms, then: a small scan-dependent queueing delay, and a larger
scan-independent one setting a hard ceiling near 3.6 ms. This experiment
refuted the scan as the source of that ceiling; the later dequeue move also
refuted waiting for `clock_service` to run. It did not localise the synchronous
work after dequeue or the sequence from FIFO entry through note selection to
`clock_settle`; that is the boundary the split diagnostic now measures.

The 4 ms attack-age guard at `0x8001ca80` was considered for that ceiling
and rejected on the code rather than on the fit. Its bound is exactly right:
`0x6244` holds cycles per millisecond, `LSL R11,0x2` makes four, and the
trigger declines and retries while the last output is younger than that. But
`0x6254` is stamped only on the two trigger-fire paths, so at 13–35 Hz the
previous output is 28–76 ms old and the guard cannot bite. The matching
ceiling is a coincidence, recorded here so the next reader does not spend
the same hour on it.

### The settle experiment, and what it settled

`clock_settle_scans = 1` (image `ec525515`) puts the external beat on claim
2: the flush stages the pitch, the millisecond timer spends a 5 ms settle,
and a later flush raises the gate. The rise is therefore re-timed onto the
1 ms timer, downstream of the claim. Measured, n=300: min 5.17 ms, mean
6.47 ms, max 8.56 ms, sigma 1.06 ms.

Subtract the settle and it is the same distribution as the shipped build:
min 0.17 against 0.26, mean 1.47 against 1.55, max 3.56 against 3.62, range
3.39 against 3.36, sigma 1.06 against 1.04. Every moment matches within
sampling noise.

**This experiment proves less than it first appears, and the reading below
was wrong.** It was taken as showing the jitter is upstream of the claim,
because a downstream re-timing would have absorbed it. The settle is a
countdown spent by the timer, so it adds a delay relative to the claim; it
does not align the rise to an absolute grid. Variance before the claim passes
through it, and so does variance after it. Inserting a constant offset cannot
localise a variable one.

That countdown is NOT started at the claim, which an earlier version of this
paragraph said and which the internal capture later disproved: it starts at
phase A, one event-17 dispatch later. See **Where the internal beat's settle
actually starts** above. The three figures here are the external clock's
measurement of exactly that path, and they are what the internal capture
should be compared against.

What the experiment does establish is worth keeping. The minimum was 5.17 ms
for a nominal 5 ms settle — five decrements of `0x60ee` by the 1 ms task —
which could not be under about 16 ms if that task ran near 300 Hz. **The 1 ms
task is punctual.**

### The dequeue move, and its null result

Acting on the mistaken reading above, the dequeue was moved off the main-loop
wrapper onto that 1 ms tick and measured at image `82789d97`: min 337 us,
mean 1.67 ms, max 3.74 ms, sigma 957 us, n=200. Range 3.40 ms against the
3.36 ms it was meant to fix. **No change.** Combined with the punctual 1 ms
task, that is direct evidence the dequeue wait was never the term, and the
change was reverted at `554283a`.

### What the trigger's pulse width excludes

**The conclusion here no longer follows.** The pulse-drop path performs its
own direct DAC transfer (see **What changed**), so the spike's width does
not read the event-17 grid and a narrow width distribution cannot establish
dispatch cadence. The measurements themselves stand.

If the DAC transfer in the factory event-17 handler were dispatched on a
coarse grid, an asynchronous external edge would wait a random fraction of it
while an in-phase internal beat waited a constant — which would fit every
row above. The spike's rise and fall both go out on that transfer, so its
WIDTH reads the grid directly. Measured on the internal clock: mean 4.66 ms,
min 4.51, max 4.90, sigma 182 us — a range of 390 us, and the external clock
about the same. A 3.4 ms grid would have produced a bimodal width spanning
3.4 ms. **The transfer is fine-grained, and identical on both sources.**

Two honest limits on the historical dequeue model. It is calibrated to the
measurement rather than a direct observation of either rate, and its mean
(1.9–2.1 ms) sits above the instrument's 1.55 ms because it polls on a
perfectly regular grid where a real main loop does not. Its scale fit; its
shape did not, and the direct dequeue move overruled it.

On hardware, compare input and output edge counts at 0.5, 10, 150, 180,
199 and 200 Hz using both sources; exercise /1, /2 and /8, tempo changes,
rests/ties, unplug/replug and a long idle. Inspect the conditioned input if a
saw still doubles or misses. A nonzero counter at 0x6258 distinguishes queue
overload from rejected or unobserved input transitions.
