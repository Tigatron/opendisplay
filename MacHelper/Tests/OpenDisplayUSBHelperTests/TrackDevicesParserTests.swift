import XCTest

final class TrackDevicesParserTests: XCTestCase {
    /// Captured on adb 1.0.41 / 36.0.0: 4-hex length + 104-byte payload (108 bytes).
    private static let capturedFrame: Data = {
        let serial = "R52T304Z7VD"
        let suffix = "device usb:1-2 product:gts8pwifizc model:SM_X800 device:gts8pwifi transport_id:2\n"
        let pad = 104 - serial.utf8.count - suffix.utf8.count
        precondition(pad >= 0)
        let payload = serial + String(repeating: " ", count: pad) + suffix
        precondition(payload.utf8.count == 104)
        let frame = Data("0068".utf8) + Data(payload.utf8)
        precondition(frame.count == 108)
        return frame
    }()

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

    func testFramedSampleEmitsOneDevice() {
        let snapshots = TrackDevicesParser().ingest(Self.capturedFrame)
        XCTAssertEqual(snapshots.count, 1)
        let device = snapshots[0][0]
        XCTAssertEqual(device.serial, "R52T304Z7VD")
        XCTAssertEqual(device.state, "device")
        XCTAssertEqual(device.model, "SM_X800")
        XCTAssertEqual(device.product, "gts8pwifizc")
        XCTAssertEqual(device.transportID, "2")
        XCTAssertEqual(device.usb, "1-2")
    }

    func testFramedSampleSplitAcrossChunks() {
        let frame = Self.capturedFrame
        let parser = TrackDevicesParser()
        XCTAssertTrue(parser.ingest(frame.prefix(2)).isEmpty)
        XCTAssertTrue(parser.ingest(frame.dropFirst(2).prefix(28)).isEmpty)
        let snapshots = parser.ingest(Data(frame.dropFirst(30)))
        XCTAssertEqual(snapshots.count, 1)
        XCTAssertEqual(snapshots[0].first?.serial, "R52T304Z7VD")
        XCTAssertEqual(snapshots[0].first?.state, "device")
        XCTAssertEqual(snapshots[0].first?.model, "SM_X800")
    }

    func testZeroLengthFrameIsEmptySnapshot() {
        let snapshots = TrackDevicesParser().ingest(Data("0000".utf8))
        XCTAssertEqual(snapshots, [[]])
    }

    func testTwoConsecutiveFramesInOneChunk() {
        var chunk = Self.capturedFrame
        chunk.append(contentsOf: Data("0000".utf8))
        let snapshots = TrackDevicesParser().ingest(chunk)
        XCTAssertEqual(snapshots.count, 2)
        XCTAssertEqual(snapshots[0].first?.serial, "R52T304Z7VD")
        XCTAssertEqual(snapshots[1], [])
    }

    func testTwoDeviceFramedPayload() {
        let payload = """
        R52T304Z7VD device usb:1-2 product:gts8pwifizc model:SM_X800 transport_id:2
        emulator-5554 unauthorized usb:1-3
        """
        + "\n"
        let length = String(format: "%04x", payload.utf8.count)
        let frame = Data(length.utf8) + Data(payload.utf8)
        let snapshots = TrackDevicesParser().ingest(frame)
        XCTAssertEqual(snapshots.count, 1)
        XCTAssertEqual(snapshots[0].map(\.serial), ["R52T304Z7VD", "emulator-5554"])
        XCTAssertEqual(snapshots[0].map(\.state), ["device", "unauthorized"])
    }

    func testLegacyBlankLineFormatStillWorks() {
        let parser = TrackDevicesParser()
        let first = parser.ingest("R52 device usb:1-1 model:SM_X800\n\n")
        XCTAssertEqual(first.count, 1)
        XCTAssertEqual(first.first?.first?.serial, "R52")
        XCTAssertEqual(first.first?.first?.model, "SM_X800")

        let empty = parser.ingest("\n")
        XCTAssertEqual(empty, [[]])
    }

    func testLegacyPartialLineWaitsForNewline() {
        let parser = TrackDevicesParser()
        XCTAssertTrue(parser.ingest("R52 device").isEmpty)
        let done = parser.ingest(" usb:1-1\n\n")
        XCTAssertEqual(done.first?.first?.serial, "R52")
    }

    func testFourHexSerialIsNotALengthPrefix() {
        let parser = TrackDevicesParser()
        let snapshots = parser.ingest("ABCD    device usb:1-1\n\n")
        XCTAssertEqual(snapshots.count, 1)
        XCTAssertEqual(snapshots[0].first?.serial, "ABCD")
        XCTAssertEqual(snapshots[0].first?.state, "device")
    }
}
