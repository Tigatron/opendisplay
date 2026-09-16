import XCTest
import dnssd

final class DNSServiceErrorTests: XCTestCase {
    func testMapsPolicyDeniedAndCommonCodes() {
        XCTAssertEqual(DNSServiceError.name(DNSServiceErrorType(kDNSServiceErr_PolicyDenied)), "PolicyDenied")
        XCTAssertEqual(Int32(kDNSServiceErr_PolicyDenied), -65570)
        XCTAssertEqual(DNSServiceError.name(DNSServiceErrorType(kDNSServiceErr_BadParam)), "BadParam")
        XCTAssertEqual(DNSServiceError.name(DNSServiceErrorType(kDNSServiceErr_NameConflict)), "NameConflict")
        XCTAssertEqual(DNSServiceError.name(DNSServiceErrorType(kDNSServiceErr_ServiceNotRunning)), "ServiceNotRunning")
        XCTAssertEqual(DNSServiceError.name(DNSServiceErrorType(kDNSServiceErr_BadInterfaceIndex)), "BadInterfaceIndex")
        XCTAssertEqual(DNSServiceError.name(DNSServiceErrorType(kDNSServiceErr_Refused)), "Refused")
    }

    func testPolicyDeniedIncludesLocalNetworkHint() {
        let text = DNSServiceError.describe(
            DNSServiceErrorType(kDNSServiceErr_PolicyDenied),
            operation: "A-record registration"
        )
        XCTAssertTrue(text.contains("PolicyDenied"))
        XCTAssertTrue(text.contains(DNSServiceError.localNetworkHint))
    }
}
