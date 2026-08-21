# What this firmware changes

Custom firmware for the **Buchla 218e V3**, built from your own copy of
Buchla's v36.9 image. Everything below is optional — the builder page turns
each part on or off, and anything left off should (hopefully) behave exactly as the factory
firmware does.

---

## Pressure

The factory response is effectively flat until about 85% of the way down, so
most of the travel does nothing and the last fraction does everything. This
replaces it with a linear response across a calibrated window.

- **Calibration.** Floor and ceiling are set in the build (561 and 847 by
  default).
- **Edit-mode knob 1** re-ranges the whole calibration. Capacitive sensing
  couples through you to ground, and something as ordinary as lifting your
  feet off the floor shrinks the signal by about 30% — that is a different
  calibration, not a different pressure. The knob multiplies floor and
  ceiling together, so the response stays identical and simply moves to where
  your body puts it.
- **Edit-mode knob 4** adds an optional curve on top, levels 0 to 4. **The
  default is 0 — off.** 

## Pitch

- **Scaling options** 1V/oct or 1.2V/oct.
- **Alternate tunings.** Up to three Scala files are compiled into the image
  and selected from the front panel. Switching tuning does not send you back
  to the trimmer — the shift is computed so the reference key stays put.
- **Per-note calibration** Correcting the 208's own tracking
  error.

## Portamento

Pressure-controlled glide: press harder and the glide shortens.

## Arpeggiator

- **Latching** — the arpeggio holds after you lift your hands.
- **Knob 1** sets note order, from strict press order through to fully
  random.
- **Knob 2** sets rhythm, from even pulses to increasingly irregular
  spacing.
- **Knob 3** sets the octave span.

## Vibrato

- **Knob 4** adds global vibrato, depth and rate rising together, up to
  about a third of a semitone.

---

## Going back

The download that built your firmware also carries the stock v36.9 image you
uploaded, and the flasher offers it in the same list. Choosing it removes
every change described here.

---

Build instructions, the option reference, and how each patch is applied are in
[BUILD.md](BUILD.md).
