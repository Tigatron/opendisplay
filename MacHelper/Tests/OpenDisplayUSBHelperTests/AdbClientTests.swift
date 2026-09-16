import XCTest

final class AdbClientTests: XCTestCase {
    func testForwardReusesExistingMatchingEntry() {
        let runner = ScriptedAdbRunner(scripts: [
            .init(expect: ["-s", "R52", "forward", "--list"], stdout: "R52 tcp:9000 tcp:9000\n")
        ])
        let client = AdbClient(runner: runner)
        XCTAssertEqual(client.forward(serial: "R52", localPort: 9000), .ok)
        XCTAssertEqual(runner.commands.count, 1)
        XCTAssertFalse(runner.commands.contains(where: { $0.contains("--no-rebind") }))
    }

    func testForwardRemovesStaleLocalThenNoRebind() {
        let runner = ScriptedAdbRunner(scripts: [
            .init(expect: ["-s", "R52", "forward", "--list"], stdout: "R52 tcp:9010 tcp:9000\n"),
            .init(expect: ["-s", "R52", "forward", "--remove", "tcp:9010"], stdout: ""),
            .init(expect: ["-s", "R52", "forward", "--no-rebind", "tcp:9000", "tcp:9000"], stdout: "")
        ])
        let client = AdbClient(runner: runner)
        XCTAssertEqual(client.forward(serial: "R52", localPort: 9000), .ok)
        XCTAssertEqual(runner.commands.count, 3)
        XCTAssertTrue(runner.commands[1].contains("-s"))
        XCTAssertTrue(runner.commands[1].contains("R52"))
    }

    func testForwardClassifiesCannotRebindAsBusy() {
        let runner = ScriptedAdbRunner(scripts: [
            .init(expect: ["-s", "R52", "forward", "--list"], stdout: ""),
            .init(
                expect: ["-s", "R52", "forward", "--no-rebind", "tcp:9000", "tcp:9000"],
                stdout: "",
                stderr: "cannot rebind existing socket",
                exitCode: 1
            )
        ])
        let client = AdbClient(runner: runner)
        XCTAssertEqual(client.forward(serial: "R52", localPort: 9000), .portBusy)
    }

    func testRemoveForwardAlwaysPassesSerial() {
        let runner = ScriptedAdbRunner(scripts: [
            .init(expect: ["-s", "R52", "forward", "--remove", "tcp:9000"], stdout: "")
        ])
        let client = AdbClient(runner: runner)
        client.removeForward(serial: "R52", localPort: 9000)
        XCTAssertEqual(runner.commands.first, ["-s", "R52", "forward", "--remove", "tcp:9000"])
    }

    func testForwardDoesNotRemoveForeignRemotePorts() {
        let mixed = """
        S tcp:63029 tcp:12969
        S tcp:9010 tcp:9000
        S tcp:9000 tcp:9000
        """
        let runner = ScriptedAdbRunner(scripts: [
            .init(expect: ["-s", "S", "forward", "--list"], stdout: mixed)
        ])
        let client = AdbClient(runner: runner)
        XCTAssertEqual(client.forward(serial: "S", localPort: 9000), .ok)
        XCTAssertFalse(runner.commands.contains(where: { $0.contains("--remove") }))
    }

    func testForwardRemovesOnlyOurStale9000Remote() {
        let mixed = """
        S tcp:63029 tcp:12969
        S tcp:9010 tcp:9000
        """
        let runner = ScriptedAdbRunner(scripts: [
            .init(expect: ["-s", "S", "forward", "--list"], stdout: mixed),
            .init(expect: ["-s", "S", "forward", "--remove", "tcp:9010"], stdout: ""),
            .init(expect: ["-s", "S", "forward", "--no-rebind", "tcp:9000", "tcp:9000"], stdout: "")
        ])
        let client = AdbClient(runner: runner)
        XCTAssertEqual(client.forward(serial: "S", localPort: 9000), .ok)
        XCTAssertEqual(runner.commands.count, 3)
        XCTAssertFalse(runner.commands.contains(where: { $0.contains("63029") }))
    }

    func testListForwardsFiltersToRequestedSerial() {
        let runner = ScriptedAdbRunner(scripts: [
            .init(
                expect: ["-s", "R52", "forward", "--list"],
                stdout: "R52 tcp:9000 tcp:9000\nOTHER tcp:9010 tcp:9000\n"
            )
        ])
        let client = AdbClient(runner: runner)
        XCTAssertEqual(client.listForwards(serial: "R52").map(\.serial), ["R52"])
    }
}

private final class ScriptedAdbRunner: AdbRunning {
    struct Script {
        var expect: [String]
        var stdout: String
        var stderr: String = ""
        var exitCode: Int32 = 0
    }

    private let scripts: [Script]
    private(set) var commands: [[String]] = []
    private var index = 0

    init(scripts: [Script]) {
        self.scripts = scripts
    }

    func run(arguments: [String], timeout: TimeInterval) -> ProcessResult {
        commands.append(arguments)
        let script = scripts[min(index, scripts.count - 1)]
        index += 1
        XCTAssertEqual(arguments, script.expect)
        return ProcessResult(exitCode: script.exitCode, stdout: script.stdout, stderr: script.stderr, timedOut: false)
    }
}
