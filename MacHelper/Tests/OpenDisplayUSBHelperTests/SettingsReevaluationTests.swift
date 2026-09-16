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

    func testEnablingAutoConnectOnReady9000WritesImmediately() {
        let action = SettingsReevaluation.actions(
            settings: autoConnect,
            phase: .ready,
            tunnelPort: 9000,
            heartbeatOn: false,
            wroteDefaults: false
        )
        XCTAssertTrue(action.writeDefaults)
        XCTAssertFalse(action.revertDefaults)
        XCTAssertFalse(action.startHeartbeat)
    }

    func testDisablingAutoConnectDeletesKeys() {
        let action = SettingsReevaluation.actions(
            settings: bothOff,
            phase: .ready,
            tunnelPort: 9000,
            heartbeatOn: false,
            wroteDefaults: true
        )
        XCTAssertTrue(action.revertDefaults)
        XCTAssertFalse(action.writeDefaults)
    }

    func testFallbackPortDoesNotWriteDefaults() {
        let action = SettingsReevaluation.actions(
            settings: autoConnect,
            phase: .ready,
            tunnelPort: 9010,
            heartbeatOn: false,
            wroteDefaults: false
        )
        XCTAssertFalse(action.writeDefaults)
    }

    func testNotReadyDoesNotWriteDefaults() {
        let action = SettingsReevaluation.actions(
            settings: autoConnect,
            phase: .publishing,
            tunnelPort: 9000,
            heartbeatOn: false,
            wroteDefaults: false
        )
        XCTAssertFalse(action.writeDefaults)
    }

    func testEnablingHeartbeatOnReady9000Starts() {
        let action = SettingsReevaluation.actions(
            settings: heartbeat,
            phase: .ready,
            tunnelPort: 9000,
            heartbeatOn: false,
            wroteDefaults: false
        )
        XCTAssertTrue(action.startHeartbeat)
        XCTAssertFalse(action.stopHeartbeat)
    }

    func testDisablingHeartbeatStops() {
        let action = SettingsReevaluation.actions(
            settings: bothOff,
            phase: .ready,
            tunnelPort: 9000,
            heartbeatOn: true,
            wroteDefaults: false
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
            wroteDefaults: false
        )
        XCTAssertFalse(action.startHeartbeat)
        XCTAssertFalse(action.stopHeartbeat)
        XCTAssertFalse(action.writeDefaults)
        XCTAssertFalse(action.revertDefaults)
    }
}
