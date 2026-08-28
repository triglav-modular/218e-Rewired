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
  through /8, and interval disagreement cannot reset the divide phase.
- An absence longer than **2600 ms**, or switching the arp off while the
  sequencer is not playing, releases division. Period acquisition accepts
  intervals up to 2400 ms, leaving margin around the 2000 ms lower-frequency
  boundary.
- The normal 5 ms pitch scan permits 200 outputs/second with
  `clock_settle_scans=0`. Additional settle scans reduce that ceiling.
  Dispatcher stalls can add latency; a finite queue cannot absorb an
  indefinite stall or a sustained rate above its output capacity.

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
| 0x6260–0x62df | Timestamp FIFO |

## Repeatable checks

```sh
python3 tools/test_clock.py
python3 tools/test_persistence.py
python3 tools/avr32/sweep.py
python3 web/test_configs.py
python3 tools/test.py --golden
```

The first command builds fresh clock-only and clock+sequencer images without
rewriting the flashers, then executes their bytes using ClockRegression.java.
It checks ISR hooks, register/stack preservation, gate ownership, chatter,
spent lows, duty/phase sensitivity, delayed dispatch, ordered pitch output,
division under jitter, timeout, FIFO overflow, COUNT wrap, warm restart and
rests/ties. Startup, main-loop and 1 ms callback pointers are exercised too.
Missing completion markers and emulator instruction-budget exhaustion fail
the run, even if Ghidra itself exits zero.

The persistence runner additionally executes `SequenceTransportRegression.java`
for both sequencer variants: real pad PLAY/STOP/CLEAR, all three physical
switch positions, changes during playback, factory RATE/setup, normal arp
handback and stale-queue cancellation. Its full external sweep runs with
the physical arp OFF; persistence's own clock sweep keeps the arp ON and
a changed preset held. Separate tests save on release and check clock
continuation after a modeled pause, not physical flash timing.

The harness supplies a zero-portamento pitch result and runs the actual
remap/DAC-slot/output hook. It models peripheral boundaries and does not
validate analog settling, interrupt response latency on a loaded board,
factory floating-point glide, or the electrical waveform at the jack.
The transport harness also models the three unchanged factory soft-float
calls in tempo conversion; its enable gates, raw-input conditioning, rate
table lookup and setup/teardown execute from the firmware image.

On hardware, compare input and output edge counts at 0.5, 10, 150, 180,
199 and 200 Hz using both sources; exercise /1, /2 and /8, tempo changes,
rests/ties, unplug/replug and a long idle. Inspect the conditioned input if a
saw still doubles or misses. A nonzero counter at 0x6258 distinguishes queue
overload from rejected or unobserved input transitions.
