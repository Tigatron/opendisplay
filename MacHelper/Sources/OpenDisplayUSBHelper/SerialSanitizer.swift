import Foundation

enum SerialSanitizer {
    /// Hostname labels allow `[A-Za-z0-9-]`. Everything else is dropped and
    /// the result is lowercased so `od-usb-<serial>.local.` is a legal name.
    static func sanitize(_ serial: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-"))
        let filtered = serial.unicodeScalars
            .filter { allowed.contains($0) }
            .map { Character($0) }
        var result = String(filtered).lowercased()
        if result.isEmpty {
            result = fallback(from: serial)
        }
        if result.count > 40 {
            result = String(result.prefix(40))
        }
        return result
    }

    static func hostname(for serial: String) -> String {
        "od-usb-\(sanitize(serial)).local."
    }

    static func txtID(for serial: String) -> String {
        "usb-\(serial)"
    }

    private static func fallback(from serial: String) -> String {
        var hash: UInt32 = 2_166_136_261
        for byte in serial.utf8 {
            hash = (hash ^ UInt32(byte)) &* 16_777_619
        }
        return String(format: "dev-%08x", hash)
    }
}
