import Foundation

/// Long-lived `adb track-devices -l`. The process is owned here; the engine
/// reconnects with backoff when it exits. Never kill-server.
///
/// Chunks are raw `Data`. adb writes smart-socket frames (`[4 hex][payload]`)
/// that must not be decoded one `availableData` at a time — a UTF-8 split
/// would drop the tail of a frame.
final class AdbDeviceStream {
    private var process: Process?
    private var stdout: FileHandle?

    var isRunning: Bool { process?.isRunning == true }

    func start(
        executable: String,
        onChunk: @escaping (Data) -> Void,
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
            onChunk(data)
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
