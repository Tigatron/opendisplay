import Foundation

enum OpenDisplayDefaultsTrigger: String, Equatable {
    case tunnelReady = "tunnel ready"
    case settingEnabled = "setting enabled"
    case tunnelDown = "tunnel down"
    case settingDisabled = "setting disabled"
    case quit
    case reconcile
}

/// Writes the stock OpenDisplay app's `host`/`port` keys and clears
/// `usb:first` from `usbDisabled` so Manual mode can dial. The stock app
/// reads `host`/`port` at launch, so writing while OpenDisplay is not
/// running is intended. Off by default — it mutates another app's preferences.
enum OpenDisplayDefaults {
    static let domain = HelperConstants.openDisplayDefaultsDomain
    static let usbDisabledKey = "usbDisabled"
    static let usbFirstEntry = "usb:first"

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

    static func usbDisabledRemovedLine(trigger: OpenDisplayDefaultsTrigger) -> String {
        "removed \(usbFirstEntry) from \(usbDisabledKey) (domain \(domain)) trigger=\(trigger.rawValue)"
    }

    static func writeTunnel(
        host: String = "127.0.0.1",
        port: UInt16 = 9000,
        trigger: OpenDisplayDefaultsTrigger,
        performWrites: Bool = true,
        usbDisabledEntries: [String]? = nil,
        writeUsbDisabled: (([String]) -> Void)? = nil,
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
        clearUsbFirst(
            trigger: trigger,
            performWrites: performWrites,
            entries: usbDisabledEntries,
            writeUsbDisabled: writeUsbDisabled,
            log: log
        )
    }

    /// Removes `usb:first` from the stock app's `usbDisabled` array.
    /// Absent / already clean → no-op. Empty result is written back as `-array`.
    static func clearUsbFirst(
        trigger: OpenDisplayDefaultsTrigger,
        performWrites: Bool = true,
        entries: [String]? = nil,
        writeUsbDisabled: (([String]) -> Void)? = nil,
        log: @escaping (String) -> Void = { HelperLogger.shared.info($0) }
    ) {
        let current: [String]
        if let entries {
            current = entries
        } else if performWrites {
            guard let read = readUsbDisabledArray() else { return }
            current = read
        } else {
            current = [usbFirstEntry]
        }
        guard let kept = UsbDisabledFilter.removingFirst(current) else { return }
        if performWrites {
            if let writeUsbDisabled {
                writeUsbDisabled(kept)
            } else {
                writeUsbDisabledArray(kept)
            }
        }
        log(usbDisabledRemovedLine(trigger: trigger))
    }

    static func readUsbDisabledArray(read: (String) -> String? = OpenDisplayDefaults.readKey) -> [String]? {
        guard let raw = read(usbDisabledKey) else { return nil }
        return PlistStringArray.parse(raw)
    }

    static func writeUsbDisabledArray(_ entries: [String]) {
        var arguments = ["write", domain, usbDisabledKey, "-array"]
        arguments.append(contentsOf: entries)
        _ = ProcessRunner.run(executable: "/usr/bin/defaults", arguments: arguments, timeout: 5)
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

/// Pure `usbDisabled` mutation. `nil` means no write.
enum UsbDisabledFilter {
    static func removingFirst(_ entries: [String]) -> [String]? {
        guard entries.contains(OpenDisplayDefaults.usbFirstEntry) else { return nil }
        return entries.filter { $0 != OpenDisplayDefaults.usbFirstEntry }
    }
}

/// Parses `defaults read` array output such as `(\n    "usb:first",\n)` .
enum PlistStringArray {
    static func parse(_ stdout: String) -> [String] {
        var values: [String] = []
        var index = stdout.startIndex
        while index < stdout.endIndex {
            if stdout[index] == "\"" {
                let start = stdout.index(after: index)
                var cursor = start
                var escaped = false
                var collected = ""
                while cursor < stdout.endIndex {
                    let char = stdout[cursor]
                    if escaped {
                        collected.append(char)
                        escaped = false
                    } else if char == "\\" {
                        escaped = true
                    } else if char == "\"" {
                        values.append(collected)
                        index = stdout.index(after: cursor)
                        break
                    } else {
                        collected.append(char)
                    }
                    cursor = stdout.index(after: cursor)
                }
                if cursor >= stdout.endIndex { break }
                continue
            }
            index = stdout.index(after: index)
        }
        return values
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
