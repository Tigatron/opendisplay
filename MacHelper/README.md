# OpenDisplay USB Helper

A macOS menu-bar helper that lets the **unmodified** stock OpenDisplay Mac app (`com.peetzweg.opensidecar.mac`) treat a USB-attached Android tablet as a Bonjour receiver.

The stock sender can browse `_opensidecar._tcp` and, once connected, probe `hello.addrs` on port 9000 with Wi-Fi/cellular prohibited. It cannot talk to Android over USB by itself. This helper:

1. Watches `adb track-devices -l` for authorized devices that have `build.terrynamic.opendisplay` installed.
2. Forwards `127.0.0.1:<P>` → device TCP 9000 (`P` is 9000 when free, otherwise 9010+).
3. Probes the tunnel for one length-prefixed `hello` frame.
4. Publishes a **loopback-only** Bonjour proxy (`lo0`) so the stock app discovers `127.0.0.1:<P>`.
5. Optionally heartbeats the receiver so a live Wi-Fi session can B2-upgrade onto the cable path.

The upstream `Mac/` tree is not modified.

## Requirements

- macOS 14+
- [Xcode](https://developer.apple.com/xcode/) 15+ (this repo is built with Xcode 27) and [XcodeGen](https://github.com/yonaskolb/XcodeGen) 2.x (`brew install xcodegen`)
- Android platform-tools: `brew install android-platform-tools`, or the SDK `platform-tools` directory
- Tablet: USB debugging on, this Mac authorized, receiver app installed
- Stock **OpenDisplay.app** (sender) from [opendisplay.app](https://opendisplay.app)

Never run `adb kill-server`. The helper reuses the existing adb daemon and only runs `adb start-server` if none is up, using the same `adb` binary it found.

## Build

```sh
cd MacHelper
./generate.sh
xcodebuild -project OpenDisplayUSBHelper.xcodeproj \
  -scheme OpenDisplayUSBHelper \
  -configuration Release \
  build
```

Or open `OpenDisplayUSBHelper.xcodeproj` in Xcode after `./generate.sh`. Signing is local (`CODE_SIGN_IDENTITY=-`); no developer team is required.

Unit tests (no adb, no network, no tablet):

```sh
xcodebuild -project OpenDisplayUSBHelper.xcodeproj \
  -scheme OpenDisplayUSBHelper \
  -configuration Debug \
  test
```

## How discovery works

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

## B2 heartbeat

When the tunnel owns **port 9000**, the helper broadcasts every 5 s:

```
adb -s SERIAL shell am broadcast \
  -n build.terrynamic.opendisplay/.ipc.HelperReceiver \
  -a build.terrynamic.opendisplay.USB_TUNNEL \
  --ei port 9000 --es helperVersion <version> --ei ttlMs 15000
```

The receiver then advertises `hello.addrs: ["127.0.0.1"]`. The stock sender's cable-upgrade probe (`probeForCablePath`) dials that address on port 9000 with Wi-Fi/cellular prohibited and migrates the live session onto the tunnel.

Disable this with **Send USB heartbeat (B2 auto-upgrade)** if you only want a fresh Bonjour connect.

## Auto-connect via OpenDisplay preferences

**Off by default.** When the 9000 tunnel comes up the helper runs:

```
defaults write com.peetzweg.opensidecar.mac host 127.0.0.1
defaults write com.peetzweg.opensidecar.mac port 9000
```

and deletes those keys when the tunnel goes down. The stock app reads `host`/`port` as an escape hatch and auto-dials `usb:first` over TCP. This mutates the running app's preferences — only enable it if you want an already-open OpenDisplay to pick up the tablet without a click.

## Settings

| Setting | Default | Purpose |
|---|---|---|
| adb path override | empty | Then `$PATH`, `/opt/homebrew/bin/adb`, `~/Library/Android/sdk/platform-tools/adb`, `$ANDROID_HOME/platform-tools/adb` |
| Launch receiver app on attach | on | `am start -n build.terrynamic.opendisplay/.MainActivity` |
| Send USB heartbeat (B2 auto-upgrade) | on | See above; only when `P == 9000` |
| Auto-connect running Mac app | off | Writes OpenDisplay `host`/`port` |
| Start at login | off | `SMAppService.mainApp` |

Logs: `~/Library/Logs/OpenDisplayUSBHelper/helper.log` (rotated at 4 MiB).

## Troubleshooting

**Multiple adb servers / version mismatch.** One helper, one `adb` binary. If `adb version` on the command line disagrees with the helper's Settings → Resolved path, set the override to the same binary (Homebrew vs. SDK copies often fight).

**Port 9000 in use.** Common occupants: a leftover `adb forward tcp:9000`, OpenDisplay Receiver on this Mac, or another `dns-sd -P` proxy. The helper binds 127.0.0.1:9000 first and falls back to 9010+ when the port is busy. B2 upgrade and the preferences escape hatch only apply to 9000.

**Bonjour name conflicts.** Auto-rename is on (`kDNSServiceFlagsNoAutoRename` is not set). mDNSResponder will publish `SM-X800 (USB) (2)` etc.; the menu shows the final name.

**Stock app hangs in Preparing.** Confirm the helper registered on `lo0` only. Browse:

```sh
dns-sd -B _opensidecar._tcp local
```

**Confirm the path the sender actually used** after connect:

```
grep "connection path" ~/Library/Logs/OpenDisplay/opendisplay.log
```

You want `connection path to <name>: lo0`.

**Device listed but no tunnel.** Unauthorized/offline devices are shown with their adb state. If the receiver APK is missing, the row says so instead of forwarding.

**Do not run two proxies at once.** A second helper, or a leftover `dns-sd -i lo0 -P` plus `adb forward tcp:9000`, will fight over the port and the service name.
