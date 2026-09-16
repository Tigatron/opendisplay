import Darwin
import XCTest

final class PortAllocatorTests: XCTestCase {
    func testFirstDeviceGets9000WhenFree() {
        var checked: [UInt16] = []
        let port = PortAllocator.propose(used: []) { candidate in
            checked.append(candidate)
            return candidate == 9000
        }
        XCTAssertEqual(port, 9000)
        XCTAssertEqual(checked, [])
    }

    func testProposeTries9000EvenWhenBindSaysBusy() {
        let port = PortAllocator.propose(used: []) { _ in false }
        XCTAssertEqual(port, 9000)
    }

    func testProposeSkips9000WhenUsedByAnotherDevice() {
        let port = PortAllocator.propose(used: [9000]) { _ in true }
        XCTAssertEqual(port, 9010)
    }

    func testFallbackWalksWhenPreferredUsedAnd9010Busy() {
        let port = PortAllocator.propose(used: [9000]) { $0 != 9010 }
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

    func testExistingForwardOwnedBySameDeviceCountsAsFree() {
        XCTAssertTrue(PortAllocator.isAvailable(9000, bindFree: false, reusableBySameDevice: true))
        XCTAssertTrue(PortAllocator.isAvailable(9000, bindFree: true, reusableBySameDevice: false))
        XCTAssertFalse(PortAllocator.isAvailable(9000, bindFree: false, reusableBySameDevice: false))

        let port = PortAllocator.propose(used: []) { candidate in
            PortAllocator.isAvailable(candidate, bindFree: false, reusableBySameDevice: candidate == 9000)
        }
        XCTAssertEqual(port, 9000)
    }

    func testReturnsNilWhenRangeExhausted() {
        var used = Set<UInt16>([9000])
        for port in PortAllocator.fallbackStart...PortAllocator.fallbackEnd {
            used.insert(port)
        }
        XCTAssertNil(PortAllocator.propose(used: used) { _ in true })
        XCTAssertNil(PortAllocator.next(after: 9100, used: used) { _ in true })
    }

    func testActiveListenerIsNotFree() {
        guard let listener = LoopbackTCP.listen() else {
            return XCTFail("could not bind a loopback listener")
        }
        defer { listener.close() }
        XCTAssertFalse(
            PortAllocator.isLoopbackPortFree(listener.port),
            "a LISTENING socket must make isLoopbackPortFree return false"
        )
    }

    func testEstablishedOnlyPortIsReportedFree() {
        guard let listener = LoopbackTCP.listen() else {
            return XCTFail("could not bind a loopback listener")
        }
        let port = listener.port
        guard let client = LoopbackTCP.connect(to: port) else {
            listener.close()
            return XCTFail("could not connect to listener")
        }
        guard let accepted = listener.accept() else {
            client.close()
            listener.close()
            return XCTFail("accept failed")
        }
        listener.close()
        defer {
            client.close()
            accepted.close()
        }
        XCTAssertTrue(
            PortAllocator.isLoopbackPortFree(port),
            "ESTABLISHED leftovers after the listener is gone must not look busy"
        )
    }
}

private struct LoopbackTCP {
    var fd: Int32
    var port: UInt16

    func close() {
        Darwin.close(fd)
    }

    func accept() -> LoopbackTCP? {
        var addr = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let accepted = withUnsafeMutablePointer(to: &addr) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.accept(fd, $0, &len)
            }
        }
        guard accepted >= 0 else { return nil }
        return LoopbackTCP(fd: accepted, port: port)
    }

    static func listen() -> LoopbackTCP? {
        let fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        guard fd >= 0 else { return nil }
        var on: Int32 = 1
        _ = setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &on, socklen_t(MemoryLayout<Int32>.size))
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = 0
        addr.sin_addr = in_addr(s_addr: inet_addr("127.0.0.1"))
        let bound = withUnsafePointer(to: &addr) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0, Darwin.listen(fd, 1) == 0 else {
            Darwin.close(fd)
            return nil
        }
        var boundAddr = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &boundAddr) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(fd, $0, &len)
            }
        }
        guard named == 0 else {
            Darwin.close(fd)
            return nil
        }
        return LoopbackTCP(fd: fd, port: UInt16(bigEndian: boundAddr.sin_port))
    }

    static func connect(to port: UInt16) -> LoopbackTCP? {
        let fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        guard fd >= 0 else { return nil }
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr = in_addr(s_addr: inet_addr("127.0.0.1"))
        let connected = withUnsafePointer(to: &addr) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard connected == 0 else {
            Darwin.close(fd)
            return nil
        }
        return LoopbackTCP(fd: fd, port: port)
    }
}
