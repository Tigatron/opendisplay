import Foundation

enum InstallLocation {
    static func isApplicationsPath(_ path: String) -> Bool {
        let standardized = URL(fileURLWithPath: path).standardizedFileURL.path
        return standardized == "/Applications" || standardized.hasPrefix("/Applications/")
    }

    static var isRunningFromApplications: Bool {
        isApplicationsPath(Bundle.main.bundlePath)
    }

    static let loginItemHint =
        "SMAppService login items only work from /Applications (or another stable path). This copy is not running from /Applications — use MacHelper/install.sh, then enable Start at login."
}
