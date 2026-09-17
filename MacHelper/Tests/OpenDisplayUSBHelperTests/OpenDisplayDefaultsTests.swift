import XCTest

final class OpenDisplayDefaultsTests: XCTestCase {
    func testWriteCallsLogHookWithExpectedMessage() {
        var lines: [String] = []
        OpenDisplayDefaults.writeTunnel(
            trigger: .tunnelReady,
            performWrites: false,
            log: { lines.append($0) }
        )
        XCTAssertEqual(lines, [
            "wrote OpenDisplay defaults host=127.0.0.1 port=9000 (domain com.peetzweg.opensidecar.mac) trigger=tunnel ready",
            "removed usb:first from usbDisabled (domain com.peetzweg.opensidecar.mac) trigger=tunnel ready"
        ])

        lines.removeAll()
        OpenDisplayDefaults.writeTunnel(
            trigger: .settingEnabled,
            performWrites: false,
            log: { lines.append($0) }
        )
        XCTAssertEqual(lines, [
            OpenDisplayDefaults.wroteLine(trigger: .settingEnabled),
            OpenDisplayDefaults.usbDisabledRemovedLine(trigger: .settingEnabled)
        ])
        XCTAssertTrue(lines[0].contains("trigger=setting enabled"))
        XCTAssertTrue(lines[1].contains("usb:first"))
    }

    func testWritePathEmitsHostPortAndUsbDisabledCleanup() {
        var written: [String]?
        var lines: [String] = []
        OpenDisplayDefaults.writeTunnel(
            trigger: .settingEnabled,
            performWrites: false,
            usbDisabledEntries: ["usb:other", "usb:first"],
            writeUsbDisabled: { written = $0 },
            log: { lines.append($0) }
        )
        XCTAssertEqual(lines, [
            OpenDisplayDefaults.wroteLine(trigger: .settingEnabled),
            OpenDisplayDefaults.usbDisabledRemovedLine(trigger: .settingEnabled)
        ])
        XCTAssertNil(written, "performWrites=false must not invoke the writer")
    }

    func testDeleteCallsLogHookWithTrigger() {
        let cases: [OpenDisplayDefaultsTrigger] = [.tunnelDown, .settingDisabled, .quit, .reconcile]
        for trigger in cases {
            var lines: [String] = []
            OpenDisplayDefaults.revertTunnel(
                trigger: trigger,
                performDeletes: false,
                log: { lines.append($0) }
            )
            XCTAssertEqual(lines, [
                "deleted OpenDisplay defaults host/port (domain com.peetzweg.opensidecar.mac) trigger=\(trigger.rawValue)"
            ])
        }
    }
}

final class UsbDisabledFilterTests: XCTestCase {
    func testRemovesOnlyUsbFirstAmongOthers() {
        XCTAssertEqual(
            UsbDisabledFilter.removingFirst(["usb:abcd", "usb:first", "usb:efgh"]),
            ["usb:abcd", "usb:efgh"]
        )
    }

    func testAbsentIsNoOp() {
        XCTAssertNil(UsbDisabledFilter.removingFirst(["usb:abcd"]))
        XCTAssertNil(UsbDisabledFilter.removingFirst([]))
    }

    func testOnlyUsbFirstBecomesEmptyArray() {
        XCTAssertEqual(UsbDisabledFilter.removingFirst(["usb:first"]), [])
    }

    func testClearUsbFirstNoOpWhenAbsent() {
        var written: [String]?
        var lines: [String] = []
        OpenDisplayDefaults.clearUsbFirst(
            trigger: .settingEnabled,
            performWrites: true,
            entries: ["usb:abcd"],
            writeUsbDisabled: { written = $0 },
            log: { lines.append($0) }
        )
        XCTAssertNil(written)
        XCTAssertTrue(lines.isEmpty)
    }

    func testClearUsbFirstWritesEmptyArray() {
        var written: [String]?
        OpenDisplayDefaults.clearUsbFirst(
            trigger: .tunnelReady,
            performWrites: true,
            entries: ["usb:first"],
            writeUsbDisabled: { written = $0 },
            log: { _ in }
        )
        XCTAssertEqual(written, [])
    }

    func testClearUsbFirstKeepsOtherEntries() {
        var written: [String]?
        OpenDisplayDefaults.clearUsbFirst(
            trigger: .settingEnabled,
            performWrites: true,
            entries: ["usb:abcd", "usb:first"],
            writeUsbDisabled: { written = $0 },
            log: { _ in }
        )
        XCTAssertEqual(written, ["usb:abcd"])
    }
}

final class PlistStringArrayTests: XCTestCase {
    func testParsesDefaultsReadArray() {
        let raw = """
        (
            "usb:first",
            "usb:R52T304Z7VD"
        )
        """
        XCTAssertEqual(PlistStringArray.parse(raw), ["usb:first", "usb:R52T304Z7VD"])
    }

    func testParsesEmptyArray() {
        XCTAssertEqual(PlistStringArray.parse("(\n)"), [])
    }
}

final class SettingsChangeLogTests: XCTestCase {
    func testLogsEachChangedKey() {
        let old = SettingsSnapshot(
            adbPathOverride: "",
            launchReceiverOnAttach: true,
            sendHeartbeat: true,
            writeOpenDisplayDefaults: false
        )
        var new = old
        new.writeOpenDisplayDefaults = true
        XCTAssertEqual(
            SettingsChangeLog.lines(from: old, to: new),
            ["settings changed: writeOpenDisplayDefaults=true"]
        )

        new.sendHeartbeat = false
        XCTAssertEqual(SettingsChangeLog.lines(from: old, to: new), [
            "settings changed: sendHeartbeat=false",
            "settings changed: writeOpenDisplayDefaults=true"
        ])
    }

    func testUnchangedSnapshotIsEmpty() {
        let snap = SettingsSnapshot(
            adbPathOverride: "/opt/homebrew/bin/adb",
            launchReceiverOnAttach: false,
            sendHeartbeat: false,
            writeOpenDisplayDefaults: true
        )
        XCTAssertTrue(SettingsChangeLog.lines(from: snap, to: snap).isEmpty)
    }
}

final class StaleDefaultsPolicyTests: XCTestCase {
    func testOffLeavesUserKeysAndHints() {
        XCTAssertEqual(
            StaleDefaultsPolicy.action(
                writeOpenDisplayDefaults: false,
                keysPresent: true,
                hasReady9000: false
            ),
            .ignoreWithHint
        )
        XCTAssertTrue(StaleDefaultsPolicy.hint.contains("writeOpenDisplayDefaults=off"))
    }

    func testOnDeletesWhenNo9000Tunnel() {
        XCTAssertEqual(
            StaleDefaultsPolicy.action(
                writeOpenDisplayDefaults: true,
                keysPresent: true,
                hasReady9000: false
            ),
            .delete
        )
    }

    func testOnKeepsKeysWhile9000Ready() {
        XCTAssertEqual(
            StaleDefaultsPolicy.action(
                writeOpenDisplayDefaults: true,
                keysPresent: true,
                hasReady9000: true
            ),
            .none
        )
    }

    func testAbsentKeysAreNone() {
        XCTAssertEqual(
            StaleDefaultsPolicy.action(
                writeOpenDisplayDefaults: true,
                keysPresent: false,
                hasReady9000: false
            ),
            .none
        )
    }

    func testHasTunnelKeysUsesInjectedReader() {
        XCTAssertTrue(OpenDisplayDefaults.hasTunnelKeys { key in
            key == "host" ? "127.0.0.1" : nil
        })
        XCTAssertFalse(OpenDisplayDefaults.hasTunnelKeys { _ in nil })
    }
}
