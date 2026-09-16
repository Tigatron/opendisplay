import XCTest

final class HelperConstantsTests: XCTestCase {
    func testReceiverAndHelperIdentities() {
        XCTAssertEqual(HelperConstants.helperBundleId, "com.terrynamic.opendisplay.usbhelper")
        XCTAssertEqual(HelperConstants.receiverPackage, "com.terrynamic.opendisplay")
        XCTAssertEqual(HelperConstants.receiverActivity, "com.terrynamic.opendisplay/.MainActivity")
        XCTAssertEqual(HelperConstants.heartbeatComponent, "com.terrynamic.opendisplay/.ipc.HelperReceiver")
        XCTAssertEqual(HelperConstants.heartbeatAction, "com.terrynamic.opendisplay.USB_TUNNEL")
    }
}
