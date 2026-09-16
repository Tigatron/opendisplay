import Foundation

struct TunnelSettingsAction: Equatable {
    var startHeartbeat = false
    var stopHeartbeat = false
    var writeDefaults = false
    var revertDefaults = false
}

/// Pure desired-state vs live-tunnel comparison. Enabling
/// `writeOpenDisplayDefaults` while a 9000 tunnel is already `.ready` writes
/// immediately; disabling deletes the OpenDisplay keys. Same for heartbeat.
enum SettingsReevaluation {
    static func actions(
        settings: SettingsSnapshot,
        phase: DevicePhase,
        tunnelPort: UInt16?,
        heartbeatOn: Bool,
        wroteDefaults: Bool
    ) -> TunnelSettingsAction {
        var action = TunnelSettingsAction()
        let readyOnPreferred = phase == .ready && tunnelPort == HelperConstants.preferredTunnelPort

        if readyOnPreferred && settings.sendHeartbeat && !heartbeatOn {
            action.startHeartbeat = true
        }
        if heartbeatOn && !settings.sendHeartbeat {
            action.stopHeartbeat = true
        }
        if readyOnPreferred && settings.writeOpenDisplayDefaults && !wroteDefaults {
            action.writeDefaults = true
        }
        if wroteDefaults && !settings.writeOpenDisplayDefaults {
            action.revertDefaults = true
        }
        return action
    }
}
