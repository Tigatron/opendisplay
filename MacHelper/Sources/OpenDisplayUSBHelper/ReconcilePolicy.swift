import Foundation

enum ReconcilePolicy {
    /// Periodic retry is only for a still-authorized tablet that failed to
    /// install-check or tunnel. A live `.ready` session must be left alone.
    static func shouldRetry(phase: DevicePhase?, busy: Bool, authorized: Bool) -> Bool {
        guard authorized, !busy else { return false }
        switch phase {
        case .receiverMissing, .failed:
            return true
        case .ready:
            return false
        default:
            return false
        }
    }
}
