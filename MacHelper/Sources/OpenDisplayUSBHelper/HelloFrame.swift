import Foundation

struct HelloMessage: Equatable {
    var pixelsWide: Int
    var pixelsHigh: Int
    var scale: Double
    var device: String?
    var id: String?
    var pv: Int?
    var addrs: [String]

    var txtProtocolVersion: Int {
        pv ?? HelperConstants.defaultProtocolVersion
    }
}

enum HelloFrameError: Error, Equatable, LocalizedError {
    case truncated
    case invalidLength(UInt32)
    case notJSON
    case notHello
    case missingDimensions
    case timeout
    case connectFailed(String)

    var errorDescription: String? {
        switch self {
        case .truncated: return "hello frame truncated"
        case .invalidLength(let length): return "hello length \(length) is out of range"
        case .notJSON: return "hello payload is not JSON"
        case .notHello: return "first frame is not a hello"
        case .missingDimensions: return "hello is missing pixelsWide/pixelsHigh"
        case .timeout: return "hello probe timed out"
        case .connectFailed(let reason): return "hello probe failed: \(reason)"
        }
    }
}

enum HelloFrame {
    static let maxPayload = 1_048_576

    /// Parse a complete length-prefixed frame. `bytes` must include the 4-byte header.
    static func parse(bytes: Data) throws -> HelloMessage {
        var buffer = bytes
        guard let message = try consume(from: &buffer) else {
            throw HelloFrameError.truncated
        }
        return message
    }

    /// Pull one frame from the front of `buffer`. Returns nil when more bytes are needed.
    static func consume(from buffer: inout Data) throws -> HelloMessage? {
        guard buffer.count >= 4 else { return nil }
        let length = buffer.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        guard length > 0, length < UInt32(maxPayload) else {
            throw HelloFrameError.invalidLength(length)
        }
        let total = 4 + Int(length)
        guard buffer.count >= total else { return nil }
        let payload = buffer.subdata(in: 4..<total)
        buffer.removeSubrange(0..<total)
        return try decode(payload: payload)
    }

    static func encodeForTests(_ message: HelloMessage) throws -> Data {
        let object: [String: Any] = {
            var dict: [String: Any] = [
                "type": "hello",
                "pixelsWide": message.pixelsWide,
                "pixelsHigh": message.pixelsHigh,
                "scale": message.scale
            ]
            if let device = message.device { dict["device"] = device }
            if let id = message.id { dict["id"] = id }
            if let pv = message.pv { dict["pv"] = pv }
            if !message.addrs.isEmpty { dict["addrs"] = message.addrs }
            return dict
        }()
        let payload = try JSONSerialization.data(withJSONObject: object)
        var header = Data(count: 4)
        let length = UInt32(payload.count).bigEndian
        header.withUnsafeMutableBytes { $0.storeBytes(of: length, as: UInt32.self) }
        return header + payload
    }

    private static func decode(payload: Data) throws -> HelloMessage {
        guard let object = try? JSONSerialization.jsonObject(with: payload) as? [String: Any] else {
            throw HelloFrameError.notJSON
        }
        guard (object["type"] as? String) == "hello" else {
            throw HelloFrameError.notHello
        }
        let wide = intValue(object["pixelsWide"])
        let high = intValue(object["pixelsHigh"])
        guard let wide, let high else {
            throw HelloFrameError.missingDimensions
        }
        return HelloMessage(
            pixelsWide: wide,
            pixelsHigh: high,
            scale: doubleValue(object["scale"]) ?? 1,
            device: object["device"] as? String,
            id: object["id"] as? String,
            pv: intValue(object["pv"]),
            addrs: (object["addrs"] as? [Any])?.compactMap { $0 as? String } ?? []
        )
    }

    private static func intValue(_ raw: Any?) -> Int? {
        switch raw {
        case let value as Int: return value
        case let value as NSNumber: return value.intValue
        default: return nil
        }
    }

    private static func doubleValue(_ raw: Any?) -> Double? {
        switch raw {
        case let value as Double: return value
        case let value as Int: return Double(value)
        case let value as NSNumber: return value.doubleValue
        default: return nil
        }
    }
}
