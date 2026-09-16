import Foundation

enum LocalNetworkPermission: String, Equatable {
    case unknown
    case granted
    case denied

    /// Infer from the last dns_sd publish: success → granted, PolicyDenied → denied, else unknown.
    static func fromLastResult(succeeded: Bool, errorMessage: String?) -> LocalNetworkPermission {
        if succeeded { return .granted }
        if let errorMessage, errorMessage.contains("PolicyDenied") { return .denied }
        return .unknown
    }
}

enum PrivacySettings {
    static let localNetworkURL = URL(
        string: "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork"
    )!
}
