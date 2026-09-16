import XCTest

final class QuitTeardownTests: XCTestCase {
    func testRunOnceInvokesInjectedTeardownExactlyOnce() {
        var events: [String] = []
        let coordinator = TerminationCoordinator {
            events.append("withdraw")
            events.append("heartbeat off")
            events.append("revert defaults")
        }
        XCTAssertFalse(coordinator.hasRun)
        coordinator.runOnce()
        coordinator.runOnce()
        XCTAssertTrue(coordinator.hasRun)
        XCTAssertEqual(events, ["withdraw", "heartbeat off", "revert defaults"])
    }

    func testQuitTearsDownEveryReadyTunnelViaInjectedHooks() {
        let first = HookLog()
        let second = HookLog()
        let settings = SettingsSnapshot(
            adbPathOverride: "",
            launchReceiverOnAttach: false,
            sendHeartbeat: true,
            writeOpenDisplayDefaults: true
        )
        let tunnels = [
            makeTunnel(serial: "R52A", log: first, settings: settings),
            makeTunnel(serial: "R52B", log: second, settings: settings)
        ]
        tunnels.forEach { XCTAssertEqual($0.attach().phase, .ready) }
        XCTAssertTrue(first.events.contains("write defaults"))
        XCTAssertTrue(second.events.contains("heartbeat on R52B:9000"))

        let coordinator = TerminationCoordinator {
            tunnels.forEach { $0.teardown(keepRowPhase: .idle) }
        }
        coordinator.runOnce()
        coordinator.runOnce()

        for (tunnel, log) in zip(tunnels, [first, second]) {
            XCTAssertEqual(tunnel.state.phase, .idle)
            XCTAssertNil(tunnel.state.tunnelPort)
            XCTAssertFalse(tunnel.state.wroteDefaults)
            XCTAssertFalse(tunnel.state.heartbeatOn)
            XCTAssertEqual(log.events.filter { $0 == "withdraw" }.count, 1)
            XCTAssertEqual(log.events.filter { $0 == "heartbeat off" }.count, 1)
            XCTAssertEqual(log.events.filter { $0 == "revert defaults" }.count, 1)
        }
    }

    private func makeTunnel(
        serial: String,
        log: HookLog,
        settings: SettingsSnapshot
    ) -> DeviceTunnel {
        let device = TrackedDevice(serial: serial, state: "device", model: "SM_X800")
        let hello = HelloMessage(
            pixelsWide: 2800,
            pixelsHigh: 1752,
            scale: 2,
            device: "AndroidTablet",
            id: "id",
            pv: 3,
            addrs: []
        )
        let tunnel = DeviceTunnel(device: device, settings: settings, helperVersion: "1.0.0")
        var hooks = TunnelHooks()
        hooks.isReceiverInstalled = { _ in true }
        hooks.launchReceiver = { _ in }
        hooks.deviceModel = { _ in "SM-X800" }
        hooks.proposePort = { _ in 9000 }
        hooks.nextPort = { _, _ in nil }
        hooks.forward = { _, _ in .ok }
        hooks.removeForward = { serial, port in log.events.append("remove \(serial):\(port)") }
        hooks.probe = { _ in hello }
        hooks.publish = { request in .success(request.name) }
        hooks.withdraw = { log.events.append("withdraw") }
        hooks.startHeartbeat = { serial, port in log.events.append("heartbeat on \(serial):\(port)") }
        hooks.stopHeartbeat = { log.events.append("heartbeat off") }
        hooks.writeDefaults = { _ in log.events.append("write defaults") }
        hooks.revertDefaults = { _ in log.events.append("revert defaults") }
        tunnel.hooks = hooks
        return tunnel
    }
}

private final class HookLog {
    var events: [String] = []
}
