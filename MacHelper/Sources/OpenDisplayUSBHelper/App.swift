import AppKit
import SwiftUI

final class HelperAppDelegate: NSObject, NSApplicationDelegate {
    var prepareToQuit: (() -> Void)?

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        prepareToQuit?()
        return .terminateNow
    }
}

@main
struct OpenDisplayUSBHelperApp: App {
    @NSApplicationDelegateAdaptor(HelperAppDelegate.self) private var appDelegate
    @StateObject private var settings: HelperSettings
    @StateObject private var controller: HelperController

    init() {
        let settings = HelperSettings()
        let controller = HelperController(settings: settings)
        _settings = StateObject(wrappedValue: settings)
        _controller = StateObject(wrappedValue: controller)
        DispatchQueue.main.async {
            controller.installProcessTerminationHooks()
            (NSApp.delegate as? HelperAppDelegate)?.prepareToQuit = {
                controller.prepareToQuit()
            }
        }
    }

    var body: some Scene {
        MenuBarExtra {
            MenuBarView(controller: controller)
        } label: {
            Image(systemName: controller.menuBarSymbol)
        }
        Settings {
            SettingsView(controller: controller, settings: settings)
                .frame(minWidth: 480, minHeight: 420)
        }
    }
}
