import XCTest

final class SettingsReevaluationTests: XCTestCase {
    private let autoConnect = SettingsSnapshot(
        adbPathOverride: "",
        launchReceiverOnAttach: true,
        sendHeartbeat: false,
        writeOpenDisplayDefaults: true
    )
    private let heartbeat = SettingsSnapshot(
        adbPathOverride: "",
        launchReceiverOnAttach: true,
        sendHeartbeat: true,
        writeOpenDisplayDefaults: false
    )
    private let bothOff = SettingsSnapshot(
        adbPathOverride: "",
        launchReceiverOnAttach: true,
        sendHeartbeat: false,
        writeOpenDisplayDefaults: false
    )

    func testEnablingAutoConnectOnReady9000WritesAndWithdraws() {
        let action = SettingsReevaluation.actions(
            settings: autoConnect,
            phase: .ready,
            tunnelPort: 9000,
            heartbeatOn: false,
            wroteDefaults: false,
            bonjourPublished: true
        )
        XCTAssertTrue(action.writeDefaults)
        XCTAssertTrue(action.withdrawProxy)
        XCTAssertFalse(action.revertDefaults)
        XCTAssertFalse(action.republishProxy)
        XCTAssertFalse(action.startHeartbeat)
    }

    func testEnablingAutoConnectWhenAlreadyManualOnlyWritesIfNeeded() {
        let action = SettingsReevaluation.actions(
            settings: autoConnect,
            phase: .ready,
            tunnelPort: 9000,
            heartbeatOn: false,
            wroteDefaults: true,
            bonjourPublished: false
        )
        XCTAssertFalse(action.writeDefaults)
        XCTAssertFalse(action.withdrawProxy)
        XCTAssertFalse(action.republishProxy)
    }

    func testDisablingAutoConnectDeletesKeysAndRepublishes() {
        let action = SettingsReevaluation.actions(
            settings: bothOff,
            phase: .ready,
            tunnelPort: 9000,
            heartbeatOn: false,
            wroteDefaults: true,
            bonjourPublished: false
        )
        XCTAssertTrue(action.revertDefaults)
        XCTAssertTrue(action.republishProxy)
        XCTAssertFalse(action.writeDefaults)
        XCTAssertFalse(action.withdrawProxy)
    }

    func testFallbackPortDoesNotWriteDefaultsOrWithdraw() {
        let action = SettingsReevaluation.actions(
            settings: autoConnect,
            phase: .ready,
            tunnelPort: 9010,
            heartbeatOn: false,
            wroteDefaults: false,
            bonjourPublished: true
        )
        XCTAssertFalse(action.writeDefaults)
        XCTAssertFalse(action.withdrawProxy)
        XCTAssertFalse(action.republishProxy)
    }

    func testNotReadyDoesNotWriteDefaults() {
        let action = SettingsReevaluation.actions(
            settings: autoConnect,
            phase: .publishing,
            tunnelPort: 9000,
            heartbeatOn: false,
            wroteDefaults: false,
            bonjourPublished: false
        )
        XCTAssertFalse(action.writeDefaults)
        XCTAssertFalse(action.withdrawProxy)
        XCTAssertFalse(action.republishProxy)
    }

    func testEnablingHeartbeatOnReady9000Starts() {
        let action = SettingsReevaluation.actions(
            settings: heartbeat,
            phase: .ready,
            tunnelPort: 9000,
            heartbeatOn: false,
            wroteDefaults: false,
            bonjourPublished: true
        )
        XCTAssertTrue(action.startHeartbeat)
        XCTAssertFalse(action.stopHeartbeat)
        XCTAssertFalse(action.republishProxy)
    }

    func testDisablingHeartbeatStops() {
        let action = SettingsReevaluation.actions(
            settings: bothOff,
            phase: .ready,
            tunnelPort: 9000,
            heartbeatOn: true,
            wroteDefaults: false,
            bonjourPublished: true
        )
        XCTAssertTrue(action.stopHeartbeat)
        XCTAssertFalse(action.startHeartbeat)
    }

    func testAlreadyMatchingStateIsNoOp() {
        let action = SettingsReevaluation.actions(
            settings: heartbeat,
            phase: .ready,
            tunnelPort: 9000,
            heartbeatOn: true,
            wroteDefaults: false,
            bonjourPublished: true
        )
        XCTAssertFalse(action.startHeartbeat)
        XCTAssertFalse(action.stopHeartbeat)
        XCTAssertFalse(action.writeDefaults)
        XCTAssertFalse(action.revertDefaults)
        XCTAssertFalse(action.withdrawProxy)
        XCTAssertFalse(action.republishProxy)
    }
}
