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
/// The adb client prints each host snapshot as a block of lines and then an
/// extra newline. A blank line therefore ends a snapshot, including the empty
/// "no devices" update (`printf("\n")` of an empty payload).
final class TrackDevicesParser {
    private var pending = ""
    private var snapshotLines: [String] = []

    @discardableResult
    func ingest(_ chunk: String) -> [[TrackedDevice]] {
        pending += chunk
        var completed: [[TrackedDevice]] = []
        while let range = pending.range(of: "\n") {
            let line = String(pending[pending.startIndex..<range.lowerBound])
                .trimmingCharacters(in: CharacterSet(charactersIn: "\r"))
            pending.removeSubrange(pending.startIndex...range.lowerBound)
            if line.isEmpty {
                completed.append(snapshotLines.compactMap(Self.parseLine))
                snapshotLines.removeAll()
            } else {
                snapshotLines.append(line)
            }
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
}
