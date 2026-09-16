import Foundation

final class HelperLogger: @unchecked Sendable {
    static let shared = HelperLogger()

    private let queue = DispatchQueue(label: "build.terrynamic.opendisplay.usbhelper.log")
    private let maxBytes: UInt64
    private let directory: URL
    private let fileURL: URL
    private let rotatedURL: URL
    private var handle: FileHandle?
    private var recent: [String] = []
    private let recentCap = 40
    private let formatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter
    }()

    var logFileURL: URL { fileURL }

    init(
        directory: URL? = nil,
        maxBytes: UInt64 = HelperConstants.logRotateBytes
    ) {
        let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Library")
        let dir = directory ?? library
            .appendingPathComponent("Logs/\(HelperConstants.logDirectoryName)", isDirectory: true)
        self.directory = dir
        self.fileURL = dir.appendingPathComponent(HelperConstants.logFileName)
        self.rotatedURL = dir.appendingPathComponent("helper-previous.log")
        self.maxBytes = maxBytes
    }

    func info(_ message: String) {
        write("INFO", message)
    }

    func error(_ message: String) {
        write("ERROR", message)
    }

    func recentLines(_ count: Int = 20) -> [String] {
        queue.sync {
            Array(recent.suffix(count))
        }
    }

    private func write(_ level: String, _ message: String) {
        let line = "[\(formatter.string(from: Date()))] \(level) \(message)"
        print(line)
        queue.async { [weak self] in
            guard let self else { return }
            self.recent.append(line)
            if self.recent.count > self.recentCap {
                self.recent.removeFirst(self.recent.count - self.recentCap)
            }
            self.appendToFile(line + "\n")
        }
    }

    private func appendToFile(_ line: String) {
        guard let data = line.data(using: .utf8) else { return }
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            if handle == nil {
                if !FileManager.default.fileExists(atPath: fileURL.path) {
                    FileManager.default.createFile(atPath: fileURL.path, contents: nil)
                }
                handle = try FileHandle(forWritingTo: fileURL)
            }
            guard let handle else { return }
            let size = try handle.seekToEnd()
            if size + UInt64(data.count) > maxBytes {
                try handle.close()
                self.handle = nil
                if FileManager.default.fileExists(atPath: rotatedURL.path) {
                    try FileManager.default.removeItem(at: rotatedURL)
                }
                try FileManager.default.moveItem(at: fileURL, to: rotatedURL)
                FileManager.default.createFile(atPath: fileURL.path, contents: nil)
                self.handle = try FileHandle(forWritingTo: fileURL)
            }
            try self.handle?.seekToEnd()
            try self.handle?.write(contentsOf: data)
        } catch {
            print("helper log write failed: \(error.localizedDescription)")
        }
    }
}
