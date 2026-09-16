import Foundation

/// Writes the stock OpenDisplay app's `host`/`port` keys. The stock app
/// reads them at launch, so writing while OpenDisplay is not running is
/// intended. Off by default — it mutates another app's preferences.
enum OpenDisplayDefaults {
    static let domain = HelperConstants.openDisplayDefaultsDomain

    static func writeTunnel(host: String = "127.0.0.1", port: UInt16 = 9000) {
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

    static func revertTunnel() {
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
}
