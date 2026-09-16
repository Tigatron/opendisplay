package com.terrynamic.opendisplay.cursor

/**
 * Shared TCP/UDP cursor sequence tracker (PROTOCOL.md §6.3).
 *
 * One tracker per TCP session. A new UDP remote (host+port) is a new flow:
 * the tracker resets and the first accepted datagram of that flow asks for
 * a TCP `cursorAck`. Datagrams are accepted only from the most recently
 * seen flow (a newer remote replaces it). A TCP `cursor` without `s`
 * applies unconditionally and does not move the sequence floor.
 */
class CursorChannel {
    data class Decision(
        val apply: Boolean,
        val sendAck: Boolean = false,
        val lost: Int = 0,
    )

    data class Remote(val host: String, val port: Int)

    var lastSequence: Long = 0
        private set
    var currentRemote: Remote? = null
        private set
    var cursorUpdates: Int = 0
        private set
    var cursorLost: Int = 0
        private set
    private var ackedCurrentFlow = false

    fun resetSession() {
        lastSequence = 0
        currentRemote = null
        ackedCurrentFlow = false
        cursorUpdates = 0
        cursorLost = 0
    }

    fun onTcp(sequence: Long?): Decision {
        return accept(sequence, allowMissing = true)
    }

    fun onUdp(host: String, port: Int, sequence: Long): Decision {
        val remote = Remote(host, port)
        if (currentRemote != remote) {
            currentRemote = remote
            lastSequence = 0
            ackedCurrentFlow = false
        }
        val decision = accept(sequence, allowMissing = false)
        val ack = decision.apply && !ackedCurrentFlow
        if (ack) ackedCurrentFlow = true
        return decision.copy(sendAck = ack)
    }

    private fun accept(sequence: Long?, allowMissing: Boolean): Decision {
        if (sequence == null) {
            if (!allowMissing) return Decision(apply = false)
            cursorUpdates += 1
            return Decision(apply = true)
        }
        if (sequence <= lastSequence) {
            return Decision(apply = false)
        }
        val gap = if (lastSequence == 0L) 0 else (sequence - lastSequence - 1).toInt().coerceAtLeast(0)
        lastSequence = sequence
        cursorLost += gap
        cursorUpdates += 1
        return Decision(apply = true, lost = gap)
    }

    companion object {
        fun advertisedPort(enabled: Boolean, peerLoopback: Boolean, boundPort: Int?): Int? {
            if (!enabled || peerLoopback) return null
            return boundPort
        }
    }
}
