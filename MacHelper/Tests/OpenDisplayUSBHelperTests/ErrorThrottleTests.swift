import XCTest

final class ErrorThrottleTests: XCTestCase {
    func testAllowsFirstThenDropsIdentical() {
        let throttle = ErrorThrottle()
        XCTAssertTrue(throttle.allow(key: "R52", message: "PolicyDenied"))
        XCTAssertFalse(throttle.allow(key: "R52", message: "PolicyDenied"))
        XCTAssertTrue(throttle.allow(key: "R52", message: "NameConflict"))
        throttle.reset(key: "R52")
        XCTAssertTrue(throttle.allow(key: "R52", message: "NameConflict"))
    }

    func testKeysAreIndependent() {
        let throttle = ErrorThrottle()
        XCTAssertTrue(throttle.allow(key: "A", message: "x"))
        XCTAssertTrue(throttle.allow(key: "B", message: "x"))
        XCTAssertFalse(throttle.allow(key: "A", message: "x"))
    }
}
