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
- The trigger **rises within 1 ms of the accepted edge**, on the 1 kHz DAC
  flush rather than the 5 ms pitch scan. Measured on the emitted firmware,
  the edge-to-rise delay was uniform over a whole scan period — 0 to 4.8 ms,
  with no fixed component — because the gate's fall was already scheduled
  from the edge itself while its rise waited for the next scan. Under the
  1 ms emulation fixture it now measures 0.8 ms peak to peak, but that
  figure is the fixture's tick rate and not a hardware bound; see
  **What the harness cannot measure** below.
- The **internal clock's beat holds the same bound**, and so does an
  external beat with a settle configured. A settle is a wait for the output
  RC, not for the scan grid: the flush stages the pitch at once, the
  millisecond timer spends the wait, and a later flush raises the gate. The
  wait itself is unchanged — `gate_settle_scans` and `clock_settle_scans`
  still mean the same number of scan periods — but it no longer rounds the
  trigger up to the next scan boundary. Under the same fixture the internal
  beat went from 5–10 ms with 4 ms of spread to a fixed 5 ms settle with
  none; the same caveat applies.
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

**The measurement below contradicts the premise of the next paragraph.** It
argues the dequeue already runs at least as often as event 17 is dispatched,
so moving it onto the flush would make it rarer. The instrument says event 17
is punctual at 1 kHz while the dequeue polls near 250–300 Hz, which makes the
move more frequent, not less. The paragraph is kept as written because the
decision it records is the owner's to revisit, not because its reasoning
still stands.

Moving the FIFO dequeue itself onto the flush was considered and is wrong.
The factory dispatcher at 0x80004c64 takes ONE event from its 32-entry ring
(0x8001030c) and returns; every jump-table arm ends in `BR{al} 0x800051b0`.
The main loop at 0x80007c5a calls `clock_service` and then that dispatcher,
once each, per iteration — so the dequeue already runs at least as often as
event 17 is dispatched, and moving it would make it rarer. What is left of
the trigger's latency is event-queue depth, and that is a floor either way:
the DAC transfer itself happens in the factory event-17 handler, which reads
state+0x354 at 0x80004fae, so nothing staged earlier moves the edge.

The claim byte at 0x625b says which of those the flush is holding: 1 to
fire on this tick, 2 to stage the pitch and start a settle, 3 while that
settle runs. Under 2 and 3 the countdown at 0x60ee is MILLISECONDS rather
than scans — `clock_settle` writes it that way when it sets the claim, the
1 ms task spends it, and `clock_output` leaves the whole step alone so the
scan cannot read those milliseconds as scans and spend them five times
over. If the glide declines the step the claim is dropped and 0x60ee is
handed back a scan count, which is exactly what declining asked for.

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

The old proposed page at 0x8001c000–0x8001c1ff is still unused. Persistence
now stores records at 0x8003e000–0x8003efff; see [PERSISTENCE.md](PERSISTENCE.md).
When enabled, its startup wrapper restores musical data before calling the
clock initializer above. Completed edit gestures can save during playback;
unfinished edits do not write.

| RAM | Meaning |
| --- | --- |
| 0x6232 | Low interval: 0 none, 1 timing, 2 qualified |
| 0x6233 | Acquired-divider latch |
| 0x6234 / 0x6235 | FIFO producer / consumer index |
| 0x6236 / 0x6237 | Input present / output step in flight |
| 0x6238 / 0x623c / 0x6240 | Low / accepted / consumed COUNT stamps |
| 0x6244–0x6253 | Cycle-based timing constants |
| 0x6254 / 0x625a | Last physical output stamp / valid flag |
| 0x6258 | Saturating FIFO-overrun count |
| 0x625b | Flush claim: 0 none, 1 fire now, 2 stage then settle, 3 settling |
| 0x6260–0x62df | Timestamp FIFO |
| 0x62f6 | Millisecond count at the last accepted edge |

The 2600 ms release is measured against the factory 1 ms counter (0x61e6),
not COUNT: COUNT is scaled by the CPU-frequency word at 0x29cc, which made
the release fire in under a second on the instrument.

## Repeatable checks

```sh
python3 tools/test_clock.py            # five builds; see below
python3 tools/test_persistence.py
python3 tools/avr32/sweep.py
python3 web/test_configs.py
python3 tools/test.py --golden
```

The first command builds five images without rewriting the flashers, then
executes their bytes using ClockRegression.java. Three carry the shipped
timing — clock-only, clock+sequencer, and the `pressure_fix = false` build
that turns output smoothing off while leaving clock division on. The other
two exist for the trigger's jitter bound alone and run the jitter tests only:
`settle-scans` builds `clock_settle_scans = 1` and `no-gate-settle` builds
`gate_settle_scans = 0`, the two settings that used to decide whether the
trigger rode the flush at all. Neither settle is a build option — both are
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
assuming it, checks a key played with the arp and sequencer off is not
claimed, checks the pitch the flush stages is the one a later scan reaches,
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
its gate on the same event 17, so **the flush and the dispatcher are
punctual**; the spread belongs to the external path alone.

The one stage the internal beat never uses is the FIFO dequeue in
`clock_service`. `loopModelJitter()`'s second sweep holds the dispatcher
punctual at 1 kHz and starves only that dequeue: at 250–300 Hz it produces
min 200–400 us, max 3.6 ms, spread 3.2–3.6 ms, bracketing the instrument's
258 us / 3.62 ms / 3.36 ms. The dequeue appears to poll at roughly the 5 ms
scan cadence rather than at 1 kHz.

A second blind spot turned up alongside the first. The fast path declines
when `0x2eee != 0` -- a real portamento time -- and sends the beat back to
the 5 ms scan, but no jitter test ever set that cell, so every figure here
is for a snapping glide. `declinedGlideJitter()` now states the glide's
condition alongside the jitter. In a `pressure_blend` build the scan derives
`0x2eee = 0` from every source tried and zeroes a written value on the next
pass, so the decline is unreachable there and the portamento knob does not
enter the external jitter. That is what makes the scan-rate experiment below
interpretable: only one mechanism is left to scale with the scan.

Two honest limits on that. It is a model calibrated to the measurement, not
a direct observation of either rate; and the model's mean (1.9–2.1 ms) sits
above the instrument's 1.55 ms, because the model polls on a perfectly
regular grid while a real main loop does not. The scale and the mechanism
fit; the shape is not fully reproduced.

On hardware, compare input and output edge counts at 0.5, 10, 150, 180,
199 and 200 Hz using both sources; exercise /1, /2 and /8, tempo changes,
rests/ties, unplug/replug and a long idle. Inspect the conditioned input if a
saw still doubles or misses. A nonzero counter at 0x6258 distinguishes queue
overload from rejected or unobserved input transitions.
