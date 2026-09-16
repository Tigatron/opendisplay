import XCTest

final class HelperSettingsTests: XCTestCase {
    private let suiteName = "com.terrynamic.opendisplay.usbhelper.settings.test"

    override func tearDown() {
        UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    func testPersistsDocumentedKeys() {
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        let settings = HelperSettings(defaults: defaults)
        settings.applyLoginItem = { _ in }

        settings.adbPathOverride = "/opt/homebrew/bin/adb"
        settings.launchReceiverOnAttach = false
        settings.sendHeartbeat = false
        settings.writeOpenDisplayDefaults = true
        settings.startAtLogin = true

        XCTAssertEqual(HelperSettings.defaultsDomain, "com.terrynamic.opendisplay.usbhelper")
        XCTAssertEqual(defaults.string(forKey: HelperSettings.Keys.adbPathOverride), "/opt/homebrew/bin/adb")
        XCTAssertEqual(defaults.object(forKey: HelperSettings.Keys.launchReceiverOnAttach) as? Bool, false)
        XCTAssertEqual(defaults.object(forKey: HelperSettings.Keys.sendHeartbeat) as? Bool, false)
        XCTAssertEqual(defaults.bool(forKey: HelperSettings.Keys.writeOpenDisplayDefaults), true)
        XCTAssertEqual(defaults.object(forKey: HelperSettings.Keys.startAtLogin) as? Bool, true)
    }

    func testReloadPicksUpExternalWrite() {
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        let settings = HelperSettings(defaults: defaults)
        settings.applyLoginItem = { _ in }
        XCTAssertFalse(settings.writeOpenDisplayDefaults)

        defaults.set(true, forKey: HelperSettings.Keys.writeOpenDisplayDefaults)
        if !settings.writeOpenDisplayDefaults {
            XCTAssertTrue(settings.reloadIfChanged())
        }
        XCTAssertTrue(settings.writeOpenDisplayDefaults)
        XCTAssertFalse(settings.reloadIfChanged())
    }
}

final class InstallLocationTests: XCTestCase {
    func testApplicationsPath() {
        XCTAssertTrue(InstallLocation.isApplicationsPath("/Applications/OpenDisplay USB Helper.app"))
        XCTAssertTrue(InstallLocation.isApplicationsPath("/Applications"))
        XCTAssertFalse(InstallLocation.isApplicationsPath("/Users/me/build/OpenDisplay USB Helper.app"))
        XCTAssertFalse(InstallLocation.isApplicationsPath("/tmp/OpenDisplay USB Helper.app"))
    }
}

final class LocalNetworkPermissionTests: XCTestCase {
    func testInferFromLastDnsSdResult() {
        XCTAssertEqual(LocalNetworkPermission.fromLastResult(succeeded: true, errorMessage: nil), .granted)
        XCTAssertEqual(
            LocalNetworkPermission.fromLastResult(
                succeeded: false,
                errorMessage: "DNSServiceRegister failed (PolicyDenied -65570)"
            ),
            .denied
        )
        XCTAssertEqual(LocalNetworkPermission.fromLastResult(succeeded: false, errorMessage: "timed out"), .unknown)
        XCTAssertEqual(PrivacySettings.localNetworkURL.scheme, "x-apple.systempreferences")
    }
}
