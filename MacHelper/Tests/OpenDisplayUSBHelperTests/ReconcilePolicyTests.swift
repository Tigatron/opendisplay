import XCTest

final class ReconcilePolicyTests: XCTestCase {
    func testDoesNotRetryReady() {
        XCTAssertFalse(ReconcilePolicy.shouldRetry(phase: .ready, busy: false, authorized: true))
        XCTAssertFalse(ReconcilePolicy.shouldRetry(phase: .ready, busy: true, authorized: true))
    }

    func testRetriesMissingOrFailedWhenIdleAndAuthorized() {
        XCTAssertTrue(ReconcilePolicy.shouldRetry(phase: .receiverMissing, busy: false, authorized: true))
        XCTAssertTrue(ReconcilePolicy.shouldRetry(phase: .failed, busy: false, authorized: true))
    }

    func testSkipsBusyUnauthorizedAndInFlight() {
        XCTAssertFalse(ReconcilePolicy.shouldRetry(phase: .failed, busy: true, authorized: true))
        XCTAssertFalse(ReconcilePolicy.shouldRetry(phase: .failed, busy: false, authorized: false))
        XCTAssertFalse(ReconcilePolicy.shouldRetry(phase: .forwarding, busy: false, authorized: true))
        XCTAssertFalse(ReconcilePolicy.shouldRetry(phase: nil, busy: false, authorized: true))
    }
}
