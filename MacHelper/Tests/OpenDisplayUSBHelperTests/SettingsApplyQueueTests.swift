import XCTest

/// Documents why `HelperEngine.updateSettings` dispatches
/// `DeviceTunnel.applySettings` onto `io` (`applySettingsOnIO`).
///
/// `hooks.publish` (HelperEngine, used by attach and by Manual-mode
/// republish) blocks the caller on a semaphore while the dns_sd work is
/// `queue.async`'d. That only completes if the caller is **not** `queue`.
final class SettingsApplyQueueTests: XCTestCase {
    func testBlockingPublishFromEngineQueueTimesOut() {
        let queue = DispatchQueue(label: "test.opendisplay.engine")
        let finished = expectation(description: "blocked publish returned")
        queue.async {
            let outcome = Self.blockingPublish(hoppingTo: queue, timeout: 0.2)
            XCTAssertEqual(outcome, .timedOut)
            finished.fulfill()
        }
        wait(for: [finished], timeout: 1)
    }

    func testBlockingPublishFromIOQueueSucceeds() {
        let queue = DispatchQueue(label: "test.opendisplay.engine")
        let io = DispatchQueue(label: "test.opendisplay.io")
        let finished = expectation(description: "off-queue publish returned")
        io.async {
            let outcome = Self.blockingPublish(hoppingTo: queue, timeout: 0.5)
            XCTAssertEqual(outcome, .published)
            finished.fulfill()
        }
        wait(for: [finished], timeout: 1)
    }

    private enum PublishOutcome: Equatable {
        case published
        case timedOut
    }

    /// Same shape as HelperEngine's `hooks.publish`: hop onto `engine` then
    /// wait on the caller thread.
    private static func blockingPublish(
        hoppingTo engine: DispatchQueue,
        timeout: TimeInterval
    ) -> PublishOutcome {
        let done = DispatchSemaphore(value: 0)
        engine.async { done.signal() }
        switch done.wait(timeout: .now() + timeout) {
        case .success: return .published
        case .timedOut: return .timedOut
        }
    }
}
