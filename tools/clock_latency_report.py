#!/usr/bin/env python3
"""Report the clock-latency diagnostic from a readout CSV.

The `diagnostics.clock_latency` build repurposes the two scan-component
telemetry fields, which the readout tool labels with their pressure names.
What they carry depends on the source the instrument was running, because
the step owner at RAM 0x6237 selects the sample at gate time. RAM 0x6038
remembers the published source; a gate from a different source clears both cells:

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

VALIDITY IS A HEURISTIC.  The frame carries no source field and no session
generation, so this tool infers clears from value movement, and value
movement cannot identify every transition.  What it can say for certain: a
running MAXIMUM that falls, or a frame reading (0,0) after data has appeared,
proves the cells were cleared under the run -- those rules hold for both
sources.  What it cannot do is prove the absence of a clear, or say WHICH
source a segment belongs to.  When the capture is ambiguous the tool says so
and asks for --external-start rather than guessing.

    python3 tools/clock_latency_report.py <this-run.csv> --scope-max-ms 3.62
    python3 tools/clock_latency_report.py <this-run.csv> --internal
    python3 tools/clock_latency_report.py <this-run.csv> --external-start 42
"""
import argparse
import csv
from pathlib import Path

CPU_HZ = 60_000_000        # the instrument's CPU frequency word at RAM 0x29cc


def ms(units: int) -> float:
    return units * 32 / CPU_HZ * 1000


def session(path: Path):
    """Every frame after the first measurement, in order, plus the frame count.

    Zero frames BEFORE any measurement are the cells' cleared startup state
    and carry nothing.  A zero frame AFTER data has appeared is different: it
    is direct evidence the cells were cleared under the run, and dropping it
    (as an earlier version did) hid explicit resets from the checks below --
    an internal series (1000,12000), (0,0), (900,13000) was accepted with
    "source resets 0".  So those rows are KEPT, marked as clears.
    """
    series = []                # (frame, a, b, is_clear_marker)
    stamps = []
    rows = 0
    with path.open(newline="") as fh:
        for row in csv.DictReader(fh):
            if "scan_component_a" not in row:
                raise SystemExit(f"{path}: not a readout CSV")
            rows += 1
            a, b = int(row["scan_component_a"]), int(row["scan_component_b"])
            if a or b:
                series.append((rows, a, b, False))
                stamps.append(row.get("timestamp", ""))
            elif series:
                series.append((rows, 0, 0, True))
                stamps.append(row.get("timestamp", ""))
    return series, stamps, rows


def segment_faults(segment, internal: bool):
    """Moves that are impossible WITHIN one population, grouped by frame.

    External: both cells are running maxima, so neither may fall.  Internal:
    cell A is a running MINIMUM and may not rise, cell B a maximum and may
    not fall.  A clear marker inside a segment is impossible in either.
    One clear wipes both cells, so a frame is one event and not two.
    """
    events = {}
    for i in range(len(segment)):
        frame, a, b, clear = segment[i]
        if clear:
            events[frame] = ["both cells read (0,0) after data: an explicit clear"]
            continue
        if i == 0 or segment[i - 1][3]:
            continue                       # first sample of the population
        _, pa, pb, _ = segment[i - 1]
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


def clear_candidates(series):
    """Frames that prove a clear happened, whatever the sources were.

    Cell B is a running maximum under BOTH sources, so B falling between
    frames is impossible inside any single population; so is a (0,0) frame
    after data.  Cell A proves nothing on its own -- it is a minimum during
    an internal pre-roll and legitimately falls there -- which is exactly the
    move an earlier version of this tool counted as a source reset, condemning
    valid captures whose pre-roll minimum was still settling.
    """
    events = []
    for i in range(1, len(series)):
        frame, _, b, clear = series[i]
        if clear:
            # A cleared cell stays zero until a valid sample arrives, so a
            # run of (0,0) frames is ONE clear observed several times, not
            # several clears.  Counting each row condemned honest captures
            # whose telemetry was read twice before the first measurement.
            if not series[i - 1][3]:
                events.append(frame)
        elif not series[i - 1][3] and b < series[i - 1][2]:
            events.append(frame)
    return events


def start_index(series, frame):
    return next((i for i, (f, _, _, _) in enumerate(series) if f >= frame),
                len(series))


def past_the_clear(series, start):
    """First index at or after `start` that carries a measurement.

    The clear itself carries nothing, and neither does the rest of its run.
    """
    while start < len(series) and series[start][3]:
        start += 1
    return start


def report_window(series, stamps, rows: int, internal: bool, period_ms,
                  external_start):
    """Validity of the capture, and which frames the figures are drawn from.

    A reset is not automatically a fault, and this is the whole subtlety.  The
    cells clear when a gate belongs to a different source, which is a
    DESIGNED behaviour: the bench procedure has a key held before the clock
    is started, so an external capture legitimately begins with an internal
    pre-roll and clears once when
    the clock arrives.  What follows that one reset is the population being
    measured.  During the pre-roll cell A is a MINIMUM, so decreases there are
    legitimate and must not be counted as resets.

    An INTERNAL capture has no such transition -- nothing should change the
    source -- so any reset there means the measurement population changed
    and the run is contaminated.

    All of this is inference from value movement; the frame carries no source
    field.  --external-start names the frame the clock was patched at and
    replaces the boundary heuristic with the operator's own knowledge.

    Returns the index in `series` at which the measured population starts.
    """
    print(f"  frames                {rows:6d}   "
          f"{len(series)} carrying a measurement or an explicit clear")
    first, last = stamps[0], stamps[-1]
    if first and last and first != last:
        print(f"  measured window       {first} .. {last}")

    if internal:
        events = segment_faults(series, internal=True)
        if events:
            print()
            print(f"  CONTAMINATED: {len(events)} reset(s), first at frame "
                  f"{events[0][0]}")
            for frame, moved in events[:5]:
                for what in moved:
                    print(f"      frame {frame}: {what}")
            raise SystemExit(
                "\n  Nothing should change the source during an internal"
                " capture, so a\n"
                "  running maximum that falls, a running minimum that rises,"
                " or a (0,0)\n"
                "  frame after data means the cells were cleared and reseeded"
                " under the\n"
                "  run. The figures would mix two populations, so they are not"
                " reported.\n"
                "  Re-take it with no clock patched and the key held"
                " throughout.")
        print(f"  resets                     0   no impossible move in either"
              " cell")
        print(f"  measured population   {len(series):6d}   frames")
        if period_ms is not None:
            print(f"  beat period           {period_ms:6.2f} ms   "
                  "(given, for the cross-check below)")
        return 0

    # External capture: [optional internal pre-roll][one clear][the clock].
    if external_start is not None:
        start = start_index(series, external_start)
        if start >= len(series):
            raise SystemExit(
                f"  --external-start {external_start} is past the last"
                f" measured frame.")
        start = past_the_clear(series, start)   # the clear carries nothing
        print(f"  external window       {external_start:6d}   "
              "given with --external-start; frames before it are pre-roll")
    else:
        events = clear_candidates(series)
        if len(events) > 1:
            print()
            print(f"  CONTAMINATED: {len(events)} clears, at frames "
                  + ", ".join(str(f) for f in events[:6]))
            raise SystemExit(
                "\n  One clear is the expected hand-off from the internal"
                " pre-roll to the\n"
                "  clock. Several prove the cells were wiped repeatedly:"
                " consistent with\n"
                "  the input dropping out and being re-acquired, so the"
                " figures mix\n"
                "  populations and are not reported. Check the patch and the"
                " input\n"
                "  levels, and re-take it.")
        if len(events) == 1:
            start = past_the_clear(series, start_index(series, events[0]))
            print(f"  source change         {events[0]:6d}   "
                  "inferred from a move impossible inside one population --")
            print("                                 read as the clock"
                  " arriving and clearing the internal pre-roll")
        else:
            # No clear detected. That does not prove there was none: a
            # pre-roll whose values never exceeded the first external sample
            # leaves no impossible move behind. A falling cell A is the
            # pre-roll's signature (it is a minimum there), so its presence
            # with no detectable boundary is exactly the ambiguous case.
            falls = [f for f, moved in segment_faults(series, internal=False)
                     if any("edge->claim fell" in m for m in moved)]
            if falls:
                print()
                print(f"  AMBIGUOUS: cell A falls at frame(s) "
                      + ", ".join(str(f) for f in falls[:6])
                      + " but no clear is detectable.")
                raise SystemExit(
                    "\n  A falling cell A is legitimate during the internal"
                    " pre-roll (it is\n"
                    "  a minimum there) and impossible after the clock"
                    " arrives, and this\n"
                    "  capture shows the fall without the boundary that"
                    " separates the two.\n"
                    "  Value movement alone cannot place it. Re-run with"
                    " --external-start\n"
                    "  <frame> naming the frame at which the clock was"
                    " patched in.")
            start = 0
            print(f"  source change           none   no clear detected;"
                  " treating the whole capture as external")

    tail = [s for s in series[start:]]
    faults = segment_faults(tail, internal=False)
    if faults:
        print()
        for frame, moved in faults[:5]:
            for what in moved:
                print(f"      frame {frame}: {what}")
        raise SystemExit(
            "  The external window itself moved impossibly after the"
            " hand-off. The\n  capture did not settle; re-take it.")
    print(f"  measured population   {len(tail):6d}   frames -- what the"
          " figures below are drawn from")
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
    print("On the current build the wait is an absolute COUNT target computed")
    print("at phase A from the actual DAC transfer, and the main-loop wrapper")
    print("services it around the dispatcher. Expect the MINIMUM at or above")
    print("the configured settle -- below it means the settle is being spent")
    print("before the pitch reaches the DAC, which is the defect this design")
    print("removed -- and a spread near the main-loop service granularity.")
    print("An absolute value of settle plus a whole dispatch, or a spread of")
    print("several milliseconds, is the older dispatch-quantised behaviour")
    print("having returned.")


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
    parser.add_argument("--external-start", type=int, metavar="FRAME",
                        help="frame at which the external clock was patched "
                             "in; replaces the boundary heuristic with the "
                             "operator's own knowledge of the run")
    args = parser.parse_args()
    if args.internal and args.scope_max_ms is not None:
        raise SystemExit(
            "--scope-max-ms is an external figure: it measures input edge to "
            "trigger,\nand an internal capture has no input edge to measure "
            "from.")
    if args.internal and args.external_start is not None:
        raise SystemExit(
            "--external-start marks the clock's arrival, and an internal "
            "capture is\nthe run with no clock patched at all.")
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
    report_window(series, stamps, rows, args.internal, args.period_ms,
                  args.external_start)
    print()
    # The last frame already holds the running figures for the population that
    # started at the source change, since neither cell is touched again.
    frame, a, b, clear = series[-1]
    if clear:
        raise SystemExit(
            "  the capture ends on cleared cells: the source changed at the"
            " very end\n  and nothing was measured after it. The final"
            " figures do not exist.")
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
