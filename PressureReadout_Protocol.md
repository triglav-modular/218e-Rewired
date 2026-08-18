# LEM218 PressureFix USB readout

The diagnostic firmware sends telemetry only when ordinary edit mode is active
and USB MIDI is enabled. It does not emit diagnostic messages during normal
performance.

The readout uses MIDI channel 16 control changes. A complete frame is:

| CC | Meaning |
|---:|---|
| 102, 103 | Baseline-subtracted instantaneous raw pressure, 14-bit MSB/LSB |
| 104, 105 | Growing raw average over the samples since touch, rounded to whole counts |
| 106, 107 | Pressure normalized between the saved endpoints, 0–913 |
| 108, 109 | Final pressure after the curve, expanded to 0–4095 |
| 110, 111 | Saved pressure floor |
| 112, 113 | Saved full-pressure endpoint/ceiling |
| 114, 115 | Active key scan component A, before factory subtraction |
| 116, 117 | Active key scan component B, before factory subtraction |
| | *(diagnostic builds repurpose these four CCs — see below)* |
| 118 | Curve level, 0–31, and end-of-frame marker |

> **Telemetry is a diagnostic approximation, not the algorithm.** The pressure
> path runs in fixed point (see `[pressure].resolution_bits`), while these
> fields are recomputed in whole counts: the average, the 0–913 normalisation
> and the curved value are rounded, so they will not reproduce the 12-bit
> output exactly. Judge the CV by the CV; use telemetry for levels and ranges.

For every adjacent pair, `value = MSB * 128 + LSB`. The readout accepts a frame
only when all eight pairs arrive before CC 118, so a partial USB packet sequence
cannot be mistaken for a measurement.

## Measurement procedure

1. Enable Polyphonic MIDI and connect the 218e directly over USB.
2. Start `ReadLEM218_Rewired.command`, then enter ordinary edit mode.
3. Start with knob 4 fully left. Live redraw is intentionally suppressed so
   terminal input remains usable. Every touch is summarized automatically when
   it is released.
4. Calibrate with knob 1 if `trim_mode = "scale"` (it moves floor and ceiling
   together); with `trim_mode = "independent"`, knob 1 sets the ceiling and
   knob 3 the floor, in that order — the floor is held at least 32 counts
   below the ceiling. Do not touch a key while checking settings.
5. Hold the lightest useful pressure steady for two seconds, release it, and
   optionally type `min` followed by return while it is still held.
6. Repeat at a musically useful middle pressure (`mid`) and the hardest intended
   pressure (`max`).
7. At the curve setting that exhibits the problem, hold one finger stationary,
   move the other hand through the problematic range, type `proximity`, and
   press return.
8. Type `q` for a compact summary. If commands are inconvenient, simply perform
   the touches in the documented order; the automatic `TOUCH 1`, `TOUCH 2`, …
   summaries and generated CSV contain the same measurements.

The scanner computes active pressure from `scan_component_a - scan_component_b`.
`scan_difference_error` should remain close to zero because the pressure path
then subtracts its fixed 110-count baseline. On the measured instrument,
component B remained exactly 167 while component A moved with both intentional
pressure and hand proximity. The active sensor therefore has no independent
component from which a weighted subtraction could distinguish the two. The
playing firmware instead uses nearby untouched keys as a spatial proxy for the
local hand field, independently for each held key; it is an estimate, not a
separate measurement. The light/mid/max measurements are still useful for
setting the floor and ceiling. Eliminating proximity completely would require
an electrical or mechanical change that makes it distinguishable at the sensor.

## Diagnostic builds

Two `[diagnostics]` settings repurpose the scan-component fields, and the
build refuses to enable both at once. With neither set — the default — CC
114–117 carry the real scan components described above.

`telemetry_smoothing` puts the live smoothing state there instead: scan A is
the filter depth in taps, scan B the finite interpolation length in 1 ms ticks.

## Scan profiler builds

With `[diagnostics].scan_profiler = true` the firmware times every event
handler with the CPU cycle counter, and the two scan-component fields carry
the result instead of scan data:

| CC | Meaning |
|---:|---|
| 114, 115 | Worst single dispatch in the last ~280 ms window, in **cycles/32** — multiply by 32 and divide by the 60 MHz clock for seconds |
| 116, 117 | Main-loop CPU load over the same window, in **tenths of a percent** |

So `2812` in the first pair is 2812 x 32 / 60e6 = 1.5 ms, and `300` in the
second is 30.0 %. The readout tool needs no changes; it just labels them
`scan_component_a` and `scan_component_b`.

Read both with a key held in edit mode, since that is the only time telemetry
is sent. Two things make the numbers conservative rather than optimistic,
which is the right direction for deciding whether a shorter scan period fits:

- telemetry is itself being sent while you measure, and that work is included;
- the profiler times the main loop's dispatcher only, so interrupt time — USB,
  timers, ADC — is *not* counted. Treat the load as a lower bound.
