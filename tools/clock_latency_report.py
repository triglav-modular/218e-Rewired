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
    """Every frame that carries a measurement, in order, plus the frame count.

    The whole series, not just the last frame: both published cells are
    RUNNING maxima, so a value that falls between frames is arithmetically
    impossible and proves the cells were cleared and reseeded mid-capture.
    That happens when RAM 0x6236 flaps -- the source changed, and a change of
    source clears the pair.  Capture 2's first attempt did exactly that and
    the only reason it was caught was a hand-read of the CSV.
    """
    series = []
    stamps = []
    rows = 0
    with path.open(newline="") as fh:
        for row in csv.DictReader(fh):
            if "scan_component_a" not in row:
                raise SystemExit(f"{path}: not a readout CSV")
            rows += 1
            a, b = int(row["scan_component_a"]), int(row["scan_component_b"])
            if a or b:
                series.append((rows, a, b))
                stamps.append(row.get("timestamp", ""))
    return series, stamps, rows


def resets(series, internal: bool):
    """Frames where a published figure moved in a direction it cannot.

    External: both cells are maxima, so neither may fall.  Internal: cell A is
    a MINIMUM and may not rise, cell B is a maximum and may not fall.  Grouped
    by FRAME -- one source change clears both cells, so it is one event and
    not two.
    """
    events = {}
    for i in range(1, len(series)):
        frame, a, b = series[i]
        _, pa, pb = series[i - 1]
        moved = []
        if internal:
            if a > pa:
                moved.append(f"min claim->gate rose {pa} -> {a}")
            if b < pb:
                moved.append(f"max claim->gate fell {pb} -> {b}")
        else:
            if a < pa:
                moved.append(f"max edge->claim fell {pa} -> {a}")
            if b < pb:
                moved.append(f"max edge->gate fell {pb} -> {b}")
        if moved:
            events[frame] = moved
    return sorted(events.items())


def report_window(series, stamps, rows: int, internal: bool, period_ms):
    """Validity of the capture, and which frames the figures are drawn from.

    A reset is not automatically a fault, and this is the whole subtlety.  The
    cells clear when RAM 0x6236 changes, which is a DESIGNED behaviour: the
    bench procedure has a key held before the clock is started, so an external
    capture legitimately begins with an internal pre-roll and clears once when
    the clock arrives.  What follows that one reset is the population being
    measured.

    An INTERNAL capture has no such transition -- nothing should change the
    source -- so any reset there means 0x6236 was flapping and the run is
    contaminated.  Getting this asymmetry wrong condemns the good external
    capture, which an earlier version of this check did.

    Returns the index in `series` at which the measured population starts.
    """
    print(f"  frames                {rows:6d}   "
          f"{len(series)} carrying a measurement")
    first, last = stamps[0], stamps[-1]
    if first and last and first != last:
        print(f"  measured window       {first} .. {last}")
    events = resets(series, internal)
    start = 0
    if internal and events:
        print()
        print(f"  CONTAMINATED: {len(events)} source reset(s), first at frame "
              f"{events[0][0]}")
        for frame, moved in events[:5]:
            for what in moved:
                print(f"      frame {frame}: {what}")
        raise SystemExit(
            "\n  Nothing should change the source during an internal capture,"
            " so a\n"
            "  running maximum that falls or a running minimum that rises means"
            " RAM\n"
            "  0x6236 was flapping and the cells were cleared and reseeded"
            " under the\n"
            "  run. The figures would mix two populations, so they are not"
            " reported.\n"
            "  Re-take it with no clock patched and the key held throughout.")
    if not internal and len(events) > 1:
        print()
        print(f"  CONTAMINATED: {len(events)} source resets, at frames "
              + ", ".join(str(f) for f, _ in events[:6]))
        raise SystemExit(
            "\n  One reset is the expected hand-off from the internal pre-roll"
            " to the\n"
            "  clock. Several mean RAM 0x6236 flapped: the input was dropping"
            " out and\n"
            "  being re-acquired, so the figures mix populations and are not"
            " reported.\n"
            "  Check the patch and the input levels, and re-take it.")
    if not internal and len(events) == 1:
        frame, moved = events[0]
        start = next(i for i, (f, _, _) in enumerate(series) if f == frame)
        print(f"  source change         {frame:6d}   "
              "the clock arriving; the internal pre-roll cleared here")
        print(f"  measured population   {len(series) - start:6d}   "
              "frames after it, which is what the figures below are drawn from")
        after = resets(series[start:], internal)
        if after:
            raise SystemExit(
                "  ...and it did not settle: the cells moved impossibly again"
                " after\n  that reset. Re-take the capture.")
    else:
        print(f"  source resets         {len(events):6d}   "
              "no impossible move in either cell")
        print(f"  measured population   {len(series):6d}   frames")
    if period_ms is not None:
        print(f"  beat period           {period_ms:6.2f} ms   "
              "(given, for the cross-check below)")
    return start


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
    print("This pair was what decided the fix, and it has decided it. On")
    print("2026-08-30 the instrument published max edge->claim 3.60 ms against")
    print("max edge->gate 3.61: the tail was UPSTREAM of the claim almost in")
    print("its entirety, so no wait counted FROM the claim could reach it.")
    print("clock_deadline_ms (default 4) now places the gate a fixed time")
    print("after the accepted EDGE instead, which absorbs exactly that.")
    print()
    print("So on a build that HAS a deadline, what these two say has changed:")
    print()
    print("  max edge->gate should sit at about the deadline")
    print("      and barely move with load. That is the fix working: the gate")
    print("      is placed relative to the edge, so what the path cost before")
    print("      the claim no longer reaches it.")
    print()
    print("  max edge->claim may be anything below it")
    print("      It is the term being absorbed. A LARGE value here with a")
    print("      steady edge->gate is the deadline earning its keep, not a")
    print("      fault.")
    print()
    print("  max edge->claim at or above the deadline")
    print("      means beats are overrunning it and keeping the older latency.")
    print("      Raise clock_deadline_ms, remembering it is bounded at build")
    print("      time by half the acquired period.")


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
    print("jitter.")
    print()
    print("This used to carry a warning that the spread was a floor no")
    print("deadline could get under. That was measured at 4.31 ms and it was")
    print("reading a defect, not a floor: clock_ms_tick spent the countdown")
    print("only under claim 3, nothing writes 3 except the flush's phase A,")
    print("and the internal claim is made in the factory half of a dispatch")
    print("whose wrapper half has already gone by -- so a whole dispatch sat")
    print("between the claim and the first decrement. The tick counts under")
    print("claim 2 now. Expect a spread near the dispatch quantisation, about")
    print("a millisecond, and an absolute value near the settle itself rather")
    print("than the settle plus a dispatch.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path, help="the one CSV from this capture")
    parser.add_argument("--internal", action="store_true",
                        help="capture taken with no external clock patched")
    parser.add_argument("--scope-max-ms", type=float,
                        help="scope input-to-trigger MAXIMUM from the same "
                             "window; external captures only")
    parser.add_argument("--period-ms", type=float,
                        help="the beat period during the capture, in ms, for "
                             "the cross-check: a figure at or above it is "
                             "being charged across beats")
    args = parser.parse_args()
    if args.internal and args.scope_max_ms is not None:
        raise SystemExit(
            "--scope-max-ms is an external figure: it measures input edge to "
            "trigger,\nand an internal capture has no input edge to measure "
            "from.")
    path = args.csv
    series, stamps, rows = session(path)
    print(f"{path.name}")
    if not series:
        raise SystemExit(
            "  no beat was ever timed. The telemetry frame only goes out while\n"
            "  a key is held, and the cells clear whenever the source changes,\n"
            "  so a clock unplugged at the end of the run empties them. Check\n"
            "  the key was held throughout and the source never changed.")
    # Validity before figures: a contaminated capture exits here rather than
    # printing numbers that mix two populations.
    report_window(series, stamps, rows, args.internal, args.period_ms)
    print()
    # The last frame already holds the running figures for the population that
    # started at the source change, since neither cell is touched again.
    _, a, b = series[-1]
    if args.internal:
        internal(a, b)
    else:
        external(a, b, args.scope_max_ms)
    if args.period_ms is not None:
        worst = ms(b)
        if worst >= args.period_ms:
            print()
            print(f"  CROSS-CHECK FAILED: {worst:.2f} ms is at or above the")
            print(f"  {args.period_ms:.2f} ms beat period. A single beat's")
            print("  latency cannot fill its own period without the next beat")
            print("  overtaking it, so this figure is being charged across")
            print("  beats -- a stale stamp timed against a later gate.")
        elif worst >= args.period_ms / 2:
            print()
            print(f"  Note: {worst:.2f} ms is over half the {args.period_ms:.2f}"
                  " ms beat period.")
            print("  Not impossible, but the margin for the dequeue is thin;")
            print("  check the overrun counter at 0x6258 was zero.")
    print()
    print("Running since power-up or since the source last changed, so this is")
    print("already the whole session: there is nothing to aggregate.")


if __name__ == "__main__":
    main()
