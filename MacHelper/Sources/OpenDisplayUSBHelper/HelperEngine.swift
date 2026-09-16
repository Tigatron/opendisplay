import Foundation

struct HelperSnapshot: Equatable {
    var adbPath: String?
    var adbVersion: String?
    var statusText: String
    var devices: [DeviceRowState]
    var lastError: String?
    var localNetworkPermission: LocalNetworkPermission = .unknown
}

/// Owns adb tracking and per-device tunnels. All mutable state lives on
/// `queue`. Process waits and TCP probes run on `io`.
final class HelperEngine: @unchecked Sendable {
    let queue = DispatchQueue(label: "\(HelperConstants.helperBundleId).engine")
    private let io = DispatchQueue(label: "\(HelperConstants.helperBundleId).io", qos: .utility)
    private let log: HelperLogger
    private let onChange: (HelperSnapshot) -> Void

    private var settings = SettingsSnapshot(
        adbPathOverride: "",
        launchReceiverOnAttach: true,
        sendHeartbeat: true,
        writeOpenDisplayDefaults: false
    )
    private var adbPath: String?
    private var adbClient: AdbClient?
    private var adbVersion: String?
    private var statusText = "Starting…"
    private var lastError: String?
    private var slots: [String: DeviceSlot] = [:]
    private var tracker = AdbDeviceStream()
    private var parser = TrackDevicesParser()
    private var reconnectAttempt = 0
    private var reconnectWork: DispatchWorkItem?
    private var reconcileTimer: DispatchSourceTimer?
    private var started = false
    private let helperVersion: String
    private let errorThrottle = ErrorThrottle()
    private let localNetwork = LocalNetworkPrompt()
    private var localNetworkPermission: LocalNetworkPermission = .unknown

    init(
        log: HelperLogger = .shared,
        helperVersion: String? = nil,
        onChange: @escaping (HelperSnapshot) -> Void
    ) {
        self.log = log
        self.onChange = onChange
        self.helperVersion = helperVersion
            ?? (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String)
            ?? "1.0.0"
    }

    func start() {
        queue.async { [weak self] in
            guard let self, !self.started else { return }
            self.started = true
            self.log.info("helper \(self.helperVersion) starting")
            self.reloadAdbAndTrack()
            self.startReconcile()
            self.localNetwork.start(queue: self.queue) { [weak self] in
                self?.queue.async { self?.reconcile() }
            }
        }
    }

    func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.started = false
            self.reconnectWork?.cancel()
            self.reconcileTimer?.cancel()
            self.localNetwork.stop()
            self.tracker.stop()
            for serial in Array(self.slots.keys) {
                self.teardown(serial: serial, remove: true)
            }
            self.publish()
        }
    }

    func updateSettings(_ snapshot: SettingsSnapshot) {
        queue.async { [weak self] in
            guard let self else { return }
            let adbChanged = snapshot.adbPathOverride != self.settings.adbPathOverride
            self.settings = snapshot
            if adbChanged {
                self.log.info("adb override changed — restarting tracker")
                self.reloadAdbAndTrack()
            }
            for slot in self.slots.values {
                slot.tunnel?.applySettings(snapshot)
            }
            self.publish()
        }
    }

    func snapshot() -> HelperSnapshot {
        queue.sync { currentSnapshot() }
    }

    // MARK: - adb

    private func reloadAdbAndTrack() {
        reconnectWork?.cancel()
        tracker.stop()
        parser = TrackDevicesParser()
        guard let path = AdbLocator.locate(override: settings.adbPathOverride) else {
            adbPath = nil
            adbClient = nil
            adbVersion = nil
            statusText = "adb not found"
            lastError = "Install android-platform-tools or set the adb path in Settings"
            log.error(lastError ?? "adb not found")
            publish()
            return
        }
        adbPath = path
        adbClient = nil
        statusText = "starting adb…"
        publish()
        io.async { [weak self] in
            guard let self else { return }
            let client = AdbClient(executable: path, log: { self.log.info($0) })
            _ = client.startServer()
            let version = client.version()
            self.queue.async {
                self.adbClient = client
                switch version {
                case .success(let text):
                    self.adbVersion = text
                    self.statusText = text
                    self.lastError = nil
                    self.log.info("using \(path) — \(text)")
                case .failure(let error):
                    self.adbVersion = nil
                    self.statusText = "adb found but not usable"
                    self.lastError = error.localizedDescription
                    self.log.error(error.localizedDescription)
                }
                self.beginTracking()
                self.publish()
            }
        }
    }

    private func beginTracking() {
        guard let path = adbPath else { return }
        do {
            try tracker.start(
                executable: path,
                onChunk: { [weak self] chunk in
                    self?.queue.async { self?.handleTrackerChunk(chunk) }
                },
                onEnd: { [weak self] code in
                    self?.queue.async { self?.handleTrackerEnd(code) }
                }
            )
            reconnectAttempt = 0
            log.info("track-devices started")
        } catch {
            lastError = "track-devices failed: \(error.localizedDescription)"
            log.error(lastError ?? "")
            scheduleReconnect()
        }
    }

    private func handleTrackerChunk(_ chunk: Data) {
        let snapshots = parser.ingest(chunk)
        for devices in snapshots {
            apply(devices)
        }
    }

    private func handleTrackerEnd(_ code: Int32) {
        guard started else { return }
        log.error("track-devices exited (\(code)) — reconnecting")
        scheduleReconnect()
    }

    private func scheduleReconnect() {
        reconnectWork?.cancel()
        let delay = min(pow(2.0, Double(reconnectAttempt)), 30)
        reconnectAttempt += 1
        statusText = "reconnecting to adb in \(Int(delay))s"
        publish()
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.started else { return }
            self.reloadAdbAndTrack()
        }
        reconnectWork = work
        queue.asyncAfter(deadline: .now() + delay, execute: work)
    }

    // MARK: - devices

    private func apply(_ devices: [TrackedDevice]) {
        let incoming = Dictionary(uniqueKeysWithValues: devices.map { ($0.serial, $0) })
        let gone = Set(slots.keys).subtracting(incoming.keys)
        for serial in gone {
            log.info("device \(serial) disappeared")
            teardown(serial: serial, remove: true)
        }
        for device in devices {
            if slots[device.serial] == nil {
                slots[device.serial] = DeviceSlot(serial: device.serial)
                log.info("device \(device.serial) \(device.state) model=\(device.displayModel ?? "?")")
            }
            sync(device)
        }
        publish()
    }

    private func sync(_ device: TrackedDevice) {
        guard let slot = slots[device.serial] else { return }
        slot.lastAdb = device
        if device.isAuthorizedReady {
            if slot.tunnel?.state.phase == .ready || slot.busy { return }
            startAttach(slot)
        } else {
            log.info("\(device.serial) is \(device.state) — tearing down tunnel if any")
            teardown(serial: device.serial, remove: false, adb: device)
        }
    }

    private func startAttach(_ slot: DeviceSlot) {
        slot.generation += 1
        let generation = slot.generation
        slot.busy = true
        let device = slot.lastAdb ?? TrackedDevice(serial: slot.serial, state: "device")
        let settings = self.settings
        let path = adbPath
        let helperVersion = self.helperVersion
        io.async { [weak self] in
            guard let self else { return }
            let client = self.queue.sync { self.cachedClient(path: path) }
            let reusable = ForwardListParser.reusableLocalPorts(
                serial: slot.serial,
                in: client?.listForwards(serial: slot.serial) ?? []
            )
            let plan: (used: Set<UInt16>, reserved: UInt16?) = self.queue.sync {
                guard slot.generation == generation, self.started else { return ([], nil) }
                let used = self.currentUsedPorts(except: slot.serial)
                let reserved = PortAllocator.propose(used: used) { candidate in
                    PortAllocator.isAvailable(
                        candidate,
                        bindFree: PortAllocator.isLoopbackPortFree(candidate),
                        reusableBySameDevice: reusable.contains(candidate)
                    )
                }
                slot.reservedPort = reserved
                return (used, reserved)
            }
            let tunnel = self.makeTunnel(
                device: device,
                settings: settings,
                used: plan.used,
                reservedPort: plan.reserved,
                client: client,
                helperVersion: helperVersion,
                slot: slot,
                generation: generation
            )
            _ = tunnel.attach(shouldAbort: { [weak self, weak slot] in
                guard let self, let slot else { return true }
                return self.queue.sync { slot.generation != generation || !self.started }
            })
            self.queue.async {
                slot.busy = false
                guard slot.generation == generation, self.started else {
                    tunnel.teardown(keepRowPhase: .idle)
                    return
                }
                slot.tunnel = tunnel
                slot.reservedPort = tunnel.state.tunnelPort
                let summary = "\(slot.serial) phase=\(tunnel.state.phase.rawValue) port=\(tunnel.state.tunnelPort.map(String.init) ?? "-") name=\(tunnel.state.bonjourName ?? "-")"
                if let error = tunnel.state.lastError {
                    if self.errorThrottle.allow(key: slot.serial, message: error) {
                        self.log.info(summary)
                        self.log.error("\(slot.serial): \(error)")
                    }
                } else {
                    self.errorThrottle.reset(key: slot.serial)
                    self.log.info(summary)
                }
                self.publish()
            }
        }
    }

    private func makeTunnel(
        device: TrackedDevice,
        settings: SettingsSnapshot,
        used: Set<UInt16>,
        reservedPort: UInt16?,
        client: AdbClient?,
        helperVersion: String,
        slot: DeviceSlot,
        generation: Int
    ) -> DeviceTunnel {
        var hooks = TunnelHooks()
        hooks.isReceiverInstalled = { serial in
            client?.isReceiverInstalled(serial: serial) ?? false
        }
        hooks.launchReceiver = { serial in
            let result = client?.launchReceiver(serial: serial)
            if let result, !result.succeeded {
                self.log.info("am start: \(result.combinedOutput)")
            }
        }
        hooks.deviceModel = { serial in
            client?.deviceModel(serial: serial)
        }
        hooks.proposePort = { extraUsed in
            let taken = used.union(extraUsed)
            if let reservedPort, !taken.contains(reservedPort) {
                return reservedPort
            }
            let reusable = ForwardListParser.reusableLocalPorts(
                serial: device.serial,
                in: client?.listForwards(serial: device.serial) ?? []
            )
            return PortAllocator.propose(used: taken) { candidate in
                PortAllocator.isAvailable(
                    candidate,
                    bindFree: PortAllocator.isLoopbackPortFree(candidate),
                    reusableBySameDevice: reusable.contains(candidate)
                )
            }
        }
        hooks.nextPort = { port, extraUsed in
            let reusable = ForwardListParser.reusableLocalPorts(
                serial: device.serial,
                in: client?.listForwards(serial: device.serial) ?? []
            )
            return PortAllocator.next(after: port, used: used.union(extraUsed)) { candidate in
                PortAllocator.isAvailable(
                    candidate,
                    bindFree: PortAllocator.isLoopbackPortFree(candidate),
                    reusableBySameDevice: reusable.contains(candidate)
                )
            }
        }
        hooks.forward = { serial, port in
            client?.forward(serial: serial, localPort: port) ?? .failed("adb missing")
        }
        hooks.removeForward = { serial, port in
            client?.removeForward(serial: serial, localPort: port)
        }
        hooks.probe = { port in
            try HelloProbe.probe(port: port)
        }
        hooks.publish = { [weak self, weak slot] request in
            guard let self, let slot else {
                return .failure(NSError(domain: "Bonjour", code: -1, userInfo: [NSLocalizedDescriptionKey: "slot gone"]))
            }
            let box = ResultBox<String>()
            let done = DispatchSemaphore(value: 0)
            self.queue.async {
                let proxy = BonjourProxy(queue: self.queue)
                slot.proxy = proxy
                proxy.publish(
                    request,
                    onName: { name in
                        box.value = .success(name)
                        done.signal()
                    },
                    onError: { message in
                        box.value = .failure(NSError(domain: "Bonjour", code: -1, userInfo: [NSLocalizedDescriptionKey: message]))
                        done.signal()
                    }
                )
            }
            let wait = done.wait(timeout: .now() + 3)
            if wait == .timedOut {
                self.noteDNSServiceResult(succeeded: false, message: "registration timed out")
                return .failure(NSError(domain: "Bonjour", code: -2, userInfo: [NSLocalizedDescriptionKey: "registration timed out"]))
            }
            let result = box.value ?? .failure(NSError(domain: "Bonjour", code: -3))
            switch result {
            case .success:
                self.noteDNSServiceResult(succeeded: true, message: nil)
            case .failure(let error):
                self.noteDNSServiceResult(succeeded: false, message: error.localizedDescription)
            }
            return result
        }
        hooks.withdraw = { [weak self, weak slot] in
            guard let self, let slot else { return }
            self.queue.async { slot.proxy?.withdraw(); slot.proxy = nil }
        }
        hooks.startHeartbeat = { [weak self, weak slot] serial, port in
            self?.queue.async { self?.startHeartbeat(slot: slot, serial: serial, port: port, generation: generation) }
        }
        hooks.stopHeartbeat = { [weak self, weak slot] in
            self?.queue.async { self?.stopHeartbeat(slot) }
        }
        hooks.writeDefaults = {
            OpenDisplayDefaults.writeTunnel()
        }
        hooks.revertDefaults = {
            OpenDisplayDefaults.revertTunnel()
        }
        return DeviceTunnel(
            device: device,
            settings: settings,
            helperVersion: helperVersion,
            hooks: hooks,
            usedPorts: { used }
        )
    }

    private func teardown(serial: String, remove: Bool, adb: TrackedDevice? = nil) {
        guard let slot = slots[serial] else { return }
        slot.generation += 1
        slot.busy = false
        stopHeartbeat(slot)
        slot.proxy?.withdraw()
        slot.proxy = nil
        if let port = slot.tunnel?.state.tunnelPort {
            let client = cachedClient(path: adbPath)
            let deviceSerial = slot.serial
            io.async {
                client?.removeForward(serial: deviceSerial, localPort: port)
            }
        }
        if slot.tunnel?.state.wroteDefaults == true {
            io.async { OpenDisplayDefaults.revertTunnel() }
        }
        slot.reservedPort = nil
        if remove {
            slots.removeValue(forKey: serial)
        } else {
            let device = adb ?? slot.lastAdb ?? TrackedDevice(serial: serial, state: "offline")
            slot.tunnel = DeviceTunnel(device: device, settings: settings, helperVersion: helperVersion)
        }
    }

    private func startHeartbeat(slot: DeviceSlot?, serial: String, port: UInt16, generation: Int) {
        guard let slot, slot.generation == generation else { return }
        stopHeartbeat(slot)
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now(), repeating: HelperConstants.heartbeatInterval)
        timer.setEventHandler { [weak self, weak slot] in
            guard let self, let slot, slot.generation == generation else { return }
            let client = self.cachedClient(path: self.adbPath)
            self.io.async {
                guard let client else { return }
                let result = client.sendHeartbeat(
                    serial: serial,
                    port: port,
                    helperVersion: self.helperVersion
                )
                if !result.succeeded {
                    self.log.info("heartbeat: \(result.combinedOutput)")
                }
            }
        }
        timer.resume()
        slot.heartbeat = timer
    }

    private func stopHeartbeat(_ slot: DeviceSlot?) {
        slot?.heartbeat?.cancel()
        slot?.heartbeat = nil
    }

    private func startReconcile() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 10, repeating: 10)
        timer.setEventHandler { [weak self] in
            self?.reconcile()
        }
        timer.resume()
        reconcileTimer = timer
    }

    private func reconcile() {
        for slot in slots.values {
            let device = slot.lastAdb
            let phase = slot.tunnel?.state.phase
            guard ReconcilePolicy.shouldRetry(
                phase: phase,
                busy: slot.busy,
                authorized: device?.isAuthorizedReady == true
            ) else { continue }
            log.info("retrying \(slot.serial) (\(phase?.rawValue ?? "nil"))")
            startAttach(slot)
        }
    }

    private func cachedClient(path: String?) -> AdbClient? {
        if let adbClient, adbPath == path { return adbClient }
        guard let path else { return nil }
        let client = AdbClient(executable: path, log: { [weak self] in self?.log.info($0) })
        if adbPath == path {
            adbClient = client
        }
        return client
    }

    private func currentUsedPorts(except serial: String) -> Set<UInt16> {
        var ports = Set<UInt16>()
        for slot in slots.values where slot.serial != serial {
            if let reserved = slot.reservedPort { ports.insert(reserved) }
            if let live = slot.tunnel?.state.tunnelPort { ports.insert(live) }
        }
        return ports
    }

    private func currentSnapshot() -> HelperSnapshot {
        let devices = slots.values
            .map { slot -> DeviceRowState in
                if var state = slot.tunnel?.state {
                    if state.model == nil { state.model = slot.lastAdb?.displayModel }
                    return state
                }
                let device = slot.lastAdb ?? TrackedDevice(serial: slot.serial, state: "unknown")
                return DeviceRowState(
                    serial: device.serial,
                    adbState: device.state,
                    model: device.displayModel,
                    product: device.product,
                    phase: DeviceTunnel.phase(forAdbState: device.state),
                    tunnelPort: nil,
                    bonjourName: nil,
                    heartbeatOn: false,
                    lastError: device.isAuthorizedReady ? nil : nil,
                    wroteDefaults: false,
                    hello: nil
                )
            }
            .sorted { $0.serial < $1.serial }
        return HelperSnapshot(
            adbPath: adbPath,
            adbVersion: adbVersion,
            statusText: statusText,
            devices: devices,
            lastError: lastError,
            localNetworkPermission: localNetworkPermission
        )
    }

    private func noteDNSServiceResult(succeeded: Bool, message: String?) {
        queue.async { [weak self] in
            guard let self else { return }
            self.localNetworkPermission = LocalNetworkPermission.fromLastResult(
                succeeded: succeeded,
                errorMessage: message
            )
            self.publish()
        }
    }

    private func publish() {
        let snapshot = currentSnapshot()
        DispatchQueue.main.async { self.onChange(snapshot) }
    }
}

private final class DeviceSlot {
    let serial: String
    var generation = 0
    var busy = false
    var lastAdb: TrackedDevice?
    var tunnel: DeviceTunnel?
    var proxy: BonjourProxy?
    var heartbeat: DispatchSourceTimer?
    var reservedPort: UInt16?

    init(serial: String) {
        self.serial = serial
    }
}

private final class ResultBox<T> {
    var value: Result<T, Error>?
}
