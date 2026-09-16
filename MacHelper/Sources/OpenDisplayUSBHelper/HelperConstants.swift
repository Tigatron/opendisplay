import Foundation

enum HelperConstants {
    static let helperBundleId = "com.terrynamic.opendisplay.usbhelper"
    static let receiverPackage = "com.terrynamic.opendisplay"
    static let receiverActivity = "com.terrynamic.opendisplay/.MainActivity"
    static let heartbeatComponent = "com.terrynamic.opendisplay/.ipc.HelperReceiver"
    static let heartbeatAction = "com.terrynamic.opendisplay.USB_TUNNEL"
    static let bonjourType = "_opensidecar._tcp"
    static let openDisplayDefaultsDomain = "com.peetzweg.opensidecar.mac"
    static let preferredTunnelPort: UInt16 = 9000
    static let fallbackPortStart: UInt16 = 9010
    static let fallbackPortEnd: UInt16 = 9100
    static let adbTimeout: TimeInterval = 10
    static let probeTimeout: TimeInterval = 2
    static let heartbeatInterval: TimeInterval = 5
    static let heartbeatTTLMs = 15_000
    static let defaultProtocolVersion = 3
    static let logDirectoryName = "OpenDisplayUSBHelper"
    static let logFileName = "helper.log"
    static let logRotateBytes: UInt64 = 4 * 1024 * 1024
}
