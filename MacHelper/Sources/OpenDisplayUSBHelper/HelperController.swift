import AppKit
import Combine
import Foundation

@MainActor
final class HelperController: ObservableObject {
    @Published var snapshot = HelperSnapshot(
        adbPath: nil,
        adbVersion: nil,
        statusText: "Starting…",
        devices: [],
        lastError: nil
    )
    @Published var recentLog: [String] = []

    private let engine: HelperEngine
    private let settings: HelperSettings
    private var cancellables = Set<AnyCancellable>()
    private var logTimer: Timer?

    init(settings: HelperSettings) {
        self.settings = settings
        let engine = HelperEngine { snapshot in
            DispatchQueue.main.async {
                NotificationCenter.default.post(name: HelperController.snapshotNote, object: snapshot)
            }
        }
        self.engine = engine
        NotificationCenter.default.publisher(for: HelperController.snapshotNote)
            .compactMap { $0.object as? HelperSnapshot }
            .receive(on: RunLoop.main)
            .sink { [weak self] snapshot in
                self?.snapshot = snapshot
            }
            .store(in: &cancellables)
        engine.start()
        settings.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                self?.pushSettings()
            }
            .store(in: &cancellables)
        pushSettings()
        refreshLog()
        logTimer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refreshLog() }
        }
    }

    var menuBarSymbol: String {
        if snapshot.adbPath == nil { return "cable.connector.slash" }
        if snapshot.devices.contains(where: { $0.phase == .ready }) {
            return "cable.connector"
        }
        if snapshot.devices.contains(where: { $0.phase == .failed }) {
            return "exclamationmark.triangle"
        }
        return "cable.connector"
    }

    func openOpenDisplay() {
        DispatchQueue.global(qos: .utility).async {
            let result = ProcessRunner.run(
                executable: "/usr/bin/open",
                arguments: ["-a", "OpenDisplay"],
                timeout: 5
            )
            if !result.succeeded {
                HelperLogger.shared.error("open OpenDisplay: \(result.combinedOutput)")
            }
        }
    }

    func revealLog() {
        let url = HelperLogger.shared.logFileURL
        let directory = url.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        if !FileManager.default.fileExists(atPath: url.path) {
            FileManager.default.createFile(atPath: url.path, contents: nil)
        }
        NSWorkspace.shared.activateFileViewerSelecting([url])
    }

    func openSettings() {
        NSApp.activate(ignoringOtherApps: true)
        NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
    }

    func quit() {
        engine.stop()
        NSApp.terminate(nil)
    }

    func validateAdb(_ path: String) -> String {
        let resolved = AdbLocator.locate(override: path.isEmpty ? nil : path)
        guard let resolved else { return "adb not found at this path" }
        switch AdbClient(executable: resolved).version() {
        case .success(let text):
            return text
        case .failure(let error):
            return error.localizedDescription
        }
    }

    private func pushSettings() {
        engine.updateSettings(settings.snapshot)
    }

    private func refreshLog() {
        recentLog = HelperLogger.shared.recentLines(20)
    }

    private static let snapshotNote = Notification.Name("OpenDisplayUSBHelper.snapshot")
}
