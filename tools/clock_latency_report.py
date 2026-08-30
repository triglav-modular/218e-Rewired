#!/usr/bin/env python3
"""Report the clock-latency diagnostic from a readout CSV.

The `diagnostics.clock_latency` build repurposes the two scan-component
telemetry fields, which the readout tool labels with their pressure names:

    scan_component_a = running MEAN accepted-edge-to-claim delay, cycles/32
    scan_component_b = running MEAN accepted-edge-to-gate delay, cycles/32

The split is the moment clock_settle makes the selected external step
claimable by the 1 kHz flush.  The first mean therefore covers FIFO wait,
clock_service and note selection.  Subtracting it from the second covers the
flush/remap/gate remainder.  Both use the COUNT stamp of the edge the dequeue
is acting on (0x6240), once per consumed edge.

A whole-path sample too large for the 14-bit field is DISCARDED from both sums
and their shared count. It only gets that large when the beat waited behind a
drained backlog, which is a different population from the delay being
measured. So the figures below describe beats that were not stalled, and a
stall costs its own measurement rather than the session's.

Both are RUNNING figures accumulated since power-up, so the last frame of a
file already holds that session's final values -- there is nothing to
aggregate. Combining files across a power cycle or firmware build is
meaningless, so this tool accepts exactly one CSV.

    python3 tools/clock_latency_report.py LEM218_PressureReadout_<exact>.csv
    python3 tools/clock_latency_report.py <exact.csv> --scope-mean-ms 1.60

The optional scope mean must come from the same start/stop window.  It lets the
tool report the pre-ISR-stamp remainder without inviting comparison with an
older scope session.
"""
import argparse
import csv
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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path, help="the one CSV from this capture")
    parser.add_argument("--scope-mean-ms", type=float,
                        help="scope input-to-trigger mean from the same window")
    args = parser.parse_args()
    path = args.csv
    last, rows = session(path)
    print(f"{path.name}   ({rows} frames)")
    if last is None:
        raise SystemExit(
            "  no beat was ever timed. The firmware only times beats while an\n"
            "  external clock is present (RAM 0x6236), and the telemetry frame\n"
            "  only goes out while a key is held. Check both.")
    claim, total = last
    if claim > total:
        raise SystemExit(
            f"  impossible split: edge->claim {claim} exceeds edge->gate {total}.\n"
            "  Power-cycle and confirm the image and CSV belong to this diagnostic.")
    downstream = total - claim
    print(f"  mean edge->claim  {claim:6d} units   {ms(claim):5.2f} ms")
    print(f"  mean claim->gate  {downstream:6d} units   {ms(downstream):5.2f} ms")
    print(f"  mean edge->gate   {total:6d} units   {ms(total):5.2f} ms")
    if args.scope_mean_ms is not None:
        before_stamp = args.scope_mean_ms - ms(total)
        print(f"  mean before stamp                   {before_stamp:5.2f} ms"
              "   (scope - edge->gate)")
        if before_stamp < 0:
            print("  ^ negative is impossible: the scope mean is not from this"
                  " capture, or the")
            print("    scope/telemetry windows were not started and stopped together.")
    print()
    print("These cover only what happens AFTER the ISR stamped the edge.")
    print()
    print("Compare them ONLY against a scope reading taken during THIS capture.")
    print("Do not compare against the earlier 1.55 ms / 3.62 ms scope figures:")
    print("sending telemetry is itself work the instrument would not otherwise")
    print("be doing, and a key must be held for it to go out at all, so this")
    print("build measures a busier board than the scope runs did. A mean above")
    print("the unloaded scope figure means the extra load, not a longer path.")
    print()
    print("With a simultaneous scope reading, mean_scope - mean_edge_to_gate is the")
    print("time spent BEFORE the ISR stamp -- interrupt latency and input")
    print("conditioning, which no firmware change reaches.")


if __name__ == "__main__":
    main()
