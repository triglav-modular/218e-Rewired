#!/usr/bin/env python3
"""Regenerate the encoder corpus from Ghidra, for the current assembler.

The corpus is the JS encoder's ground truth: every (instruction, bytes) pair
Ghidra emitted.  It went stale once - the assembler changed twice while the
corpus kept passing, because the test can only fail on entries the corpus
has.  This makes regeneration one command, and stamps the corpus with the
sha256 of the program it was extracted from so tools/test.py can refuse a
stale one instead of trusting it.

    python3 tools/avr32/make_corpus.py        # needs Ghidra (config/local.toml)

Four configurations, because finish() only prints a block's listing when the
block is enabled and !feature() branches vanish in builds where the feature
is on:

    default   the shipped configuration, via tools/build.py
    allon     every block and feature forced on
    featoff   every block on, every feature off
    nomulti   allon, minus multi-key pressure (its guarded branches differ)
"""
from __future__ import annotations

import hashlib
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
BUILD = REPO / "build"
sys.path.insert(0, str(REPO / "tools"))


def flip(base: dict[str, str], name: str) -> dict[str, str]:
    out = dict(base)
    for key in out:
        if key.startswith("block."):
            out[key] = "1"
        elif key.startswith("feature."):
            if name == "featoff":
                out[key] = "0"
            elif name == "nomulti" and key == "feature.multi_key_pressure":
                out[key] = "0"
            else:
                out[key] = "1"
    return out


def main() -> None:
    import build as buildmod

    # A fresh default build through Ghidra: writes build.properties and
    # build/assemble.log for the shipped configuration.
    print("default build through Ghidra...")
    r = subprocess.run([sys.executable, str(REPO / "tools" / "build.py")],
                       capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(r.stdout[-800:] + r.stderr[-800:])

    base: dict[str, str] = {}
    order: list[str] = []
    for line in (BUILD / "build.properties").read_text().splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            base[k] = v
            order.append(k)

    for name in ("allon", "featoff", "nomulti"):
        props = BUILD / f"build.{name}.properties"
        flipped = flip(base, name)
        props.write_text("\n".join(f"{k}={flipped[k]}" for k in order) + "\n")
        print(f"{name} through Ghidra...")
        # run_ghidra needs only tools/firmware paths from the config.
        import tomllib
        raw = tomllib.loads((REPO / "config" / "218e.toml").read_text())
        local = REPO / "config" / "local.toml"
        tools = raw.get("tools", {})
        if local.exists():
            tools.update(tomllib.loads(local.read_text()).get("tools", {}))
        mini = {"firmware": raw["firmware"], "tools": tools}
        buildmod.run_ghidra(mini, props, BUILD / f"assemble.{name}.log")

    print("extracting corpus...")
    r = subprocess.run([sys.executable, str(REPO / "tools" / "avr32" / "extract_corpus.py")],
                       capture_output=True, text=True)
    print(r.stdout.strip())
    if r.returncode != 0:
        sys.exit(r.stderr[-800:])

    # Stamp the corpus with what it was extracted from, so a changed
    # assembler makes the staleness loud instead of silent.
    import json
    corpus_path = REPO / "tools" / "avr32" / "corpus.json"
    corpus = json.loads(corpus_path.read_text())
    stamp = hashlib.sha256((REPO / "tools" / "avr32" / "program.js").read_bytes()).hexdigest()
    if isinstance(corpus, list):
        corpus = {"program_sha256": stamp, "entries": corpus}
    else:
        corpus["program_sha256"] = stamp
    corpus_path.write_text(json.dumps(corpus, indent=0) + "\n")
    print(f"stamped against program.js {stamp[:12]}")


if __name__ == "__main__":
    main()
