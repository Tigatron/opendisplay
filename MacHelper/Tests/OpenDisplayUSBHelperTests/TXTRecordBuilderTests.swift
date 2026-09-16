import XCTest

final class TXTRecordBuilderTests: XCTestCase {
    func testBuildsPVIdAndModelInOrder() {
        let data = TXTRecordBuilder.make(pv: 3, id: "usb-R52", model: "SM-X800")
        let pairs = TXTRecordBuilder.pairs(from: data)
        XCTAssertEqual(pairs.map(\.key), ["pv", "id", "model"])
        XCTAssertEqual(pairs.map(\.value), ["3", "usb-R52", "SM-X800"])
        XCTAssertEqual(data.first, UInt8("pv=3".utf8.count))
        XCTAssertTrue(data.contains { _ in true })
        XCTAssertGreaterThan(data.count, 10)
    }

    func testOmitsEmptyModel() {
        let data = TXTRecordBuilder.make(pv: 2, id: "usb-abc", model: nil)
        let pairs = TXTRecordBuilder.pairs(from: data)
        XCTAssertEqual(pairs.map(\.key), ["pv", "id"])
        XCTAssertEqual(Dictionary(uniqueKeysWithValues: pairs.map { ($0.key, $0.value) })["pv"], "2")
    }

    func testLengthPrefixedEncoding() {
        let data = TXTRecordBuilder.make(pv: 3, id: "usb-x", model: nil)
        var index = data.startIndex
        while index < data.endIndex {
            let length = Int(data[index])
            XCTAssertGreaterThan(length, 0)
            index = data.index(index, offsetBy: 1 + length)
        }
        XCTAssertEqual(index, data.endIndex)
    }
}
