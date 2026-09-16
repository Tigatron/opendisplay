import XCTest

final class SerialSanitizerTests: XCTestCase {
    func testLowercasesAndKeepsHyphens() {
        XCTAssertEqual(SerialSanitizer.sanitize("R52T30-ABC"), "r52t30-abc")
        XCTAssertEqual(SerialSanitizer.sanitize("emulator-5554"), "emulator-5554")
    }

    func testStripsIllegalHostnameCharacters() {
        XCTAssertEqual(SerialSanitizer.sanitize("ABC:DEF/12"), "abcdef12")
    }

    func testFallbackWhenNothingLegalRemains() {
        let value = SerialSanitizer.sanitize(":::")
        XCTAssertTrue(value.hasPrefix("dev-"))
        XCTAssertEqual(value.count, 12)
    }

    func testHostnameAndTXTIdentity() {
        XCTAssertEqual(SerialSanitizer.hostname(for: "R52T"), "od-usb-r52t.local.")
        XCTAssertEqual(SerialSanitizer.txtID(for: "R52T"), "usb-R52T")
    }
}
