import Foundation

struct TrackedDevice: Equatable {
    var serial: String
    var state: String
    var product: String?
    var model: String?
    var device: String?
    var usb: String?
    var transportID: String?

    var isAuthorizedReady: Bool { state == "device" }

    var displayModel: String? {
        if let model, !model.isEmpty {
            return model.replacingOccurrences(of: "_", with: "-")
        }
        return nil
    }
}

/// Incremental parser for `adb track-devices -l`.
///
/// Modern adb (verified 1.0.41 / 36.0.0) writes the raw smart-socket stream
/// to stdout: each snapshot is `[4 ASCII hex digits][payload of that length]`.
/// There is no blank line between snapshots. `0000` is an empty list.
///
/// Heuristic vs. the older line-oriented dump (blank line ends a snapshot):
/// prefer framed mode whenever the first 4 bytes are hex AND either the
/// buffer is exactly those 4 bytes or the remainder is long enough for
/// `len` (or we are already waiting on that `len`). A serial that is itself
/// 4 hex characters is not framed: after those 4 bytes a real device line
/// continues with whitespace and a state word (`device`, `offline`, …).
final class TrackDevicesParser {
    private enum Mode {
        case unknown
        case framed
        case legacy
    }

    private static let adbStates: Set<String> = [
        "device", "offline", "unauthorized", "authorizing", "authorising",
        "connecting", "unknown", "recovery", "sideload", "bootloader",
        "host", "no"
    ]

    private var buffer = Data()
    private var snapshotLines: [String] = []
    private var mode: Mode = .unknown

    @discardableResult
    func ingest(_ chunk: String) -> [[TrackedDevice]] {
        ingest(Data(chunk.utf8))
    }

    @discardableResult
    func ingest(_ chunk: Data) -> [[TrackedDevice]] {
        buffer.append(chunk)
        var completed: [[TrackedDevice]] = []
        while let snapshot = pullSnapshot() {
            completed.append(snapshot)
        }
        return completed
    }

    /// Parse a finished snapshot block (tests and one-shot `adb devices -l`).
    static func parseSnapshot(_ text: String) -> [TrackedDevice] {
        text.split(whereSeparator: { $0 == "\n" || $0 == "\r" })
            .compactMap { parseLine(String($0)) }
    }

    static func parseLine(_ raw: String) -> TrackedDevice? {
        let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if line.isEmpty { return nil }
        if line.hasPrefix("List of devices") { return nil }
        if line.hasPrefix("* daemon") { return nil }
        if line.hasPrefix("adb:") { return nil }

        let parts = line.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        guard parts.count >= 2 else { return nil }
        let serial = parts[0]
        let state = parts[1]
        var product: String?
        var model: String?
        var device: String?
        var usb: String?
        var transportID: String?
        for token in parts.dropFirst(2) {
            guard let split = token.firstIndex(of: ":") else { continue }
            let key = String(token[..<split])
            let value = String(token[token.index(after: split)...])
            switch key {
            case "product": product = value
            case "model": model = value
            case "device": device = value
            case "usb": usb = value
            case "transport_id": transportID = value
            default: break
            }
        }
        return TrackedDevice(
            serial: serial,
            state: state,
            product: product,
            model: model,
            device: device,
            usb: usb,
            transportID: transportID
        )
    }

    private func pullSnapshot() -> [TrackedDevice]? {
        switch mode {
        case .framed:
            return pullFramed()
        case .legacy:
            return pullLegacy()
        case .unknown:
            switch peekFramed() {
            case .needMore:
                if !buffer.isEmpty && !Self.isHexPrefix(buffer.prefix(min(4, buffer.count))) {
                    mode = .legacy
                    return pullLegacy()
                }
                return nil
            case .notFramed:
                mode = .legacy
                return pullLegacy()
            case .ready(let length):
                mode = .framed
                return takeFramed(length: length)
            }
        }
    }

    private enum FramePeek {
        case ready(Int)
        case needMore
        case notFramed
    }

    private func peekFramed() -> FramePeek {
        if buffer.count < 4 {
            if buffer.isEmpty || Self.isHexPrefix(buffer) {
                return .needMore
            }
            return .notFramed
        }
        guard let length = Self.asciiHexLength(buffer.prefix(4)) else {
            return .notFramed
        }
        if buffer.count == 4 {
            return length == 0 ? .ready(0) : .needMore
        }
        switch Self.classifyRest(afterHexPrefix: buffer) {
        case .legacyState:
            return .notFramed
        case .needMore:
            return .needMore
        case .framedPayload:
            if buffer.count >= 4 + length {
                return .ready(length)
            }
            return .needMore
        }
    }

    private func pullFramed() -> [TrackedDevice]? {
        switch peekFramed() {
        case .ready(let length):
            return takeFramed(length: length)
        case .needMore, .notFramed:
            return nil
        }
    }

    private func takeFramed(length: Int) -> [TrackedDevice] {
        let end = 4 + length
        let payload = buffer.subdata(in: 4..<end)
        buffer.removeSubrange(0..<end)
        return Self.parseSnapshot(String(decoding: payload, as: UTF8.self))
    }

    private func pullLegacy() -> [TrackedDevice]? {
        guard let decoded = Self.decodeUTF8Prefix(buffer) else { return nil }
        var text = decoded.string
        buffer.removeSubrange(0..<decoded.consumed)
        var completed: [TrackedDevice]?
        while let range = text.range(of: "\n") {
            let line = String(text[text.startIndex..<range.lowerBound])
                .trimmingCharacters(in: CharacterSet(charactersIn: "\r"))
            text.removeSubrange(text.startIndex...range.lowerBound)
            if line.isEmpty {
                completed = snapshotLines.compactMap(Self.parseLine)
                snapshotLines.removeAll()
                break
            } else {
                snapshotLines.append(line)
            }
        }
        if !text.isEmpty {
            buffer = Data(text.utf8) + buffer
        }
        return completed
    }

    private enum RestKind {
        case framedPayload
        case legacyState
        case needMore
    }

    /// After 4 hex bytes: whitespace + an adb state word means those 4 bytes
    /// were a serial, not a length. Anything else (or end of buffer) is a frame.
    private static func classifyRest(afterHexPrefix data: Data) -> RestKind {
        let rest = data.dropFirst(4)
        guard !rest.isEmpty else { return .framedPayload }

        var index = rest.startIndex
        var sawWhitespace = false
        while index < rest.endIndex, rest[index] == 0x20 || rest[index] == 0x09 {
            sawWhitespace = true
            index = rest.index(after: index)
        }
        if !sawWhitespace {
            return .framedPayload
        }
        if index == rest.endIndex {
            return .needMore
        }

        var end = index
        while end < rest.endIndex {
            let byte = rest[end]
            if byte == 0x20 || byte == 0x09 || byte == 0x0a || byte == 0x0d { break }
            end = rest.index(after: end)
        }
        if end == index { return .needMore }
        let word = String(decoding: rest[index..<end], as: UTF8.self)
        if adbStates.contains(word) {
            return .legacyState
        }
        if end == rest.endIndex, rest.last != 0x0a, rest.last != 0x20, rest.last != 0x09 {
            return .needMore
        }
        return .framedPayload
    }

    private static func isHexPrefix(_ data: Data) -> Bool {
        !data.isEmpty && data.allSatisfy(isHexDigit)
    }

    private static func asciiHexLength(_ data: Data) -> Int? {
        guard data.count == 4, data.allSatisfy(isHexDigit) else { return nil }
        var value = 0
        for byte in data {
            value <<= 4
            switch byte {
            case 0x30...0x39: value += Int(byte - 0x30)
            case 0x41...0x46: value += Int(byte - 0x41 + 10)
            case 0x61...0x66: value += Int(byte - 0x61 + 10)
            default: return nil
            }
        }
        return value
    }

    private static func isHexDigit(_ byte: UInt8) -> Bool {
        (0x30...0x39).contains(byte) || (0x41...0x46).contains(byte) || (0x61...0x66).contains(byte)
    }

    private static func decodeUTF8Prefix(_ data: Data) -> (string: String, consumed: Int)? {
        if data.isEmpty { return ("", 0) }
        if let string = String(data: data, encoding: .utf8) {
            return (string, data.count)
        }
        for drop in 1...3 where data.count > drop {
            let slice = data.prefix(data.count - drop)
            if let string = String(data: slice, encoding: .utf8) {
                return (string, slice.count)
            }
        }
        return nil
    }
}
