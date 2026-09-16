import Foundation

/// Long-lived `adb track-devices -l`. The process is owned here; the engine
/// reconnects with backoff when it exits. Never kill-server.
final class AdbDeviceStream {
    private var process: Process?
    private var stdout: FileHandle?

    var isRunning: Bool { process?.isRunning == true }

    func start(
        executable: String,
        onChunk: @escaping (String) -> Void,
        onEnd: @escaping (Int32) -> Void
    ) throws {
        stop()
        let process = Process()
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = ["track-devices", "-l"]
        process.standardInput = FileHandle.nullDevice
        let out = Pipe()
        let err = Pipe()
        process.standardOutput = out
        process.standardError = err
        let handle = out.fileHandleForReading
        handle.readabilityHandler = { file in
            let data = file.availableData
            if data.isEmpty { return }
            if let text = String(data: data, encoding: .utf8) {
                onChunk(text)
            }
        }
        process.terminationHandler = { finished in
            handle.readabilityHandler = nil
            onEnd(finished.terminationStatus)
        }
        try process.run()
        self.process = process
        self.stdout = handle
    }

    func stop() {
        stdout?.readabilityHandler = nil
        process?.terminationHandler = nil
        if process?.isRunning == true {
            process?.terminate()
        }
        process = nil
        stdout = nil
    }
}
