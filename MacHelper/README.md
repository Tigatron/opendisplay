# OpenDisplay USB Helper

A macOS menu-bar helper that lets the **unmodified** stock OpenDisplay Mac app (`com.peetzweg.opensidecar.mac`) treat a USB-attached Android tablet as a Bonjour receiver.

The stock sender can browse `_opensidecar._tcp` and, once connected, probe `hello.addrs` on port 9000 with Wi-Fi/cellular prohibited. It cannot talk to Android over USB by itself. This helper:

1. Watches `adb track-devices -l` for authorized devices that have `com.terrynamic.opendisplay` installed.
2. Forwards `127.0.0.1:<P>` → device TCP 9000 (`P` is 9000 when free, otherwise 9010+).
3. Probes the tunnel for one length-prefixed `hello` frame.
4. Publishes a **loopback-only** Bonjour proxy (`lo0`) so the stock app discovers `127.0.0.1:<P>`.
5. Optionally heartbeats the receiver so a live Wi-Fi session can B2-upgrade onto the cable path.
6. Optionally writes the stock app's `host`/`port` defaults so it auto-dials the tunnel.

The upstream `Mac/` tree is not modified. Helper bundle id: `com.terrynamic.opendisplay.usbhelper`.

## Requirements

- macOS 14+
- [Xcode](https://developer.apple.com/xcode/) 15+ (this repo is built with Xcode 27) and [XcodeGen](https://github.com/yonaskolb/XcodeGen) 2.x (`brew install xcodegen`)
- Android platform-tools: `brew install android-platform-tools`, or the SDK `platform-tools` directory
- Tablet: USB debugging on, this Mac authorized, receiver app `com.terrynamic.opendisplay` installed
- Stock **OpenDisplay.app** (sender) from [opendisplay.app](https://opendisplay.app)

Never run `adb kill-server`. The helper reuses the existing adb daemon and only runs `adb start-server` if none is up, using the same `adb` binary it found.

## Build / release / install

Debug / iterate:

```sh
cd MacHelper
./generate.sh
xcodebuild -project OpenDisplayUSBHelper.xcodeproj \
  -scheme OpenDisplayUSBHelper \
  -configuration Debug \
  -derivedDataPath build \
  build
```

Or open `OpenDisplayUSBHelper.xcodeproj` in Xcode after `./generate.sh`. Signing is local (`CODE_SIGN_IDENTITY=-`); no developer team is required. `Info.plist` uses `$(PRODUCT_BUNDLE_IDENTIFIER)`.

Unit tests (no adb, no network, no tablet):

```sh
cd MacHelper
./generate.sh
xcodebuild -project OpenDisplayUSBHelper.xcodeproj \
  -scheme OpenDisplayUSBHelper \
  -configuration Debug \
  -derivedDataPath build \
  test
```

Release package (ad-hoc signed, no team) then install into `/Applications`. `SMAppService` Start at login only works from `/Applications` or another stable path — Settings shows a hint when the helper is not running from there.

```sh
cd MacHelper
./build-release.sh          # xcodegen → Release build → MacHelper/dist/OpenDisplay USB Helper.app
./install.sh                # asks before overwrite, ditto → /Applications, launches the app
```

`MacHelper/dist/` is gitignored.

## Permissions

**Local Network.** The first Bonjour browse/publish shows the system dialog. Allow **OpenDisplay USB Helper**. The menu bar shows the inferred state from the last dns_sd result: `granted` after a successful publish, `denied` after `PolicyDenied`, `unknown` otherwise. **Open Privacy Settings** opens `x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork`.

macOS keys that permission to the **bundle id**. After renaming from `build.terrynamic.opendisplay.usbhelper` to `com.terrynamic.opendisplay.usbhelper`, System Settings treats the helper as a new app — allow Local Network again.

**Start at login.** Enable in Settings after installing under `/Applications`. Status `.notFound` means `SMAppService` cannot see a stable login-item path.

## Connection modes

Three independent ways the stock sender can reach the USB tunnel. B1 is always on once a device is ready; B2 and defaults are toggles.

### B1 — Bonjour proxy (always)

Loopback-only DNS-SD so the stock app's browser finds the tablet as a normal receiver.

```
tablet :9000  ←  adb forward tcp:P tcp:9000  ←  127.0.0.1:P
                                                  ↑
stock OpenDisplay  ←  NWBrowser(_opensidecar._tcp)
                                                  ↑
              DNS-SD proxy on lo0 only
              name:  "<model> (USB)"
              host:  od-usb-<sanitized-serial>.local. → A 127.0.0.1
              TXT:   pv=<hello.pv or 3>  id=usb-<serial>  model=<model>
```

The proxy is registered with the dns_sd C API (`DNSServiceCreateConnection` + `DNSServiceRegisterRecord` + `DNSServiceRegister`) on `if_nametoindex("lo0")`.

- Registering on **all interfaces** makes the stock app's automatic dial hang in `.preparing`.
- `kDNSServiceInterfaceIndexLocalOnly` is **invisible** to `NWBrowser`.
- `id` is deliberately `usb-<serial>`, not the receiver's install UUID. Withdrawing the record then makes the stock app end the session promptly (`endSessionsWhoseServiceVanished`).

Equivalent proven CLI (do not use this in production; the app calls the API):

```sh
dns-sd -i lo0 -P "SM-X800 (USB)" _opensidecar._tcp local 9000 tab-usb.local 127.0.0.1 id=usb-SERIAL pv=3
```

Works on any assigned `P`. Pick the USB device in the stock app's receiver list.

### B2 — heartbeat auto-upgrade (default on, port 9000 only)

When the tunnel owns **port 9000**, the helper broadcasts every 5 s:

```
adb -s SERIAL shell am broadcast \
  -n com.terrynamic.opendisplay/.ipc.HelperReceiver \
  -a com.terrynamic.opendisplay.USB_TUNNEL \
  --ei port 9000 --es helperVersion <version> --ei ttlMs 15000
```

The receiver then advertises `hello.addrs: ["127.0.0.1"]`. The stock sender's cable-upgrade probe (`probeForCablePath`) dials that address on port 9000 with Wi-Fi/cellular prohibited and migrates the live session onto the tunnel.

Disable with **Send USB heartbeat (B2 auto-upgrade)** (`sendHeartbeat`) if you only want a fresh Bonjour connect. Enabling the toggle while a :9000 tunnel is already up starts heartbeats immediately.

### Optional defaults auto-connect (default off, port 9000 only)

**Off by default.** Writes the stock app's escape-hatch keys:

```
defaults write com.peetzweg.opensidecar.mac host 127.0.0.1
defaults write com.peetzweg.opensidecar.mac port 9000
```

and deletes them when the 9000 tunnel goes down, the toggle is turned off, or the helper quits. The stock app reads `host`/`port` at launch (and as a running-app escape hatch) and auto-dials `usb:first` over TCP.

Writing while OpenDisplay is **not running** is intended — keep the behavior simple; the stock app picks the keys up on next launch. This mutates another app's preferences. Enabling `writeOpenDisplayDefaults` while a :9000 tunnel is already up writes immediately.

## Settings

| UI label | Key | Type | Default | Purpose |
|---|---|---|---|---|
| adb path override | `adbPathOverride` | string | empty | Then `$PATH`, `/opt/homebrew/bin/adb`, `~/Library/Android/sdk/platform-tools/adb`, `$ANDROID_HOME/platform-tools/adb` |
| Launch receiver app on attach | `launchReceiverOnAttach` | bool | true | `am start -n com.terrynamic.opendisplay/.MainActivity` |
| Send USB heartbeat (B2 auto-upgrade) | `sendHeartbeat` | bool | true | B2; only when `P == 9000` |
| Auto-connect running Mac app | `writeOpenDisplayDefaults` | bool | false | Writes OpenDisplay `host`/`port` even if that app is not running |
| Start at login | `startAtLogin` | bool | false | `SMAppService.mainApp` (only from `/Applications`) |

UserDefaults domain is the helper bundle id: **`com.terrynamic.opendisplay.usbhelper`**. The engine observes `UserDefaults.didChangeNotification` and periodically reloads, so `defaults write` applies to a live helper.

```sh
defaults write com.terrynamic.opendisplay.usbhelper adbPathOverride -string "/opt/homebrew/bin/adb"
defaults write com.terrynamic.opendisplay.usbhelper launchReceiverOnAttach -bool true
defaults write com.terrynamic.opendisplay.usbhelper sendHeartbeat -bool true
defaults write com.terrynamic.opendisplay.usbhelper writeOpenDisplayDefaults -bool true
defaults write com.terrynamic.opendisplay.usbhelper startAtLogin -bool false
```

Logs: `~/Library/Logs/OpenDisplayUSBHelper/helper.log` (rotated at 4 MiB). Quit / SIGTERM tears down every device (withdraw Bonjour, stop heartbeats, revert OpenDisplay defaults if written) before exit.

## Troubleshooting

**adb 36 `track-devices` framing.** Modern adb (1.0.41 / 36.0.0) writes the smart-socket stream to stdout: each snapshot is `[4 ASCII hex digits][payload of that length]`, with no blank line between snapshots (`0000` is an empty list). The helper's `TrackDevicesParser` prefers this framed mode. If the menu never lists a plugged-in tablet, confirm `adb track-devices -l` output and the helper log line `track-devices started` (then `device <serial> device model=…`). A reconnect looks like `track-devices exited (<code>) — reconnecting`.

**Multiple adb servers / version mismatch.** One helper, one `adb` binary. If `adb version` on the command line disagrees with Settings → Resolved path, set `adbPathOverride` to the same binary (Homebrew vs. SDK copies often fight). Never `adb kill-server` while the helper or another client is using the daemon.

**Port 9000 busy.** Common occupants: a leftover `adb forward tcp:9000`, OpenDisplay Receiver on this Mac, or another `dns-sd -P` proxy. The helper binds 127.0.0.1:9000 first and falls back to 9010+ when the port is busy. B2 upgrade and the preferences escape hatch only apply to 9000. The helper reuses an existing same-serial `tcp:P → tcp:9000` instead of `--no-rebind` failing.

**Bonjour name conflicts.** Auto-rename is on (`kDNSServiceFlagsNoAutoRename` is not set). mDNSResponder will publish `SM-X800 (USB) (2)` etc.; the menu shows the final name.

**Local Network denied.** Menu shows `Local Network: denied`. Allow the helper (new bundle id) under Privacy & Security → Local Network, or use **Open Privacy Settings**. A `PolicyDenied` dns_sd error includes that hint.

**Stock app hangs in Preparing.** Confirm the helper registered on `lo0` only. Browse:

```sh
dns-sd -B _opensidecar._tcp local
```

**Confirm the path the sender actually used** after connect:

```
grep "connection path" ~/Library/Logs/OpenDisplay/opendisplay.log
```

You want `connection path to <name>: lo0`.

**Device listed but no tunnel.** Unauthorized/offline devices are shown with their adb state. If the receiver APK is missing, the row says `Install com.terrynamic.opendisplay to tunnel` instead of forwarding.

**Do not run two proxies at once.** A second helper, or a leftover `dns-sd -i lo0 -P` plus `adb forward tcp:9000`, will fight over the port and the service name.

## E2E checklist

Do not run two helpers. Install the receiver as `com.terrynamic.opendisplay`, authorize this Mac, then:

1. `./build-release.sh` && `./install.sh` (or run the Debug app from Xcode). Allow **Local Network** if prompted (required again after the bundle id rename).
2. Confirm Settings → Resolved is the same `adb` you use in the terminal. Menu: `Local Network: granted` after the first successful publish.
3. Plug the tablet. Receiver should launch if `launchReceiverOnAttach` is on.
4. In `~/Library/Logs/OpenDisplayUSBHelper/helper.log` expect lines like:

```
INFO helper 1.0.0 starting
INFO using /opt/homebrew/bin/adb — Android Debug Bridge version 1.0.41 · Version 36.0.0-…
INFO track-devices started
INFO device <SERIAL> device model=<MODEL>
INFO <SERIAL> phase=ready port=9000 name=<MODEL> (USB)
```

If 9000 was already forwarded for that serial: `INFO reusing existing forward <SERIAL> tcp:9000 tcp:9000` before `phase=ready`. If 9000 is busy for another process, `port=9010` (or higher) and B2 / defaults stay off.

5. **B1:** stock OpenDisplay lists `<MODEL> (USB)`. Connect. Sender log: `connection path to <MODEL> (USB): lo0`.
6. **B2:** start a Wi-Fi session first, then attach USB with `sendHeartbeat` on and `port=9000`. Session should migrate onto the tunnel without a new click.
7. **Defaults auto-connect (optional):** `defaults write com.terrynamic.opendisplay.usbhelper writeOpenDisplayDefaults -bool true`. Helper writes `com.peetzweg.opensidecar.mac` `host`/`port` even if OpenDisplay is not running. Launch or focus the stock app and it should dial `127.0.0.1:9000`.
8. Quit the helper (menu **Quit** or SIGTERM). Log: `INFO helper stopping — tearing down N device(s)`. OpenDisplay `host`/`port` keys are deleted if this helper wrote them. The USB Bonjour name disappears.
