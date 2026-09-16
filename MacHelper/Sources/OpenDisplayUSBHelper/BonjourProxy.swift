import Darwin
import Foundation
import dnssd

/// Loopback-only DNS-SD proxy. Registering on every interface makes the stock
/// OpenDisplay Mac app hang in `.preparing`; `kDNSServiceInterfaceIndexLocalOnly`
/// is invisible to `NWBrowser`. `if_nametoindex("lo0")` is the combination
/// that both advertises and stays reachable.
final class BonjourProxy {
    struct Request: Equatable {
        var name: String
        var hostname: String
        var port: UInt16
        var txt: Data
    }

    private var connection: DNSServiceRef?
    private var service: DNSServiceRef?
    private var record: DNSRecordRef?
    private let queue: DispatchQueue
    private var onName: ((String) -> Void)?
    private var onError: ((String) -> Void)?
    private(set) var resolvedName: String?

    init(queue: DispatchQueue) {
        self.queue = queue
    }

    deinit {
        withdraw()
    }

    func publish(
        _ request: Request,
        onName: @escaping (String) -> Void,
        onError: @escaping (String) -> Void
    ) {
        withdraw()
        self.onName = onName
        self.onError = onError

        let interface = if_nametoindex("lo0")
        guard interface != 0 else {
            onError("lo0 is missing")
            return
        }

        var connection: DNSServiceRef?
        var error = DNSServiceCreateConnection(&connection)
        guard error == kDNSServiceErr_NoError, let connection else {
            onError("DNSServiceCreateConnection failed (\(error))")
            return
        }
        self.connection = connection

        var ipv4 = in_addr()
        _ = "127.0.0.1".withCString { inet_aton($0, &ipv4) }
        var record: DNSRecordRef?
        error = request.hostname.withCString { host in
            withUnsafePointer(to: &ipv4) { addr in
                addr.withMemoryRebound(to: UInt8.self, capacity: 4) { bytes in
                    DNSServiceRegisterRecord(
                        connection,
                        &record,
                        DNSServiceFlags(kDNSServiceFlagsUnique),
                        interface,
                        host,
                        UInt16(kDNSServiceType_A),
                        UInt16(kDNSServiceClass_IN),
                        4,
                        bytes,
                        240,
                        BonjourProxy.recordCallback,
                        Unmanaged.passUnretained(self).toOpaque()
                    )
                }
            }
        }
        if error != kDNSServiceErr_NoError {
            withdraw()
            onError("DNSServiceRegisterRecord failed (\(error))")
            return
        }
        self.record = record

        error = DNSServiceSetDispatchQueue(connection, queue)
        if error != kDNSServiceErr_NoError {
            withdraw()
            onError("DNSServiceSetDispatchQueue (A) failed (\(error))")
            return
        }

        var service: DNSServiceRef?
        let txtCopy = request.txt
        error = request.name.withCString { name in
            HelperConstants.bonjourType.withCString { type in
                request.hostname.withCString { host in
                    txtCopy.withUnsafeBytes { raw in
                        DNSServiceRegister(
                            &service,
                            0,
                            interface,
                            name,
                            type,
                            nil,
                            host,
                            request.port.bigEndian,
                            UInt16(txtCopy.count),
                            raw.baseAddress,
                            BonjourProxy.registerCallback,
                            Unmanaged.passUnretained(self).toOpaque()
                        )
                    }
                }
            }
        }
        if error != kDNSServiceErr_NoError || service == nil {
            withdraw()
            onError("DNSServiceRegister failed (\(error))")
            return
        }
        self.service = service
        error = DNSServiceSetDispatchQueue(service, queue)
        if error != kDNSServiceErr_NoError {
            withdraw()
            onError("DNSServiceSetDispatchQueue (SRV) failed (\(error))")
        }
    }

    func withdraw() {
        if let service {
            DNSServiceRefDeallocate(service)
            self.service = nil
        }
        if let connection, let record {
            _ = DNSServiceRemoveRecord(connection, record, 0)
            self.record = nil
        }
        if let connection {
            DNSServiceRefDeallocate(connection)
            self.connection = nil
        }
        record = nil
        resolvedName = nil
        onName = nil
        onError = nil
    }

    private static let recordCallback: DNSServiceRegisterRecordReply = { _, _, _, error, context in
        guard let context else { return }
        let proxy = Unmanaged<BonjourProxy>.fromOpaque(context).takeUnretainedValue()
        if error != kDNSServiceErr_NoError {
            proxy.onError?("A-record registration failed (\(error))")
        }
    }

    private static let registerCallback: DNSServiceRegisterReply = { _, _, error, name, _, _, context in
        guard let context else { return }
        let proxy = Unmanaged<BonjourProxy>.fromOpaque(context).takeUnretainedValue()
        if error != kDNSServiceErr_NoError {
            proxy.onError?("service registration failed (\(error))")
            return
        }
        if let name {
            let resolved = String(cString: name)
            proxy.resolvedName = resolved
            proxy.onName?(resolved)
        }
    }
}

protocol BonjourPublishing: AnyObject {
    func publish(
        _ request: BonjourProxy.Request,
        onName: @escaping (String) -> Void,
        onError: @escaping (String) -> Void
    )
    func withdraw()
}

extension BonjourProxy: BonjourPublishing {}
