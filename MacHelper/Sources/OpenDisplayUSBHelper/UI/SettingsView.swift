import SwiftUI

struct SettingsView: View {
    @ObservedObject var controller: HelperController
    @ObservedObject var settings: HelperSettings
    @State private var adbValidation = ""

    var body: some View {
        Form {
            Section("adb") {
                HStack {
                    TextField("adb path override", text: $settings.adbPathOverride)
                        .textFieldStyle(.roundedBorder)
                    Button("Validate") {
                        adbValidation = controller.validateAdb(settings.adbPathOverride)
                    }
                }
                Text("Leave empty to search $PATH, Homebrew, and the Android SDK.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if !adbValidation.isEmpty {
                    Text(adbValidation)
                        .font(.caption)
                        .textSelection(.enabled)
                }
                if let path = controller.snapshot.adbPath {
                    LabeledContent("Resolved") { Text(path).textSelection(.enabled) }
                }
            }

            Section("On attach") {
                Toggle("Launch receiver app on attach", isOn: $settings.launchReceiverOnAttach)
                Toggle("Send USB heartbeat (B2 auto-upgrade)", isOn: $settings.sendHeartbeat)
                Toggle("Auto-connect running Mac app (writes OpenDisplay preferences)", isOn: $settings.writeOpenDisplayDefaults)
                Text("Auto-connect writes host=127.0.0.1 and port=9000 into the stock OpenDisplay defaults domain (\(HelperConstants.openDisplayDefaultsDomain)) so an already-running sender dials the USB tunnel. It is deleted again when that tunnel goes down. Leave this off unless you want the running app to connect without clicking.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Start at login") {
                Toggle("Start at login", isOn: loginBinding)
                Text(HelperSettings.loginItemLabel(settings.loginItemStatus))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Diagnostics") {
                if controller.recentLog.isEmpty {
                    Text("No log lines yet.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(Array(controller.recentLog.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.system(.caption, design: .monospaced))
                            .textSelection(.enabled)
                    }
                }
                Button("Reveal Log") { controller.revealLog() }
            }
        }
        .formStyle(.grouped)
        .padding(8)
        .onAppear { settings.refreshLoginItemStatus() }
    }

    private var loginBinding: Binding<Bool> {
        Binding(
            get: { settings.loginItemStatus == .enabled },
            set: { enabled in
                do {
                    try settings.setStartAtLogin(enabled)
                } catch {
                    HelperLogger.shared.error("login item: \(error.localizedDescription)")
                    settings.refreshLoginItemStatus()
                }
            }
        )
    }
}
