#!/usr/bin/env python3
"""Report the clock-latency diagnostic from a readout CSV.

The `diagnostics.clock_latency` build repurposes the two scan-component
telemetry fields, which the readout tool labels with their pressure names:

    scan_component_a = running MAX accepted-edge-to-gate delay, cycles/32
    scan_component_b = running MIN accepted-edge-to-gate delay, cycles/32

Both are measured by the firmware itself, from the COUNT stamp the GPIO ISR
wrote at 0x623c to COUNT at the gate raise. Their difference is the spread to
compare against the scope's edge-to-trigger figure.

    python3 tools/clock_latency_report.py LEM218_PressureReadout_*.csv

A zero MIN means no sample yet -- the encoding uses 0 as "unset", so an
exactly-zero delay is not representable. That cannot occur on hardware.
"""
import csv
import sys
from pathlib import Path

CPU_HZ = 60_000_000        # the instrument's CPU frequency word at RAM 0x29cc


def ms(units: int) -> float:
    return units * 32 / CPU_HZ * 1000


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    hi = lo = None
    rows = 0
    for path in sys.argv[1:]:
        with Path(path).open(newline="") as fh:
            for row in csv.DictReader(fh):
                if "scan_component_a" not in row:
                    raise SystemExit(f"{path}: not a readout CSV")
                a, b = int(row["scan_component_a"]), int(row["scan_component_b"])
                rows += 1
                # Both are running extremes, so the last non-zero wins; take
                # the widest seen in case the instrument was power-cycled
                # mid-capture and the counters restarted.
                if a and (hi is None or a > hi):
                    hi = a
                if b and (lo is None or b < lo):
                    lo = b
    if not rows:
        raise SystemExit("no telemetry frames in that CSV")
    if hi is None or lo is None:
        raise SystemExit(
            f"{rows} frames, but the latency cells never filled. The firmware "
            "only times beats while an external clock is present (RAM 0x6236) "
            "-- check the clock is patched and locked, and a key is held so "
            "the telemetry frame is sent at all.")
    print(f"frames read          {rows}")
    print(f"min edge->gate       {lo:6d} units   {ms(lo):6.2f} ms")
    print(f"max edge->gate       {hi:6d} units   {ms(hi):6.2f} ms")
    print(f"spread               {hi-lo:6d} units   {ms(hi-lo):6.2f} ms")
    print()
    print("The scope measured 3.36 ms of edge-to-trigger spread. This figure")
    print("covers only what happens AFTER the ISR stamped the edge.")
    print("  close to 3.4 ms -> the delay is inside the firmware, after the stamp")
    print("  far below       -> the delay is BEFORE the stamp: interrupt latency")
    print("                     or input conditioning, which no firmware change reaches")


if __name__ == "__main__":
    main()
