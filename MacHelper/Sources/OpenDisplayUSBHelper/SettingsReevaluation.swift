import Foundation

struct TunnelSettingsAction: Equatable {
    var startHeartbeat = false
    var stopHeartbeat = false
    var writeDefaults = false
    var revertDefaults = false
    var withdrawProxy = false
    var republishProxy = false
}

/// Pure desired-state vs live-tunnel comparison. Enabling
/// `writeOpenDisplayDefaults` (Manual mode) while a 9000 tunnel is already
/// `.ready` writes host/port immediately and withdraws the lo0 Bonjour proxy;
/// disabling deletes the OpenDisplay keys and republishes B1. Heartbeat is
/// independent of Manual mode.
enum SettingsReevaluation {
    static func actions(
        settings: SettingsSnapshot,
        phase: DevicePhase,
        tunnelPort: UInt16?,
        heartbeatOn: Bool,
        wroteDefaults: Bool,
        bonjourPublished: Bool
    ) -> TunnelSettingsAction {
        var action = TunnelSettingsAction()
        let readyOnPreferred = phase == .ready && tunnelPort == HelperConstants.preferredTunnelPort
        let ready = phase == .ready

        if readyOnPreferred && settings.sendHeartbeat && !heartbeatOn {
            action.startHeartbeat = true
        }
        if heartbeatOn && !settings.sendHeartbeat {
            action.stopHeartbeat = true
        }
        if readyOnPreferred && settings.writeOpenDisplayDefaults {
            if !wroteDefaults {
                action.writeDefaults = true
            }
            if bonjourPublished {
                action.withdrawProxy = true
            }
        }
        if wroteDefaults && !settings.writeOpenDisplayDefaults {
            action.revertDefaults = true
        }
        if ready && !settings.writeOpenDisplayDefaults && !bonjourPublished {
            action.republishProxy = true
        }
        return action
    }
}

enum SettingsChangeLog {
    static func line(key: String, value: Any) -> String {
        "settings changed: \(key)=\(value)"
    }

    static func lines(from old: SettingsSnapshot, to new: SettingsSnapshot) -> [String] {
        var lines: [String] = []
        if old.adbPathOverride != new.adbPathOverride {
            lines.append(line(key: HelperSettings.Keys.adbPathOverride, value: new.adbPathOverride))
        }
        if old.launchReceiverOnAttach != new.launchReceiverOnAttach {
            lines.append(line(key: HelperSettings.Keys.launchReceiverOnAttach, value: new.launchReceiverOnAttach))
        }
        if old.sendHeartbeat != new.sendHeartbeat {
            lines.append(line(key: HelperSettings.Keys.sendHeartbeat, value: new.sendHeartbeat))
        }
        if old.writeOpenDisplayDefaults != new.writeOpenDisplayDefaults {
            lines.append(line(key: HelperSettings.Keys.writeOpenDisplayDefaults, value: new.writeOpenDisplayDefaults))
        }
        return lines
    }
}
