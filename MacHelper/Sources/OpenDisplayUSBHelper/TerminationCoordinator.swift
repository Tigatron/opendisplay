import Foundation

/// Runs injected device teardown exactly once so Quit and SIGTERM cannot
/// double-withdraw Bonjour, double-stop heartbeats, or double-revert defaults.
final class TerminationCoordinator {
    private let lock = NSLock()
    private var didRun = false
    var teardownAll: () -> Void

    init(teardownAll: @escaping () -> Void = {}) {
        self.teardownAll = teardownAll
    }

    var hasRun: Bool {
        lock.lock()
        defer { lock.unlock() }
        return didRun
    }

    func runOnce() {
        lock.lock()
        let shouldRun = !didRun
        if shouldRun { didRun = true }
        lock.unlock()
        if shouldRun {
            teardownAll()
        }
    }
}
