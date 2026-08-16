import CoreMIDI
import Foundation
import Darwin

struct PressureFrame {
    let timestamp: Date
    let rawInstant: Int
    let rawAverage: Int
    let normalized: Int
    let output12: Int
    let floor: Int
    let ceiling: Int
    let scanComponentA: Int
    let scanComponentB: Int
    let curveLevel: Int

    var curved: Int {
        // Firmware expands the integer curved value from 0...913 to 0...4095.
        // That mapping is injective, so this rounding recovers it approximately.
        return (output12 * 913 + 2047) / 4095
    }

    var scanDifference: Int {
        return scanComponentA - scanComponentB
    }

    var scanDifferenceError: Int {
        // The factory pressure input is max(A-B,0)-110. This should normally
        // be zero and makes a stale or incorrectly addressed scan pair obvious.
        return scanDifference - (rawInstant + 110)
    }
}

final class PressureMonitor {
    private let lock = NSLock()
    private var pending: [Int: Int] = [:]
    private var recent: [PressureFrame] = []
    private var activeTouch: [PressureFrame] = []
    private var touchNumber = 0
    private var announcedTelemetry = false
    private var captures: [(String, PressureFrame, ClosedRange<Int>)] = []
    private let csv: FileHandle
    private let dateFormatter = ISO8601DateFormatter()

    init(csvPath: String) throws {
        FileManager.default.createFile(atPath: csvPath, contents: nil)
        csv = try FileHandle(forWritingTo: URL(fileURLWithPath: csvPath))
        dateFormatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        writeCSV("timestamp,event,raw_instant,raw_average,scan_component_a,scan_component_b,scan_difference,scan_difference_error,normalized,curved,output_12bit,floor,ceiling,curve_level\n")
    }

    deinit {
        flushCSV()
        csvQueue.sync {}
        try? csv.synchronize()
        try? csv.close()
    }

    // Telemetry arrives on the CoreMIDI callback, where a synchronous write
    // plus fsync can stall long enough to drop frames — which corrupts the
    // very cadence measurements the readout exists to make.  Buffer instead,
    // and flush from a background queue.
    private var csvBuffer = Data()
    private let csvQueue = DispatchQueue(label: "lem218.csv")

    private func writeCSV(_ text: String) {
        guard let data = text.data(using: .utf8) else { return }
        csvBuffer.append(data)
        guard csvBuffer.count >= 8192 else { return }
        flushCSV()
    }

    /// Flush and fsync before an explicit exit, which bypasses deinit.
    func finishWriting() {
        lock.lock(); flushCSV(); lock.unlock()
        csvQueue.sync {}
        try? csv.synchronize()
    }

    private func flushCSV() {
        guard !csvBuffer.isEmpty else { return }
        let pendingData = csvBuffer
        csvBuffer.removeAll(keepingCapacity: true)
        csvQueue.async { [csv] in
            try? csv.write(contentsOf: pendingData)
        }
    }

    func consume(_ bytes: [UInt8]) {
        var index = 0
        while index + 2 < bytes.count {
            let status = bytes[index]
            if status == 0xBF {
                accept(controller: Int(bytes[index + 1]), value: Int(bytes[index + 2]))
                index += 3
            } else {
                index += 1
            }
        }
    }

    private func value14(_ controller: Int) -> Int? {
        guard let msb = pending[controller], let lsb = pending[controller + 1] else {
            return nil
        }
        return (msb << 7) | lsb
    }

    private func accept(controller: Int, value: Int) {
        guard (102...118).contains(controller) else { return }

        lock.lock()
        defer { lock.unlock() }
        pending[controller] = value
        guard controller == 118 else { return }
        // The terminator ends the frame either way: on an incomplete frame the
        // partial fields are discarded, so they can never be combined with the
        // next frame's.
        defer { pending.removeAll(keepingCapacity: true) }
        guard let rawInstant = value14(102),
              let rawAverage = value14(104),
              let normalized = value14(106),
              let output12 = value14(108),
              let floor = value14(110),
              let ceiling = value14(112),
              let scanComponentA = value14(114),
              let scanComponentB = value14(116) else {
            return
        }

        let frame = PressureFrame(
            timestamp: Date(), rawInstant: rawInstant, rawAverage: rawAverage,
            normalized: normalized, output12: output12, floor: floor,
            ceiling: ceiling, scanComponentA: scanComponentA,
            scanComponentB: scanComponentB,
            curveLevel: value)
        recent.append(frame)
        if recent.count > 50 { recent.removeFirst(recent.count - 50) }

        writeFrame(frame, event: "sample")
        if !announcedTelemetry {
            announcedTelemetry = true
            print("Telemetry received. Live redraw is suppressed so the command line remains usable.")
        }

        if frame.rawInstant > 0 || frame.rawAverage > 0 {
            activeTouch.append(frame)
        } else if !activeTouch.isEmpty {
            finishActiveTouch()
        }
    }

    private(set) var framesWritten = 0

    private func writeFrame(_ frame: PressureFrame, event: String) {
        if event == "sample" { framesWritten += 1 }
        writeCSV("\(dateFormatter.string(from: frame.timestamp)),\(event),\(frame.rawInstant),\(frame.rawAverage),\(frame.scanComponentA),\(frame.scanComponentB),\(frame.scanDifference),\(frame.scanDifferenceError),\(frame.normalized),\(frame.curved),\(frame.output12),\(frame.floor),\(frame.ceiling),\(frame.curveLevel)\n")
    }

    private func representative(of frames: [PressureFrame]) -> (PressureFrame, ClosedRange<Int>) {
        let stable: [PressureFrame]
        if frames.count >= 10 {
            stable = Array(frames.dropFirst(3).dropLast(3))
        } else {
            stable = frames
        }
        func median(_ values: [Int]) -> Int {
            let sorted = values.sorted()
            return sorted[sorted.count / 2]
        }
        let frame = PressureFrame(
            timestamp: Date(),
            rawInstant: median(stable.map(\.rawInstant)),
            rawAverage: median(stable.map(\.rawAverage)),
            normalized: median(stable.map(\.normalized)),
            output12: median(stable.map(\.output12)),
            floor: median(stable.map(\.floor)),
            ceiling: median(stable.map(\.ceiling)),
            scanComponentA: median(stable.map(\.scanComponentA)),
            scanComponentB: median(stable.map(\.scanComponentB)),
            curveLevel: median(stable.map(\.curveLevel)))
        let rawValues = stable.map(\.rawAverage)
        return (frame, rawValues.min()!...rawValues.max()!)
    }

    private func finishActiveTouch() {
        touchNumber += 1
        let label = "touch\(touchNumber)"
        let (frame, rawRange) = representative(of: activeTouch)
        activeTouch.removeAll(keepingCapacity: true)
        captures.append((label, frame, rawRange))
        writeFrame(frame, event: "capture_\(label)")
        print("TOUCH \(touchNumber): raw avg \(frame.rawAverage) (stable range \(rawRange.lowerBound)...\(rawRange.upperBound)), scan A/B \(frame.scanComponentA)/\(frame.scanComponentB), A-B \(frame.scanDifference), check \(frame.scanDifferenceError), output \(frame.output12), curve \(frame.curveLevel)")
        print("Commands: settings, min, mid, max, proximity, or q, followed by return.")
    }

    func capture(_ label: String) {
        lock.lock()
        defer { lock.unlock() }
        guard !recent.isEmpty else {
            print("\nNo telemetry frame has arrived yet. Enter edit mode and touch a key.")
            return
        }

        // The last 15 frames provide a stable representative value while also
        // exposing the raw fluctuation range instead of hiding it in one sample.
        let window = Array(recent.suffix(15))
        let (frame, rawRange) = representative(of: window)
        captures.append((label, frame, rawRange))
        writeFrame(frame, event: "capture_\(label)")
        print("CAPTURE \(label.uppercased()): raw avg \(frame.rawAverage) (range \(rawRange.lowerBound)...\(rawRange.upperBound)), scan A/B \(frame.scanComponentA)/\(frame.scanComponentB), A-B \(frame.scanDifference), check \(frame.scanDifferenceError), output \(frame.output12), curve \(frame.curveLevel)")
        print("Hold the next pressure steady, then type settings, min, mid, max, proximity, or q and press return.")
    }

    func printSettings() {
        lock.lock()
        defer { lock.unlock() }
        guard let frame = recent.last else {
            print("\nNo telemetry frame has arrived yet. Enter ordinary edit mode and wait a moment.")
            return
        }

        writeFrame(frame, event: "settings")
        print("\nSETTINGS: floor \(frame.floor), ceiling \(frame.ceiling), curve \(frame.curveLevel)")
        print("Adjust knob 3 for floor, knob 1 for ceiling, or knob 4 for curve; then type settings again.")
    }

    func printSummary() {
        lock.lock()
        defer { lock.unlock() }
        if !activeTouch.isEmpty {
            finishActiveTouch()
        }
        print("\nCaptured measurements:")
        if captures.isEmpty {
            print("  none")
            return
        }
        print("  label       raw avg (range)    scan A/B    A-B  check   normalized   output   floor..ceiling   curve")
        for (label, frame, range) in captures {
            print(String(
                format: "  %-10@ %4d (%4d...%4d)  %4d/%-4d  %4d  %+4d     %4d/913   %4d   %3d...%-4d      %2d",
                label as NSString, frame.rawAverage, range.lowerBound,
                range.upperBound, frame.scanComponentA, frame.scanComponentB,
                frame.scanDifference, frame.scanDifferenceError, frame.normalized,
                frame.output12, frame.floor, frame.ceiling, frame.curveLevel))
        }
    }
}

private let midiReadProc: MIDIReadProc = { packetList, readRefCon, _ in
    guard let readRefCon else { return }
    let monitor = Unmanaged<PressureMonitor>.fromOpaque(readRefCon).takeUnretainedValue()
    var packet = packetList.pointee.packet
    for _ in 0..<packetList.pointee.numPackets {
        let length = Int(packet.length)
        withUnsafeBytes(of: &packet.data) { storage in
            monitor.consume(Array(storage.prefix(length)))
        }
        packet = MIDIPacketNext(&packet).pointee
    }
}

func endpointName(_ endpoint: MIDIEndpointRef) -> String {
    var property: Unmanaged<CFString>?
    if MIDIObjectGetStringProperty(endpoint, kMIDIPropertyDisplayName, &property) == noErr,
       let name = property?.takeRetainedValue() {
        return name as String
    }
    if MIDIObjectGetStringProperty(endpoint, kMIDIPropertyName, &property) == noErr,
       let name = property?.takeRetainedValue() {
        return name as String
    }
    return "unnamed MIDI source"
}

let arguments = CommandLine.arguments
var signalSources: [DispatchSourceSignal] = []
let selfTest = arguments.contains("--self-test")
let unattended = arguments.contains("--unattended")
let csvPath = arguments.dropFirst().first(where: { !$0.hasPrefix("--") })
    ?? FileManager.default.currentDirectoryPath + "/LEM218_PressureReadout.csv"

do {
    let monitor = try PressureMonitor(csvPath: csvPath)
    // Buffered rows would otherwise be lost on Ctrl-C or a terminal close,
    // which are ordinary ways to end a capture.
    for signalNumber in [SIGINT, SIGTERM, SIGHUP] {
        signal(signalNumber, SIG_IGN)
        let source = DispatchSource.makeSignalSource(signal: signalNumber, queue: .main)
        source.setEventHandler { monitor.finishWriting(); exit(0) }
        source.resume()
        signalSources.append(source)
    }
    if selfTest {
        let testValues = [(102, 120), (104, 125), (106, 400),
                          (108, 2000), (110, 20), (112, 500),
                          (114, 900), (116, 670)]
        var bytes: [UInt8] = []
        for (controller, value) in testValues {
            bytes += [0xBF, UInt8(controller), UInt8((value >> 7) & 0x7F)]
            bytes += [0xBF, UInt8(controller + 1), UInt8(value & 0x7F)]
        }
        bytes += [0xBF, 118, 17]
        monitor.consume(bytes)
        monitor.printSettings()
        monitor.capture("selftest")
        monitor.printSummary()
        monitor.finishWriting()
        exit(0)
    }

    var client = MIDIClientRef()
    var inputPort = MIDIPortRef()
    guard MIDIClientCreate("LEM218 Pressure Readout" as CFString, nil, nil, &client) == noErr,
          MIDIInputPortCreate(
            client, "LEM218 Pressure Input" as CFString, midiReadProc,
            Unmanaged.passUnretained(monitor).toOpaque(), &inputPort) == noErr else {
        fputs("Could not create a CoreMIDI input.\n", stderr)
        exit(1)
    }

    var connected: [String] = []
    var available: [String] = []
    for index in 0..<MIDIGetNumberOfSources() {
        let source = MIDIGetSource(index)
        let name = endpointName(source)
        available.append(name)
        if name.localizedCaseInsensitiveContains("218e") {
            if MIDIPortConnectSource(inputPort, source, nil) == noErr {
                connected.append(name)
            }
        }
    }

    guard !connected.isEmpty else {
        fputs("No CoreMIDI source containing '218e' was found.\n", stderr)
        if !available.isEmpty {
            fputs("Available sources: \(available.joined(separator: ", "))\n", stderr)
        }
        exit(2)
    }

    print("Connected to: \(connected.joined(separator: ", "))")
    print("CSV log: \(csvPath)")

    if unattended {
        // Every telemetry frame is already written as a "sample" row, so a
        // capture needs no interaction at all — the labelled commands below
        // only add extra rows.  This mode exists for diagnosing things that
        // cannot be done with a hand on the keyboard and one on the keys.
        print("Enter ordinary edit mode; telemetry exists only there.")
        print("Logging every frame. Press Ctrl-C or close this window when done.\n")
        let heartbeat = DispatchSource.makeTimerSource(queue: .main)
        heartbeat.schedule(deadline: .now() + 2, repeating: 2)
        heartbeat.setEventHandler {
            let written = monitor.framesWritten
            FileHandle.standardOutput.write(
                "\r\(written) frame\(written == 1 ? "" : "s") logged".data(using: .utf8)!)
        }
        heartbeat.resume()
        RunLoop.main.run()
        exit(0)
    }

    print("Enter ordinary edit mode; telemetry exists only there.")
    print("Hold a pressure steady for about two seconds, then type settings, min, mid, max, or proximity and press return.")
    print("Type q and press return to show the capture summary and quit.\n")

    DispatchQueue.global(qos: .userInitiated).async {
        while let command = readLine()?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            if command == "q" || command == "quit" {
                monitor.printSummary()
                monitor.finishWriting()
                exit(0)
            } else if command == "settings" {
                monitor.printSettings()
            } else if ["min", "mid", "max", "proximity"].contains(command) {
                monitor.capture(command)
            } else if !command.isEmpty {
                print("\nUnknown command. Use settings, min, mid, max, proximity, or q.")
            }
        }
    }

    RunLoop.main.run()
} catch {
    fputs("Pressure readout failed: \(error)\n", stderr)
    exit(1)
}
