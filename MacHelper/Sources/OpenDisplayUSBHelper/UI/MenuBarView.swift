import SwiftUI

struct MenuBarView: View {
    @ObservedObject var controller: HelperController

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(controller.snapshot.statusText)
                .font(.headline)
            if let path = controller.snapshot.adbPath {
                Text(path)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            if let error = controller.snapshot.lastError {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        }
        .padding(.horizontal, 4)

        Divider()

        if controller.snapshot.devices.isEmpty {
            Text("No Android devices")
                .foregroundStyle(.secondary)
        } else {
            ForEach(controller.snapshot.devices) { device in
                DeviceMenuRow(device: device)
            }
        }

        Divider()

        Button("Open OpenDisplay") { controller.openOpenDisplay() }
        Button("Reveal Log") { controller.revealLog() }
        Button("Settings…") { controller.openSettings() }
        Divider()
        Button("Quit") { controller.quit() }
    }
}

private struct DeviceMenuRow: View {
    let device: DeviceRowState

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(device.model ?? "Android")
                .font(.body.weight(.medium))
            Text("\(device.serial) · \(device.phase.rawValue)\(device.tunnelPort.map { " · :\($0)" } ?? "")")
                .font(.caption)
                .foregroundStyle(.secondary)
            if let name = device.bonjourName {
                Text("\(name) · heartbeat \(device.heartbeatOn ? "on" : "off")")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else if device.phase == .receiverMissing {
                Text("Install \(HelperConstants.receiverPackage) to tunnel")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
            if let error = device.lastError {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .lineLimit(4)
            }
        }
        .padding(.vertical, 2)
    }
}
