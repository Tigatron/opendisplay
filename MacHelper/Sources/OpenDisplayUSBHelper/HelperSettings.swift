import Combine
import Foundation
import ServiceManagement

struct SettingsSnapshot: Equatable {
    var adbPathOverride: String
    var launchReceiverOnAttach: Bool
    var sendHeartbeat: Bool
    var writeOpenDisplayDefaults: Bool
}

final class HelperSettings: ObservableObject {
    enum Keys {
        static let adbPathOverride = "adbPathOverride"
        static let launchReceiverOnAttach = "launchReceiverOnAttach"
        static let sendHeartbeat = "sendHeartbeat"
        static let writeOpenDisplayDefaults = "writeOpenDisplayDefaults"
    }

    @Published var adbPathOverride: String {
        didSet { defaults.set(adbPathOverride, forKey: Keys.adbPathOverride) }
    }
    @Published var launchReceiverOnAttach: Bool {
        didSet { defaults.set(launchReceiverOnAttach, forKey: Keys.launchReceiverOnAttach) }
    }
    @Published var sendHeartbeat: Bool {
        didSet { defaults.set(sendHeartbeat, forKey: Keys.sendHeartbeat) }
    }
    @Published var writeOpenDisplayDefaults: Bool {
        didSet { defaults.set(writeOpenDisplayDefaults, forKey: Keys.writeOpenDisplayDefaults) }
    }
    @Published var loginItemStatus: SMAppService.Status

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        adbPathOverride = defaults.string(forKey: Keys.adbPathOverride) ?? ""
        launchReceiverOnAttach = defaults.object(forKey: Keys.launchReceiverOnAttach) as? Bool ?? true
        sendHeartbeat = defaults.object(forKey: Keys.sendHeartbeat) as? Bool ?? true
        writeOpenDisplayDefaults = defaults.bool(forKey: Keys.writeOpenDisplayDefaults)
        loginItemStatus = SMAppService.mainApp.status
    }

    var snapshot: SettingsSnapshot {
        SettingsSnapshot(
            adbPathOverride: adbPathOverride,
            launchReceiverOnAttach: launchReceiverOnAttach,
            sendHeartbeat: sendHeartbeat,
            writeOpenDisplayDefaults: writeOpenDisplayDefaults
        )
    }

    func refreshLoginItemStatus() {
        loginItemStatus = SMAppService.mainApp.status
    }

    func setStartAtLogin(_ enabled: Bool) throws {
        if enabled {
            try SMAppService.mainApp.register()
        } else {
            try SMAppService.mainApp.unregister()
        }
        refreshLoginItemStatus()
    }

    static func loginItemLabel(_ status: SMAppService.Status) -> String {
        switch status {
        case .enabled: return "Enabled"
        case .requiresApproval: return "Needs approval in System Settings → Login Items"
        case .notFound: return "Not found — install the app under /Applications to enable"
        case .notRegistered: return "Not registered"
        @unknown default: return "Unknown"
        }
    }
}
