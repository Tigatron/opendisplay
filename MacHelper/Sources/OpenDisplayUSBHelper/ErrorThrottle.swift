import Foundation

/// Drops repeated identical messages for the same key (one serial, one error).
final class ErrorThrottle {
    private var last: [String: String] = [:]

    func allow(key: String, message: String) -> Bool {
        if last[key] == message { return false }
        last[key] = message
        return true
    }

    func reset(key: String) {
        last.removeValue(forKey: key)
    }
}
