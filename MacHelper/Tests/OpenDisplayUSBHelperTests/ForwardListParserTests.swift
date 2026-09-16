import XCTest

final class ForwardListParserTests: XCTestCase {
    func testParsesTcpTcpLines() {
        let text = """
        R52T30ABC tcp:9000 tcp:9000
        emulator-5554 tcp:9010 tcp:9000
        """
        let forwards = ForwardListParser.parse(text)
        XCTAssertEqual(forwards, [
            AdbForward(serial: "R52T30ABC", localPort: 9000, remotePort: 9000),
            AdbForward(serial: "emulator-5554", localPort: 9010, remotePort: 9000)
        ])
    }

    func testIgnoresNonTCPAndJunk() {
        let text = """
        R52 tcp:9000 localabstract:foo
        * daemon not running
        R52 tcp:9010 tcp:9000
        """
        XCTAssertEqual(ForwardListParser.parse(text).map(\.localPort), [9010])
    }

    func testExistingAndStaleHelpers() {
        let forwards = [
            AdbForward(serial: "R52", localPort: 9000, remotePort: 9000),
            AdbForward(serial: "R52", localPort: 9010, remotePort: 9000),
            AdbForward(serial: "OTHER", localPort: 9011, remotePort: 9000)
        ]
        XCTAssertNotNil(ForwardListParser.existing(serial: "R52", localPort: 9000, in: forwards))
        XCTAssertNil(ForwardListParser.existing(serial: "R52", localPort: 9011, in: forwards))
        XCTAssertEqual(
            ForwardListParser.staleLocals(serial: "R52", keeping: 9000, in: forwards),
            [9010]
        )
        XCTAssertEqual(
            ForwardListParser.reusableLocalPorts(serial: "R52", in: forwards),
            [9000, 9010]
        )
    }

    func testStaleLocalsIgnoreForeignRemotePorts() {
        let forwards = [
            AdbForward(serial: "S", localPort: 63029, remotePort: 12969),
            AdbForward(serial: "S", localPort: 9010, remotePort: 9000),
            AdbForward(serial: "S", localPort: 9000, remotePort: 9000)
        ]
        XCTAssertNotNil(ForwardListParser.existing(serial: "S", localPort: 9000, in: forwards))
        XCTAssertEqual(
            ForwardListParser.staleLocals(serial: "S", keeping: 9000, in: forwards),
            [9010]
        )
        XCTAssertEqual(
            ForwardListParser.reusableLocalPorts(serial: "S", in: forwards),
            [9010, 9000]
        )
        XCTAssertFalse(
            ForwardListParser.staleLocals(serial: "S", keeping: 9000, in: forwards).contains(63029)
        )
    }
}
