import Foundation

enum OpenDisplayDefaultsTrigger: String, Equatable {
    case tunnelReady = "tunnel ready"
    case settingEnabled = "setting enabled"
    case tunnelDown = "tunnel down"
    case settingDisabled = "setting disabled"
    case quit
    case reconcile
}

/// Writes the stock OpenDisplay app's `host`/`port` keys. The stock app
/// reads them at launch, so writing while OpenDisplay is not running is
/// intended. Off by default — it mutates another app's preferences.
enum OpenDisplayDefaults {
    static let domain = HelperConstants.openDisplayDefaultsDomain

    static func wroteLine(
        host: String = "127.0.0.1",
        port: UInt16 = 9000,
        trigger: OpenDisplayDefaultsTrigger
    ) -> String {
        "wrote OpenDisplay defaults host=\(host) port=\(port) (domain \(domain)) trigger=\(trigger.rawValue)"
    }

    static func deletedLine(trigger: OpenDisplayDefaultsTrigger) -> String {
        "deleted OpenDisplay defaults host/port (domain \(domain)) trigger=\(trigger.rawValue)"
    }

    static func writeTunnel(
        host: String = "127.0.0.1",
        port: UInt16 = 9000,
        trigger: OpenDisplayDefaultsTrigger,
        performWrites: Bool = true,
        log: @escaping (String) -> Void = { HelperLogger.shared.info($0) }
    ) {
        if performWrites {
            _ = ProcessRunner.run(
                executable: "/usr/bin/defaults",
                arguments: ["write", domain, "host", "-string", host],
                timeout: 5
            )
            _ = ProcessRunner.run(
                executable: "/usr/bin/defaults",
                arguments: ["write", domain, "port", "-string", String(port)],
                timeout: 5
            )
        }
        log(wroteLine(host: host, port: port, trigger: trigger))
    }

    static func revertTunnel(
        trigger: OpenDisplayDefaultsTrigger,
        performDeletes: Bool = true,
        log: @escaping (String) -> Void = { HelperLogger.shared.info($0) }
    ) {
        if performDeletes {
            _ = ProcessRunner.run(
                executable: "/usr/bin/defaults",
                arguments: ["delete", domain, "host"],
                timeout: 5
            )
            _ = ProcessRunner.run(
                executable: "/usr/bin/defaults",
                arguments: ["delete", domain, "port"],
                timeout: 5
            )
        }
        log(deletedLine(trigger: trigger))
    }

    static func readKey(_ key: String) -> String? {
        let result = ProcessRunner.run(
            executable: "/usr/bin/defaults",
            arguments: ["read", domain, key],
            timeout: 5
        )
        guard result.succeeded else { return nil }
        let value = result.stdout.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    static func hasTunnelKeys(read: (String) -> String? = OpenDisplayDefaults.readKey) -> Bool {
        read("host") != nil || read("port") != nil
    }
}

enum StaleDefaultsPolicy {
    enum Action: Equatable {
        case none
        case ignoreWithHint
        case delete
    }

    static func action(
        writeOpenDisplayDefaults: Bool,
        keysPresent: Bool,
        hasReady9000: Bool
    ) -> Action {
        guard keysPresent else { return .none }
        if writeOpenDisplayDefaults {
            return hasReady9000 ? .none : .delete
        }
        return .ignoreWithHint
    }

    static let hint =
        "stale OpenDisplay defaults host/port present in \(HelperConstants.openDisplayDefaultsDomain) (writeOpenDisplayDefaults=off — leaving them; they may be the user's own)"
}
