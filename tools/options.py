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
    'portamento': {'blend_slew_shift': 2, 'pressure_blend': True, 'zero_snap': True},
    'pressure': {   'black_key_scale': 1.2,
                    'calibration': {   'ceiling': 893,
                                       'floor': 592,
                                       'trim_mode': 'scale',
                                       'trim_span': 512},
                    'common_mode': True,
                    'curve': {'default_level': 0, 'onset_db': -10.0, 'onset_fade': 60, 'span': 913},
                    'error_diffusion': True,
                    'multi_key': 'max',
                    'output_smoothing': 5,
                    'proximity_reference': 300,
                    'resolution_bits': 4,
                    'smoothing_taps': 8},
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


def expand(options: dict) -> dict:
    """Seven options in, full internal settings out."""
    cfg = copy.deepcopy(INTERNAL_DEFAULTS)

    def want(name: str, default):
        return options.get(name, default)

    # 1. Latching arpeggiator ------------------------------------------------
    cfg["arp"]["switch"] = "latch" if want("latching_arp", True) else "factory"

    # 2. Remap knobs 1-4 ----------------------------------------------------
    remap = want("remap_knobs", True)
    live = {"knob1": "arp_order", "knob2": "arp_rhythm",
            "knob3": "arp_octaves", "knob4": "vibrato"}
    cfg["knobs"] = {k: (v if remap else "factory") for k, v in live.items()}

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
        for name in tunings:
            if not (REPO / name).exists():
                raise SystemExit(f"alternate_tunings: no such file: {name}")
        # Unused slots fall back to the instrument's own temperament, so the
        # edit-mode selector always has three valid tables to switch between.
        cfg["tuning"]["slots"] = list(tunings) + ["factory"] * (3 - len(tunings))
    else:
        cfg["tuning"]["slots"] = ["factory"] * 3

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
