import Foundation

enum DevicePhase: String, Equatable {
    case idle
    case offline
    case unauthorized
    case other
    case checkingReceiver
    case receiverMissing
    case launching
    case forwarding
    case probing
    case publishing
    case ready
    case failed
}

struct DeviceRowState: Equatable, Identifiable {
    var id: String { serial }
    var serial: String
    var adbState: String
    var model: String?
    var product: String?
    var phase: DevicePhase
    var tunnelPort: UInt16?
    var bonjourName: String?
    var heartbeatOn: Bool
    var lastError: String?
    var wroteDefaults: Bool
    var hello: HelloMessage?

    var summary: String {
        var parts = [adbState]
        if let tunnelPort { parts.append(":\(tunnelPort)") }
        if let bonjourName { parts.append(bonjourName) }
        parts.append(heartbeatOn ? "heartbeat on" : "heartbeat off")
        return parts.joined(separator: " · ")
    }
}

struct TunnelHooks {
    var isReceiverInstalled: (String) -> Bool = { _ in false }
    var launchReceiver: (String) -> Void = { _ in }
    var deviceModel: (String) -> String? = { _ in nil }
    var proposePort: (Set<UInt16>) -> UInt16? = { _ in nil }
    var nextPort: (UInt16, Set<UInt16>) -> UInt16? = { _, _ in nil }
    var forward: (String, UInt16) -> ForwardOutcome = { _, _ in .failed("unset") }
    var removeForward: (String, UInt16) -> Void = { _, _ in }
    var probe: (UInt16) throws -> HelloMessage = { _ in throw HelloFrameError.timeout }
    var publish: (BonjourProxy.Request) -> Result<String, Error> = { _ in
        .failure(NSError(domain: "Bonjour", code: -1))
    }
    var withdraw: () -> Void = {}
    var startHeartbeat: (String, UInt16) -> Void = { _, _ in }
    var stopHeartbeat: () -> Void = {}
    var writeDefaults: () -> Void = {}
    var revertDefaults: () -> Void = {}
}

/// Linear attach pipeline. All I/O is injected so unit tests never touch adb
/// or mDNS. `shouldAbort` is checked between steps so a disconnect mid-attach
/// drops the in-flight work instead of publishing a stale proxy.
final class DeviceTunnel {
    private(set) var state: DeviceRowState
    var hooks: TunnelHooks
    var settings: SettingsSnapshot
    var usedPorts: () -> Set<UInt16>
    private let helperVersion: String

    init(
        device: TrackedDevice,
        settings: SettingsSnapshot,
        helperVersion: String = "1.0.0",
        hooks: TunnelHooks = TunnelHooks(),
        usedPorts: @escaping () -> Set<UInt16> = { [] }
    ) {
        self.state = DeviceRowState(
            serial: device.serial,
            adbState: device.state,
            model: device.displayModel,
            product: device.product,
            phase: Self.phase(forAdbState: device.state),
            tunnelPort: nil,
            bonjourName: nil,
            heartbeatOn: false,
            lastError: nil,
            wroteDefaults: false,
            hello: nil
        )
        self.settings = settings
        self.helperVersion = helperVersion
        self.hooks = hooks
        self.usedPorts = usedPorts
    }

    static func phase(forAdbState state: String) -> DevicePhase {
        switch state {
        case "device": return .idle
        case "offline": return .offline
        case "unauthorized": return .unauthorized
        default: return .other
        }
    }

    @discardableResult
    func applyAdb(_ device: TrackedDevice, shouldAbort: () -> Bool = { false }) -> DeviceRowState {
        state.adbState = device.state
        if state.model == nil {
            state.model = device.displayModel
        }
        state.product = device.product
        if device.isAuthorizedReady {
            if state.phase == .ready || isAttaching {
                return state
            }
            return attach(shouldAbort: shouldAbort)
        }
        teardown(keepRowPhase: Self.phase(forAdbState: device.state))
        return state
    }

    var isAttaching: Bool {
        switch state.phase {
        case .checkingReceiver, .launching, .forwarding, .probing, .publishing:
            return true
        default:
            return false
        }
    }

    @discardableResult
    func attach(shouldAbort: () -> Bool = { false }) -> DeviceRowState {
        func aborted() -> Bool {
            if shouldAbort() {
                teardown(keepRowPhase: Self.phase(forAdbState: state.adbState))
                return true
            }
            return false
        }

        state.lastError = nil
        state.phase = .checkingReceiver
        if aborted() { return state }

        guard hooks.isReceiverInstalled(state.serial) else {
            state.phase = .receiverMissing
            state.lastError = "Receiver app not installed (\(HelperConstants.receiverPackage))"
            return state
        }
        if aborted() { return state }

        if settings.launchReceiverOnAttach {
            state.phase = .launching
            hooks.launchReceiver(state.serial)
            if aborted() { return state }
        }

        state.phase = .forwarding
        var tried = usedPorts()
        guard var port = hooks.proposePort(tried) else {
            fail("no free local port for adb forward")
            return state
        }

        while true {
            if aborted() { return state }
            switch hooks.forward(state.serial, port) {
            case .ok:
                state.tunnelPort = port
                return probeAndPublish(port: port, shouldAbort: shouldAbort)
            case .portBusy:
                tried.insert(port)
                guard let next = hooks.nextPort(port, tried) else {
                    fail("could not bind a local forward port")
                    return state
                }
                port = next
            case .failed(let message):
                fail(message)
                return state
            }
        }
    }

    @discardableResult
    func detach() -> DeviceRowState {
        teardown(keepRowPhase: .idle)
        return state
    }

    func teardown(keepRowPhase: DevicePhase) {
        hooks.stopHeartbeat()
        hooks.withdraw()
        if let port = state.tunnelPort {
            hooks.removeForward(state.serial, port)
        }
        if state.wroteDefaults {
            hooks.revertDefaults()
        }
        state.phase = keepRowPhase
        state.tunnelPort = nil
        state.bonjourName = nil
        state.heartbeatOn = false
        state.wroteDefaults = false
        state.hello = nil
        if keepRowPhase != .failed && keepRowPhase != .receiverMissing {
            state.lastError = nil
        }
    }

    private func probeAndPublish(port: UInt16, shouldAbort: () -> Bool) -> DeviceRowState {
        state.phase = .probing
        if shouldAbort() {
            teardown(keepRowPhase: Self.phase(forAdbState: state.adbState))
            return state
        }

        let hello: HelloMessage
        do {
            hello = try hooks.probe(port)
        } catch {
            fail("hello probe: \(error.localizedDescription)")
            hooks.removeForward(state.serial, port)
            state.tunnelPort = nil
            return state
        }
        state.hello = hello
        if shouldAbort() {
            teardown(keepRowPhase: Self.phase(forAdbState: state.adbState))
            return state
        }

        if let model = hooks.deviceModel(state.serial), !model.isEmpty {
            state.model = model
        }
        if state.model == nil || state.model?.isEmpty == true {
            state.model = hello.device ?? "Android"
        }

        state.phase = .publishing
        let displayName = "\(state.model ?? "Android") (USB)"
        let request = BonjourProxy.Request(
            name: displayName,
            hostname: SerialSanitizer.hostname(for: state.serial),
            port: port,
            txt: TXTRecordBuilder.make(
                pv: hello.txtProtocolVersion,
                id: SerialSanitizer.txtID(for: state.serial),
                model: state.model
            )
        )
        switch hooks.publish(request) {
        case .success(let name):
            state.bonjourName = name
            state.phase = .ready
            if port == HelperConstants.preferredTunnelPort {
                if settings.sendHeartbeat {
                    hooks.startHeartbeat(state.serial, port)
                    state.heartbeatOn = true
                }
                if settings.writeOpenDisplayDefaults {
                    hooks.writeDefaults()
                    state.wroteDefaults = true
                }
            }
        case .failure(let error):
            fail("Bonjour: \(error.localizedDescription)")
            hooks.removeForward(state.serial, port)
            state.tunnelPort = nil
        }
        if shouldAbort() {
            teardown(keepRowPhase: Self.phase(forAdbState: state.adbState))
        }
        return state
    }

    private func fail(_ message: String) {
        hooks.stopHeartbeat()
        hooks.withdraw()
        if let port = state.tunnelPort {
            hooks.removeForward(state.serial, port)
        }
        if state.wroteDefaults {
            hooks.revertDefaults()
            state.wroteDefaults = false
        }
        state.phase = .failed
        state.lastError = message
        state.tunnelPort = nil
        state.bonjourName = nil
        state.heartbeatOn = false
        state.hello = nil
    }
}
