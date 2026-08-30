#!/usr/bin/env python3
"""Report the clock-latency diagnostic from a readout CSV.

The `diagnostics.clock_latency` build repurposes the two scan-component
telemetry fields, which the readout tool labels with their pressure names:

    scan_component_a = running MAX accepted-edge-to-gate delay, cycles/32
    scan_component_b = running MEAN of the same, cycles/32

Both are measured by the firmware itself, from the COUNT stamp the GPIO ISR
wrote at 0x623c to COUNT at the gate raise, once per accepted edge.

Both are RUNNING figures accumulated since power-up, so the last frame of a
file already holds that session's final values -- there is nothing to
aggregate. Each file is a separate session and is reported separately;
combining them across a power cycle, or across firmware builds, is
meaningless. Pass one file unless you want them compared.

    python3 tools/clock_latency_report.py LEM218_PressureReadout_<newest>.csv

The MEAN is the figure to trust. A max is a single sample and one outlier
moves it.
"""
import csv
import sys
from pathlib import Path

CPU_HZ = 60_000_000        # the instrument's CPU frequency word at RAM 0x29cc


def ms(units: int) -> float:
    return units * 32 / CPU_HZ * 1000


def session(path: Path):
    """Last frame that carries a measurement, plus the frame count."""
    last = None
    rows = 0
    with path.open(newline="") as fh:
        for row in csv.DictReader(fh):
            if "scan_component_a" not in row:
                raise SystemExit(f"{path}: not a readout CSV")
            rows += 1
            a, b = int(row["scan_component_a"]), int(row["scan_component_b"])
            if a or b:
                last = (a, b)
    return last, rows


def main() -> None:
    paths = [Path(a) for a in sys.argv[1:]]
    if not paths:
        raise SystemExit(__doc__)
    if len(paths) > 1:
        print(f"{len(paths)} files: each is a separate session, reported separately.")
        print("Only compare them if you know they are the same firmware.\n")
    for path in paths:
        last, rows = session(path)
        print(f"{path.name}   ({rows} frames)")
        if last is None:
            print("  no beat was ever timed. The firmware only times beats while an")
            print("  external clock is present (RAM 0x6236), and the telemetry frame")
            print("  only goes out while a key is held. Check both.\n")
            continue
        hi, avg = last
        print(f"  mean edge->gate   {avg:6d} units   {ms(avg):5.2f} ms")
        print(f"  max  edge->gate   {hi:6d} units   {ms(hi):5.2f} ms\n")
    print("These cover only what happens AFTER the ISR stamped the edge.")
    print()
    print("Compare them ONLY against a scope reading taken during THIS capture.")
    print("Do not compare against the earlier 1.55 ms / 3.62 ms scope figures:")
    print("sending telemetry is itself work the instrument would not otherwise")
    print("be doing, and a key must be held for it to go out at all, so this")
    print("build measures a busier board than the scope runs did. A mean above")
    print("the unloaded scope figure means the extra load, not a longer path.")
    print()
    print("With a simultaneous scope reading, mean_scope - mean_here is the")
    print("time spent BEFORE the ISR stamp -- interrupt latency and input")
    print("conditioning, which no firmware change reaches.")


if __name__ == "__main__":
    main()
