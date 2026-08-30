#!/usr/bin/env python3
"""Report the clock-latency diagnostic from a readout CSV.

The `diagnostics.clock_latency` build repurposes the two scan-component
telemetry fields, which the readout tool labels with their pressure names.
What they carry depends on the source the instrument was running, because
RAM 0x6236 selects it and a CHANGE of source clears both cells:

    external clock present      internal beat (no clock patched)
    scan_component_a = MAX      scan_component_a = MIN claim-to-gate
        edge-to-claim           scan_component_b = MAX claim-to-gate
    scan_component_b = MAX
        edge-to-gate

Both are in cycles/32.  Pass --internal for a capture taken with no clock
patched; the tool cannot tell from the CSV alone.

This build reports MAXIMA, not the means an earlier build published.  The
mean split was taken on 2026-08-30: 0.77 ms edge-to-claim, 0.60 ms
claim-to-gate, 1.37 ms edge-to-gate, against a simultaneous 1.61 ms scope
mean.  That settled where the average goes.  It could not touch the open
question, which is a tail -- 3.36 ms peak to peak against a 1-2 ms target,
flat-topped rather than Gaussian -- because no mean localises an outlier.

A sample too large for the 14-bit field is DISCARDED from both cells rather
than clamped into them.  Clamping is what destroys a maximum: one bad sample
pegs the figure at 16383 for the rest of the power-up, which the instrument
did once.  A sample only gets that large behind a drained backlog, which is a
different population from the delay being measured.

Both figures are RUNNING since power-up or since the source last changed, so
the last frame of a file already holds that session's final values -- there is
nothing to aggregate.  Combining files across a power cycle or firmware build
is meaningless, so this tool accepts exactly one CSV.

    python3 tools/clock_latency_report.py <this-run.csv> --scope-max-ms 3.62
    python3 tools/clock_latency_report.py <this-run.csv> --internal
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


def external(claim: int, total: int, scope_max_ms) -> None:
    if claim > total:
        raise SystemExit(
            f"  impossible: max edge->claim {claim} exceeds max edge->gate "
            f"{total}.\n"
            "  A sub-interval's maximum cannot exceed the whole path's.\n"
            "  Power-cycle and confirm the image and CSV belong to this build.")
    print(f"  max edge->claim   {claim:6d} units   {ms(claim):5.2f} ms")
    print(f"  max edge->gate    {total:6d} units   {ms(total):5.2f} ms")
    if scope_max_ms is not None:
        remainder = scope_max_ms - ms(total)
        print(f"  scope max - this                    {remainder:5.2f} ms")
        if remainder < 0:
            print("  ^ negative is impossible: the scope figure is not from"
                  " this capture, or the")
            print("    scope and telemetry windows were not started and"
                  " stopped together.")
    print()
    print("Do NOT read claim-to-gate as the difference of these two. They are")
    print("independent maxima and need not come from the same beat, so their")
    print("difference is not the maximum of anything. The same caution applies")
    print("to the scope line above: it is a sanity band, not the pre-stamp")
    print("figure the mean capture measured directly at 0.24 ms.")
    print()
    print("What the pair decides:")
    print()
    print("  max edge->claim close to max edge->gate")
    print("      The tail is UPSTREAM of the claim, in the FIFO wait,")
    print("      clock_service and note selection. A settle computed from the")
    print("      accepted-edge stamp instead of counted from the claim absorbs")
    print("      it. Size that deadline at max edge->claim: beats that overrun")
    print("      it keep exactly today's jitter.")
    print()
    print("  max edge->claim well below max edge->gate")
    print("      The tail is DOWNSTREAM of the claim, where a deadline computed")
    print("      at the claim cannot reach it. That fix is the wrong fix and")
    print("      the search goes back to the flush, remap and gate call.")


def internal(low: int, high: int) -> None:
    if low > high:
        raise SystemExit(
            f"  impossible: min claim->gate {low} exceeds max {high}.\n"
            "  Power-cycle and confirm the image and CSV belong to this build.\n"
            "  If a clock WAS patched, drop --internal: these cells then hold\n"
            "  the external pair, where a claim maximum below the whole-path\n"
            "  maximum is ordinary.")
    print(f"  min claim->gate   {low:6d} units   {ms(low):5.2f} ms")
    print(f"  max claim->gate   {high:6d} units   {ms(high):5.2f} ms")
    print(f"  spread            {high - low:6d} units   {ms(high - low):5.2f} ms")
    print()
    print("The internal beat has no accepted edge, so there is no edge-to-gate")
    print("for it and these are the shared half only: the flush wait, the pitch")
    print("remap and the gate call, on a source that spends none of the FIFO,")
    print("clock_service or note selection the external path spends first.")
    print()
    print("The SPREAD is the figure to read. The absolute values include the")
    print("build's internal settle, which is a deliberate constant and not")
    print("jitter. That spread is the floor a deadline computed at the claim")
    print("has to live above, since no such deadline reaches past the claim.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path, help="the one CSV from this capture")
    parser.add_argument("--internal", action="store_true",
                        help="capture taken with no external clock patched")
    parser.add_argument("--scope-max-ms", type=float,
                        help="scope input-to-trigger MAXIMUM from the same "
                             "window; external captures only")
    args = parser.parse_args()
    if args.internal and args.scope_max_ms is not None:
        raise SystemExit(
            "--scope-max-ms is an external figure: it measures input edge to "
            "trigger,\nand an internal capture has no input edge to measure "
            "from.")
    path = args.csv
    last, rows = session(path)
    print(f"{path.name}   ({rows} frames)")
    if last is None:
        raise SystemExit(
            "  no beat was ever timed. The telemetry frame only goes out while\n"
            "  a key is held, and the cells clear whenever the source changes,\n"
            "  so a clock unplugged at the end of the run empties them. Check\n"
            "  the key was held throughout and the source never changed.")
    a, b = last
    if args.internal:
        internal(a, b)
    else:
        external(a, b, args.scope_max_ms)
    print()
    print("Running since power-up or since the source last changed, so this is")
    print("already the whole session: there is nothing to aggregate.")


if __name__ == "__main__":
    main()
