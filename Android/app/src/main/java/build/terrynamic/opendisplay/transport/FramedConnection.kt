package build.terrynamic.opendisplay.transport

import android.os.SystemClock
import android.util.Log
import build.terrynamic.opendisplay.protocol.WireProtocol
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class FramedConnection(
    private val socket: Socket,
    initialBytes: ByteArray = ByteArray(0),
) : Closeable {
    private val rawInput: InputStream = socket.getInputStream()
    private val input: InputStream = BufferedInputStream(
        if (initialBytes.isEmpty()) {
            rawInput
        } else {
            SequenceInputStream(ByteArrayInputStream(initialBytes), rawInput)
        },
        1 shl 18,
    )
    private val output = BufferedOutputStream(socket.getOutputStream(), 1 shl 16)
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    private val headerIn = ByteArray(4)
    private val headerOut = ByteArray(4)
    private val lastReadAtMs = AtomicLong(nowMs())

    val isOpen: Boolean get() = !closed.get() && socket.isConnected && !socket.isClosed

    val lastReadAtElapsedMs: Long get() = lastReadAtMs.get()

    val remoteAddress: InetAddress? get() = socket.inetAddress

    val transport: String
        get() = classifyTransport(socket.inetAddress)

    fun idleForMs(nowElapsedMs: Long = nowMs()): Long =
        (nowElapsedMs - lastReadAtMs.get()).coerceAtLeast(0L)

    fun readFrame(): ByteArray {
        return Framer.read(input, headerIn) { n ->
            if (n > 0) lastReadAtMs.set(nowMs())
        }
    }

    fun writeFrame(payload: ByteArray) {
        if (closed.get()) return
        synchronized(writeLock) {
            try {
                Framer.write(output, payload, headerOut)
            } catch (e: IOException) {
                Log.w(WireProtocol.LOG_TAG, "writeFrame failed: ${e.message}")
                throw e
            }
        }
    }

    fun writeJson(obj: JSONObject) {
        writeFrame(obj.toString().toByteArray(Charsets.UTF_8))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            socket.shutdownInput()
        } catch (_: Exception) {
        }
        try {
            socket.shutdownOutput()
        } catch (_: Exception) {
        }
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        fun configureSocket(socket: Socket) {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            try {
                socket.trafficClass = 0x10
            } catch (_: Exception) {
            }
        }

        fun classifyTransport(address: InetAddress?): String {
            if (address == null) return "wifi"
            if (address.isLoopbackAddress) return "usb"
            val host = address.hostAddress ?: return "wifi"
            if (host == "127.0.0.1" || host == "::1" || host == "https://example.net/id/garnet") return "usb"
            return "wifi"
        }

        private fun nowMs(): Long = SystemClock.elapsedRealtime()
    }
}
