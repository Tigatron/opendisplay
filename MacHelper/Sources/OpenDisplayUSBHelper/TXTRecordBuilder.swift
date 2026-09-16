import Foundation
import dnssd

enum TXTRecordBuilder {
    /// Build a DNS-SD TXT record with `TXTRecordCreate` / `TXTRecordSetValue`.
    /// Keys are set in a stable order: pv, id, optional model.
    static func make(pv: Int, id: String, model: String?) -> Data {
        var record = TXTRecordRef()
        TXTRecordCreate(&record, 0, nil)
        defer { TXTRecordDeallocate(&record) }
        set(&record, key: "pv", value: String(pv))
        set(&record, key: "id", value: id)
        if let model, !model.isEmpty {
            set(&record, key: "model", value: model)
        }
        let length = TXTRecordGetLength(&record)
        guard let bytes = TXTRecordGetBytesPtr(&record), length > 0 else {
            return Data()
        }
        return Data(bytes: bytes, count: Int(length))
    }

    /// Decode length-prefixed TXT pairs for tests and diagnostics.
    static func pairs(from data: Data) -> [(key: String, value: String)] {
        var pairs: [(String, String)] = []
        var index = data.startIndex
        while index < data.endIndex {
            let length = Int(data[index])
            index = data.index(after: index)
            guard length > 0, data.distance(from: index, to: data.endIndex) >= length else { break }
            let slice = data[index..<data.index(index, offsetBy: length)]
            index = data.index(index, offsetBy: length)
            guard let text = String(data: Data(slice), encoding: .utf8),
                  let eq = text.firstIndex(of: "=") else { continue }
            pairs.append((String(text[..<eq]), String(text[text.index(after: eq)...])))
        }
        return pairs
    }

    private static func set(_ record: inout TXTRecordRef, key: String, value: String) {
        value.withCString { pointer in
            _ = TXTRecordSetValue(&record, key, UInt8(value.utf8.count), pointer)
        }
    }
}
