import Foundation
import dnssd

enum DNSServiceError {
    static let localNetworkHint =
        "System Settings → Privacy & Security → Local Network: allow OpenDisplay USB Helper"

    static func name(_ code: DNSServiceErrorType) -> String {
        switch Int(code) {
        case Int(kDNSServiceErr_NoError): return "NoError"
        case Int(kDNSServiceErr_Unknown): return "Unknown"
        case Int(kDNSServiceErr_NoSuchName): return "NoSuchName"
        case Int(kDNSServiceErr_NoMemory): return "NoMemory"
        case Int(kDNSServiceErr_BadParam): return "BadParam"
        case Int(kDNSServiceErr_BadReference): return "BadReference"
        case Int(kDNSServiceErr_BadState): return "BadState"
        case Int(kDNSServiceErr_BadFlags): return "BadFlags"
        case Int(kDNSServiceErr_Unsupported): return "Unsupported"
        case Int(kDNSServiceErr_NotInitialized): return "NotInitialized"
        case Int(kDNSServiceErr_AlreadyRegistered): return "AlreadyRegistered"
        case Int(kDNSServiceErr_NameConflict): return "NameConflict"
        case Int(kDNSServiceErr_Invalid): return "Invalid"
        case Int(kDNSServiceErr_Firewall): return "Firewall"
        case Int(kDNSServiceErr_Incompatible): return "Incompatible"
        case Int(kDNSServiceErr_BadInterfaceIndex): return "BadInterfaceIndex"
        case Int(kDNSServiceErr_Refused): return "Refused"
        case Int(kDNSServiceErr_NoSuchRecord): return "NoSuchRecord"
        case Int(kDNSServiceErr_NoAuth): return "NoAuth"
        case Int(kDNSServiceErr_ServiceNotRunning): return "ServiceNotRunning"
        case Int(kDNSServiceErr_Timeout): return "Timeout"
        case Int(kDNSServiceErr_DefunctConnection): return "DefunctConnection"
        case Int(kDNSServiceErr_PolicyDenied): return "PolicyDenied"
        default: return "code \(code)"
        }
    }

    static func describe(_ code: DNSServiceErrorType, operation: String) -> String {
        let named = "\(operation) failed (\(name(code)) \(code))"
        if Int(code) == Int(kDNSServiceErr_PolicyDenied) {
            return "\(named). \(localNetworkHint)"
        }
        return named
    }
}
