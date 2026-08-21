# What this firmware changes

Custom firmware for the **Buchla 218e V3**, built from your own copy of
Buchla's v36.9 image. Everything below is optional — the builder page turns
each part on or off, and anything left off behaves exactly as the factory
firmware does.

---

## Pressure

The factory response is effectively flat until about 85% of the way down, so
most of the travel does nothing and the last fraction does everything. This
replaces it with a **linear** response across a calibrated window, which is
the change that actually makes pressure playable.

- **Calibration.** Floor and ceiling are set in the build (561 and 847 by
  default), so the instrument plays correctly straight after a flash with no
  calibration ritual.
- **Edit-mode knob 1** re-ranges the whole calibration. Capacitive sensing
  couples through you to ground, and something as ordinary as lifting your
  feet off the floor shrinks the signal by about 30% — that is a different
  calibration, not a different pressure. The knob multiplies floor and
  ceiling together, so the response stays identical and simply moves to where
  your body puts it. **The knob runs clockwise-down**: fully anticlockwise is
  the feet-up case, and the default sits at about 4 of 10.
- **Edit-mode knob 4** adds an optional curve on top, levels 0 to 4. **The
  default is 0 — off.** Linear is the good setting; the curve is there if you
  want a softer bottom end, not because the response needs it.
- **Resolution.** The signal is averaged before anything nonlinear touches it
  and the leftovers of each conversion are carried into the next reading, so
  the output resolves far finer than one sensor count. Sensor noise and finger
  tremor become visible on a scope — that is the amplification working, not a
  fault.
- **Smoothing** interpolates each new reading over 1–8 milliseconds, which
  removes the stepping without adding lag you can feel.

## Pitch

Replaces an external tuning module entirely.

- **1 V/octave** at the pitch jack, with per-semitone correction measured on
  the instrument. `volts_per_octave = 1.2` rescales to the 208's native law
  instead, if that is what your system expects.
- **Alternate tunings.** Up to three Scala files are compiled into the image
  and selected from the front panel. Switching tuning does not send you back
  to the trimmer — the shift is computed so the reference key stays put.
- **Per-note calibration** from a CSV, correcting the 208's own tracking
  error rather than the keyboard's.

## Portamento

Pressure-controlled glide: press harder and the glide shortens. With several
keys held the sounding pitch is a weighted blend rather than a jump, so
chords slide rather than snap. Can be switched to the factory behaviour.

## Arpeggiator

- **Latching** — the arpeggio holds after you lift your hands.
- **Knob 1** sets note order, from strict press order through to fully
  random.
- **Knob 2** sets rhythm, from even pulses to increasingly irregular
  spacing.
- **Knob 3** sets the octave span.
- **Knob 4** adds global vibrato, depth and rate rising together, up to
  about a third of a semitone.

## Timing

Note-on is reported as soon as the key contact is certain rather than after a
fixed settle period, which removes a consistent delay from every note.

## MIDI

Polyphonic MIDI output defaults to **off**, so the instrument comes up as a
monophonic controller unless you turn it on. The factory default is on.

---

## Resetting

Pads 1+2+3+4 performs the factory reset, and it works normally under this
firmware — the values it restores are **this firmware's** defaults, including
the pressure calibration and curve level, not Buchla's.

## Going back

The download that built your firmware also carries the stock v36.9 image you
uploaded, and the flasher offers it in the same list. Choosing it removes
every change described here.

---

Build instructions, the option reference, and how each patch is applied are in
[BUILD.md](BUILD.md).
