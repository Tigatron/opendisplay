import Foundation

enum ForwardOutcome: Equatable {
    case ok
    case portBusy(String)
    case failed(String)
}

protocol AdbRunning {
    func run(arguments: [String], timeout: TimeInterval) -> ProcessResult
}

struct RealAdbRunner: AdbRunning {
    let executable: String

    func run(arguments: [String], timeout: TimeInterval = HelperConstants.adbTimeout) -> ProcessResult {
        ProcessRunner.run(executable: executable, arguments: arguments, timeout: timeout)
    }
}

struct AdbClient {
    var runner: AdbRunning
    var log: (String) -> Void

    init(executable: String, log: @escaping (String) -> Void = { _ in }) {
        self.runner = RealAdbRunner(executable: executable)
        self.log = log
    }

    init(runner: AdbRunning, log: @escaping (String) -> Void = { _ in }) {
        self.runner = runner
        self.log = log
    }

    func version() -> Result<String, Error> {
        let result = runner.run(arguments: ["version"], timeout: HelperConstants.adbTimeout)
        guard result.succeeded else {
            return .failure(adbError("version", result))
        }
        let lines = result.stdout
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        return .success(lines.prefix(2).joined(separator: " · "))
    }

    /// Idempotent. Never `kill-server`.
    func startServer() -> Result<Void, Error> {
        let result = runner.run(arguments: ["start-server"], timeout: HelperConstants.adbTimeout)
        if result.succeeded { return .success(()) }
        return .failure(adbError("start-server", result))
    }

    func receiverPath(serial: String) -> String {
        let result = runner.run(
            arguments: ["-s", serial, "shell", "pm", "path", HelperConstants.receiverPackage],
            timeout: HelperConstants.adbTimeout
        )
        return result.stdout.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    func isReceiverInstalled(serial: String) -> Bool {
        let path = receiverPath(serial: serial)
        return path.contains("package:") || path.contains(".apk")
    }

    func launchReceiver(serial: String) -> ProcessResult {
        runner.run(
            arguments: ["-s", serial, "shell", "am", "start", "-n", HelperConstants.receiverActivity],
            timeout: HelperConstants.adbTimeout
        )
    }

    func deviceModel(serial: String) -> String? {
        let result = runner.run(
            arguments: ["-s", serial, "shell", "getprop", "ro.product.model"],
            timeout: HelperConstants.adbTimeout
        )
        let model = result.stdout.trimmingCharacters(in: .whitespacesAndNewlines)
        return model.isEmpty ? nil : model
    }

    func listForwards(serial: String) -> [AdbForward] {
        let result = runner.run(
            arguments: ["-s", serial, "forward", "--list"],
            timeout: HelperConstants.adbTimeout
        )
        return ForwardListParser.parse(result.stdout).filter { $0.serial == serial }
    }

    func forward(serial: String, localPort: UInt16) -> ForwardOutcome {
        let existing = listForwards(serial: serial)
        if ForwardListParser.existing(serial: serial, localPort: localPort, in: existing) != nil {
            log("reusing existing forward \(serial) tcp:\(localPort) tcp:9000")
            return .ok
        }
        for stale in ForwardListParser.staleLocals(serial: serial, keeping: localPort, in: existing) {
            log("removing stale forward \(serial) tcp:\(stale)")
            removeForward(serial: serial, localPort: stale)
        }
        let result = runner.run(
            arguments: ["-s", serial, "forward", "--no-rebind", "tcp:\(localPort)", "tcp:9000"],
            timeout: HelperConstants.adbTimeout
        )
        if result.succeeded { return .ok }
        let text = result.combinedOutput
        let lowered = text.lowercased()
        if lowered.contains("already in use")
            || lowered.contains("cannot bind")
            || lowered.contains("address already")
            || lowered.contains("rebind") {
            return .portBusy(text.isEmpty ? "already in use" : text)
        }
        return .failed(describe("forward tcp:\(localPort)", result))
    }

    func removeForward(serial: String, localPort: UInt16) {
        _ = runner.run(
            arguments: ["-s", serial, "forward", "--remove", "tcp:\(localPort)"],
            timeout: HelperConstants.adbTimeout
        )
    }

    func sendHeartbeat(serial: String, port: UInt16, helperVersion: String) -> ProcessResult {
        runner.run(
            arguments: [
                "-s", serial, "shell", "am", "broadcast",
                "-n", HelperConstants.heartbeatComponent,
                "-a", HelperConstants.heartbeatAction,
                "--ei", "port", String(port),
                "--es", "helperVersion", helperVersion,
                "--ei", "ttlMs", String(HelperConstants.heartbeatTTLMs)
            ],
            timeout: HelperConstants.adbTimeout
        )
    }

    private func adbError(_ verb: String, _ result: ProcessResult) -> NSError {
        NSError(
            domain: "AdbClient",
            code: Int(result.exitCode),
            userInfo: [NSLocalizedDescriptionKey: describe(verb, result)]
        )
    }

    private func describe(_ verb: String, _ result: ProcessResult) -> String {
        if result.timedOut { return "adb \(verb) timed out" }
        let output = result.combinedOutput
        return output.isEmpty ? "adb \(verb) exited \(result.exitCode)" : "adb \(verb): \(output)"
    }
}
