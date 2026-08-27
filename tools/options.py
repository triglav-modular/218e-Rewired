#!/usr/bin/env python3
"""Expand the seven user options into the full internal build settings.

config/218e.toml holds seven switches.  Everything else that used to be
configurable is frozen at the value the shipped firmware was built and tested
with, and lives in INTERNAL_DEFAULTS below.

This is a front-end only: it produces exactly the settings dict tools/build.py
already consumed, so the generators, the safety checks, the Ghidra assembler
and the JavaScript toolchain underneath are all unchanged.

    latching_arp        = true/false     arp switch: latch / factory
    remap_knobs         = true/false     knobs 1-4: arp+vibrato / factory
    pitch_correction    = "<csv>"/false  per-key offsets, or a flat ramp
    alternate_tunings   = [scl,...]/false  up to 3 Scala files, or factory
    volts_per_octave    = 1.2 / 1.0      pitch ramp scaling
    pressure_fix        = true/false     the reworked pressure path, or factory
    pressure_portamento = true/false     pitch follows relative pressure
"""

from __future__ import annotations

import copy
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

# Frozen default behaviour for every setting the simplified config does not
# expose.  Generated once from the historical full config; the seven user
# options in config/218e.toml override the few fields they name.  Not a
# user surface — edit the option set in build.py, not these constants.
INTERNAL_DEFAULTS = {   'arp': {'latch_match_tolerance': 8, 'switch': 'latch'},
    'diagnostics': {   'factory_gain_shift': 3,
                       'latch_probe': False,
                       'pressure_ab_switch': False,
                       'scan_profiler': False,
                       'telemetry_smoothing': False},
    'knobs': {   'knob1': 'arp_order',
                 'knob2': 'arp_rhythm',
                 'knob3': 'arp_octaves',
                 'knob4': 'vibrato'},
    'midi': {'poly_default': 'off'},
    'pitch': {   'calibration_csv': 'calibration/218e-pitch-calibration.csv',
                 'dac_counts': 4096,
                 'dac_gain': 4.09,
                 'dac_vref': 2.5},
    'portamento': {   'blend_filter_shift': 2,
                      'blend_hysteresis': 3,
                      'blend_slew_taper': 1,
                      'blend_slew_shift': 2,
                      'pressure_blend': True,
                      'zero_snap': True},
    'pressure': {   'black_key_scale': 1.2,
                    'calibration': {   'ceiling': 847,
                                       'floor': 561,
                                       'trim_min': 0.70,
                                       'trim_mode': 'scale',
                                       'trim_span': 512},
                    'common_mode': True,
                    'curve': {   'default_level': 0,
                                 'knob_max_level': 4,
                                 'onset_db': -10.0,
                                 'onset_fade': 60,
                                 'span': 913},
                    'error_diffusion': True,
                    'multi_key': 'max',
                    'output_smoothing': 5,
                    'proximity_reference': 300,
                    'resolution_bits': 4,
                    'smoothing_taps': 8},
    'vibrato': {'dither': 0},
    'arp_order': {'knob1_orders': 0},
    'knob4': {'octaves': 0},
    # Knob 2: 'randomness' is what 1.x does, 'patterns' turns the knob into a
    # bank selector over step masks, 'swing' delays every other step.
    'knob2': {'mode': 'randomness', 'patterns': [], 'lengths': []},
    'timing': {'gate_settle_scans': 1, 'scan_period_ms': 5},
    'tuning': {   'base_units': 485,
                  'reference_key': 9,
                  'slots': [   'tunings/Sabat II (C-rooted).scl',
                               'tunings/5-Limit JI with Septimal 7th.scl',
                               'tunings/12TET.scl'],
                  'units_per_octave': 484}}

# A flat pitch ramp: no per-key correction, every semitone exactly 100 cents.
# 79 rows, matching what the firmware reads (semitones 0..78).
FLAT_CALIBRATION = REPO / "build" / "_flat_pitch_calibration.csv"


def _write_flat_calibration() -> Path:
    FLAT_CALIBRATION.parent.mkdir(exist_ok=True)
    rows = ["# Generated: pitch_correction = false -> an ideal ramp, no per-key trim.",
            "Semitone;Note;Key;Offset_Cents;Source"]
    rows += [f"{i};;;0.000000;flat" for i in range(79)]
    FLAT_CALIBRATION.write_text("\n".join(rows) + "\n")
    return FLAT_CALIBRATION


# What each option is allowed to be.  Without this a quoted "false" is a
# non-empty string and therefore true, so `latching_arp = "false"` turned the
# arpeggiator on; and `volts_per_octave = true` was accepted as 1.0, because a
# Python bool is an int and compares equal to one.  A misspelled name was
# simply not there, and the default quietly took its place.
OPTION_TYPES = {
    "latching_arp":        bool,
    "remap_knobs":         bool,
    "pressure_fix":        bool,
    "pressure_portamento": bool,
    "volts_per_octave":    float,
    "pitch_correction":    (bool, str),
    "alternate_tunings":   (bool, list),
    "knob1":               str,
    "knob2":               str,
    "knob3":               str,
    "knob4":               str,
    "arp_patterns":        list,
    "sequencer":           bool,
    "clock_divide":        bool,
}

# What each preset knob may be set to.  The first entry of each is what
# remap_knobs = true has always meant, so a config that never mentions a knob
# keeps the behaviour it had.
KNOB_ROLES = {
    "knob1": ("order", "orders", "factory"),
    "knob2": ("spacing", "swing", "patterns", "factory"),
    "knob3": ("octaves", "factory"),
    "knob4": ("vibrato", "trn", "factory"),
}


def check(options: dict) -> None:
    """Refuse anything that is not one of the seven, as the thing it must be."""
    unknown = sorted(set(options) - set(OPTION_TYPES))
    if unknown:
        known = ", ".join(sorted(OPTION_TYPES))
        raise SystemExit(
            f"unknown option{'s' if len(unknown) > 1 else ''}: "
            f"{', '.join(unknown)}\n  the options are: {known}")

    for name, allowed in OPTION_TYPES.items():
        if name not in options:
            continue
        value = options[name]
        # bool before float: True is an int in Python and would pass as one.
        if allowed is float:
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                raise SystemExit(
                    f"{name} must be a number, not {type(value).__name__}: "
                    f"{value!r}")
            continue
        if allowed is bool:
            if not isinstance(value, bool):
                raise SystemExit(
                    f"{name} must be true or false, not "
                    f"{type(value).__name__}: {value!r}"
                    + ('\n  a quoted "false" is a string, and every non-empty '
                       'string is true' if isinstance(value, str) else ""))
            continue
        if not isinstance(value, allowed):
            names = " or ".join(t.__name__ for t in allowed)
            raise SystemExit(
                f"{name} must be {names}, not {type(value).__name__}: {value!r}")
        # bool passed the tuple check above, but only False means anything:
        # "true" carries no path and no files, and expand() used to die on it
        # with a raw TypeError instead of a sentence.
        # arp_patterns is the exception: true means the CLIX bank, which is a
        # real answer, where a tuning or a calibration cannot be conjured.
        if value is True and name != "arp_patterns":
            raise SystemExit(
                f"{name} = true says nothing to build from - give it "
                + ("a CSV path" if name == "pitch_correction"
                   else "a list of Scala files") + ", or false")
        if name == "alternate_tunings" and isinstance(value, list):
            for i, entry in enumerate(value):
                # A slot is a Scala file, or that file paired with a .kbm
                # keyboard mapping: ["scale.scl", "scale.kbm"].
                if isinstance(entry, (list, tuple)):
                    if not 1 <= len(entry) <= 2 or not all(
                            isinstance(part, str) for part in entry):
                        raise SystemExit(
                            f"alternate_tunings[{i}] as a pair must be "
                            f'["scale.scl", "map.kbm"]: {entry!r}')
                elif not isinstance(entry, (str, dict)):
                    raise SystemExit(
                        f"alternate_tunings[{i}] must be a filename, a "
                        f'["scale.scl", "map.kbm"] pair, or \'factory\', '
                        f"not {type(entry).__name__}: {entry!r}")


def expand(options: dict) -> dict:
    """Seven options in, full internal settings out."""
    check(options)
    cfg = copy.deepcopy(INTERNAL_DEFAULTS)

    def want(name: str, default):
        return options.get(name, default)

    # 1. Latching arpeggiator ------------------------------------------------
    cfg["arp"]["switch"] = "latch" if want("latching_arp", True) else "factory"

    # 2. What each preset knob does -----------------------------------------
    # remap_knobs still sets them all at once, and each knob can then be named
    # individually - which is the only way to say "arpeggiator octaves on knob
    # 3, preset voltage on the rest", and the only way to reach the roles that
    # did not exist in 1.x.
    remap = want("remap_knobs", True)
    live = {"knob1": "arp_order", "knob2": "arp_rhythm",
            "knob3": "arp_octaves", "knob4": "vibrato"}
    cfg["knobs"] = {k: (v if remap else "factory") for k, v in live.items()}
    roles = {}
    for knob, allowed in KNOB_ROLES.items():
        role = want(knob, None)
        if role is None:
            role = allowed[0] if remap else "factory"
        if role not in allowed:
            raise SystemExit(
                f"{knob} = {role!r} is not one of "
                + ", ".join(repr(a) for a in allowed))
        roles[knob] = role
        cfg["knobs"][knob] = "factory" if role == "factory" else live[knob]
    # The sequencer's controls live on a pad chord.  It does NOT require
    # remap_knobs: with factory knobs the chord still works - the arm freezes
    # the active pad so the selecting press cannot change a preset, and the
    # knob-moved refusal reads the editor cave, which every build carries.
    # Where along the bend strip the line between a rest and a tie falls, in
    # the strip's own position units - 0 at one end, 1023 at the other, which
    # is the range state+0x306 is read and clamped over.  512 is the middle,
    # and the middle is the rule; the number is here so a real strip can move
    # it if its ends do not reach.
    cfg["sequencer"] = {"on": bool(want("sequencer", False)),
                        "strip_halfway_units": 512,
                        # How far a tie slides into the note after it, on the
                        # factory's own 0..1024 glide scale.  Another number
                        # that wants a real instrument to settle.
                        "tie_glide_rate": 60,
                        # How long pad 4 has to be held before pads 1-3 mean
                        # anything, in ~5 ms scans.  300 is a second and a half.
                        "chord_hold_scans": 300}
    # The arp rate knob divides an external clock once one is locked.
    cfg["clock"] = {"divide": bool(want("clock_divide", False))}
    cfg["arp_order"]["knob1_orders"] = 1 if roles["knob1"] == "orders" else 0
    cfg["knob4"]["octaves"] = 1 if roles["knob4"] == "trn" else 0
    cfg["knob2"]["mode"] = (roles["knob2"] if roles["knob2"] in ("patterns", "swing")
                            else "randomness")

    # 3. Per-key pitch correction -------------------------------------------
    correction = want("pitch_correction", False)
    if correction:
        path = REPO / correction
        if not path.exists():
            raise SystemExit(f"pitch_correction: no such file: {correction}")
        cfg["pitch"]["calibration_csv"] = correction
    else:
        cfg["pitch"]["calibration_csv"] = str(
            _write_flat_calibration().relative_to(REPO))

    # 4. Alternate tunings ---------------------------------------------------
    tunings = want("alternate_tunings", False)
    if tunings:
        if isinstance(tunings, str):
            tunings = [tunings]
        if not 1 <= len(tunings) <= 3:
            raise SystemExit("alternate_tunings: give one to three Scala files")
        for entry in tunings:
            for name in ([entry] if isinstance(entry, str) else list(entry)):
                if not (REPO / name).exists():
                    raise SystemExit(f"alternate_tunings: no such file: {name}")
        # Unused slots fall back to the instrument's own temperament, so the
        # edit-mode selector always has three valid tables to switch between.
        cfg["tuning"]["slots"] = [
            entry if isinstance(entry, str) else list(entry) for entry in tunings
        ] + ["factory"] * (3 - len(tunings))
    else:
        cfg["tuning"]["slots"] = ["factory"] * 3

    # 8. Knob 2's bank, when knob 2 is set to patterns ----------------------
    # Each entry is a string of steps - a dot is a rest, anything else a hit -
    # or a [pattern, length] pair to make it repeat sooner than it is written.
    # Left out, the bank is the CLIX fills.
    patterns = want("arp_patterns", None)
    if patterns:
        masks, lengths = [], []
        for i, entry in enumerate(patterns):
            if isinstance(entry, (list, tuple)):
                if len(entry) != 2:
                    raise SystemExit(
                        f"arp_patterns[{i}] as a pair must be "
                        '["x.x.x...", length]')
                text, length = entry
            else:
                text, length = entry, None
            if not isinstance(text, str):
                raise SystemExit(
                    f"arp_patterns[{i}] must be a string of steps, "
                    f"not {type(text).__name__}: {text!r}")
            steps = [c for c in text if not c.isspace()]
            if not 1 <= len(steps) <= 32:
                raise SystemExit(
                    f"arp_patterns[{i}] has {len(steps)} steps; "
                    "it must have 1 to 32")
            mask = sum(1 << k for k, c in enumerate(steps) if c != ".")
            if mask == 0:
                raise SystemExit(
                    f"arp_patterns[{i}] is all rests — it would never sound")
            if length is None:
                length = len(steps)
            if not isinstance(length, int) or isinstance(length, bool) \
                    or not 1 <= length <= 32:
                raise SystemExit(
                    f"arp_patterns[{i}] length must be a whole number 1..32")
            masks.append(mask)
            lengths.append(length)
        cfg["knob2"]["patterns"] = masks
        cfg["knob2"]["lengths"] = lengths

    # 5. Volts per octave ----------------------------------------------------
    # The pair is a hardware limit, not a shortlist.  The keyboard spans 6.5
    # octaves and the DAC reaches 10.22 V (4096 counts / 400.59 per volt), so
    # the steepest scaling that still fits the top note is 1.573 V/oct.  A
    # vintage 2 V/oct would want 13.00 V and run out at semitone 61, leaving
    # the top 17 keys pinned at the ceiling playing one pitch; it was weighed
    # and declined rather than shipped with a dead upper register.
    vpo = want("volts_per_octave", 1.2)
    if vpo not in (1.0, 1.2):
        raise SystemExit("volts_per_octave must be 1.2 (standard Buchla) or 1.0")
    cfg["pitch"]["volts_per_octave"] = vpo

    # 6. Pressure response fix ----------------------------------------------
    # One switch over the whole reworked pressure path.  Off returns every
    # activation pool to the factory pointer, so the original curve, filter and
    # single-key sourcing all run untouched.
    if not want("pressure_fix", True):
        cfg["pressure"]["multi_key"] = "factory"
        cfg["pressure"]["common_mode"] = False
        cfg["pressure"]["error_diffusion"] = False
        cfg["pressure"]["output_smoothing"] = 0
        cfg["_pressure_factory"] = True

    # 7. Pressure-based portamento ------------------------------------------
    # The blend weights pitch by per-key pressure from the corrected-pressure
    # cache, and the only thing that fills that cache is the reworked pressure
    # pass.  With pressure_fix off the cave is unreachable, the cache stays at
    # its power-up zeros, and the blend's own zero-sum guard skips it forever —
    # the option would build and then silently do nothing.  Refuse instead.
    blend = want("pressure_portamento", True)
    if blend and not want("pressure_fix", True):
        raise SystemExit(
            "pressure_portamento needs pressure_fix: the blend weights pitch by "
            "per-key pressure, and only the reworked pressure path measures it. "
            "Set pressure_portamento = false as well, or turn pressure_fix on.")
    cfg["portamento"]["pressure_blend"] = blend
    cfg["portamento"]["zero_snap"] = blend

    return cfg
