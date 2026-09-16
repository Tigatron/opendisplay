import Darwin
import Foundation

enum PortAllocator {
    static let preferred = HelperConstants.preferredTunnelPort
    static let fallbackStart = HelperConstants.fallbackPortStart
    static let fallbackEnd = HelperConstants.fallbackPortEnd

    /// First device gets 9000 when that loopback port is free and unused.
    /// Everyone else (or a busy 9000) walks 9010...9100.
    static func propose(used: Set<UInt16>, isFree: (UInt16) -> Bool) -> UInt16? {
        if !used.contains(preferred), isFree(preferred) {
            return preferred
        }
        for port in fallbackStart...fallbackEnd where !used.contains(port) && isFree(port) {
            return port
        }
        for port in fallbackStart...fallbackEnd where !used.contains(port) {
            return port
        }
        return nil
    }

    /// Next candidate after `adb forward --no-rebind` reports the port busy.
    static func next(after port: UInt16, used: Set<UInt16>, isFree: (UInt16) -> Bool) -> UInt16? {
        var nextUsed = used
        nextUsed.insert(port)
        let start = max(UInt16(port &+ 1), fallbackStart)
        guard start <= fallbackEnd else { return nil }
        for candidate in start...fallbackEnd where !nextUsed.contains(candidate) && isFree(candidate) {
            return candidate
        }
        for candidate in start...fallbackEnd where !nextUsed.contains(candidate) {
            return candidate
        }
        return nil
    }

    /// An adb listener we already own for this serial counts as free: bind would
    /// fail, but `--no-rebind` is the wrong diagnosis — reuse the forward.
    static func isAvailable(_ port: UInt16, bindFree: Bool, reusableBySameDevice: Bool) -> Bool {
        reusableBySameDevice || bindFree
    }

    /// Bind 127.0.0.1:port (no SO_REUSEADDR). Success means nothing is listening.
    static func isLoopbackPortFree(_ port: UInt16) -> Bool {
        let fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        guard fd >= 0 else { return false }
        defer { close(fd) }
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr = in_addr(s_addr: inet_addr("127.0.0.1"))
        let bound = withUnsafePointer(to: &addr) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        return bound == 0
    }
}
