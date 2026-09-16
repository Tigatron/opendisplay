import Foundation

enum AdbLocator {
    static func locate(override: String?) -> String? {
        let candidates = candidatePaths(override: override)
        return candidates.first { FileManager.default.isExecutableFile(atPath: $0) }
    }

    static func candidatePaths(override: String?) -> [String] {
        var paths: [String] = []
        if let override, !override.trimmingCharacters(in: .whitespaces).isEmpty {
            paths.append((override as NSString).expandingTildeInPath)
        }
        let env = ProcessInfo.processInfo.environment
        if let path = env["PATH"] {
            for item in path.split(separator: ":") {
                paths.append((String(item) as NSString).appendingPathComponent("adb"))
            }
        }
        paths.append("/opt/homebrew/bin/adb")
        paths.append((NSHomeDirectory() as NSString).appendingPathComponent("Library/Android/sdk/platform-tools/adb"))
        if let androidHome = env["ANDROID_HOME"], !androidHome.isEmpty {
            paths.append((androidHome as NSString).appendingPathComponent("platform-tools/adb"))
        }
        var seen = Set<String>()
        return paths.filter { seen.insert($0).inserted }
    }
}
