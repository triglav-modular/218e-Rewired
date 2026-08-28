#!/usr/bin/env python3
"""Check the browser pipeline against the Python one across configurations.

For each config: build it with tools/build.py (producing build/build.properties)
and generate the same properties with web/buildlib.js, then compare byte for
byte.  Equality there means an identical image, because the assembler consuming
those properties is already proven identical to Ghidra.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
JSC = Path("/System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc")
TMP = REPO / "build"
SCALES = ["tunings/Sabat II (C-rooted).scl", "tunings/5-Limit JI with Septimal 7th.scl",
          "tunings/12TET.scl"]
CAL = "calibration/218e-pitch-calibration.csv"


def calibration_rows() -> list[dict]:
    rows = []
    for line in (REPO / CAL).read_text().splitlines():
        if line.startswith("#") or line.startswith("Semitone"):
            continue
        parts = line.split(";")
        rows.append({"semitone": int(parts[0]), "cents": float(parts[3])})
    return rows


def scala(paths: list[str]) -> list[dict]:
    return [{"name": Path(p).name, "text": (REPO / p).read_text()} for p in paths]


def mapped(pairs: list[tuple[str, str]]) -> list[dict]:
    """Scale-and-.kbm slots, in the shape the page keeps them."""
    return [{"name": Path(s).name, "text": (REPO / s).read_text(),
             "kbmName": Path(k).name, "kbmText": (REPO / k).read_text()}
            for s, k in pairs]


# name -> (toml edits, browser options)
CONFIGS = [
    ("defaults",          [], {}),
    ("arp_off",           [(r"^latching_arp = true", "latching_arp = false")],
                          {"latching_arp": False}),
    ("knobs_off",         [(r"^remap_knobs = true", "remap_knobs = false")],
                          {"remap_knobs": False}),
    ("pressure_off",      [(r"^pressure_fix = true", "pressure_fix = false"),
                           (r"^pressure_portamento = true", "pressure_portamento = false")],
                          {"pressure_fix": False, "pressure_portamento": False}),
    ("portamento_off",    [(r"^pressure_portamento = true", "pressure_portamento = false")],
                          {"pressure_portamento": False}),
    ("one_volt",          [(r"^volts_per_octave = 1.2", "volts_per_octave = 1.0")],
                          {"volts_per_octave": 1.0}),
    ("pitch_correction",  [(r"^pitch_correction = false", f'pitch_correction = "{CAL}"')],
                          {"pitch_correction": calibration_rows()}),
    ("tunings_one",       [(r"^alternate_tunings = false",
                            'alternate_tunings = ["tunings/12TET.scl"]')],
                          {"alternate_tunings": scala(["tunings/12TET.scl"])}),
    ("tunings_three",     [(r"^alternate_tunings = false",
                            "alternate_tunings = [" + ", ".join(
                                f'"{s}"' for s in SCALES) + "]")],
                          {"alternate_tunings": scala(SCALES)}),
    # A .kbm changes the key table without touching the firmware, so the two
    # builders have to agree on the mapping as exactly as on the scale.  Three
    # shapes at once: more degrees than keys, fewer, and unmapped positions.
    ("tuning_maps",       [(r"^alternate_tunings = false",
                            'alternate_tunings = ['
                            '["tunings/24TET.scl", "tunings/24TET-full.kbm"], '
                            '["tunings/24TET.scl", "tunings/24TET-neutral.kbm"], '
                            '["tunings/diatonic7.scl", "tunings/diatonic7.kbm"]]')],
                          {"alternate_tunings": mapped([
                              ("tunings/24TET.scl", "tunings/24TET-full.kbm"),
                              ("tunings/24TET.scl", "tunings/24TET-neutral.kbm"),
                              ("tunings/diatonic7.scl", "tunings/diatonic7.kbm")])}),
    # A scale that repeats at a 3/1 rewrites the factory's own octave
    # arithmetic, so the two builders have to agree about five patch sites as
    # well as the table.  All three slots, because the period is one setting.
    # The three knob options together: knob 2's gate wraps knob 1's selector,
    # so both builders have to agree about the chain as well as the bank.
    ("knob_options",      [(r"^remap_knobs = true",
                            'remap_knobs = true\nknob1 = "orders"\n'
                            'knob2 = "patterns"\nknob4 = "trn"\n'
                            'arp_patterns = ["x...x...x...x...", "x.x.x.x.", ["xx..", 4]]')],
                          {"knob1": "orders", "knob2": "patterns", "knob4": "trn",
                           "arp_patterns": ["x...x...x...x...", "x.x.x.x.", ["xx..", 4]]}),
    # Swing takes the rhythm randomiser's own hook, so the two builders have to
    # agree about which of the two the pool word names.
    ("knob2_swing",       [(r"^remap_knobs = true",
                            'remap_knobs = true\nknob2 = "swing"')],
                          {"knob2": "swing"}),
    # The sequencer adds a cave and a per-scan call, so both builders have to
    # agree about the housekeeping chain as well as the cave itself.
    ("sequencer",         [(r"^sequencer = false", "sequencer = true")],
                          {"sequencer": True}),
    # The divider takes a factory dispatcher case and adds a per-scan call, so
    # both builders have to agree about the housekeeping chain too.
    ("clock_divide",      [(r"^clock_divide = false", "clock_divide = true")],
                          {"clock_divide": True}),
    ("persist",           [(r"^sequencer = false", "sequencer = true"),
                           (r"^clock_divide = false", "clock_divide = true"),
                           (r"^persist = false", "persist = true")],
                          {"sequencer": True, "clock_divide": True, "persist": True}),
    ("persist_only",      [(r"^persist = false", "persist = true")],
                          {"persist": True}),
    ("persist_seq",       [(r"^persist = false", "persist = true"),
                           (r"^sequencer = false", "sequencer = true")],
                          {"persist": True, "sequencer": True}),
    ("persist_clock",     [(r"^persist = false", "persist = true"),
                           (r"^clock_divide = false", "clock_divide = true")],
                          {"persist": True, "clock_divide": True}),
    ("non_octave",        [(r"^alternate_tunings = false",
                            'alternate_tunings = ['
                            + ", ".join(['["tunings/BohlenPierce.scl", '
                                         '"tunings/BohlenPierce.kbm"]'] * 3)
                            + ']')],
                          {"alternate_tunings": mapped([
                              ("tunings/BohlenPierce.scl", "tunings/BohlenPierce.kbm")] * 3)}),
    ("historical",        [(r"^pitch_correction = false", f'pitch_correction = "{CAL}"'),
                           (r"^alternate_tunings = false",
                            "alternate_tunings = [" + ", ".join(
                                f'"{s}"' for s in SCALES) + "]")],
                          {"pitch_correction": calibration_rows(),
                           "alternate_tunings": scala(SCALES)}),
]


# build.py writes these beside the image it built, and a variant build
# overwrites them even though its hex is redirected to build/_web.hex.  Left
# alone, the last variant's metadata sits there describing the default image -
# build/VERSION naming a checksum no shipped file has.
METADATA = ("VERSION", "build.properties", "patch_manifest.txt", "tables.txt")


def snapshot() -> dict[str, bytes | None]:
    return {name: (REPO / "build" / name).read_bytes()
            if (REPO / "build" / name).exists() else None
            for name in METADATA}


def restore(saved: dict[str, bytes | None]) -> None:
    for name, content in saved.items():
        path = REPO / "build" / name
        if content is None:
            path.unlink(missing_ok=True)     # nothing was there to begin with
        else:
            path.write_bytes(content)


def main() -> None:
    base = (REPO / "config" / "218e.toml").read_text()
    saved = snapshot()
    rows: list[tuple[str, str, str, bool]] = []
    try:
        run(base, rows)
    finally:
        restore(saved)

    width = max(len(r[0]) for r in rows)
    print(f"\n{'config'.ljust(width)}  {'build.properties':16s}  image")
    for name, result, image, _failed in rows:
        print(f"{name.ljust(width)}  {result:16s}  {image}")
    failures = sum(1 for r in rows if r[3])
    print(f"\n{len(rows) - failures}/{len(rows)} match")
    if failures:
        raise SystemExit(1)


def run(base: str, rows: list) -> None:
    for name, edits, options in CONFIGS:
        text = base
        for pattern, replacement in edits:
            text, n = re.subn(pattern, replacement, text, flags=re.M)
            if n != 1:
                raise SystemExit(f"{name}: edit {pattern!r} matched {n} times")
        text = re.sub(r'^output_hex\s*=\s*".*"', 'output_hex     = "build/_web.hex"',
                      text, flags=re.M)
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
        cfg_path = REPO / "config" / "_web.toml"
        cfg_path.write_text(text)
        try:
            built = subprocess.run(
                [sys.executable, "tools/build.py", "--no-ghidra", "--config", str(cfg_path)],
                capture_output=True, text=True, cwd=REPO)
            if built.returncode != 0:
                rows.append((name, "build.py failed", "-", True)); continue
            sha = re.search(r"SHA-256 ([0-9a-f]{64})", built.stdout).group(1)

            (TMP / "_opts.json").write_text(json.dumps(options))
            js = subprocess.run(
                # The whole stack: the harness asks WEBBUILD.build() for the
                # properties now instead of deriving the flags a second time,
                # so it needs build.js and the assembler it pulls in.
                [str(JSC), "web/generated.js", "web/sha256.js", "web/buildlib.js",
                 "tools/avr32/encoder.js", "tools/avr32/runtime.js",
                 "tools/avr32/program.js", "web/build.js",
                 "web/test_properties.js", "--", str(TMP / "_opts.json"),
                 "firmware/218eV3_v369_DFU.hex", str(TMP / "build.properties")],
                capture_output=True, text=True, cwd=REPO)
            out = (js.stdout + js.stderr).strip()
            props_ok = out.startswith("IDENTICAL")

            # And the whole image, not just the settings that feed it.
            (TMP / "_opts.json").write_text(json.dumps(options))
            e2e = subprocess.run(
                [str(JSC), "web/generated.js", "web/sha256.js", "web/buildlib.js",
                 "tools/avr32/encoder.js", "tools/avr32/runtime.js",
                 "tools/avr32/program.js", "web/build.js", "web/test_e2e.js", "--",
                 str(TMP / "_opts.json"), "firmware/218eV3_v369_DFU.hex"],
                capture_output=True, text=True, cwd=REPO)
            m = re.search(r"SHA ([0-9a-f]{64})", e2e.stdout)
            web_sha = m.group(1) if m else (e2e.stdout + e2e.stderr).strip()[:60]
            image_ok = web_sha == sha
            rows.append((name,
                         "identical" if props_ok else out.replace("\n", "\n      "),
                         sha[:12] if image_ok else "MISMATCH " + str(web_sha)[:20],
                         not (props_ok and image_ok)))
        finally:
            cfg_path.unlink(missing_ok=True)
            (REPO / "build" / "_web.hex").unlink(missing_ok=True)


if __name__ == "__main__":
    main()
