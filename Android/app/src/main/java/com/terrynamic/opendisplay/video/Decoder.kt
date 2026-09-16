package com.terrynamic.opendisplay.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.terrynamic.opendisplay.protocol.TelemetryPrefixParser
import com.terrynamic.opendisplay.protocol.WireProtocol
import com.terrynamic.opendisplay.transport.TransportBuffering
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class RenderedFrame(
    val arrivedElapsedMs: Long,
    val queuedElapsedMs: Long,
    val renderedElapsedMs: Long,
    val renderedWallMs: Long,
    val capMs: Long?,
)

class Decoder(
    private val onVideoSize: (width: Int, height: Int) -> Unit,
    private val onNeedKeyframe: (reason: String?) -> Unit,
    private val onRendered: (RenderedFrame) -> Unit = {},
    private val onConfigured: (width: Int, height: Int) -> Unit = { _, _ -> },
) {
    private val callbackThread = HandlerThread("od-decoder").apply { start() }
    private val handler = Handler(callbackThread.looper)
    private val lock = Any()
    private val released = AtomicBoolean(false)

    private val queue = ArrayDeque<QueuedAu>()
    private var queuedBytes = 0
    private var droppingUntilIdr = false

    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var configured = false
    private var awaitingIdr = true
    private var presentationIndex = 0L
    private var videoWidth = 0
    private var videoHeight = 0
    private val stalls = AtomicInteger(0)
    private val drops = AtomicInteger(0)
    private val freeInputs = ArrayDeque<Int>()
    private val inFlight = ArrayDeque<QueuedAu>()
    @Volatile
    private var maxQueueFrames = TransportBuffering.WIFI_QUEUE_FRAMES

    val queuedFrames: Int get() = synchronized(lock) { queue.size }
    val stallCount: Int get() = stalls.get()
    val dropCount: Int get() = drops.get()

    @Volatile
    var codecName: String? = null
        private set

    @Volatile
    var lowLatency: Boolean = false
        private set

    fun setQueueDepth(frames: Int) {
        maxQueueFrames = frames.coerceAtLeast(1)
    }

    fun attachSurface(newSurface: Surface?) {
        handler.post {
            val changed = surface !== newSurface
            surface = newSurface
            if (newSurface == null || !newSurface.isValid) {
                stopCodecLocked()
                awaitingIdr = true
                return@post
            }
            if (changed && configured) {
                stopCodecLocked()
                awaitingIdr = true
            }
            if (sps != null && pps != null) {
                configureLocked()
                onNeedKeyframe(null)
            }
        }
    }

    fun reset() {
        if (released.get()) return
        if (Looper.myLooper() == handler.looper) {
            resetOnWorker()
            return
        }
        val done = CountDownLatch(1)
        handler.post {
            try {
                resetOnWorker()
            } finally {
                done.countDown()
            }
        }
        try {
            if (!done.await(2, TimeUnit.SECONDS)) {
                Log.w(WireProtocol.LOG_TAG, "decoder reset timed out")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun resetOnWorker() {
        synchronized(lock) {
            queue.clear()
            queuedBytes = 0
            droppingUntilIdr = false
            inFlight.clear()
        }
        stopCodecLocked()
        sps = null
        pps = null
        awaitingIdr = true
        videoWidth = 0
        videoHeight = 0
        presentationIndex = 0
        stalls.set(0)
        drops.set(0)
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        handler.post {
            synchronized(lock) {
                queue.clear()
                queuedBytes = 0
            }
            stopCodecLocked()
            surface = null
            callbackThread.quitSafely()
        }
    }

    fun offerAccessUnit(payload: ByteArray) {
        if (released.get() || payload.isEmpty()) return
        val scan = AnnexBScanner.scan(payload)
        if (scan.firstStartCode < 0) return

        var spsChanged = false
        var ppsChanged = false
        for (nalu in scan.nalus) {
            when (nalu.type) {
                7 -> {
                    val bytes = payload.copyOfRange(nalu.start, nalu.end)
                    if (sps == null || !sps.contentEquals(bytes)) {
                        sps = bytes
                        spsChanged = true
                        SpsParser.parseDimensions(bytes)?.let { publishSize(it.first, it.second) }
                    }
                }
                8 -> {
                    val bytes = payload.copyOfRange(nalu.start, nalu.end)
                    if (pps == null || !pps.contentEquals(bytes)) {
                        pps = bytes
                        ppsChanged = true
                    }
                }
            }
        }

        if (spsChanged || ppsChanged) {
            handler.post {
                awaitingIdr = true
                configureLocked()
            }
        }

        val video = if (scan.firstStartCode == 0) payload else payload.copyOfRange(scan.firstStartCode, payload.size)
        val isIdr = scan.hasIdr
        val telemetry = TelemetryPrefixParser.parse(payload, scan.firstStartCode)
        val overflow = synchronized(lock) {
            if (droppingUntilIdr && !isIdr) {
                drops.incrementAndGet()
                return
            }
            if (droppingUntilIdr && isIdr) {
                queue.clear()
                queuedBytes = 0
                droppingUntilIdr = false
            }
            val wouldOverflow = queue.size >= maxQueueFrames ||
                queuedBytes + video.size > TransportBuffering.QUEUE_BYTES_CAP
            if (wouldOverflow && !isIdr) {
                queue.clear()
                queuedBytes = 0
                droppingUntilIdr = true
                drops.incrementAndGet()
                true
            } else {
                if (wouldOverflow) {
                    queue.clear()
                    queuedBytes = 0
                }
                queue.addLast(
                    QueuedAu(
                        bytes = video,
                        isIdr = isIdr,
                        arrivedElapsedMs = android.os.SystemClock.elapsedRealtime(),
                        capMs = telemetry?.capMs,
                    ),
                )
                queuedBytes += video.size
                false
            }
        }
        if (overflow) {
            stalls.incrementAndGet()
            onNeedKeyframe(KeyframeRequestThrottle.BACKPRESSURE)
        }
        handler.post { feedLocked() }
    }

    private fun configureLocked() {
        val surface = this.surface
        val sps = this.sps
        val pps = this.pps
        if (surface == null || !surface.isValid || sps == null || pps == null) {
            Log.i(WireProtocol.LOG_TAG, "codec wait: surface=${surface != null} sps=${sps != null} pps=${pps != null}")
            return
        }
        stopCodecLocked()
        awaitingIdr = true
        val dims = SpsParser.parseDimensions(sps)
        val width = dims?.first ?: videoWidth.takeIf { it > 0 } ?: 1920
        val height = dims?.second ?: videoHeight.takeIf { it > 0 } ?: 1080
        publishSize(width, height)
        var newCodec: MediaCodec? = null
        try {
            val format = baseVideoFormat(width, height, sps, pps)
            newCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val name = newCodec.name
            val lowLatencyFeature = lowLatencyFeatureSupported(newCodec)
            Log.i(WireProtocol.LOG_TAG, "decoder $name FEATURE_LowLatency=$lowLatencyFeature")
            val qcom = QualcommDecoderHints.matches(name)
            if (qcom) {
                QualcommDecoderHints.apply(format)
            }
            newCodec.setCallback(callback, handler)
            try {
                newCodec.configure(format, surface, null, 0)
            } catch (e: Exception) {
                if (!qcom) throw e
                Log.w(
                    WireProtocol.LOG_TAG,
                    "Qualcomm vendor keys rejected (${e.javaClass.name}: ${e.message}); " +
                        "retrying with KEY_LOW_LATENCY only",
                )
                try {
                    newCodec.release()
                } catch (_: Exception) {
                }
                newCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                newCodec.setCallback(callback, handler)
                newCodec.configure(baseVideoFormat(width, height, sps, pps), surface, null, 0)
            }
            newCodec.start()
            codec = newCodec
            codecName = name
            lowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            configured = true
            freeInputs.clear()
            Log.i(
                WireProtocol.LOG_TAG,
                "MediaCodec configured ${width}x${height} codec=$name lowLatency=$lowLatency",
            )
            onConfigured(width, height)
        } catch (e: Exception) {
            Log.e(WireProtocol.LOG_TAG, "MediaCodec configure failed: ${e.message}", e)
            try {
                newCodec?.release()
            } catch (_: Exception) {
            }
            configured = false
            lowLatency = false
            onNeedKeyframe(null)
        }
    }

    private fun baseVideoFormat(width: Int, height: Int, sps: ByteArray, pps: ByteArray): MediaFormat {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps)))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(pps)))
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, 120)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        return format
    }

    private fun lowLatencyFeatureSupported(codec: MediaCodec): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            codec.codecInfo
                .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        } catch (_: Exception) {
            false
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (this@Decoder.codec !== codec) {
                try {
                    codec.queueInputBuffer(index, 0, 0, 0, 0)
                } catch (_: Exception) {
                }
                return
            }
            freeInputs.addLast(index)
            feedLocked()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (this@Decoder.codec !== codec) {
                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (_: Exception) {
                }
                return
            }
            try {
                codec.releaseOutputBuffer(index, true)
                val au = synchronized(lock) { if (inFlight.isNotEmpty()) inFlight.removeFirst() else null }
                val nowElapsed = android.os.SystemClock.elapsedRealtime()
                onRendered(
                    RenderedFrame(
                        arrivedElapsedMs = au?.arrivedElapsedMs ?: nowElapsed,
                        queuedElapsedMs = au?.queuedElapsedMs ?: nowElapsed,
                        renderedElapsedMs = nowElapsed,
                        renderedWallMs = System.currentTimeMillis(),
                        capMs = au?.capMs,
                    ),
                )
            } catch (e: Exception) {
                Log.e(WireProtocol.LOG_TAG, "output release failed: ${e.message}", e)
                rebuild("output error")
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(WireProtocol.LOG_TAG, "MediaCodec error: ${e.diagnosticInfo}", e)
            rebuild("codec error")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            val codedW = format.getInteger(MediaFormat.KEY_WIDTH)
            val codedH = format.getInteger(MediaFormat.KEY_HEIGHT)
            val parsed = sps?.let { SpsParser.parseDimensions(it) }
            val w = visible(format, codedW, MediaFormat.KEY_CROP_LEFT, MediaFormat.KEY_CROP_RIGHT)
                ?: parsed?.first ?: codedW
            val h = visible(format, codedH, MediaFormat.KEY_CROP_TOP, MediaFormat.KEY_CROP_BOTTOM)
                ?: parsed?.second ?: codedH
            if (w > 0 && h > 0) {
                publishSize(w, h)
                Log.i(WireProtocol.LOG_TAG, "output format: ${w}x${h}")
            }
        }
    }

    private fun feedLocked() {
        val current = codec ?: return
        while (freeInputs.isNotEmpty()) {
            val au = synchronized(lock) {
                val next = queue.peekFirst() ?: return
                if (awaitingIdr && !next.isIdr) {
                    queue.removeFirst()
                    queuedBytes -= next.bytes.size
                    null
                } else {
                    queue.removeFirst()
                    queuedBytes -= next.bytes.size
                    next
                }
            } ?: continue
            val index = freeInputs.removeFirst()
            try {
                val buffer = current.getInputBuffer(index)
                if (buffer == null) {
                    freeInputs.addFirst(index)
                    return
                }
                buffer.clear()
                if (au.bytes.size > buffer.capacity()) {
                    Log.w(WireProtocol.LOG_TAG, "AU ${au.bytes.size} exceeds input ${buffer.capacity()}")
                    current.queueInputBuffer(index, 0, 0, 0, 0)
                    onNeedKeyframe(null)
                    continue
                }
                buffer.put(au.bytes)
                val pts = presentationIndex++ * 16_667L
                au.queuedElapsedMs = android.os.SystemClock.elapsedRealtime()
                current.queueInputBuffer(index, 0, au.bytes.size, pts, 0)
                synchronized(lock) { inFlight.addLast(au) }
                if (au.isIdr && awaitingIdr) {
                    awaitingIdr = false
                    Log.i(WireProtocol.LOG_TAG, "decoder synchronized on IDR")
                }
            } catch (e: Exception) {
                Log.e(WireProtocol.LOG_TAG, "input queue failed: ${e.message}", e)
                rebuild("input error")
                return
            }
        }
    }

    private fun rebuild(reason: String) {
        handler.post {
            Log.w(WireProtocol.LOG_TAG, "$reason — rebuilding decoder and requesting keyframe")
            awaitingIdr = true
            configureLocked()
            onNeedKeyframe(null)
        }
    }

    private fun stopCodecLocked() {
        val current = codec
        codec = null
        configured = false
        freeInputs.clear()
        synchronized(lock) { inFlight.clear() }
        if (current != null) {
            try {
                current.stop()
            } catch (_: Exception) {
            }
            try {
                current.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun publishSize(width: Int, height: Int) {
        if (width == videoWidth && height == videoHeight) return
        videoWidth = width
        videoHeight = height
        onVideoSize(width, height)
    }

    private fun visible(format: MediaFormat, coded: Int, startKey: String, endKey: String): Int? {
        if (!format.containsKey(startKey) || !format.containsKey(endKey)) return null
        val start = format.getInteger(startKey)
        val end = format.getInteger(endKey)
        return (end - start + 1).takeIf { start >= 0 && end >= start && it <= coded }
    }

    private data class QueuedAu(
        val bytes: ByteArray,
        val isIdr: Boolean,
        val arrivedElapsedMs: Long,
        val capMs: Long?,
        var queuedElapsedMs: Long = 0,
    )

    companion object {
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        private fun withStartCode(nalu: ByteArray): ByteArray {
            val out = ByteArray(4 + nalu.size)
            System.arraycopy(START_CODE, 0, out, 0, 4)
            System.arraycopy(nalu, 0, out, 4, nalu.size)
            return out
        }
    }
}
