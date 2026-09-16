package com.terrynamic.opendisplay.transport

import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class OutboundKind {
    HELLO,
    PING,
    TOUCH_BEGAN,
    TOUCH_MOVED,
    TOUCH_ENDED,
    TOUCH_CANCELLED,
    SCROLL,
    KEYFRAME,
    STATS,
    SLEEPING,
    CLOSING,
    CURSOR_ACK,
    PENCIL,
    PENCIL_MOVE,
    PROXIMITY,
}

data class OutboundMessage(
    val kind: OutboundKind,
    val payload: ByteArray,
)

/**
 * Bounded outbound queue. Coalesces `touch moved`, pencil move/hover,
 * `scroll`, and `ping` so a burst of input does not pile up behind
 * video-sized writes.
 */
class OutboundQueue(
    private val capacity: Int = 64,
) {
    private val items = LinkedBlockingDeque<OutboundMessage>(capacity)
    private val closed = AtomicBoolean(false)

    fun offer(message: OutboundMessage): Boolean {
        if (closed.get()) return false
        if (isCoalescible(message.kind)) {
            items.removeIf { it.kind == message.kind }
        }
        if (!items.offerLast(message)) {
            val droppable = items.firstOrNull { isCoalescible(it.kind) }
            if (droppable != null) items.remove(droppable) else items.pollFirst()
            items.offerLast(message)
        }
        return true
    }

    fun take(): OutboundMessage? {
        while (!closed.get()) {
            val next = items.pollFirst(100, TimeUnit.MILLISECONDS)
            if (next != null) return next
        }
        return null
    }

    fun clear() {
        items.clear()
    }

    fun close() {
        closed.set(true)
        items.clear()
    }

    val size: Int get() = items.size

    private fun isCoalescible(kind: OutboundKind): Boolean {
        return kind == OutboundKind.TOUCH_MOVED ||
            kind == OutboundKind.PENCIL_MOVE ||
            kind == OutboundKind.SCROLL ||
            kind == OutboundKind.PING
    }
}
