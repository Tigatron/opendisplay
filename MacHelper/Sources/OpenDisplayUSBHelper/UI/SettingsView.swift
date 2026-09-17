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
                Toggle("Manual mode (host/port, no Bonjour)", isOn: $settings.writeOpenDisplayDefaults)
                Text("Writes host=127.0.0.1 and port=9000 into \(HelperConstants.openDisplayDefaultsDomain), clears usb:first from usbDisabled, and withdraws the lo0 Bonjour proxy so the stock app dials Manual. Enabling this on a live :9000 tunnel applies immediately; turning it off deletes the keys and republishes Bonjour.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Start at login") {
                Toggle("Start at login", isOn: loginBinding)
                Text(HelperSettings.loginItemLabel(settings.loginItemStatus))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if !InstallLocation.isRunningFromApplications {
                    Text(InstallLocation.loginItemHint)
                        .font(.caption)
                        .foregroundStyle(.orange)
                }
            }

            Section("Command line") {
                Text("UserDefaults domain: \(HelperSettings.defaultsDomain)")
                    .font(.caption)
                    .textSelection(.enabled)
                Text("defaults write \(HelperSettings.defaultsDomain) writeOpenDisplayDefaults -bool true")
                    .font(.system(.caption, design: .monospaced))
                    .textSelection(.enabled)
                Text("Keys: adbPathOverride, launchReceiverOnAttach, sendHeartbeat, writeOpenDisplayDefaults, startAtLogin. Changes are picked up while the helper is running.")
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
            get: { settings.startAtLogin },
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
