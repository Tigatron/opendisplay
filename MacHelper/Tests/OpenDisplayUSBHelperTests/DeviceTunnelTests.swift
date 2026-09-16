import XCTest

final class DeviceTunnelTests: XCTestCase {
    private let tablet = TrackedDevice(
        serial: "R52T30ABC",
        state: "device",
        product: "gts8wifi",
        model: "SM_X800",
        device: "gts8wifi",
        usb: "1-1",
        transportID: "1"
    )

    private let hello = HelloMessage(
        pixelsWide: 2800,
        pixelsHigh: 1752,
        scale: 2,
        device: "AndroidTablet",
        id: "real-install-uuid",
        pv: 3,
        addrs: []
    )

    func testAttachTunnelsProbesPublishesAndDetaches() {
        let log = HookLog()
        let tunnel = makeTunnel(log: log, port: 9000)

        let attached = tunnel.attach()
        XCTAssertEqual(attached.phase, .ready)
        XCTAssertEqual(attached.tunnelPort, 9000)
        XCTAssertEqual(attached.bonjourName, "SM-X800 (USB)")
        XCTAssertEqual(attached.model, "SM-X800")
        XCTAssertTrue(attached.heartbeatOn)
        XCTAssertFalse(attached.wroteDefaults)
        XCTAssertEqual(log.events, [
            "check R52T30ABC",
            "launch R52T30ABC",
            "forward R52T30ABC:9000",
            "probe 9000",
            "model R52T30ABC",
            "publish SM-X800 (USB) od-usb-r52t30abc.local. 9000 usb-R52T30ABC pv=3",
            "heartbeat on R52T30ABC:9000"
        ])

        let detached = tunnel.detach()
        XCTAssertEqual(detached.phase, .idle)
        XCTAssertNil(detached.tunnelPort)
        XCTAssertNil(detached.bonjourName)
        XCTAssertFalse(detached.heartbeatOn)
        XCTAssertTrue(log.events.contains("withdraw"))
        XCTAssertTrue(log.events.contains("remove R52T30ABC:9000"))
        XCTAssertTrue(log.events.contains("heartbeat off"))
        XCTAssertFalse(log.events.contains("revert defaults"))
    }

    func testReceiverMissingDoesNotForward() {
        let log = HookLog()
        let tunnel = makeTunnel(log: log, installed: false)
        let state = tunnel.attach()
        XCTAssertEqual(state.phase, .receiverMissing)
        XCTAssertNil(state.tunnelPort)
        XCTAssertTrue(state.lastError?.contains("not installed") == true)
        XCTAssertEqual(log.events, ["check R52T30ABC"])
    }

    func testPortBusyFallsBackAndSkipsHeartbeat() {
        let log = HookLog()
        var busyOnce = true
        let tunnel = makeTunnel(log: log, port: 9000)
        tunnel.hooks.forward = { serial, port in
            log.events.append("forward \(serial):\(port)")
            if port == 9000, busyOnce {
                busyOnce = false
                return .portBusy
            }
            return .ok
        }
        tunnel.hooks.nextPort = { _, _ in 9010 }
        tunnel.hooks.probe = { port in
            log.events.append("probe \(port)")
            return self.hello
        }

        let state = tunnel.attach()
        XCTAssertEqual(state.phase, .ready)
        XCTAssertEqual(state.tunnelPort, 9010)
        XCTAssertFalse(state.heartbeatOn)
        XCTAssertFalse(log.events.contains(where: { $0.hasPrefix("heartbeat on") }))
    }

    func testAutoConnectWritesAndRevertsDefaultsOn9000() {
        let log = HookLog()
        let settings = SettingsSnapshot(
            adbPathOverride: "",
            launchReceiverOnAttach: false,
            sendHeartbeat: false,
            writeOpenDisplayDefaults: true
        )
        let tunnel = makeTunnel(log: log, port: 9000, settings: settings)
        XCTAssertEqual(tunnel.attach().wroteDefaults, true)
        XCTAssertTrue(log.events.contains("write defaults"))
        _ = tunnel.detach()
        XCTAssertTrue(log.events.contains("revert defaults"))
    }

    func testUnauthorizedTearsDownLiveTunnel() {
        let log = HookLog()
        let tunnel = makeTunnel(log: log, port: 9000)
        _ = tunnel.attach()
        XCTAssertEqual(tunnel.state.phase, .ready)

        var unauthorized = tablet
        unauthorized.state = "unauthorized"
        let state = tunnel.applyAdb(unauthorized)
        XCTAssertEqual(state.phase, .unauthorized)
        XCTAssertNil(state.tunnelPort)
        XCTAssertTrue(log.events.contains("withdraw"))
        XCTAssertTrue(log.events.contains("remove R52T30ABC:9000"))
    }

    func testAbortDuringAttachWithdraws() {
        let log = HookLog()
        let tunnel = makeTunnel(log: log, port: 9000)
        var calls = 0
        let state = tunnel.attach {
            calls += 1
            return calls > 2
        }
        XCTAssertNotEqual(state.phase, .ready)
        XCTAssertTrue(log.events.contains("withdraw") || state.tunnelPort == nil)
    }

    func testApplySettingsWritesDefaultsOnLive9000() {
        let log = HookLog()
        var settings = SettingsSnapshot(
            adbPathOverride: "",
            launchReceiverOnAttach: false,
            sendHeartbeat: false,
            writeOpenDisplayDefaults: false
        )
        let tunnel = makeTunnel(log: log, port: 9000, settings: settings)
        _ = tunnel.attach()
        XCTAssertFalse(tunnel.state.wroteDefaults)

        settings.writeOpenDisplayDefaults = true
        tunnel.applySettings(settings)
        XCTAssertTrue(tunnel.state.wroteDefaults)
        XCTAssertTrue(log.events.contains("write defaults"))

        settings.writeOpenDisplayDefaults = false
        tunnel.applySettings(settings)
        XCTAssertFalse(tunnel.state.wroteDefaults)
        XCTAssertTrue(log.events.contains("revert defaults"))
    }

    func testApplySettingsTogglesHeartbeatOnLive9000() {
        let log = HookLog()
        var settings = SettingsSnapshot(
            adbPathOverride: "",
            launchReceiverOnAttach: false,
            sendHeartbeat: false,
            writeOpenDisplayDefaults: false
        )
        let tunnel = makeTunnel(log: log, port: 9000, settings: settings)
        _ = tunnel.attach()
        XCTAssertFalse(tunnel.state.heartbeatOn)

        settings.sendHeartbeat = true
        tunnel.applySettings(settings)
        XCTAssertTrue(tunnel.state.heartbeatOn)
        XCTAssertTrue(log.events.contains("heartbeat on R52T30ABC:9000"))

        settings.sendHeartbeat = false
        tunnel.applySettings(settings)
        XCTAssertFalse(tunnel.state.heartbeatOn)
        XCTAssertTrue(log.events.contains("heartbeat off"))
    }

    func testPublishUsesSyntheticTXTIdentityNotReceiverID() {
        let log = HookLog()
        let tunnel = makeTunnel(log: log, port: 9000)
        _ = tunnel.attach()
        XCTAssertTrue(log.events.contains(where: { $0.contains("usb-R52T30ABC") }))
        XCTAssertFalse(log.events.contains(where: { $0.contains("real-install-uuid") }))
    }

    private func makeTunnel(
        log: HookLog,
        port: UInt16 = 9000,
        installed: Bool = true,
        settings: SettingsSnapshot = SettingsSnapshot(
            adbPathOverride: "",
            launchReceiverOnAttach: true,
            sendHeartbeat: true,
            writeOpenDisplayDefaults: false
        )
    ) -> DeviceTunnel {
        let tunnel = DeviceTunnel(device: tablet, settings: settings, helperVersion: "1.0.0")
        var hooks = TunnelHooks()
        hooks.isReceiverInstalled = { serial in
            log.events.append("check \(serial)")
            return installed
        }
        hooks.launchReceiver = { serial in
            log.events.append("launch \(serial)")
        }
        hooks.deviceModel = { serial in
            log.events.append("model \(serial)")
            return "SM-X800"
        }
        hooks.proposePort = { _ in port }
        hooks.nextPort = { _, _ in nil }
        hooks.forward = { serial, local in
            log.events.append("forward \(serial):\(local)")
            return .ok
        }
        hooks.removeForward = { serial, local in
            log.events.append("remove \(serial):\(local)")
        }
        hooks.probe = { local in
            log.events.append("probe \(local)")
            return self.hello
        }
        hooks.publish = { request in
            let pairs = TXTRecordBuilder.pairs(from: request.txt)
            let id = pairs.first(where: { $0.key == "id" })?.value ?? "?"
            let pv = pairs.first(where: { $0.key == "pv" })?.value ?? "?"
            log.events.append("publish \(request.name) \(request.hostname) \(request.port) \(id) pv=\(pv)")
            return .success(request.name)
        }
        hooks.withdraw = { log.events.append("withdraw") }
        hooks.startHeartbeat = { serial, local in
            log.events.append("heartbeat on \(serial):\(local)")
        }
        hooks.stopHeartbeat = { log.events.append("heartbeat off") }
        hooks.writeDefaults = { log.events.append("write defaults") }
        hooks.revertDefaults = { log.events.append("revert defaults") }
        tunnel.hooks = hooks
        return tunnel
    }
}

private final class HookLog {
    var events: [String] = []
}
