# OpenDisplay for Android

Receiver for the stock OpenDisplay Mac sender (`pv` 3). Listens on TCP 9000, advertises `_opensidecar._tcp.`, and decodes the H.264 Annex-B stream described in [PROTOCOL.md](../PROTOCOL.md).

Handoff (what is done, what is in flight, what is next): [HANDOFF.md](../HANDOFF.md).

Package / `applicationId`: `com.terrynamic.opendisplay`. Default Bonjour name: `BUILD.TERRYNAMIC`.

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
  -n com.terrynamic.opendisplay/.ipc.HelperReceiver \
  -a com.terrynamic.opendisplay.USB_TUNNEL \
  --ei port 9000 \
  --es helperVersion 1.0 \
  --ei ttlMs 15000
```

While that heartbeat is fresh (`port == 9000` and within `ttlMs`, default 15 s), `hello.addrs` is `["127.0.0.1"]` so the stock sender can probe the cable path (PROTOCOL.md §6.4). When it expires, `addrs` is omitted.

The stock Mac app remembers **both** Bonjour entries (`SM-X800 (USB)` via the helper proxy and `BUILD.TERRYNAMIC` on Wi-Fi) and will auto-dial both. The receiver therefore prefers USB: while the live session's peer is loopback it rebinds TCP 9000 from `0.0.0.0` to `127.0.0.1` so Wi-Fi dials get `ECONNREFUSED` and the Mac drops that session after three refusals. The loopback listener stays up for helper probes and cable upgrades. When the USB session ends (EOF/RST/watchdog) — not when the 15 s heartbeat expires — it immediately rebinds `0.0.0.0:9000`. The stock app redials Wi-Fi within ~1–2 s **only if the session started on `BUILD.TERRYNAMIC`** (then migrated). A session that started on `SM-X800 (USB)` waits for that proxy name and does not switch mid-session. NSD stays registered the whole time. A Wi-Fi newcomer that already passed `accept` before the swap still follows the normal 3 s parking rule.

Do not change the tablet USB mode (`svc usb setFunctions`). Stop any other app bound to :9000 before testing:

```bash
adb shell am force-stop com.peetzweg.opendisplay
adb shell am force-stop io.github.josepacelli.opendisplay
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Settings

| Setting | Meaning | `content update` value |
|---|---|---|
| Service name | Bonjour instance name (persisted). NSD may suffix it on collision. | `serviceName` string |
| Decode ceiling | Advertised as `hello.maxEncodeWide/High`. Auto / Panel / 1920x1080 / Custom / Off. Off omits the fields. Auto is the largest of the panel size and MediaCodec 60 fps performance points (API 29+; falls back to Panel). When virtual desktop > 100% and ceiling is Auto/Panel, `maxEncode*` is exactly the physical panel. | `auto`, `panel`, `off`, or `<W>x<H>` |
| Virtual desktop | `hello.pixelsWide/High` = physical pixels × 100/125/150%, rounded even. Idle/streaming UI shows `desktop <W>x<H>pt` (hello pixels / 2) and `stream <W>x<H>` (SPS). | `100`, `125`, `150` |
| Stats overlay | Monospace overlay (default off): transport, fps, mbps, e2e50/95, ph50, dec50, queue, stream, desktop, plus sender `capFps`/`encDrops`/`netDrops`/`pending`. | `0` or `1` |
| Cursor UDP | UDP cursor side channel (default on). Off mid-session closes the socket and re-sends `hello` without `cursorPort`. | `0` or `1` |

`InfoProvider.update` is honored only for `Binder` uid `2000` (`SHELL_UID`) or `0`. Other callers are logged and rejected. Applying a hello-relevant key re-sends `hello` on the live session; `statsOverlay` toggles immediately.

```bash
# Query current settings
adb shell content query --uri content://com.terrynamic.opendisplay.info

# Cap the encoded stream at 1080p (live re-hello)
adb shell content update --uri content://com.terrynamic.opendisplay.info --bind decodeCeiling:s:1920x1080

# Other keys
adb shell content update --uri content://com.terrynamic.opendisplay.info --bind virtualDesktop:s:150
adb shell content update --uri content://com.terrynamic.opendisplay.info --bind statsOverlay:s:1
adb shell content update --uri content://com.terrynamic.opendisplay.info --bind cursorUdp:s:0
adb shell content update --uri content://com.terrynamic.opendisplay.info --bind serviceName:s:BUILD.TERRYNAMIC
```

## Protocol alignment

| Topic | This app |
|---|---|
| `pv` / `min` | 3 / 1 |
| Framing | `[u32 BE length][payload]`; inbound 1..16 MiB, outbound 1..2^20-1 |
| Demux | Isolated `Demux`: `len < 32768 && payload[0]=='{' && no 0x00` → JSON |
| Hello | Always `type,pixelsWide,pixelsHigh,scale,device,id,pv`. Optional `maxEncode*`, `addrs`, and `cursorPort`. |
| Newcomer | Immediate `hello`. Adopt if idle; otherwise park 3 s until ≥1 byte, then swap. |
| Liveness | Receiver `ping` every 2 s. Drop after 8 s without inbound bytes (`watchdog`). Never reply `pong` to the sender's `ping`. |
| Video | Annex-B, 4-byte start codes, optional `{cap,snd}` telemetry prefix (hand-parsed), SPS-derived size, async `MediaCodec` + `SurfaceView`. |
| Buffering | Listen `SO_RCVBUF` is 1 MiB (inherited at accept). Decoder queue: usb 3 frames / wifi 8 frames, 12 MiB bytes cap. |
| Input | One-finger `touch` (normalized in the letterboxed video rect). Two-finger `scroll` in video pixels, natural sign. |
| USB class | Peer 127.0.0.1 / ::1 / ::ffff:127.0.0.1 → `usb`, else `wifi`. Live `usb` rebinds the listener to `127.0.0.1` so the stock Mac's parallel Wi-Fi Bonjour dial is refused. |

Unknown control `type` values are ignored (logged once per type).

## Cursor UDP (PROTOCOL.md §6.3)

Wi-Fi sessions bind UDP on TCP port + 1 (`9001`, or an ephemeral port if taken) and advertise it as `hello.cursorPort`. Loopback / USB (`adb` tunnel cannot carry UDP) omits `cursorPort` so the sender stays on TCP. Datagrams are one `cursor` JSON each (no length prefix) with `s`. One sequence tracker is shared by TCP and UDP; `s <= last` is dropped; a TCP `cursor` without `s` applies unconditionally. The first accepted datagram of a remote host+port flow sends `cursorAck` over TCP. Turning `cursorUdp` off mid-session closes the socket and re-hellos without the field.

## S Pen (PROTOCOL.md §6.1)

`TOOL_TYPE_STYLUS` / `TOOL_TYPE_ERASER` map to `pencil` (`down|move|up`) and hover to `proximity` / `pencil phase:hover`. Coordinates use the same aspect-fit video space as touch. `azimuth = wrapPi(AXIS_ORIENTATION - π/2)` (Android 0 = toward the top of the screen; protocol 0 = toward +x). `altitude = π/2 - AXIS_TILT`. `rotation` is always 0. Stylus buttons are ignored. Gated on `welcome.pv >= 3`; before `welcome` or against an older sender the stylus degrades to `touch` (never `pencil`/`proximity`).

## Stats

Every 5 s the receiver sends `stats` (first report waits for a full 5 s window after adopt) and (if enabled) paints a top-left overlay:

`transport, fps, mbps, e2e50, e2e95, ph50, ph95, dec50, stalls, queue, drops, offsetKnown, cursorUpdates, cursorLost`

`e2e*` are omitted until the ping/pong offset is known: `e2e = renderedAtPhoneMs - (cap - offset)`. `ph` is arrive→render; `dec` is queuedToCodec→render. Rolling window is the last ~300 frames. `fps` is rendered frames in the 5 s window.

### Latency (SM-X800, stock Mac 1.19.0)

USB rows: E2E round 6/7, `-testPattern YES`, `c2.qti.avc.decoder`, `lowLatency=true`. Receiver `stalls/drops/queue=0`. Default `auto` keeps native 2800×1752 (~40 fps, stock encoder bound); set `decodeCeiling=1920x1080` for ~56 fps. Wi-Fi motion still unmeasured (idle desktop is not a target).

| | e2e50 | e2e95 | ph50 | dec50 | fps | mbps |
|---|---|---|---|---|---|---|
| Wi-Fi (motion) | — | — | — | — | — | — |
| USB (test pattern) | 31 | 34 | 12 | 12 | 37–42 | 1.1–1.3 |
| USB (test pattern, decodeCeiling=1920x1080) | 28 | 31 | 10 | 9 | ~56 | ~1.0 |

## Known limitations

- **Wi-Fi fallback is only for sessions that started on Wi-Fi.** If the stock Mac app connected via the helper's `SM-X800 (USB)` name, unplugging ends that session and the app waits for that name — it does not redial `BUILD.TERRYNAMIC` in the middle of a session. Start on Wi-Fi (then let the helper migrate) if you want unplug → Wi-Fi. Cursor UDP (`9001`) is the same: advertised only to non-loopback peers, so it needs a Wi-Fi-originated session.
- **The stock app's session label does not follow the real path.** `wifi:BUILD.TERRYNAMIC` can be flowing over the USB tunnel and still say `wifi:`. Trust logcat / `transport=`, not the menu name.

## Logs

- Tablet: `adb logcat -s OpenDisplay`
- Sleep / wake: `sleep session — liveness sends paused`, `resume from sleep (event)` (broadcast / `onResume`), `resume from sleep (device-state)` (tick self-heal if unlock signals were missed)
- Stock Mac sender: `~/Library/Logs/OpenDisplay/opendisplay.log` (`phone hello`, `virtual display created`, `stream capped at`, `Extending to AndroidTablet`)
