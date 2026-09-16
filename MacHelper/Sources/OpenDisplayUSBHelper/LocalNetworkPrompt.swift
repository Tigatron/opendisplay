import Foundation
import Network

/// Starts an `NWBrowser` for `_opensidecar._tcp` so macOS shows the Local
/// Network prompt as soon as the helper launches (the first real
/// `DNSServiceRegister` otherwise fails with PolicyDenied and only prompts
/// after that error). `onReady` fires when the browse is allowed so reconcile
/// can republish without restarting the helper.
final class LocalNetworkPrompt {
    private var browser: NWBrowser?

    func start(queue: DispatchQueue, onReady: @escaping () -> Void) {
        stop()
        let browser = NWBrowser(for: .bonjour(type: HelperConstants.bonjourType, domain: nil), using: .tcp)
        browser.stateUpdateHandler = { state in
            if case .ready = state {
                onReady()
            }
        }
        browser.start(queue: queue)
        self.browser = browser
    }

    func stop() {
        browser?.cancel()
        browser = nil
    }
}
