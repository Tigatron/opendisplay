import Combine
import Foundation
import ServiceManagement

struct SettingsSnapshot: Equatable {
    var adbPathOverride: String
    var launchReceiverOnAttach: Bool
    var sendHeartbeat: Bool
    var writeOpenDisplayDefaults: Bool
}

/// Persisted in UserDefaults domain `com.terrynamic.opendisplay.usbhelper`
/// (the helper bundle id). External `defaults write` is picked up via
/// `UserDefaults.didChangeNotification` plus a periodic reload.
final class HelperSettings: ObservableObject {
    enum Keys {
        static let adbPathOverride = "adbPathOverride"
        static let launchReceiverOnAttach = "launchReceiverOnAttach"
        static let sendHeartbeat = "sendHeartbeat"
        static let writeOpenDisplayDefaults = "writeOpenDisplayDefaults"
        static let startAtLogin = "startAtLogin"
    }

    static let defaultsDomain = HelperConstants.helperBundleId

    @Published var adbPathOverride: String {
        didSet { persist(adbPathOverride, key: Keys.adbPathOverride) }
    }
    @Published var launchReceiverOnAttach: Bool {
        didSet { persist(launchReceiverOnAttach, key: Keys.launchReceiverOnAttach) }
    }
    @Published var sendHeartbeat: Bool {
        didSet { persist(sendHeartbeat, key: Keys.sendHeartbeat) }
    }
    @Published var writeOpenDisplayDefaults: Bool {
        didSet { persist(writeOpenDisplayDefaults, key: Keys.writeOpenDisplayDefaults) }
    }
    @Published var startAtLogin: Bool {
        didSet { persist(startAtLogin, key: Keys.startAtLogin) }
    }
    @Published var loginItemStatus: SMAppService.Status

    var applyLoginItem: (Bool) throws -> Void = { enabled in
        if enabled {
            try SMAppService.mainApp.register()
        } else {
            try SMAppService.mainApp.unregister()
        }
    }

    private let defaults: UserDefaults
    private var applyingExternal = false
    private var cancellables = Set<AnyCancellable>()

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        adbPathOverride = defaults.string(forKey: Keys.adbPathOverride) ?? ""
        launchReceiverOnAttach = defaults.object(forKey: Keys.launchReceiverOnAttach) as? Bool ?? true
        sendHeartbeat = defaults.object(forKey: Keys.sendHeartbeat) as? Bool ?? true
        writeOpenDisplayDefaults = defaults.bool(forKey: Keys.writeOpenDisplayDefaults)
        let status = SMAppService.mainApp.status
        loginItemStatus = status
        if let stored = defaults.object(forKey: Keys.startAtLogin) as? Bool {
            startAtLogin = stored
        } else {
            startAtLogin = status == .enabled
        }
        NotificationCenter.default.publisher(for: UserDefaults.didChangeNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                self?.reloadIfChanged()
            }
            .store(in: &cancellables)
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

    func applyLoginItemFromSettings() throws {
        try applyLoginItem(startAtLogin)
        refreshLoginItemStatus()
    }

    func setStartAtLogin(_ enabled: Bool) throws {
        startAtLogin = enabled
        try applyLoginItemFromSettings()
    }

    /// Re-read the store so `defaults write com.terrynamic.opendisplay.usbhelper …`
    /// is visible while the helper is running.
    @discardableResult
    func reloadIfChanged() -> Bool {
        defaults.synchronize()
        let nextOverride = defaults.string(forKey: Keys.adbPathOverride) ?? ""
        let nextLaunch = defaults.object(forKey: Keys.launchReceiverOnAttach) as? Bool ?? true
        let nextHeartbeat = defaults.object(forKey: Keys.sendHeartbeat) as? Bool ?? true
        let nextWrite = defaults.bool(forKey: Keys.writeOpenDisplayDefaults)
        let nextLogin = defaults.object(forKey: Keys.startAtLogin) as? Bool ?? startAtLogin

        let loginChanged = nextLogin != startAtLogin
        let otherChanged = nextOverride != adbPathOverride
            || nextLaunch != launchReceiverOnAttach
            || nextHeartbeat != sendHeartbeat
            || nextWrite != writeOpenDisplayDefaults
        guard loginChanged || otherChanged else { return false }

        applyingExternal = true
        adbPathOverride = nextOverride
        launchReceiverOnAttach = nextLaunch
        sendHeartbeat = nextHeartbeat
        writeOpenDisplayDefaults = nextWrite
        startAtLogin = nextLogin
        applyingExternal = false

        if loginChanged {
            do {
                try applyLoginItemFromSettings()
            } catch {
                HelperLogger.shared.error("login item: \(error.localizedDescription)")
                refreshLoginItemStatus()
            }
        }
        return true
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

    private func persist(_ value: Any, key: String) {
        guard !applyingExternal else { return }
        defaults.set(value, forKey: key)
    }
}
