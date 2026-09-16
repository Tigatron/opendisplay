import XCTest

final class HelloFrameTests: XCTestCase {
    func testParsesLengthPrefixedHello() throws {
        let json = """
        {"type":"hello","pixelsWide":2800,"pixelsHigh":1752,"scale":2,"device":"AndroidTablet","id":"abc-uuid","pv":3}
        """
        var bytes = Data()
        let payload = Data(json.utf8)
        var header = UInt32(payload.count).bigEndian
        bytes.append(Data(bytes: &header, count: 4))
        bytes.append(payload)

        let hello = try HelloFrame.parse(bytes: bytes)
        XCTAssertEqual(hello.pixelsWide, 2800)
        XCTAssertEqual(hello.pixelsHigh, 1752)
        XCTAssertEqual(hello.scale, 2)
        XCTAssertEqual(hello.device, "AndroidTablet")
        XCTAssertEqual(hello.id, "abc-uuid")
        XCTAssertEqual(hello.pv, 3)
        XCTAssertEqual(hello.txtProtocolVersion, 3)
    }

    func testAbsentPVFallsBackToThreeForTXT() throws {
        let json = """
        {"type":"hello","pixelsWide":100,"pixelsHigh":200,"scale":1.5}
        """
        let payload = Data(json.utf8)
        var header = UInt32(payload.count).bigEndian
        var bytes = Data(bytes: &header, count: 4)
        bytes.append(payload)
        let hello = try HelloFrame.parse(bytes: bytes)
        XCTAssertNil(hello.pv)
        XCTAssertEqual(hello.txtProtocolVersion, 3)
        XCTAssertEqual(hello.scale, 1.5, accuracy: 0.001)
    }

    func testConsumeWaitsForFullFrame() throws {
        let encoded = try HelloFrame.encodeForTests(
            HelloMessage(pixelsWide: 10, pixelsHigh: 20, scale: 1, device: nil, id: nil, pv: 2, addrs: [])
        )
        var buffer = encoded.prefix(3)
        var working = Data(buffer)
        XCTAssertNil(try HelloFrame.consume(from: &working))

        working = Data(encoded)
        let hello = try HelloFrame.consume(from: &working)
        XCTAssertEqual(hello?.pixelsWide, 10)
        XCTAssertTrue(working.isEmpty)
    }

    func testRejectsZeroLengthAndNonHello() {
        XCTAssertThrowsError(try HelloFrame.parse(bytes: Data([0, 0, 0, 0])))
        let payload = Data(#"{"type":"ping","t":1}"#.utf8)
        var header = UInt32(payload.count).bigEndian
        var bytes = Data(bytes: &header, count: 4)
        bytes.append(payload)
        XCTAssertThrowsError(try HelloFrame.parse(bytes: bytes)) { error in
            XCTAssertEqual(error as? HelloFrameError, .notHello)
        }
    }
}
