# OpenDisplay for Android

Receiver for the stock OpenDisplay Mac sender (`pv` 3). Listens on TCP 9000, advertises `_opensidecar._tcp.`, and decodes the H.264 Annex-B stream described in [PROTOCOL.md](../PROTOCOL.md).

Package / `applicationId`: `build.terrynamic.opendisplay`. Default Bonjour name: `BUILD.TERRYNAMIC`.

## Build

Requires JDK 17 and an Android SDK with platform `android-36`.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
# Android/local.properties must contain: sdk.dir=/path/to/Android/sdk
cd Android
./gradlew :app:assembleDebug --no-daemon
./gradlew :app:testDebugUnitTest --no-daemon
```

`Android/local.properties` is gitignored.

## Pairing over Wi-Fi

1. Install and launch the app on the tablet. Grant notifications if asked (foreground service).
2. Confirm the idle screen shows **Listening on :9000** and a service name (`BUILD.TERRYNAMIC`, possibly suffixed by NSD).
3. On the Mac, open stock OpenDisplay 1.19.0 and pick this device. The sender dials port 9000 after Bonjour resolution.
4. Tablet logcat: `adb logcat -s OpenDisplay`.

## USB via the Mac helper

The helper (sibling `MacHelper/`, written in parallel) is responsible for:

- `adb forward tcp:9000 tcp:9000` so the Mac can dial `127.0.0.1:9000`
- a Bonjour proxy on loopback advertising this tablet
- a heartbeat broadcast that tells the receiver to put `127.0.0.1` in `hello.addrs`

Heartbeat (from the helper):

```bash
adb shell am broadcast \
  -n build.terrynamic.opendisplay/.ipc.HelperReceiver \
  -a build.terrynamic.opendisplay.USB_TUNNEL \
  --ei port 9000 \
  --es helperVersion 1.0 \
  --ei ttlMs 15000
```

While that heartbeat is fresh (`port == 9000` and within `ttlMs`, default 15 s), `hello.addrs` is `["127.0.0.1"]` so the stock sender can probe the cable path (PROTOCOL.md §6.4). When it expires, `addrs` is omitted.

Do not change the tablet USB mode (`svc usb setFunctions`). Stop any other app bound to :9000 before testing:

```bash
adb shell am force-stop com.peetzweg.opendisplay
adb shell am force-stop io.github.josepacelli.opendisplay
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Settings

| Setting | Meaning |
|---|---|
| Service name | Bonjour instance name (persisted). NSD may suffix it on collision. |
| Decode ceiling | Advertised as `hello.maxEncodeWide/High`. Auto / Panel / 1920x1080 / Custom / Off. Off omits the fields. Auto is the largest of the panel size and MediaCodec 60 fps performance points (API 29+; falls back to Panel). |
| Virtual desktop | `hello.pixelsWide/High` = physical pixels × 100/125/150%, rounded even. The ceiling keeps the encoded stream at or below what the panel/decoder can sustain. |
| Stats overlay | Local fps / mbps / queue text while streaming. |

## Protocol alignment

| Topic | This app |
|---|---|
| `pv` / `min` | 3 / 1 |
| Framing | `[u32 BE length][payload]`; inbound 1..16 MiB, outbound 1..2^20-1 |
| Demux | Isolated `Demux`: `len < 32768 && payload[0]=='{' && no 0x00` → JSON |
| Hello | Always `type,pixelsWide,pixelsHigh,scale,device,id,pv`. Optional `maxEncode*` and `addrs`. No `cursorPort` in this phase. |
| Newcomer | Immediate `hello`. Adopt if idle; otherwise park 3 s until ≥1 byte, then swap. |
| Liveness | Receiver `ping` every 2 s. Drop after 8 s without inbound bytes (`watchdog`). Never reply `pong` to the sender's `ping`. |
| Video | Annex-B, 4-byte start codes, SPS-derived size, async `MediaCodec` + `SurfaceView`. |
| Input | One-finger `touch` (normalized in the letterboxed video rect). Two-finger `scroll` in video pixels, natural sign. |
| USB class | Peer 127.0.0.1 / ::1 / ::ffff:127.0.0.1 → `usb`, else `wifi`. |

Unknown control `type` values are ignored (logged once per type).

## Logs

- Tablet: `adb logcat -s OpenDisplay`
- Stock Mac sender: `~/Library/Logs/OpenDisplay/opendisplay.log` (`phone hello`, `virtual display created`, `stream capped at`, `Extending to AndroidTablet`)
