import SwiftUI

@main
struct OpenDisplayUSBHelperApp: App {
    @StateObject private var settings: HelperSettings
    @StateObject private var controller: HelperController

    init() {
        let settings = HelperSettings()
        _settings = StateObject(wrappedValue: settings)
        _controller = StateObject(wrappedValue: HelperController(settings: settings))
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
