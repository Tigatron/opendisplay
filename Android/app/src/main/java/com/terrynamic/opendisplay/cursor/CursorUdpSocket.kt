package com.terrynamic.opendisplay.cursor

import android.util.Log
import com.terrynamic.opendisplay.protocol.WireProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Best-effort UDP listener for cursor datagrams. Prefers TCP port + 1 (9001)
 * and falls back to an ephemeral port if that bind fails.
 */
class CursorUdpSocket(
    private val preferredPort: Int = WireProtocol.DEFAULT_PORT + 1,
    private val onDatagram: (InetSocketAddress, ByteArray) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    @Volatile
    var boundPort: Int? = null
        private set

    fun start(): Int? {
        if (!running.compareAndSet(false, true)) return boundPort
        val datagram = bindPreferred() ?: run {
            running.set(false)
            return null
        }
        socket = datagram
        boundPort = datagram.localPort
        thread = Thread({ receiveLoop(datagram) }, "od-cursor-udp").apply {
            isDaemon = true
            start()
        }
        Log.i(WireProtocol.LOG_TAG, "cursor UDP listening on :${datagram.localPort}")
        return datagram.localPort
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        boundPort = null
        thread = null
        Log.i(WireProtocol.LOG_TAG, "cursor UDP closed")
    }

    private fun bindPreferred(): DatagramSocket? {
        return try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(preferredPort))
            }
        } catch (_: SocketException) {
            try {
                DatagramSocket(0).also {
                    Log.i(
                        WireProtocol.LOG_TAG,
                        "cursor UDP :$preferredPort busy — bound ephemeral :${it.localPort}",
                    )
                }
            } catch (e: Exception) {
                Log.w(WireProtocol.LOG_TAG, "cursor UDP bind failed: ${e.message}")
                null
            }
        }
    }

    private fun receiveLoop(datagram: DatagramSocket) {
        val buf = ByteArray(2048)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                datagram.receive(packet)
                val from = packet.socketAddress as? InetSocketAddress ?: continue
                if (packet.length <= 0) continue
                onDatagram(from, buf.copyOf(packet.length))
            } catch (_: SocketException) {
                if (running.get()) {
                    Log.w(WireProtocol.LOG_TAG, "cursor UDP receive ended")
                }
                break
            } catch (e: Exception) {
                if (running.get()) {
                    Log.w(WireProtocol.LOG_TAG, "cursor UDP receive error: ${e.message}")
                }
            }
        }
    }
}
