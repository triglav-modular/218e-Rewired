#!/usr/bin/env python3
"""Build many configurations both ways and check the images agree.

For each variant: tools/build.py assembles it through Ghidra, then again with
--no-ghidra through the JavaScript toolchain, and the two SHA-256s must match.  Agreement on one configuration
proves very little — the whole point of the feature gating is that different
settings emit different code — so this walks the options.

    python3 tools/avr32/sweep.py            # all variants
    python3 tools/avr32/sweep.py latch      # only variants matching a substring
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "tools"))
import build as build_mod  # noqa: E402
BASE = REPO / "config" / "218e.toml"
TEMP = REPO / "config" / "_sweep.toml"

# name -> list of (pattern, replacement) applied to config/218e.toml.
# Every one of the seven options at both settings, plus the interactions that
# one-at-a-time rows cannot reach.
VARIANTS: list[tuple[str, list[tuple[str, str]]]] = [
    ("defaults",              []),
    ("arp_off",               [(r"^latching_arp = true", "latching_arp = false")]),
    ("knobs_off",             [(r"^remap_knobs = true", "remap_knobs = false")]),
    ("pitch_correction",      [(r"^pitch_correction = false",
                               'pitch_correction = "calibration/218e-pitch-calibration.csv"')]),
    ("tunings_one",           [(r"^alternate_tunings = false",
                               'alternate_tunings = ["tunings/12TET.scl"]')]),
    ("tunings_three",         [(r"^alternate_tunings = false", 'alternate_tunings = ["tunings/Sabat II (C-rooted).scl",\n                     "tunings/5-Limit JI with Septimal 7th.scl",\n                     "tunings/12TET.scl"]')]),
    ("one_volt",              [(r"^volts_per_octave = 1.2", "volts_per_octave = 1.0")]),
    ("pressure_off",          [(r"^pressure_fix = true", "pressure_fix = false"),
                               (r"^pressure_portamento = true", "pressure_portamento = false")]),
    ("portamento_off",        [(r"^pressure_portamento = true", "pressure_portamento = false")]),
    # Interactions the one-at-a-time rows cannot reach.
    ("arp_off_portamento_off",[(r"^latching_arp = true", "latching_arp = false"),
                               (r"^pressure_portamento = true", "pressure_portamento = false")]),
    ("one_volt_corrected",    [(r"^volts_per_octave = 1.2", "volts_per_octave = 1.0"),
                               (r"^pitch_correction = false",
                                'pitch_correction = "calibration/218e-pitch-calibration.csv"')]),
    # The author's own instrument: the shipped calibration, three tunings, and
    # the 1 V/oct ramp that 208 is trimmed to.  Those go together — that table
    # was measured at that scaling — so this is the one configuration where
    # the calibration in calibration/ is the right one to use.
    ("historical_config",     [(r"^volts_per_octave = 1.2", "volts_per_octave = 1.0"),
                               (r"^alternate_tunings = false", 'alternate_tunings = ["tunings/Sabat II (C-rooted).scl",\n                     "tunings/5-Limit JI with Septimal 7th.scl",\n                     "tunings/12TET.scl"]'),
                               (r"^pitch_correction = false",
                                'pitch_correction = "calibration/218e-pitch-calibration.csv"')]),
    # The 2.0 features.  They were missing from this sweep, which is how a
    # clock hook that jumped to an invalid address reached a release build:
    # the browser-parity matrix compares two toolchains against each other and
    # both were told the same wrong thing, and the emulations called each cave
    # directly rather than through its hook.  This sweep is the only check
    # that builds every one of them through Ghidra.
    # All three ship ON, so the variants worth building are the ones that
    # turn them OFF - those are the images the defaults no longer cover, and
    # the ones whose housekeeping chain loses a call.  Persistence joined the
    # defaults after the divider knob was reversed, which is why the four
    # rows below read "volatile" rather than "persist": the combination that
    # needs proving is the one nobody gets by accident.
    ("sequencer_off",         [(r"^sequencer = true", "sequencer = false")]),
    ("clock_divide_off",      [(r"^clock_divide = true", "clock_divide = false")]),
    ("seq_and_clock_off",     [(r"^sequencer = true", "sequencer = false"),
                               (r"^clock_divide = true", "clock_divide = false")]),
    ("volatile_bare",         [(r"^persist = true", "persist = false"),
                               (r"^sequencer = true", "sequencer = false"),
                               (r"^clock_divide = true", "clock_divide = false")]),
    ("volatile_sequencer",    [(r"^persist = true", "persist = false"),
                               (r"^clock_divide = true", "clock_divide = false")]),
    ("volatile_clock",        [(r"^persist = true", "persist = false"),
                               (r"^sequencer = true", "sequencer = false")]),
    ("volatile_only",         [(r"^persist = true", "persist = false")]),
    ("knob_roles",            [(r"^remap_knobs = true",
                               'remap_knobs = true\nknob1 = "orders"\nknob2 = "patterns"\nknob4 = "trn"')]),
    ("knob2_swing",           [(r"^remap_knobs = true", 'remap_knobs = true\nknob2 = "swing"')]),
    ("arp_patterns",          [(r"^remap_knobs = true",
                               'remap_knobs = true\nknob2 = "patterns"\n'
                               'arp_patterns = ["x...x...x...x...", "x.x.x.x.", ["xx..", 4]]')]),
    ("tuning_maps",           [(r"^alternate_tunings = false",
                               'alternate_tunings = [["tunings/24TET.scl", "tunings/24TET-full.kbm"]]')]),
    ("non_octave",            [(r"^alternate_tunings = false",
                               'alternate_tunings = [' + ', '.join(
                                   ['["tunings/BohlenPierce.scl", "tunings/BohlenPierce.kbm"]'] * 3)
                               + ']')]),
    ("everything_on",         [(r"^pitch_correction = false",
                                'pitch_correction = "calibration/218e-pitch-calibration.csv"'),
                               (r"^alternate_tunings = false",
                                'alternate_tunings = ["tunings/12TET.scl"]')]),
    ("everything_off",        [(r"^latching_arp = true", "latching_arp = false"),
                               (r"^remap_knobs = true", "remap_knobs = false"),
                               (r"^pressure_fix = true", "pressure_fix = false"),
                               (r"^pressure_portamento = true", "pressure_portamento = false")]),
]

SHA_RE = re.compile(r"SHA-256 ([0-9a-f]{64})")


def audit_call_pools(image_path) -> list[str]:
    """Every MCALL in this image must read a word naming emitted code.

    Per-variant, because the bug class is per-variant: a pool that is right
    with everything on can name erased flash once the callee's block is off.
    """
    flash, _ = build_mod.parse_hex(image_path)
    word = lambda a: int.from_bytes(bytes(flash.get(a + i, 0xFF) for i in range(4)), "big")

    def called(target: int) -> bool:
        for pc in range(0x80002000, 0x80020000, 2):
            if flash.get(pc) != 0xF0 or flash.get(pc + 1) != 0x1F:
                continue
            d = (flash.get(pc + 2, 0) << 8) | flash.get(pc + 3, 0)
            if d & 0x8000:
                d -= 0x10000
            if (pc & ~3) + d * 4 == target:
                return True
        return False

    source = (REPO / "src" / "AssemblePressureFix.java").read_text()
    targets = sorted({int(m, 16) for m in
                      re.findall(r'emit\("MCALL PC\[(0x[0-9a-f]+)\]"\);', source)})
    bad = []
    for t in targets:
        if t not in flash or not called(t):
            continue
        v = word(t)
        if not (0x80000000 <= v < 0x80020000 and v % 2 == 0):
            bad.append(f"{t:#x} holds {v:#010x}")
        elif flash.get(v, 0xFF) == 0xFF:
            bad.append(f"{t:#x} -> {v:#x} (erased flash)")
    return bad

# Configurations whose image is pinned, so an unintended change shows up here
# rather than in someone's instrument.
#
# historical_config is the fully-specified build — measured calibration, three
# tunings, 1 V/oct — that the firmware shipped with before the config was
# reduced to seven options.  It no longer reproduces that old image byte for
# byte, and cannot: the init marker is a hash over the build settings AND the
# assembler source, so renaming a block or even editing a comment in
# AssemblePressureFix.java moves it.  That is the marker working as intended —
# a changed build forces a fresh power-up init.  What this pin still buys is a
# stable anchor for the most complex configuration.
# Re-pinned after the calibration defaults moved to the settings that suit the
# instrument this was measured on, and both trims were centred on them; and
# again for 2.0, which added caves and moved the first-use clear; and again
# when rests and ties moved to an absolute strip position, which moved the
# clear once more; and for interrupt-timestamped clock capture, whose FIFO
# extends that clear through 0x62df; and for canonical persistence plus
# independent sequence transport, completed-gesture persistence, and
# one-shot preview/explicit CLEAR ownership, strip gesture boundaries,
# preset-4 role isolation, the corrected up-down zone, early transpose
# ownership, held-only reverse history, and pitch-aware note ordering; and
# for the millisecond-timed clock release, latch-aware sequence recording,
# held preview/backspace, the pad-1 record toggle and preset knob pickup;
# and again for the audit corrections: recording at today's transpose with
# clamped pitches, playback without a second latch re-base, preset edits
# declining the bare-pad hold, and the unconditional first-use fill; and
# once more for the follow-up: the audition re-aimed at the allocated latch
# slot, the blend parked during playback, and partial pad touches keeping
# the preset edit's ownership; and again for the octave round: recording
# transposed in every arp position, the audition pinned to its recorded
# pitch, previews absolute while play follows the pads, pressure routed by
# slot ownership, the re-base vetoed during playback, preview end sentinels,
# no arp-OFF double audition, and the delete-pad flash — whose two new
# caves are gated with the code they call, so a latch-free or sequencer-free
# build stops emitting them instead of pointing a call pool at erased flash;
# and once more when the divider knob was reversed so /1 sits at zero, and
# persistence joined the shipped defaults — which this anchor carries too.
# and again when leaving the latch switch position stopped releasing keys
# that are still under a finger; and again for the clock_latency diagnostic,
# which moves only the four marker bytes when it is off -- the marker hashes
# the assembler source, so adding the option repins even though no shipped
# behaviour changed; and again when that diagnostic went from publishing
# running means to publishing maxima and learned to time the internal beat,
# which is once more a diagnostic-only change to a shipped image that carries
# none of it; and again when the deadline moved onto an absolute COUNT target
# with the RC settle measured from the actual DAC transfer and the main-loop
# wrapper servicing pending output around the dispatcher, which is a real
# behavioural change to every clocked build; and again when the gate's target
# moved off a latch pitch stamp it had been sharing and the external beat's
# pitch was held back to the gate's own transfer, which is behavioural for
# every deadline build and a bug fix for every latch one; and again when the
# audit of that change found the held pitch published from the wrong context,
# the internal beat's settle spent on the wrong pitch, and an external edge
# able to overwrite a pending internal step; and again when pending output
# switched from GPIO presence to stable step ownership across clock takeover;
# and when fast output began preparing the transposed target before staging it.
# Both assemblers must verify this pin.
EXPECTED = {
    "historical_config": "4f929f62d8f2a98a10ebead5c6b8a47a3c3bcc3d6d9803bbb75d9507bf6e98e6",
}


def write_variant(edits: list[tuple[str, str]]) -> None:
    text = BASE.read_text()
    for pattern, replacement in edits:
        text, n = re.subn(pattern, replacement, text, flags=re.M)
        if n != 1:
            raise SystemExit(f"edit {pattern!r} matched {n} times, expected 1")
    # Never touch the shipped image or the updater.
    text, n = re.subn(r'^output_hex\s*=\s*".*"', 'output_hex     = "build/_sweep.hex"',
                      text, flags=re.M)
    assert n == 1
    # Strip the updater list so a variant build cannot rewrite the real
    # flashers with its own checksum.  This is asserted rather than assumed:
    # it was previously a single-line regex, the key became a multi-line array,
    # the match silently stopped happening, and every sweep quietly left the
    # shipped flashers expecting a variant image.
    text, removed = re.subn(r'^updaters?\s*=\s*(?:".*"|\[[^\]]*\])\n', "",
                            text, flags=re.M | re.S)
    if removed != 1:
        raise SystemExit("could not strip [firmware].updaters from the config — "
                         "a variant build would overwrite the real flashers")
    TEMP.write_text(text)


def run(cmd: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, cwd=REPO)


def main() -> None:
    wanted = sys.argv[1] if len(sys.argv) > 1 else ""
    variants = [v for v in VARIANTS if wanted in v[0]]
    rows, failures = [], 0
    try:
        for name, edits in variants:
            write_variant(edits)
            ghidra = run([sys.executable, "tools/build.py", "--config", str(TEMP)])
            if ghidra.returncode != 0:
                reason = (ghidra.stdout + ghidra.stderr).strip().splitlines()[-1][:58]
                rows.append((name, "-", f"build.py rejected: {reason}"))
                failures += 1
                continue
            match = SHA_RE.search(ghidra.stdout)
            if not match:
                rows.append((name, "-", "no SHA in build.py output"))
                failures += 1
                continue
            sha = match.group(1)
            pool_bad = audit_call_pools(REPO / "build" / "_sweep.hex")
            if pool_bad:
                rows.append((name, sha[:12], "BAD CALL POOL: " + "; ".join(pool_bad)))
                failures += 1
                continue
            js = run([sys.executable, "tools/build.py", "--no-ghidra",
                      "--config", str(TEMP), "--expect-sha", sha])
            ok = js.returncode == 0
            note = "match" if ok else "MISMATCH"
            want = EXPECTED.get(name)
            if want and sha != want:
                note, ok = f"WRONG IMAGE (expected {want[:12]})", False
            elif want:
                note = "match + known image"
            rows.append((name, sha[:12], note))
            if not ok:
                failures += 1
    finally:
        TEMP.unlink(missing_ok=True)
        (REPO / "build" / "_sweep.hex").unlink(missing_ok=True)

    width = max(len(r[0]) for r in rows)
    print(f"\n{'config'.ljust(width)}  {'image':12s}  result")
    for name, sha, result in rows:
        print(f"{name.ljust(width)}  {sha:12s}  {result}")
    print(f"\n{len(rows) - failures}/{len(rows)} configurations agree")

    # Every variant must change the firmware.  Without this a substitution that
    # silently stopped matching would still report "match" — a green result
    # proving nothing.
    images = [r[1] for r in rows if r[1] != "-"]
    duplicates = {i for i in images if images.count(i) > 1}
    if duplicates:
        print(f"WARNING: {len(duplicates)} image(s) produced by more than one "
              f"configuration — a variant may not be taking effect")
        failures += 1
    else:
        print(f"all {len(images)} images distinct")
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
