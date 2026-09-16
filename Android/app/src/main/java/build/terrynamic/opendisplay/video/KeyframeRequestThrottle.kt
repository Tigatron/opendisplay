package build.terrynamic.opendisplay.video

import build.terrynamic.opendisplay.protocol.WireProtocol

sealed class KeyframeRequestAction {
    data object SendNow : KeyframeRequestAction()
    data class RetryAfter(val delayMs: Long) : KeyframeRequestAction()
    data object Coalesced : KeyframeRequestAction()
    data object Ignored : KeyframeRequestAction()
}

data class KeyframeRequestMetadata(
    val reason: String?,
    val logReason: String,
) {
    fun upgradedWith(reason: String?, logReason: String): KeyframeRequestMetadata {
        return if (this.reason == null && reason != null) {
            KeyframeRequestMetadata(reason, logReason)
        } else {
            this
        }
    }
}

/**
 * Per-session IDR request throttle: at most one send per second, with a
 * single pending retry so a backpressure resync is not forgotten.
 */
class KeyframeRequestThrottle(
    private val intervalMs: Long = 1_000L,
) {
    private var generation: Long? = null
    private var lastSentAtMs: Long? = null
    private var retryPending = false

    init {
        require(intervalMs > 0L)
    }

    @Synchronized
    fun request(generation: Long, nowMs: Long): KeyframeRequestAction {
        activate(generation)
        if (retryPending) return KeyframeRequestAction.Coalesced
        val delayMs = remainingDelay(nowMs)
        if (delayMs == 0L) {
            lastSentAtMs = nowMs
            return KeyframeRequestAction.SendNow
        }
        retryPending = true
        return KeyframeRequestAction.RetryAfter(delayMs)
    }

    @Synchronized
    fun retry(generation: Long, nowMs: Long): KeyframeRequestAction {
        if (this.generation != generation || !retryPending) {
            return KeyframeRequestAction.Ignored
        }
        val delayMs = remainingDelay(nowMs)
        if (delayMs > 0L) return KeyframeRequestAction.RetryAfter(delayMs)
        retryPending = false
        lastSentAtMs = nowMs
        return KeyframeRequestAction.SendNow
    }

    @Synchronized
    fun abandonRetry(generation: Long) {
        if (this.generation == generation) retryPending = false
    }

    @Synchronized
    fun invalidate(generation: Long) {
        if (this.generation != generation) return
        this.generation = null
        lastSentAtMs = null
        retryPending = false
    }

    @Synchronized
    fun invalidateAll() {
        generation = null
        lastSentAtMs = null
        retryPending = false
    }

    private fun activate(generation: Long) {
        if (this.generation == generation) return
        this.generation = generation
        lastSentAtMs = null
        retryPending = false
    }

    private fun remainingDelay(nowMs: Long): Long {
        val last = lastSentAtMs ?: return 0L
        val elapsed = if (nowMs >= last) {
            (nowMs - last).coerceAtMost(intervalMs)
        } else {
            0L
        }
        return intervalMs - elapsed
    }

    companion object {
        const val BACKPRESSURE = WireProtocol.KEYFRAME_REASON_BACKPRESSURE
    }
}
