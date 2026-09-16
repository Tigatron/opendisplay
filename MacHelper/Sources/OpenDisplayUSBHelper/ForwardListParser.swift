import Foundation

struct AdbForward: Equatable {
    var serial: String
    var localPort: UInt16
    var remotePort: UInt16
}

/// Parses `adb forward --list` / `adb -s S forward --list`.
/// Host lines look like `SERIAL tcp:<local> tcp:<remote>`.
enum ForwardListParser {
    static func parse(_ text: String) -> [AdbForward] {
        text.split(whereSeparator: { $0 == "\n" || $0 == "\r" })
            .compactMap { parseLine(String($0)) }
    }

    static func parseLine(_ raw: String) -> AdbForward? {
        let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if line.isEmpty { return nil }
        let parts = line.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        guard parts.count >= 3 else { return nil }
        guard let local = tcpPort(parts[1]), let remote = tcpPort(parts[2]) else {
            return nil
        }
        return AdbForward(serial: parts[0], localPort: local, remotePort: remote)
    }

    static func existing(
        serial: String,
        localPort: UInt16,
        remotePort: UInt16 = 9000,
        in forwards: [AdbForward]
    ) -> AdbForward? {
        forwards.first {
            $0.serial == serial && $0.localPort == localPort && $0.remotePort == remotePort
        }
    }

    static func staleLocals(
        serial: String,
        keeping localPort: UInt16,
        remotePort: UInt16 = 9000,
        in forwards: [AdbForward]
    ) -> [UInt16] {
        forwards.compactMap { entry in
            guard entry.serial == serial else { return nil }
            if entry.localPort == localPort, entry.remotePort == remotePort { return nil }
            return entry.localPort
        }
    }

    static func reusableLocalPorts(serial: String, remotePort: UInt16 = 9000, in forwards: [AdbForward]) -> Set<UInt16> {
        Set(
            forwards.compactMap { entry in
                guard entry.serial == serial, entry.remotePort == remotePort else { return nil }
                return entry.localPort
            }
        )
    }

    private static func tcpPort(_ token: String) -> UInt16? {
        let value: String
        if token.lowercased().hasPrefix("tcp:") {
            value = String(token.dropFirst(4))
        } else {
            return nil
        }
        guard let parsed = UInt16(value) else { return nil }
        return parsed
    }
}
