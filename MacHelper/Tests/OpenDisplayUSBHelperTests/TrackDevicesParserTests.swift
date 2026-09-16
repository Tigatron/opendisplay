import XCTest

final class TrackDevicesParserTests: XCTestCase {
    func testParsesAuthorizedDeviceWithLongListing() {
        let line = "R52T30ABC123    device usb:1-1.2 product:gts8wifi model:SM_X800 device:gts8wifi transport_id:3"
        let device = TrackDevicesParser.parseLine(line)
        XCTAssertEqual(device?.serial, "R52T30ABC123")
        XCTAssertEqual(device?.state, "device")
        XCTAssertEqual(device?.product, "gts8wifi")
        XCTAssertEqual(device?.model, "SM_X800")
        XCTAssertEqual(device?.displayModel, "SM-X800")
        XCTAssertEqual(device?.usb, "1-1.2")
        XCTAssertEqual(device?.transportID, "3")
        XCTAssertTrue(device?.isAuthorizedReady == true)
    }

    func testParsesOfflineAndUnauthorized() {
        XCTAssertEqual(TrackDevicesParser.parseLine("ABC offline")?.state, "offline")
        XCTAssertEqual(TrackDevicesParser.parseLine("DEF\tunauthorized")?.state, "unauthorized")
        XCTAssertFalse(TrackDevicesParser.parseLine("DEF unauthorized")!.isAuthorizedReady)
    }

    func testIgnoresHeadersAndEmpty() {
        XCTAssertNil(TrackDevicesParser.parseLine("List of devices attached"))
        XCTAssertNil(TrackDevicesParser.parseLine("* daemon not running; starting now at tcp:5037"))
        XCTAssertNil(TrackDevicesParser.parseLine(""))
        XCTAssertNil(TrackDevicesParser.parseLine("   "))
    }

    func testSnapshotExtractsMultipleDevices() {
        let text = """
        emulator-5554   device product:sdk model:sdk_gphone64_arm64
        R52T30ABC123    unauthorized usb:1-1
        """
        let devices = TrackDevicesParser.parseSnapshot(text)
        XCTAssertEqual(devices.map(\.serial), ["emulator-5554", "R52T30ABC123"])
        XCTAssertEqual(devices.map(\.state), ["device", "unauthorized"])
    }

    func testIncrementalStreamCommitsOnBlankLine() {
        let parser = TrackDevicesParser()
        let first = parser.ingest("R52 device usb:1-1 model:SM_X800\n\n")
        XCTAssertEqual(first.count, 1)
        XCTAssertEqual(first.first?.first?.serial, "R52")
        XCTAssertEqual(first.first?.first?.model, "SM_X800")

        let empty = parser.ingest("\n")
        XCTAssertEqual(empty, [[]])
    }

    func testPartialLineWaitsForNewline() {
        let parser = TrackDevicesParser()
        XCTAssertTrue(parser.ingest("R52 device").isEmpty)
        let done = parser.ingest(" usb:1-1\n\n")
        XCTAssertEqual(done.first?.first?.serial, "R52")
    }
}
