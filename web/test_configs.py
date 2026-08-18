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
SCALES = ["tunings/Sabat II (C-rooted).scl", "tunings/ADDAC Just Intonation.scl",
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


# name -> (toml edits, browser options)
CONFIGS = [
    ("defaults",          [], {}),
    ("arp_off",           [(r"^latching_arp = true", "latching_arp = false")],
                          {"latching_arp": False}),
    ("knobs_off",         [(r"^remap_knobs = true", "remap_knobs = false")],
                          {"remap_knobs": False}),
    ("pressure_off",      [(r"^pressure_fix = true", "pressure_fix = false")],
                          {"pressure_fix": False}),
    ("portamento_off",    [(r"^pressure_portamento = true", "pressure_portamento = false")],
                          {"pressure_portamento": False}),
    ("buchla_volts",      [(r"^volts_per_octave = 1.0", "volts_per_octave = 1.2")],
                          {"volts_per_octave": 1.2}),
    ("pitch_correction",  [(r"^pitch_correction = false", f'pitch_correction = "{CAL}"')],
                          {"pitch_correction": calibration_rows()}),
    ("tunings_one",       [(r"^alternate_tunings = false",
                            'alternate_tunings = ["tunings/12TET.scl"]')],
                          {"alternate_tunings": scala(["tunings/12TET.scl"])}),
    ("tunings_three",     [(r"^alternate_tunings = false",
                            "alternate_tunings = [" + ", ".join(
                                f'"{s}"' for s in SCALES) + "]")],
                          {"alternate_tunings": scala(SCALES)}),
    ("historical",        [(r"^pitch_correction = false", f'pitch_correction = "{CAL}"'),
                           (r"^alternate_tunings = false",
                            "alternate_tunings = [" + ", ".join(
                                f'"{s}"' for s in SCALES) + "]")],
                          {"pitch_correction": calibration_rows(),
                           "alternate_tunings": scala(SCALES)}),
]


def main() -> None:
    base = (REPO / "config" / "218e.toml").read_text()
    rows, failures = [], 0
    for name, edits, options in CONFIGS:
        text = base
        for pattern, replacement in edits:
            text, n = re.subn(pattern, replacement, text, flags=re.M)
            if n != 1:
                raise SystemExit(f"{name}: edit {pattern!r} matched {n} times")
        text = re.sub(r'^output_hex\s*=\s*".*"', 'output_hex     = "build/_web.hex"',
                      text, flags=re.M)
        text = re.sub(r'^updater\s*=\s*".*"\n', "", text, flags=re.M)
        cfg_path = REPO / "config" / "_web.toml"
        cfg_path.write_text(text)
        try:
            built = subprocess.run(
                [sys.executable, "tools/build.py", "--no-ghidra", "--config", str(cfg_path)],
                capture_output=True, text=True, cwd=REPO)
            if built.returncode != 0:
                rows.append((name, "build.py failed", "-")); failures += 1; continue
            sha = re.search(r"SHA-256 ([0-9a-f]{64})", built.stdout).group(1)

            (TMP / "_opts.json").write_text(json.dumps(options))
            js = subprocess.run(
                [str(JSC), "web/generated.js", "web/sha256.js", "web/buildlib.js",
                 "web/test_properties.js", "--", str(TMP / "_opts.json"),
                 "mac/firmware/218eV3_v369_DFU.hex", str(TMP / "build.properties"),
                 "config/_web.toml"],
                capture_output=True, text=True, cwd=REPO)
            out = (js.stdout + js.stderr).strip()
            props_ok = out.startswith("IDENTICAL")

            # And the whole image, not just the settings that feed it.
            (TMP / "_opts.json").write_text(json.dumps(options))
            e2e = subprocess.run(
                [str(JSC), "web/generated.js", "web/sha256.js", "web/buildlib.js",
                 "tools/avr32/encoder.js", "tools/avr32/runtime.js",
                 "tools/avr32/program.js", "web/build.js", "web/test_e2e.js", "--",
                 str(TMP / "_opts.json"), "mac/firmware/218eV3_v369_DFU.hex"],
                capture_output=True, text=True, cwd=REPO)
            m = re.search(r"SHA ([0-9a-f]{64})", e2e.stdout)
            web_sha = m.group(1) if m else (e2e.stdout + e2e.stderr).strip()[:60]
            image_ok = web_sha == sha
            rows.append((name,
                         "identical" if props_ok else out.replace("\n", "\n      "),
                         sha[:12] if image_ok else "MISMATCH " + str(web_sha)[:20]))
            if not (props_ok and image_ok):
                failures += 1
        finally:
            cfg_path.unlink(missing_ok=True)
            (REPO / "build" / "_web.hex").unlink(missing_ok=True)

    width = max(len(r[0]) for r in rows)
    print(f"\n{'config'.ljust(width)}  {'build.properties':16s}  image")
    for name, result, image in rows:
        print(f"{name.ljust(width)}  {result:16s}  {image}")
    print(f"\n{len(rows) - failures}/{len(rows)} match")
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
