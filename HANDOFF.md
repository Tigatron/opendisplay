# OpenDisplay Android USB — 交接文档

**日期：** 2026-09-17（初稿 2026-09-16；§13 为当日现场）  
**分支：** `android` @ `aa32d2a`（`aa32d2a0b59222db1b3fc9b5a19a54b0f2b1080d`）  
**相对 `origin/android`：** 领先 15 个提交，**尚未推送**；工作区另有**未提交**改动（B0 Manual、SessionLifecycle、livenessTick/wake、本文）  
**相对 `main`：** merge-base `542eae4`；本分支是在对齐上游 `main` 之后从零重写的 Android 接收端 + Mac helper  
**远端：** `origin` = `git@github.com:Tigatron/opendisplay.git`；`upstream` = `git@github.com:peetzweg/opendisplay.git`  
**本文读者：** 接手剩余工作的 agent。先读本文，再读 `Android/README.md`、`MacHelper/README.md`、`PROTOCOL.md`。不要改 `Mac/`。

---

## 0. 一句话现状

库存 OpenDisplay Mac 1.19.0 **一行不改**，已经可以通过本仓的 Android 接收端 + Mac 菜单栏 helper，经 `adb forward` + **仅 lo0** 的 Bonjour 代理，在 USB 上自动发现、自动连接、建立 2800×1752 扩展桌面。  
**B1（Bonjour USB 自动连）和接收端 USB 门控已通过第六轮 E2E。B0 Manual 模式已实现并在第八轮验收（撤回 lo0 代理 + 清 `usbDisabled` 里的 `usb:first`）。**  
未完成的是：原生面板 60 fps（Mac 侧 `enc↓` 瓶颈）、helper 在 `adb reconnect` / 真拔线后的恢复、以及 WiFi UDP 光标与 S Pen 的人工验收。

---

## 1. 硬约束（下一任不得打破）

| 约束 | 说明 |
|---|---|
| **不改 `Mac/`** | 库存 sender 以已安装的 `~/Applications/OpenDisplay.app` 1.19.0 为准。本仓 `Mac/` 仅作只读参考。 |
| **不改平板 USB 模式** | 禁止 `svc usb setFunctions` / 切换 RNDIS·NCM。Tab S8+ 的 USB 网络共享是 RNDIS，**方案 A 在此机不可用**，首版不做。 |
| **不 `adb kill-server`** | 本机可能还有 AndroMeld 的 adb。Helper 只 `start-server`（没有 daemon 时），复用现有 daemon。 |
| **Bonjour 代理只注册 `lo0`** | 全接口注册会让库存 App 卡在 `.preparing`。`kDNSServiceInterfaceIndexLocalOnly` 对 `NWBrowser` **不可见**。 |
| **TXT `id` 必须是 `usb-<serial>`** | 不要复用平板真实 install id。撤回代理记录时，库存 App 才能走 `endSessionsWhoseServiceVanished`。真实 id 仍走隧道里的 `hello.id`。 |
| **端口 9000 优先** | B2 心跳和 B0 defaults **只对本地端口 9000** 生效。被占则 9010–9100，那两条路径自动关闭。 |
| **只动自己的 adb forward** | 只增删 remote 为 `tcp:9000` 的项。AndroMeld 一类 `tcp:63029 → tcp:12969` 禁止碰。 |
| **不要同时跑两个 helper / 两个 `dns-sd -P`** | 会抢 9000 和服务名。 |
| **旧第三方接收端保持 force-stop** | `com.peetzweg.opendisplay`、`io.github.josepacelli.opendisplay`。 |

---

## 2. 环境（本机实测机）

| 项 | 值 |
|---|---|
| Mac | 开发机；库存 App `~/Applications/OpenDisplay.app` **1.19.0**（`com.peetzweg.opensidecar.mac`） |
| 库存 App 日志 | `~/Library/Logs/OpenDisplay/opendisplay.log` |
| Helper 日志 | `~/Library/Logs/OpenDisplayUSBHelper/helper.log`（4 MiB 轮转） |
| 平板 | Samsung Galaxy Tab S8+ WiFi，`SM-X800`，serial **`R52T304Z7VD`**，面板 **2800×1752**，Android 16 |
| 接收端包名 | `com.terrynamic.opendisplay`（旧名 `build.terrynamic.opendisplay` 已卸） |
| install id | `719b15b0-c262-4e64-bb35-de55ddf28d35` |
| Bonjour 名 | WiFi：`BUILD.TERRYNAMIC`；USB 代理：`SM-X800 (USB)` |
| adb | `/opt/homebrew/bin/adb`，1.0.41 / **36.0.0-13206524**（`track-devices` 输出是 4 位十六进制长度前缀帧，不是纯行模式） |
| JDK / SDK | JDK 17（`/opt/homebrew/opt/openjdk@17/...`）；`Android/local.properties` 本机 SDK，已 gitignore |
| Xcode | 27；`sudo xcodebuild -license accept` 已做过。Release 构建会打 CoreDevice / CoreSimulator 版本告警，**不影响**产出 |
| Herdr | 本会话在 Herdr 内。E2E 用 Codex pane **`w1:p3`**。协调者 pane 是 `w1:p1` |
| 第六轮结束时现场 | Helper PID 仍可能活着（`MacHelper/dist/OpenDisplay USB Helper.app`）；stock App 可能仍挂着 **WiFi** 会话；**平板 USB 在 `adb reconnect` 后未回来**，`adb devices` 为空。接手后先目视插线 / 授权。 |

当前偏好（2026-09-16 19:20 之后）：

```
com.terrynamic.opendisplay.usbhelper.writeOpenDisplayDefaults = 0
com.peetzweg.opensidecar.mac.host / port = 不存在
com.peetzweg.opensidecar.mac.wifiRemembered = ("wifi:BUILD.TERRYNAMIC", "wifi:SM-X800 (USB)")
```

---

## 3. 架构（方案 B，首版唯一路径）

```
平板 :9000  ←  adb forward tcp:P tcp:9000  ←  127.0.0.1:P
                                                 ↑
库存 OpenDisplay  ←  NWBrowser(_opensidecar._tcp)
                                                 ↑
              Helper：仅 lo0 的 DNS-SD 代理
              name:  "SM-X800 (USB)"
              host:  od-usb-<sanitized-serial>.local. → A 127.0.0.1
              TXT:   pv=3  id=usb-R52T304Z7VD  model=SM-X800
```

三条互相独立的到达方式（B1 默认开；B2 / B0 是开关）：

| 代号 | 机制 | 默认 | 端口限制 |
|---|---|---|---|
| **B1** | lo0 Bonjour 代理。库存 App 把 `SM-X800 (USB)` 当成普通 WiFi 设备记忆并在启动 2–12 s 窗口自动拨号。菜单里运输标签仍是 “WiFi”（loopback 不算 wired）。 | 隧道 ready 即开 | 任意 P |
| **B2** | Helper 每 5 s `am broadcast` 心跳；接收端 `hello.addrs = ["127.0.0.1"]`；库存 App `probeForCablePath` 禁 WiFi/蜂窝拨 9000，把**现有会话**迁到隧道。`transport` 标签保留，断隧道后会重拨**原来的 Bonjour 端点**（可回 WiFi）。 | 开 | **仅 P=9000** |
| **B0** | Manual 模式：写 `host=127.0.0.1` / `port=9000`，只从 `usbDisabled` 去掉 `usb:first`（保留其他项），并**撤回** lo0 代理。库存 App 拨 `usb:first`（TCP hostPort，不是 usbmuxd）。**默认关**。会改别人的偏好。第八轮已验收。 | 关 | **仅 P=9000** |

接收端 USB 门控（`65a00bb`）：live 会话 peer 是 loopback 时，监听从 `0.0.0.0:9000` 换成 `127.0.0.1:9000`，WiFi 拨号得 `ECONNREFUSED`。USB 会话结束（EOF/RST/watchdog）——**不是** 15 s 心跳过期——立刻绑回 `0.0.0.0`。NSD 一直注册。

**方案 A（USB tethering / NCM + 真 mDNS）已否决进首版。** 此平板 USB 共享是 RNDIS，macOS 对不上。不要重新开启，除非换机能证明 CDC-NCM 接口。

---

## 4. 仓库地图

### 4.1 只关心这两个树

- `Android/` — pv 3 接收端，包名 `com.terrynamic.opendisplay`
- `MacHelper/` — Swift 菜单栏 helper，bundle `com.terrynamic.opendisplay.usbhelper`

`Mac/`、`iOS/`、`public/` 是上游，本任务只读。`docs/` 被根 `.gitignore` 忽略（落地页构建输出），**不要**把交接文档放进 `docs/`。

### 4.2 Android 关键类

| 文件 | 职责 |
|---|---|
| `ReceiverController.kt` | 会话编排：hello、解码、NSD、心跳、统计、输入、sleep/wake |
| `session/SessionLifecycle.kt` | 控制线程上的 adopt/close 世代门闩（未提交） |
| `LivenessTickPolicy.kt` / `DeviceWakeState.kt` | 500 ms ping/stats 续期；`asleep` 卡死时按真实解锁状态自愈（未提交） |
| `transport/ReceiverListener.kt` | accept / adopt / rebind；失败回退旧 host（`d3acbdc`） |
| `transport/NewcomerMachine.kt` | 空闲立即 adopt；有会话则停车 3 s 等 ≥1 字节再换 |
| `transport/ListenerGate.kt` | live+usb → `127.0.0.1`，否则 `0.0.0.0` |
| `transport/ListenerSwapPolicy.kt` | Linux 上 `0.0.0.0` 与 `127.0.0.1` 共享端口，只能 close-then-bind |
| `protocol/PanelReadyGate.kt` | 面板尺寸未知不 bind、拒占位 1920×1080 hello；**`asleep` 时也不 bind** |
| `video/Decoder.kt` + `QualcommDecoderHints.kt` | 异步 MediaCodec；`c2.qti.*` / `OMX.qcom.*` vendor 低延迟键（`aa32d2a`） |
| `video/DecodeCeiling.kt` | Auto/Panel/Fhd/Custom/Off → `hello.maxEncode*` |
| `link/LinkPolicy.kt` | 心跳 port==9000 且未过期 → `addrs=["127.0.0.1"]` |
| `ipc/HelperReceiver.kt` | action `com.terrynamic.opendisplay.USB_TUNNEL` |
| `ipc/InfoProvider.kt` | `content://com.terrynamic.opendisplay.info`；update 仅 uid 0/2000 |
| `cursor/CursorChannel.kt` + `CursorUdpSocket.kt` | USB 不广告 `cursorPort`；WiFi 默认 9001 |
| `input/PencilMapper.kt` / `TouchMapper.kt` | S Pen / 触摸；`welcome.pv>=3` 才发 pencil |

### 4.3 MacHelper 关键类

| 文件 | 职责 |
|---|---|
| `HelperEngine.swift` | adb 跟踪、每设备 tunnel、settings、defaults 对账 |
| `DeviceTunnel.swift` | forward → hello 探测 → lo0 发布 → 心跳 |
| `AdbClient.swift` | 复用同 serial 的 `tcp:P→tcp:9000`；只删 remote 9000 |
| `TrackDevicesParser.swift` | 优先长度前缀帧（adb 36） |
| `PortAllocator.swift` | 先让 adb 绑 9000；`SO_RESTARTADDR` 探测，避免把 ESTABLISHED 当成 LISTEN（`387e51e`） |
| `BonjourProxy.swift` | `if_nametoindex("lo0")` + dns_sd C API |
| `OpenDisplayDefaults.swift` | `defaults write/delete` 库存 `host`/`port`；写 Manual 时从 `usbDisabled` 去掉 `usb:first` |
| `HelperSettings.swift` | domain = helper bundle id；运行中 1.5 s 内生效 |

### 4.4 标识符速查

| 项 | 值 |
|---|---|
| Android applicationId | `com.terrynamic.opendisplay` |
| Provider | `content://com.terrynamic.opendisplay.info` |
| 心跳 action | `com.terrynamic.opendisplay.USB_TUNNEL` |
| logcat | `adb logcat -s OpenDisplay` |
| Helper bundle | `com.terrynamic.opendisplay.usbhelper` |
| Helper UserDefaults | 同 bundle id |
| TCP / UDP | 9000 / 9001（占用则 ephemeral） |
| 协议 | `pv` 3，`min` 1 |

### 4.5 设置键

Android（`adb shell content update --uri content://com.terrynamic.opendisplay.info --bind KEY:s:VALUE`）：

`serviceName`、`decodeCeiling`（`auto|panel|off|WxH`）、`virtualDesktop`（`100|125|150`）、`statsOverlay`（`0|1`）、`cursorUdp`（`0|1`）

Helper：

`adbPathOverride`、`launchReceiverOnAttach`（默认 true）、`sendHeartbeat`（默认 true）、`writeOpenDisplayDefaults`（默认 false）、`startAtLogin`（默认 false；只有 `/Applications` 下 `SMAppService` 才有效）

---

## 5. 已完成工作

### 5.1 调研与决策（已关闭）

- 第三方 fork `josepacelli/opendisplay-android`：不改 Mac 客户端，但无 USB、不全屏；不作为实现基线。
- 本地旧 Android 分支已归档为 `archive/android-v1`，**可整支丢弃**。新接收端按上游 `PROTOCOL.md` pv 3 重写。
- 方案 A（USB tethering / NCM）在 SM-X800 上不可用（RNDIS）。热点替代验证已跳过。
- 方案 B 已在库存 1.19.0 上用临时 `adb forward` + `dns-sd -P` 证伪风险：库存 App **能**发现并自动连 lo0 代理；隧道应用层 RTT ~1.4 ms。

### 5.2 Android 接收端（功能完成，待性能与人工验收）

按提交时间：

| Hash | 内容 |
|---|---|
| `a389327` | Gradle 9 工程从归档 wrapper 拉起 |
| `3cc360f` | pv 3 接收端（framing / demux / hello / newcomer / MediaCodec / NSD / 触摸） |
| `7be94a7` | 被 `build/` ignore 挡掉的源码补进树 |
| `1e4fe8a` | 接管时先同步 reset 再装 `onFrame`（保住首个 IDR）；`PanelReadyGate` 拒占位 hello |
| `d42e768` | 包名 `com.terrynamic.opendisplay` |
| `11a112d` | 延迟 stats / overlay、传输自适应缓冲、shell 可改设置 |
| `8b83e6e` | UDP 光标侧信道（WiFi）；USB 省略 `cursorPort` |
| `bcb8619` | S Pen `pencil` / `proximity` |
| `8906f30` | Android README |
| `65a00bb` | USB 会话期间监听绑 loopback，拒绝并行 WiFi 拨号 |
| `d3acbdc` | rebind 失败立即绑回旧 host，`acceptLoop` 不再空转 |
| `aa32d2a` | Qualcomm decode-order 低延迟 vendor keys |

单测：`Android/app/src/test/...` **24** 个 JVM 文件（含未提交的 `SessionLifecycle` / `LivenessTickPolicy` / `DeviceWakeState`）。无 instrumentation 测试。

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
cd Android && ./gradlew :app:assembleDebug --no-daemon && ./gradlew :app:testDebugUnitTest --no-daemon
```

### 5.3 Mac helper（功能完成；B0 Manual 已验收，待恢复路径）

| Hash | 内容 |
|---|---|
| `3c45069` | XcodeGen 工程 + README |
| `f9f2571` | 隧道 + lo0 代理 + 菜单栏 |
| `09c4563` | 解析器 / 端口 / TXT / 状态机单测 |
| `482ef9e` | 复用已有 forward；`forward --remove` 带 `-s` |
| `01db0f1` | adb 36 长度前缀 `track-devices` |
| `ec7055e` | `NSLocalNetworkUsageDescription` + `NSBonjourServices`；只删自己的 9000 forward |
| `a63332d` | bundle id → `com.terrynamic.opendisplay.usbhelper` |
| `1c8425f` | `build-release.sh` / `install.sh` / 运行中改设置 |
| `a4b546e` | Quit/SIGTERM 拆隧道；设置即时生效 |
| `d654ae5` | `startAtLogin` 初始化顺序 |
| `e2e2dbb` | Helper README |
| `63f422e` | 打 defaults 写入/删除与 settings 变更日志 |
| `387e51e` | 9000 被 ESTABLISHED 误判占用 → 现优先 9000 |

单测：`MacHelper/Tests/...` **17** 个文件（含未提交的 `SettingsApplyQueueTests`；无 adb / 无平板）。

```bash
cd MacHelper && ./generate.sh
xcodebuild -project OpenDisplayUSBHelper.xcodeproj -scheme OpenDisplayUSBHelper \
  -configuration Debug -derivedDataPath build test
```

Release：`MacHelper/build-release.sh` → `MacHelper/dist/OpenDisplay USB Helper.app`（dist gitignored）。装到 `/Applications` 用 `install.sh`（登录项才可用）。

**本地网络权限绑 bundle id。** 改名后 macOS 当新 App。菜单显示 `granted` / `denied` / `unknown`。

### 5.4 已修过的实坑（不要再踩）

1. `adb track-devices -l` 在 36.x 是 smart-socket 长度前缀，不是按行。
2. 缺 Local Network / Bonjour plist → `kDNSServiceErr_PolicyDenied (-65570)`。
3. 清理 forward 过宽会删掉 AndroMeld。
4. helper 重启遇到已有 `tcp:9000` 时 `--no-rebind` 会被当成端口忙，退到 9010，**B2/B0 静默失效**。必须先 `forward --list` 复用。
5. `isLoopbackPortFree` 不设 `SO_REUSEADDR` 时，会把残留 ESTABLISHED 当成 LISTEN（第五轮落到 9010；`387e51e` 已修，第六轮确认 `port 9000 (preferred)`）。
6. `onAdopted` 异步 reset 会丢掉首个 IDR。
7. 面板未就绪就发 1920×1080 hello，Mac 会建错虚拟屏。
8. rebind 先 close 再 bind，失败后 `listen` 指向已关 socket，`acceptLoop` 空转打满 CPU。
9. 库存 App 会**同时**自动连 `wifi:SM-X800 (USB)` 和 `wifi:BUILD.TERRYNAMIC`。两个都是 `.wifi` 会话，**原生 `dedupeSessions` 去不掉**（它只丢“有 `.usb` 会话且 deviceID/TXT/usbmux 名对得上”的 WiFi 孪生）。接收端必须在 USB live 时拒绝网卡来的 TCP。
10. Bonjour 代理 TXT 若用真实 install id，撤回代理时 WiFi 广告仍匹配，库存 App 不会快切会话。
11. `sessionGeneration` 跨线程非 volatile：过期 close 能拆掉新会话的 decoder surface（`codec wait: surface=false`）。adopt 必须上控制线程 + 世代门闩，并保住 `lastSurface`。
12. `livenessTick` 在 `asleep` / `!listeningEnabled` 时 early-return 会跳过 `postDelayed`，循环永久死；`resumeFromSleep` 还必须重挂。
13. `asleep` 若只靠 USER_PRESENT / SCREEN_ON / onResume 清掉，漏信号就会卡死：listener 能起来但永不 ping。tick 必须按 `isInteractive && !isKeyguardLocked` 自愈，且 asleep 时拒绝 bind。
14. helper `republishBonjour` 若在 engine 队列上 `hooks.publish` 再 `queue.async`+semaphore，会自死锁。`applySettings` 必须走 io 队列并带 generation。

### 5.5 文档（部分完成）

- 有：`Android/README.md`、`MacHelper/README.md`、`PROTOCOL.md`、本文。
- **没有：** `ANDROID.md`、`docs/android/`。根 `README.md` 的 “Android receivers” 仍只链外部仓库，**未描述本仓 `Android/` / `MacHelper/`**。
- 源码几乎无 `TODO`/`FIXME`（仅 `Android/.../data_extraction_rules.xml` 模板注释）。

---

## 6. E2E 成绩单

测试者：Herdr Codex `w1:p3`。规则：不改源码；不改 USB 模式；不 `kill-server`；不卸载。人工步骤（权限弹窗、S Pen、真拔线）由用户点。

Brief 在 `/tmp/od-e2e-brief.md`、`od-e2e-round4.md`、`od-e2e-round5.md`、`od-e2e-round6.md`（**不在仓库里**，重启后可能丢）。

### 6.1 第一～三轮（helper 从不能发现设备 → B1/B2 跑通）

| 项 | 结果 |
|---|---|
| B1 启动后自动连 USB | **PASS**，约 **2.3 s**（第三轮）；第六轮复测 **2.317 s** |
| 路径 | `connection path to SM-X800 (USB): lo0` |
| 虚拟屏 | `Extending to AndroidTablet (2800×1752)` |
| B2 WiFi → USB | **PASS**（人工拔插轮），约 **4 s** |
| 拔线回 WiFi | **PASS**，约 **1.3 s** |
| 重插再迁 USB | **PASS**，≤ **10 s** |
| 不误删他人 forward | **PASS**（`ec7055e` 后） |

第三轮之前的失败都已修：parser、PolicyDenied、forward 误删。

### 6.2 第四轮（P3/P4 功能 + 暴露双会话）

已看到：

- USB hello **不带** `cursorPort`（正确）。
- WiFi 会话 `hello cursorPort=9001`、`cursorAck sent`（logcat）。
- `decodeCeiling=1920x1080` 后 Mac：`stream capped at ...`（shell 设置生效）。
- `virtualDesktop=150` 的 live re-hello 路径已实现（以当时日志为准）。

失败根因：库存 App 同时拨两个 Bonjour 条目 → 接收端来回 adopt → fps 40→25→0、`offsetKnown` 长期 false。催生 `65a00bb` 门控。

**UDP 光标的 Mac 侧确认句 `cursor channel confirmed by the receiver`、S Pen 书写手感：未做人工验收。**

### 6.3 第五轮（门控 + 60 fps + B0）— 整体 FAIL，两个实现 bug 已修

- Helper 误选 **9010**（`387e51e` 已修）。
- 监听 rebind 失败空转（`d3acbdc` 已修）。
- 静态桌面 `dec50` 80–104 ms（`aa32d2a` 已修）。

### 6.4 第六轮（`387e51e` + `d3acbdc` + `aa32d2a`）— 部分 PASS

时间 UTC+8。Helper：`MacHelper/dist/OpenDisplay USB Helper.app`。Android APK 已是 `aa32d2a`。

| 步 | 结果 | 证据 |
|---|---|---|
| 0 重建 helper | **PASS** | `19:14:40.366 port 9000 (preferred)`；`19:14:41.309 phase=ready port=9000 name=SM-X800 (USB)` |
| 1 门控 / 单会话 | **PASS** | `19:15:38.677 connection ready to SM-X800 (USB)`，path `lo0`；`listener bound 127.0.0.1:9000`；`BUILD.TERRYNAMIC` 只有 `dial timed out in preparing`；20 s 内 `transport=usb`、`offsetKnown=true` |
| 2 60 fps | **FAIL**（延迟过、帧率不过） | 见下表 |
| 3 B0 Manual/dedupe | **FAIL** | defaults 写入 **PASS**（0.47 s）；重启后仍是 Bonjour USB，**无** `Manual` / **无** `keeping the cable`；`wifiRemembered` 两行都在 |
| 4 `adb reconnect` | **FAIL** | 设备 offline 后 **30 s 内未再 online**；Mac 9.4 s 后改连 WiFi；无新 Manual |
| 5 关 B0 | 键已在步 4 `trigger=tunnel down` 删掉；未见 `setting disabled` | B0=0，host/port 不存在 |
| 6 收尾 | **FAIL** | 最终 `transport=wifi`；`adb devices` 空 |

**步 2 测试图样（USB，45 s 后最后 4 条 PHONE-STATS）：**

| 时间 | fps | Mbps | e2e50 | e2e95 | ph50 | dec50 | stalls/drops/queue |
|---|---|---|---|---|---|---|---|
| 19:16:57 | 37.22 | 1.11 | 31.10 | 34.10 | 12 | 12 | 0/0/0 |
| 19:17:02 | 42.03 | 1.31 | 31.10 | 34.10 | 12 | 12 | 0/0/0 |
| 19:17:07 | 40.65 | 1.32 | 31.10 | 34.10 | 12 | 12 | 0/0/0 |
| 19:17:12 | 38.47 | 1.26 | 31.22 | 34.22 | 13 | 12 | 0/0/0 |

全部：`transport=usb`，`codecName=c2.qti.avc.decoder`，`lowLatency=true`。  
同期 Mac：`enc↓=116/90/96/109`，`net↓=0`。  
logcat：`FEATURE_LowLatency=false`，但 `MediaCodec configured ... lowLatency=true`（vendor key 已上）。

目标曾是：fps ≥ 55，e2e50 < 60 ms，dec50 < 20 ms。**后两项达标，fps 未达标。**

---

## 7. 进行中的工作

2026-09-16 写初稿时工作区是干净的。**2026-09-17 工作区有未提交改动**（B0 Manual + republish 死锁、SessionLifecycle、livenessTick、wake 自愈、本文）。下面这些是“已经开了头、还没验收完”的线：

| ID | 状态 | 说明 |
|---|---|---|
| 人工 · WiFi UDP 光标 | **未做** | 需要用户看鼠标在平板上的跟随，并在 Mac 日志里找 `cursor channel confirmed by the receiver`。WiFi 起源会话上已见到 `cursorPort=9001` |
| 人工 · S Pen | **未做** | 需要用户在扩展桌面上用 S Pen 写/悬停；logcat 应有 `pencil` / `proximity`，且 `welcome.pv>=3` |
| 人工 · 真拔插 B2 | 第三轮做过一次；第六轮用 `adb reconnect` **不能**代替 | 下次必须真拔线 / 真插回。库存 App 只对 **WiFi 起源** 会话做拔线回 WiFi，见 §13 |
| `writeOpenDisplayDefaults`（B0） | **DONE**（第八轮） | 选项 B：撤回代理 + 清 `usb:first`。见 §8.2 / §13 |
| 接收端 adopt 竞态 / tick 死亡 / asleep 卡死 | **DONE**（第九轮，未提交） | 见 §13 |
| 60 fps @ 2800×1752 | 解码侧已做完；**发送端在掉帧** | 见 §8.1 |
| helper 断线恢复 | 正常 track-devices 上下线可以；**`adb reconnect` 后 30 s 无设备** | 见 §8.3 |
| 文档收口 | README 两份 + 本文已补 9-17 现场；根 README / `ANDROID.md` / 推送未做 | 见 §8.5 |
| 推送 | 早期推过一次；**当前又领先 15 个提交 + 未提交工作区** | 需用户明确说 push |

---

## 8. 将进行的工作（按建议优先级）

不要让子代理自己发明计划。下面每条已经写好目标、已有事实、建议验证。实现仍用 Grok 4.6 extra-high + fast 子代理；E2E 仍丢 Herdr Codex `w1:p3`；需要人点的事先问用户。

### 8.1 P0 — 弄清 40 fps，再决定要不要改接收端

**事实（不要再怪解码器）：**

- 接收端 `dec50=12 ms`，`stalls=drops=queue=0`。
- 库存 `MacSender` 把 `pendingEncodes` 封顶为 1：编码中的采集直接算 `enc↓`（`Mac/MacSender.swift` 约 162–168、1938、2000 行）。
- 第六轮 5 s 窗口约 90–116 次 enc drop ≈ 少 18–23 fps，和测到的 37–42 fps 对得上。
- `net↓=0`，不是 TCP 发满。
- 编码是 2800×1752 H.264 18 Mbps `quality=best`。

**可做（仍不改 `Mac/`）：**

1. 用 `-testPattern YES` 再测 `decodeCeiling=1920x1080` 的稳态 fps。第四轮设置路径已通。若 1080p 能到 ≥55，把默认天花板或文档目标改成“原生面板低延迟 / 1080p 高帧率”二选一。
2. 看 Mac 日志 `capFps` / `inp50`：是采集不到 60 还是编码跟不上。
3. **不要**为了 60 fps 去改库存编码器。那是硬约束。

### 8.2 P0 — B0 / Manual — **已完成（2026-09-17，选 B）**

第六轮预期（后来证明按库存代码是错的）：开 B0 → 出现 `Manual (127.0.0.1:9000)`，并且打出 `two sessions for one device — keeping the cable, dropping wifi:…`，然后忘掉 Bonjour 孪生。那条预期不成立，不要再按它写测试。

**已实现（选项 B，未提交）：** `writeOpenDisplayDefaults` 写 `host=127.0.0.1` / `port=9000`，只从 `usbDisabled` 去掉 `usb:first`（保留其他项；若它是唯一项则写回空数组），开启时撤回 helper 的 lo0 代理，关闭时再发布。只对端口 9000。不改 `wifiRemembered`，不依赖原生 `keeping the cable`。`applySettings` 走 io 队列 + generation，避免 `republishBonjour` 在 engine 队列上自死锁。

**第八轮验收：** Manual 连接 **0.178 s**；60 s 零 churn；关 B0 后 republish ~**2.7 s**。B1 会话热切 B0（不重启 App）~**1.8 s**。

**库存代码事实**（`Mac/OpenSidecarMacApp.swift`，仍有效）：

- `host` 存在则 `autoConnect()` 拨 `usb:first`（与 Bonjour **并行**，不是替代）——所以必须撤回代理，否则仍会抢 `SM-X800 (USB)`。
- `dedupeSessions` 只结束 `.wifi` 会话，且仅当：其 `deviceID` / TXT `id` 落在某个 **未失败的 `.usb` 会话** 的 `deviceID` 集合里，或服务名等于 **usbmux 设备名**。
- Android **不是** usbmux 设备，`cabledNames` 为空。
- 代理 TXT `id=usb-R52T304Z7VD`，hello.id 是 UUID。`usb:first` 连上后可以按 UUID 丢掉 `BUILD.TERRYNAMIC`，**丢不掉** `SM-X800 (USB)`。
- `wifiRemembered` 只在用户连 WiFi 时插入，结束会话**不会**自动删。现场还见过列表被剪掉 `BUILD.TERRYNAMIC`（机制不明，见 §13）。
- `usbDisabled` 含 `usb:first` 时库存 App **静默不拨** Manual（约 3 次 usb 失败后写入）。Helper 写 Manual defaults 时清掉它。

库存 App **不会**在运行中热读 `@Published var host`（只在 init 读一次）；`autoConnect` 倒是每次看 `UserDefaults.object(forKey: "host")`。热切已在第八轮测过（~1.8 s）。

### 8.3 P0 — USB 消失后的恢复（helper + 接收端）

第六轮 `adb reconnect`：

```
[19:18:34.155] R52T304Z7VD is offline — tearing down tunnel
[19:18:34.156] device R52T304Z7VD disappeared
[19:18:34.468] deleted OpenDisplay defaults ... trigger=tunnel down
```

之后 30 s：**没有** `track-devices exited — reconnecting`，**没有** device online，**没有** `phase=ready`。`adb devices` 空。这更像 **adb/USB 没在窗口内回来**，而不是 parser 再炸一次。

同时库存 App 在 9.4 s 后连上 `BUILD.TERRYNAMIC`（门控在 USB 会话死后绑回 `0.0.0.0`，这部分符合设计）。

**下一任必须拆开两件事：**

1. **物理拔插**（用户配合）：helper 是否 `disappeared` → 再 `device … device` → `phase=ready port=9000`；B2 是否把 WiFi 迁回 USB。第三轮做过，要回归。
2. 若物理插回后 `track-devices` 有设备但 helper 不 attach：再查 `TrackDevicesParser` / `apply` / `sync`（`ready` 时 `slot.tunnel?.state.phase == .ready || slot.busy` 会直接 return）。
3. 不要再把 `adb reconnect` 当成“模拟拔插”。它会拆 forward，且设备可能长时间不回来，还可能弹未授权。
4. 接收端：USB 死后必须立刻 `listener bound 0.0.0.0:9000`。第六轮这一步因 `waiting for device` 没采到 logcat。

### 8.4 P1 — 必须由用户做的验收

1. **WiFi UDP 光标：** helper 停、只留 WiFi 会话、`cursorUdp=1`。动 Mac 鼠标。期望：平板上有光标；Mac `cursor channel confirmed by the receiver`；stats `cursorUpdates` 增加；USB 会话 hello **仍无** `cursorPort`。
2. **S Pen：** `welcome.pv>=3` 时 logcat 有 `pencil` / `proximity`；压感/倾角能用；旧 sender 则降级 `touch`。按钮故意忽略。
3. **真拔插 B2：** 先 WiFi，插 USB（心跳开、端口 9000）应自动迁隧道；拔线后 1–2 s 回 WiFi；再插 ≤10 s 迁回。
4. 若再弹「OpenDisplay USB Helper 想要查找并连接到本地网络上的设备」→ 点允许。系统设置 → 隐私与安全性 → 本地网络。

### 8.5 P2 — 文档与发布

- 根 `README.md` 加本仓 Android / helper 的入口（或明确“这是 fork 的 android 分支”）。
- 按早期计划补 `ANDROID.md`（或把内容维持在 `Android/README.md`，不要再承诺第三份）。
- `Android/README.md` 延迟表：USB 测试图样行已用第六轮数字填；**WiFi 运动场景仍空**。
- 用户明确要求后再 `git push -u origin android`（15 个提交 + 未提交工作区 + 本文）。
- **不要**把本分支直接 PR 进 upstream `main`，除非用户要。

### 8.6 P3 — 明确不做（首版）

- 方案 A / USB 网络共享 / 让平板在 tethering 口上发 mDNS。
- 改库存 Mac 客户端、改 usbmuxd、给 Android 做 Apple 线协议。
- 多平板同时隧道的产品化（端口分配已写 9010+，但 B2/B0 会丢）。
- 默认打开 B0。
- 为了 E2E 去 `adb kill-server`。

---

## 9. 下一任怎么动手

### 9.1 开工检查

```bash
cd /Users/terrylan/Development/apps/opendisplay
git checkout android
git log -1 --oneline          # 期望 aa32d2a，或其后你自己的提交
adb devices -l                # 期望 R52T304Z7VD device
adb shell am force-stop com.peetzweg.opendisplay
adb shell am force-stop io.github.josepacelli.opendisplay
pgrep -fl 'OpenDisplay USB Helper'
pgrep -fl 'OpenDisplay.app/Contents/MacOS'
defaults read com.peetzweg.opensidecar.mac wifiRemembered
defaults read com.terrynamic.opendisplay.usbhelper
```

平板未授权时看屏幕「允许 USB 调试」，勾选「一直允许」。

只留一个 helper。调试用 `MacHelper/dist/` 或 `/Applications`。改 bundle 后重新给本地网络权限。

### 9.2 分工（与本轮相同）

- **实现：** Cursor Grok 4.6 extra-high + fast 子代理。brief 写死目录（只 `Android/` 或只 `MacHelper/`）、提交信息、禁止事项。子代理不许自己改计划。
- **审阅：** 协调者看完再合。不对就重写 brief 再开一条，不要默默补。
- **E2E：** `herdr agent prompt w1:p3 "…" --wait`。Brief 写在 `/tmp/od-e2e-roundN.md`。禁止改仓库。
- **人：** 权限弹窗、S Pen、真拔插、看菜单栏 Devices。

### 9.3 建议的下一条实现 brief（先做测量，不要先改默认画质）

只读 + 设备实验，不改 `Mac/`：

1. 确认 USB 已连（helper `phase=ready port=9000`，门控 logcat）。
2. `decodeCeiling=1920x1080`，stock App `--args -testPattern YES`，45 s，抄最后 4 条 PHONE-STATS 和 `enc↓`。
3. 恢复 `auto`。
4. 交回：1080p fps/e2e/enc↓，以及“默认要不要封 1080p”的建议。**先不要改代码。**

### 9.4 常用命令

```bash
# 接收端
adb logcat -s OpenDisplay
adb shell content query --uri content://com.terrynamic.opendisplay.info
adb shell content update --uri content://com.terrynamic.opendisplay.info --bind statsOverlay:s:1
adb shell content update --uri content://com.terrynamic.opendisplay.info --bind decodeCeiling:s:1920x1080

# Helper
defaults write com.terrynamic.opendisplay.usbhelper writeOpenDisplayDefaults -bool true
defaults write com.terrynamic.opendisplay.usbhelper sendHeartbeat -bool true
tail -f ~/Library/Logs/OpenDisplayUSBHelper/helper.log

# 库存 App
osascript -e 'tell application "OpenDisplay" to quit'
pkill -f '/OpenDisplay.app/Contents/MacOS/OpenDisplay' || true
open -a ~/Applications/OpenDisplay.app
open -na ~/Applications/OpenDisplay.app --args -testPattern YES
grep -v PHONE-STATS ~/Library/Logs/OpenDisplay/opendisplay.log | tail
grep PHONE-STATS ~/Library/Logs/OpenDisplay/opendisplay.log | tail
dns-sd -B _opensidecar._tcp local    # 3 s 后自己停

# 接收端 sleep/wake（第九轮）
adb logcat -s OpenDisplay | grep -E 'sleep session|resume from sleep|liveness tick failed|stats transport='
```

---

## 10. 库存 App 行为备忘（只读 `Mac/`）

这些是写接收端 / helper 时必须迁就的事实：

| 行为 | 细节 |
|---|---|
| 记忆 WiFi | `wifiRemembered`；**仅启动后 2–12 s** 自动连，中途新出现的不自动抓 |
| 记忆 USB | usbmux 设备插上就连；Android **不在**这条路径 |
| 代理会话标签 | 菜单显示 “WiFi”，`onUSB=false` |
| Manual 标签 | `Manual (127.0.0.1:9000)`，`sessionID=usb:first` |
| 去重 | 见 §8.2；两个 Bonjour 名去不掉 |
| 拒拨 | 曾连上后连续 3 次 `ECONNREFUSED` 结束；preparing 超时走 10 s 宽限 |
| 服务撤回 | WiFi 会话、广告消失 ≥3 s → `peerServiceWithdrawn` |
| hello.addrs | `probeForCablePath`，禁 WiFi/蜂窝，拨那些地址的 **9000** |
| `host`/`port` | 启动时读进 `@Published`；`autoConnect` 看 UserDefaults 里有没有 `host` |
| 测试图样 | `open … --args -testPattern YES` 或 `defaults write com.peetzweg.opensidecar.mac testPattern -bool true` |
| WiFi 回退 | **只对 WiFi 起源的会话。** 从 `SM-X800 (USB)` 发起的会话只有 lo0；拔线后等这个名字，**不会**中途改拨 `wifi:BUILD.TERRYNAMIC`。从 `BUILD.TERRYNAMIC` 发起的可双向迁移 |
| UI 运输标签 | 菜单名**不**跟真实路径走。`wifi:BUILD.TERRYNAMIC` 走隧道时仍显示 `wifi:`。看日志 / `transport=` |
| 会话丢失后重拨 | `device gone` 后代理再现：现场一次 ~3 s 重拨，第二次丢失后闲置 6+ min 直到重启 App。重启约 2.5 s 必连 |
| `usbDisabled` | 约 3 次 usb 拨号失败后写入 `usb:first`，之后 Manual **静默不拨** |
| `cursorPort` | 只对非 loopback peer 广告。光标 UDP 需要 **WiFi 起源** 会话（迁移后 WiFi 仍在则继续可用） |

---

## 11. 已知现场问题

- 第六轮后平板可能从 adb 消失。先重插 / 点「允许 USB 调试」，再谈 helper bug。
- 库存 App 仍记得两个 Bonjour 名。这是常态，不是脏数据。门控就是为这个写的。
- 构建 helper 时 Xcode 27 的 DVTCoreDeviceCore / CoreSimulator 告警可忽略。
- 另一份 adb（AndroMeld）可能占 5037。对版本，设 `adbPathOverride`。
- `open -na … --args -testPattern YES` 只影响那一次；之后普通 `open -a` 没有图样。
- Helper 改名后权限对话框可能不再弹，但系统设置里必须是**新** bundle。
- 库存 App 的 WiFi 回退 / 菜单标签 / 重拨窗口见 §13.3，不要当成接收端或 helper 回归。

---

## 12. 成功标准（首版可交用户日常用）

可以宣布“USB 日常能用”的最低线：

1. 插线（调试已授权）后 helper `phase=ready port=9000`，库存 App 在 15 s 内自动出扩展桌面，路径 `lo0`。
2. 同时存在 WiFi 条目时 **只有一条** 视频会话，`transport=usb` 稳定，`offsetKnown=true`。
3. 拔线后回 WiFi，再插回 USB（B2），虚拟屏不拆掉重建一整块（允许短暂中断）。
4. 延迟：运动画面 e2e50 < 60 ms，dec50 < 20 ms（第六轮 USB **已达到**）。
5. 帧率：要么 1080p ≥55 fps，要么书面接受原生 2800×1752 ≈40 fps（第六轮数字）。
6. S Pen + WiFi 光标人工过一次。
7. B0 Manual 已按选项 B（撤回代理 + 清 `usb:first`）实现并在第八轮测过，默认继续关。

**2026-09-16 已达到 1、2、4。2026-09-17 加上 7，以及接收端 adopt/tick/wake 三修（§13）。** 3 在第三轮人工拔插过、第六轮模拟失败。5、6 未完成。拔线回 WiFi（标准 3）只保证 **WiFi 起源** 会话。

---

## 13. Round 8–9 + 现场发现（2026-09-17）

写本文时这些改动都还在工作区，**不要当已提交**。不要动 `Mac/`。

### 13.1 B0 Manual — DONE，已验收

实现：`writeOpenDisplayDefaults` 写 `host=127.0.0.1` / `port=9000`，只从 `usbDisabled` 去掉 `usb:first`（保留其他项；若它是唯一项则写空数组），开启时撤回 lo0 Bonjour 代理，关闭时再发布。只对端口 9000。

先前 `republishBonjour` 死锁：`hooks.publish` 在 engine 队列上再 `queue.async`+semaphore。已改成 `applySettings` 走 io 队列 + generation guard。

第八轮数字：

| 项 | 结果 |
|---|---|
| Manual 连接 | **0.178 s** |
| 60 s 稳态 | 零会话 churn |
| 关 B0 → republish | ~**2.7 s** |
| 热切（B1 会话中开 B0，不重启 App） | Manual ~**1.8 s** |

### 13.2 接收端三修（均未提交，第九轮真机过）

**Session adopt/close 竞态。** `sessionGeneration` 是非 volatile，listener 线程写、控制线程读。过期 close 能穿过守卫，reset 新会话的 decoder，并把 phase 打成 IDLE，surface 永久丢（`codec wait: surface=false`，fps=0）。修法：adopt 经 `runOnControlThread` 串到控制线程；`SessionLifecycle` + `SessionCloseGuard`；保住 `lastSurface` 并在 adopt 时重新 attach；stats 的 `transport` 优先取 `liveConnection`（修 wifi 误标）。

**livenessTick 死亡。** 500 ms ping/stats 循环在 `!listeningEnabled` 或 `asleep` 时 early-return，**跳过** `postDelayed`，睡一次循环就死。`resumeFromSleep` 也不重挂。表现：accept / adopt / decode 都在，就是没有 ping/stats → 库存 App watchdog 约每 **8 s** 重连到死。修法：`LivenessTickPolicy`（除非 `stopped` 一律续期；send 单独门控；send 包 try/catch）+ resume 时 `removeCallbacks`/`post`。

**`asleep` 卡死。** `asleep` 原先只由 `resumeFromSleep` 清（`USER_PRESENT` / `SCREEN_ON`+keyguard / `MainActivity.onResume`+`isLocked()`）。三条都漏时，listener 仍可能经 **非 resume 路径** 起来——现场是 `onResume` → `updatePanel` → `maybeStartListenerLocked`，当时仍锁屏所以跳过 `resumeFromSleep`。之后是僵尸：能 adopt/解码，tick 因 `asleep==true` 永不 send，同样 8 s watchdog。修法：tick 每 500 ms 拍 `DeviceWakeProbe`（`isInteractive && !isKeyguardLocked`），已解锁则走与 `resumeFromSleep` 同一套 `applyResumeLocked`（日志 `resume from sleep (device-state)`）；`PanelReadyGate.shouldBindListener` **asleep 时拒绑**，避免半醒僵尸。真机：锁屏 → 解锁 → 会话恢复，零 watchdog。

诊断行（logcat `-s OpenDisplay`）：

| 行 | 含义 |
|---|---|
| `sleep session — liveness sends paused` | 已睡，停 send，循环仍转 |
| `resume from sleep (event)` | 广播 / onResume 快路径 |
| `resume from sleep (device-state)` | tick 自愈（漏了解锁信号） |
| `liveness tick failed: …` | send 抛错但循环还在 |
| `stats transport=` | 活着；卡死后这条会消失 |

### 13.3 库存 App 行为（边界，不是我们的 bug）

- **WiFi 回退只对 WiFi 起源的会话。** 从 `SM-X800 (USB)` 代理名发起的会话只有 lo0：拔线会话结束，App 等这个名字，**不会**中途改拨 `wifi:BUILD.TERRYNAMIC`（记忆名自动拨只在启动 2–12 s 窗口）。从 `BUILD.TERRYNAMIC` 发起的可双向迁移（已测：WiFi→USB 数秒，USB→WiFi ~**1.2 s**，不重建虚拟屏）。
- **菜单上的会话名不反映运输。** `wifi:BUILD.TERRYNAMIC` 走隧道时仍显示 `wifi:`。看日志 / `transport=`。
- **会话丢失后重拨不稳定。** `device gone` 后代理再现：现场一次 ~3 s 重拨，第二次丢失后闲置 6+ min，直到重启 App。重启总是约 **2.5 s** 连上。
- **App 可能剪掉 `wifiRemembered`。** 一次 churn 窗口里 `BUILD.TERRYNAMIC` 从列表消失，机制不明。
- **`usbDisabled` 是真的。** 约 3 次 usb 拨号失败后写入 `usb:first`，之后 Manual 静默不拨。Helper 写 Manual defaults 时清掉它。
- **`cursorPort` 只对非 loopback peer 广告。** 光标 UDP（9001）需要 WiFi 起源会话（已测可用，含已迁移会话——WiFi 仍在）。

下一任不要把「USB 代理会话拔线后应回 WiFi」写成接收端 bug；那是库存 App 的记忆名窗口。
