package com.terrynamic.opendisplay

import android.app.Application
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.terrynamic.opendisplay.link.LinkPolicy
import com.terrynamic.opendisplay.cursor.CursorChannel
import com.terrynamic.opendisplay.input.PencilMapper
import com.terrynamic.opendisplay.cursor.CursorUdpSocket
import com.terrynamic.opendisplay.protocol.ClockOffset
import com.terrynamic.opendisplay.protocol.ControlMessages
import com.terrynamic.opendisplay.protocol.InboundControl
import com.terrynamic.opendisplay.protocol.LatencyStats
import com.terrynamic.opendisplay.protocol.PanelReadyGate
import com.terrynamic.opendisplay.protocol.SenderHealth
import com.terrynamic.opendisplay.protocol.StatsSnapshot
import com.terrynamic.opendisplay.protocol.WireMessage
import com.terrynamic.opendisplay.protocol.WireProtocol
import com.terrynamic.opendisplay.transport.TransportBuffering
import com.terrynamic.opendisplay.service.NsdAdvertiser
import com.terrynamic.opendisplay.transport.Demux
import com.terrynamic.opendisplay.transport.FrameKind
import com.terrynamic.opendisplay.transport.FramedConnection
import com.terrynamic.opendisplay.transport.OutboundKind
import com.terrynamic.opendisplay.transport.OutboundMessage
import com.terrynamic.opendisplay.transport.ReceiverListener
import com.terrynamic.opendisplay.video.DecodeCeilingResolver
import com.terrynamic.opendisplay.video.Decoder
import com.terrynamic.opendisplay.video.KeyframeRequestAction
import com.terrynamic.opendisplay.video.KeyframeRequestThrottle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

class ReceiverController(private val app: Application) {
    private val store = SettingsStore(app)
    private val installId = InstallId.get(app)
    private val controlThread = HandlerThread("od-control").apply { start() }
    private val handler = Handler(controlThread.looper)
    private val linkPolicy = LinkPolicy()
    private val clock = ClockOffset()
    private val kfThrottle = KeyframeRequestThrottle()
    private val unknownTypes = mutableSetOf<String>()
    private val nsd = NsdAdvertiser(app) { name ->
        handler.post { _state.update { it.copy(registeredName = name) } }
    }

    private val decoder = Decoder(
        onVideoSize = { w, h ->
            _state.update { it.copy(videoWidth = w, videoHeight = h) }
            handler.post { publishOverlay() }
        },
        onNeedKeyframe = { reason -> handler.post { requestKeyframe(reason) } },
        onRendered = { frame ->
            framesThisWindow.incrementAndGet()
            val offset = clock.offsetMs
            val e2e = if (frame.capMs != null && offset != null) {
                frame.renderedWallMs - (frame.capMs - offset)
            } else {
                null
            }
            latency.record(
                e2eMs = e2e,
                phMs = (frame.renderedElapsedMs - frame.arrivedElapsedMs).toDouble(),
                decMs = (frame.renderedElapsedMs - frame.queuedElapsedMs).toDouble(),
            )
        },
        onConfigured = { w, h ->
            Log.i(WireProtocol.LOG_TAG, "decoder configured / waiting for IDR ${w}x${h}")
        },
    )

    private var listener: ReceiverListener? = null
    private var sessionGeneration = 0L
    private val cursorChannel = CursorChannel()
    private var cursorUdp: CursorUdpSocket? = null
    private var lastAdvertisedAddrs: List<String> = emptyList()
    private var lastHelloWide = 0
    private var lastHelloHigh = 0
    private val panel = PanelReadyGate()
    private var lastPingAt = 0L
    private var lastStatsAt = 0L
    private var windowStartMs = 0L
    private val framesThisWindow = AtomicInteger(0)
    private val bytesThisWindow = AtomicLong(0)
    private val latency = LatencyStats()
    private var lastOverlay = StatsSnapshot(transport = "wifi")
    private var senderHealth: SenderHealth? = null
    private var listeningEnabled = false
    private var asleep = false
    private var stopped = false
    @Volatile
    private var senderPv = WireProtocol.ASSUMED_WHEN_ABSENT

    private val _state = MutableStateFlow(
        UiState(
            installId = installId,
            installIdShort = InstallId.shortId(installId),
            settings = store.load(),
            serviceName = store.load().serviceName,
            registeredName = store.load().serviceName,
            showStats = store.load().showStats,
        ),
    )
    val uiState: StateFlow<UiState> = _state.asStateFlow()

    val snapshot: UiState get() = _state.value

    private val livenessTick = object : Runnable {
        override fun run() {
            if (!listeningEnabled || asleep) return
            val now = SystemClock.elapsedRealtime()
            if (listener?.liveConnection != null) {
                if (now - lastPingAt >= WireProtocol.PING_INTERVAL_MS) {
                    lastPingAt = now
                    listener?.sendControl(
                        OutboundKind.PING,
                        ControlMessages.ping(System.currentTimeMillis().toDouble()),
                    )
                }
                if (now - lastStatsAt >= WireProtocol.STATS_INTERVAL_MS) {
                    lastStatsAt = now
                    sendStats()
                }
                val link = linkPolicy.snapshot()
                if (link.addrs != lastAdvertisedAddrs) {
                    lastAdvertisedAddrs = link.addrs
                    sendHello()
                    _state.update { it.copy(usbHelperActive = link.helperActive) }
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    fun startListening() {
        handler.post {
            if (stopped || asleep) return@post
            listeningEnabled = true
            maybeStartListenerLocked()
            handler.removeCallbacks(livenessTick)
            handler.post(livenessTick)
        }
    }

    fun sleepSession() {
        handler.post {
            asleep = true
            listener?.sendControl(OutboundKind.SLEEPING, ControlMessages.sleeping())
            stopListenerLocked(sendClosing = false)
            _state.update { it.copy(status = "Sleeping — waiting for unlock", phase = ReceiverPhase.IDLE) }
        }
    }

    fun resumeFromSleep() {
        handler.post {
            asleep = false
            if (listeningEnabled) maybeStartListenerLocked()
            _state.update {
                it.copy(
                    status = if (panel.isReady) "Listening on :${WireProtocol.DEFAULT_PORT}"
                    else "Waiting for panel size",
                )
            }
        }
    }

    fun shutdown(sendClosing: Boolean) {
        handler.post {
            if (stopped) return@post
            stopped = true
            listeningEnabled = false
            asleep = false
            if (sendClosing) {
                listener?.sendControl(OutboundKind.CLOSING, ControlMessages.closing())
            }
            stopListenerLocked(sendClosing = false)
            decoder.release()
            nsd.stop()
            controlThread.quitSafely()
        }
    }

    fun updatePanel(width: Int, height: Int, scale: Float, smallestWidthDp: Int) {
        handler.post {
            val changed = panel.update(width, height, scale, smallestWidthDp)
            if (!panel.isReady) return@post
            refreshAnnouncedGeometry()
            maybeStartListenerLocked()
            if (changed && listener?.liveConnection != null) {
                sendHello()
            }
        }
    }

    fun attachSurface(surface: Surface?) {
        decoder.attachSurface(surface)
        if (surface != null) handler.post { requestKeyframe(null) }
    }

    fun sendTouch(phase: String, x: Double, y: Double) {
        val t = clock.stampSenderClock(System.currentTimeMillis().toDouble())
        val kind = when (phase) {
            "moved" -> OutboundKind.TOUCH_MOVED
            "ended" -> OutboundKind.TOUCH_ENDED
            "cancelled" -> OutboundKind.TOUCH_CANCELLED
            else -> OutboundKind.TOUCH_BEGAN
        }
        listener?.sendControl(kind, ControlMessages.touch(phase, x, y, t))
    }

    fun sendScroll(dx: Double, dy: Double) {
        listener?.sendControl(OutboundKind.SCROLL, ControlMessages.scroll(dx, dy))
    }

    fun senderSupportsPencil(): Boolean = PencilMapper.supportsPencil(senderPv)

    fun sendPencil(
        phase: String,
        x: Double,
        y: Double,
        pressure: Double,
        azimuth: Double,
        altitude: Double,
    ) {
        val t = clock.stampSenderClock(System.currentTimeMillis().toDouble())
        val kind = if (phase == "move" || phase == "hover") OutboundKind.PENCIL_MOVE else OutboundKind.PENCIL
        listener?.sendControl(
            kind,
            ControlMessages.pencil(phase, x, y, pressure, azimuth, altitude, macClockMs = t),
        )
    }

    fun sendProximity(entering: Boolean, x: Double, y: Double) {
        listener?.sendControl(OutboundKind.PROXIMITY, ControlMessages.proximity(entering, x, y))
    }

    fun updateSettings(settings: AppSettings) {
        handler.post { applySettingsLocked(settings) }
    }

    fun applyShellPatch(patch: SettingsPatch): Boolean {
        if (patch.isEmpty) return false
        handler.post {
            applySettingsLocked(patch.applyTo(store.load()))
        }
        return true
    }

    private fun applySettingsLocked(settings: AppSettings) {
        val previous = _state.value.settings
        store.save(settings)
        val renamed = settings.serviceName != previous.serviceName
        val helloChanged = settings.decodeCeiling != previous.decodeCeiling ||
            settings.virtualDesktop != previous.virtualDesktop
        val cursorChanged = settings.cursorUdp != previous.cursorUdp
        _state.update {
            it.copy(
                settings = settings,
                serviceName = settings.serviceName,
                showStats = settings.showStats,
            )
        }
        refreshAnnouncedGeometry()
        publishOverlay()
        if (renamed && listeningEnabled && !asleep) {
            nsd.rename(settings.serviceName)
        }
        if (cursorChanged && !settings.cursorUdp) {
            stopCursorUdp()
        }
        if ((helloChanged || cursorChanged) && listener?.liveConnection != null) {
            sendHello()
        }
    }

    fun onHelperHeartbeat(port: Int, helperVersion: String?, ttlMs: Int) {
        handler.post {
            val changed = linkPolicy.onHeartbeat(port, helperVersion, ttlMs)
            val snap = linkPolicy.snapshot()
            _state.update { it.copy(usbHelperActive = snap.helperActive) }
            if (changed && listener?.liveConnection != null) {
                lastAdvertisedAddrs = snap.addrs
                sendHello()
            }
        }
    }

    fun helloJson(peer: InetAddress? = null): JSONObject {
        if (!panel.isReady) {
            val ready = PanelReadyGate.awaitReady(
                isReady = { panel.isReady },
                timeoutMs = PanelReadyGate.HELLO_WAIT_MS,
                nowMs = { SystemClock.elapsedRealtime() },
                sleepMs = { slice -> Thread.sleep(slice) },
            )
            if (!ready) {
                throw IllegalStateException("panel size unknown — refusing placeholder hello")
            }
        }
        val settings = _state.value.settings
        val factor = settings.virtualDesktop.factor
        val wide = even((panel.wide * factor).roundToInt())
        val high = even((panel.high * factor).roundToInt())
        val ceiling = DecodeCeilingResolver.resolve(
            settings.decodeCeiling,
            panel.wide,
            panel.high,
            settings.virtualDesktop.factor,
        )
        val addrs = linkPolicy.addrs()
        lastHelloWide = wide
        lastHelloHigh = high
        lastAdvertisedAddrs = addrs
        publishAnnouncedGeometry(wide, high)
        val loopback = peer?.isLoopbackAddress == true
        val boundPort = if (settings.cursorUdp && !loopback && peer != null) {
            ensureCursorUdp()
        } else {
            null
        }
        val cursorPort = CursorChannel.advertisedPort(settings.cursorUdp, loopback, boundPort)
        return ControlMessages.hello(
            pixelsWide = wide,
            pixelsHigh = high,
            scale = panel.scale,
            device = if (panel.smallestWidthDp >= 600) "AndroidTablet" else "Android",
            installId = installId,
            maxEncodeWide = ceiling?.wide,
            maxEncodeHigh = ceiling?.high,
            addrs = addrs,
            cursorPort = cursorPort,
        )
    }

    private fun maybeStartListenerLocked() {
        if (!PanelReadyGate.shouldBindListener(listeningEnabled, panel.isReady, listener != null)) {
            return
        }
        startListenerLocked()
    }

    private fun startListenerLocked() {
        if (listener != null) return
        val current = ReceiverListener(
            helloProvider = { peer -> helloJson(peer) },
            onSession = object : ReceiverListener.SessionCallbacks {
                override fun onAdopted(
                    connection: FramedConnection,
                    generation: Long,
                    initialBytes: ByteArray,
                    transport: String,
                ): (ByteArray) -> Unit {
                    adoptSession(connection, generation, transport)
                    return { payload ->
                        bytesThisWindow.addAndGet(payload.size.toLong())
                        handleFrame(payload)
                    }
                }

                override fun onSessionClosed(generation: Long) {
                    handler.post { handleClosed(generation) }
                }

                override fun onListening(bound: Boolean) {
                    _state.update {
                        it.copy(
                            listening = bound,
                            status = if (bound) "Listening on :${WireProtocol.DEFAULT_PORT}" else "Stopped",
                        )
                    }
                }

                override fun onWatchdog() {
                    Log.i(WireProtocol.LOG_TAG, "watchdog")
                }
            },
        )
        listener = current
        current.start()
        nsd.start(_state.value.serviceName, installId, WireProtocol.DEFAULT_PORT)
    }

    private fun stopListenerLocked(sendClosing: Boolean) {
        if (sendClosing) {
            listener?.sendControl(OutboundKind.CLOSING, ControlMessages.closing())
        }
        listener?.stop()
        listener = null
        nsd.stop()
        decoder.reset()
        clock.reset()
        kfThrottle.invalidateAll()
        stopCursorUdp()
        resetCursor()
        sessionGeneration = 0
        _state.update {
            it.copy(
                phase = if (it.updateMessage != null) ReceiverPhase.BLOCKED else ReceiverPhase.IDLE,
                listening = false,
                transport = null,
                videoWidth = 0,
                videoHeight = 0,
            )
        }
    }

    private fun adoptSession(connection: FramedConnection, generation: Long, transport: String) {
        sessionGeneration = generation
        senderPv = WireProtocol.ASSUMED_WHEN_ABSENT
        cursorChannel.resetSession()
        clock.reset()
        latency.reset()
        kfThrottle.invalidateAll()
        unknownTypes.clear()
        decoder.reset()
        decoder.setQueueDepth(TransportBuffering.queueFrames(transport))
        resetCursor()
        lastPingAt = 0
        lastStatsAt = SystemClock.elapsedRealtime()
        windowStartMs = lastStatsAt
        framesThisWindow.set(0)
        bytesThisWindow.set(0)
        senderHealth = null
        lastOverlay = StatsSnapshot(transport = transport)
        publishOverlay()
        _state.update {
            it.copy(
                phase = if (it.updateMessage != null) ReceiverPhase.BLOCKED else ReceiverPhase.STREAMING,
                status = "Connected ($transport)",
                transport = transport,
                senderTooOld = false,
            )
        }
        Log.i(WireProtocol.LOG_TAG, "session adopted from ${connection.remoteAddress?.hostAddress} ($transport)")
    }

    private fun handleClosed(generation: Long) {
        if (generation != sessionGeneration && sessionGeneration != 0L) return
        decoder.reset()
        clock.reset()
        resetCursor()
        _state.update {
            it.copy(
                phase = if (it.updateMessage != null) ReceiverPhase.BLOCKED else ReceiverPhase.IDLE,
                status = if (it.listening) "Listening on :${WireProtocol.DEFAULT_PORT}" else "Stopped",
                transport = null,
                videoWidth = 0,
                videoHeight = 0,
                statsText = null,
            )
        }
    }

    private fun handleFrame(payload: ByteArray) {
        when (Demux.classify(payload)) {
            FrameKind.CONTROL -> handleControl(payload)
            FrameKind.VIDEO -> decoder.offerAccessUnit(payload)
        }
    }

    private fun handleControl(payload: ByteArray) {
        val obj = InboundControl.parse(payload) ?: return
        when (val type = obj.optString("type")) {
            WireMessage.PONG -> {
                val t1 = obj.optDouble("t", Double.NaN)
                val mt = obj.optDouble("mt", Double.NaN)
                if (t1.isFinite() && mt.isFinite()) {
                    clock.onPong(t1, mt, System.currentTimeMillis().toDouble())
                }
            }
            WireMessage.PING -> {
                senderHealth = SenderHealth.fromPing(obj)
            }
            WireMessage.WELCOME -> {
                val welcome = InboundControl.welcome(obj) ?: return
                senderPv = welcome.pv
                if (welcome.pv < WireProtocol.MIN_SUPPORTED_PEER) {
                    _state.update {
                        it.copy(
                            senderTooOld = true,
                            status = "Update the Mac app to continue",
                        )
                    }
                }
            }
            WireMessage.UPDATE_REQUIRED -> {
                val required = InboundControl.updateRequired(obj)
                _state.update {
                    it.copy(
                        phase = ReceiverPhase.BLOCKED,
                        updateMessage = required.message,
                        status = required.message,
                    )
                }
            }
            WireMessage.CURSOR -> {
                val pos = InboundControl.cursorPosition(obj) ?: return
                val decision = cursorChannel.onTcp(pos.sequence)
                if (!decision.apply) return
                applyCursor(pos.x, pos.y, pos.visible)
            }
            WireMessage.CURSOR_IMG -> {
                val image = InboundControl.cursorImage(obj) ?: return
                val bitmap = BitmapFactory.decodeByteArray(image.png, 0, image.png.size) ?: return
                _state.update {
                    it.copy(
                        cursor = it.cursor.copy(
                            bitmap = bitmap,
                            normalizedWidth = image.normalizedWidth,
                            normalizedHeight = image.normalizedHeight,
                            anchorX = image.anchorX,
                            anchorY = image.anchorY,
                        ),
                    )
                }
            }
            else -> InboundControl.logUnknownTypeOnce(unknownTypes, type)
        }
    }

    private fun requestKeyframe(reason: String?) {
        val generation = sessionGeneration
        if (generation == 0L) return
        when (val action = kfThrottle.request(generation, SystemClock.elapsedRealtime())) {
            KeyframeRequestAction.SendNow -> {
                listener?.sendControl(OutboundKind.KEYFRAME, ControlMessages.keyframeRequest(reason))
            }
            is KeyframeRequestAction.RetryAfter -> {
                handler.postDelayed({
                    when (kfThrottle.retry(generation, SystemClock.elapsedRealtime())) {
                        KeyframeRequestAction.SendNow ->
                            listener?.sendControl(OutboundKind.KEYFRAME, ControlMessages.keyframeRequest(reason))
                        is KeyframeRequestAction.RetryAfter -> Unit
                        else -> Unit
                    }
                }, action.delayMs)
            }
            else -> Unit
        }
    }

    private fun sendHello() {
        val peer = listener?.liveConnection?.connection?.remoteAddress
        val json = helloJson(peer)
        listener?.sendControl(OutboundKind.HELLO, json)
        val wide = json.optInt("pixelsWide")
        val high = json.optInt("pixelsHigh")
        val encodeW = json.optInt("maxEncodeWide", -1)
        val encodeH = json.optInt("maxEncodeHigh", -1)
        val cursorPort = json.optInt("cursorPort", -1)
        Log.i(
            WireProtocol.LOG_TAG,
            "hello sent ${wide}x${high}" + if (cursorPort > 0) " cursorPort=$cursorPort" else "",
        )
        if (encodeW > 0 && encodeH > 0 && (encodeW < wide || encodeH < high)) {
            Log.i(WireProtocol.LOG_TAG, "stream capped at ${encodeW}x${encodeH}")
        }
    }

    private fun sendStats() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = ((now - windowStartMs).coerceAtLeast(1)).toDouble() / 1000.0
        val frames = framesThisWindow.getAndSet(0)
        val bytes = bytesThisWindow.getAndSet(0)
        windowStartMs = now
        val fps = frames / elapsed
        val mbps = (bytes * 8.0) / elapsed / 1_000_000.0
        val offsetKnown = clock.offsetMs != null
        val latencySnap = latency.snapshot(offsetKnown)
        val transport = _state.value.transport ?: "wifi"
        val health = senderHealth
        val state = _state.value
        lastOverlay = StatsSnapshot(
            transport = transport,
            fps = fps,
            mbps = mbps,
            e2e50 = latencySnap.e2e50,
            e2e95 = latencySnap.e2e95,
            ph50 = latencySnap.ph50,
            ph95 = latencySnap.ph95,
            dec50 = latencySnap.dec50,
            stalls = decoder.stallCount,
            queue = decoder.queuedFrames,
            drops = decoder.dropCount,
            offsetKnown = offsetKnown,
            streamWide = state.videoWidth,
            streamHigh = state.videoHeight,
            desktopPtWide = state.desktopPtWide,
            desktopPtHigh = state.desktopPtHigh,
            capFps = health?.capFps,
            encDrops = health?.encDrops,
            netDrops = health?.netDrops,
            pending = health?.pending,
            codecName = decoder.codecName,
            lowLatency = decoder.lowLatency,
        )
        publishOverlay()
        Log.i(
            WireProtocol.LOG_TAG,
            "stats transport=$transport fps=${"%.1f".format(fps)} mbps=${"%.2f".format(mbps)} " +
                "dec50=${"%.1f".format(latencySnap.dec50)} ph50=${"%.1f".format(latencySnap.ph50)} " +
                "offsetKnown=$offsetKnown e2e50=${latencySnap.e2e50} e2e95=${latencySnap.e2e95} " +
                "codecName=${decoder.codecName} lowLatency=${decoder.lowLatency}",
        )
        listener?.sendControl(
            OutboundKind.STATS,
            ControlMessages.stats(
                transport = transport,
                fps = fps,
                mbps = mbps,
                ph50 = latencySnap.ph50,
                ph95 = latencySnap.ph95,
                dec50 = latencySnap.dec50,
                stalls = decoder.stallCount,
                queue = decoder.queuedFrames,
                drops = decoder.dropCount,
                offsetKnown = offsetKnown,
                e2e50 = latencySnap.e2e50,
                e2e95 = latencySnap.e2e95,
                cursorUpdates = cursorChannel.cursorUpdates,
                cursorLost = cursorChannel.cursorLost,
                codecName = decoder.codecName,
                lowLatency = decoder.lowLatency,
            ),
        )
    }

    private fun applyCursor(x: Float, y: Float, visible: Boolean) {
        _state.update {
            it.copy(cursor = it.cursor.copy(x = x, y = y, visible = visible))
        }
    }

    @Synchronized
    private fun ensureCursorUdp(): Int? {
        cursorUdp?.boundPort?.let { return it }
        val socket = CursorUdpSocket { from, bytes ->
            handler.post { handleCursorDatagram(from, bytes) }
        }
        val port = socket.start() ?: return null
        cursorUdp = socket
        return port
    }

    @Synchronized
    private fun stopCursorUdp() {
        cursorUdp?.stop()
        cursorUdp = null
    }

    private fun handleCursorDatagram(from: InetSocketAddress, bytes: ByteArray) {
        val obj = InboundControl.parse(bytes) ?: return
        if (obj.optString("type") != WireMessage.CURSOR) return
        val pos = InboundControl.cursorPosition(obj) ?: return
        val seq = pos.sequence ?: return
        val host = from.address?.hostAddress ?: from.hostString ?: return
        val decision = cursorChannel.onUdp(host, from.port, seq)
        if (decision.sendAck) {
            listener?.sendControl(OutboundKind.CURSOR_ACK, ControlMessages.cursorAck())
            Log.i(WireProtocol.LOG_TAG, "cursorAck sent")
        }
        if (decision.apply) {
            applyCursor(pos.x, pos.y, pos.visible)
        }
    }

    private fun refreshAnnouncedGeometry() {
        if (!panel.isReady) return
        val factor = _state.value.settings.virtualDesktop.factor
        val wide = even((panel.wide * factor).roundToInt())
        val high = even((panel.high * factor).roundToInt())
        publishAnnouncedGeometry(wide, high)
    }

    private fun publishAnnouncedGeometry(wide: Int, high: Int) {
        _state.update {
            it.copy(
                helloWide = wide,
                helloHigh = high,
                desktopPtWide = wide / 2,
                desktopPtHigh = high / 2,
            )
        }
        lastOverlay = lastOverlay.copy(
            desktopPtWide = wide / 2,
            desktopPtHigh = high / 2,
            streamWide = _state.value.videoWidth,
            streamHigh = _state.value.videoHeight,
        )
    }

    private fun publishOverlay() {
        val state = _state.value
        val overlay = lastOverlay.copy(
            transport = state.transport ?: lastOverlay.transport,
            streamWide = state.videoWidth,
            streamHigh = state.videoHeight,
            desktopPtWide = state.desktopPtWide,
            desktopPtHigh = state.desktopPtHigh,
            capFps = senderHealth?.capFps ?: lastOverlay.capFps,
            encDrops = senderHealth?.encDrops ?: lastOverlay.encDrops,
            netDrops = senderHealth?.netDrops ?: lastOverlay.netDrops,
            pending = senderHealth?.pending ?: lastOverlay.pending,
            codecName = decoder.codecName ?: lastOverlay.codecName,
            lowLatency = decoder.lowLatency,
        )
        lastOverlay = overlay
        _state.update { it.copy(statsText = overlay.overlayText()) }
    }

    private fun resetCursor() {
        cursorChannel.resetSession()
        _state.update { it.copy(cursor = CursorUi()) }
    }

    private fun even(value: Int): Int {
        val aligned = value and 1.inv()
        return if (aligned < 16) 16 else aligned
    }
}
