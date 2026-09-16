import XCTest

final class PortAllocatorTests: XCTestCase {
    func testFirstDeviceGets9000WhenFree() {
        var checked: [UInt16] = []
        let port = PortAllocator.propose(used: []) { candidate in
            checked.append(candidate)
            return candidate == 9000
        }
        XCTAssertEqual(port, 9000)
        XCTAssertEqual(checked, [9000])
    }

    func testFallsBackFrom9010When9000Busy() {
        let port = PortAllocator.propose(used: []) { $0 != 9000 && $0 != 9010 }
        XCTAssertEqual(port, 9011)
    }

    func testSkipsAlreadyUsedPorts() {
        let port = PortAllocator.propose(used: [9000, 9010, 9011]) { _ in true }
        XCTAssertEqual(port, 9012)
    }

    func testNextWalksUpAfterBusyForward() {
        let next = PortAllocator.next(after: 9010, used: [9000, 9010]) { _ in true }
        XCTAssertEqual(next, 9011)
    }

    func testReturnsNilWhenRangeExhausted() {
        var used = Set<UInt16>([9000])
        for port in PortAllocator.fallbackStart...PortAllocator.fallbackEnd {
            used.insert(port)
        }
        XCTAssertNil(PortAllocator.propose(used: used) { _ in true })
        XCTAssertNil(PortAllocator.next(after: 9100, used: used) { _ in true })
    }
}
