package com.terrynamic.opendisplay.session

import com.terrynamic.opendisplay.ReceiverPhase

enum class CloseDecision {
    Apply,
    IgnoreStale,
}

object SessionCloseGuard {
    /**
     * A close for an older generation must never tear down a newer adopted
     * session. Generation `0` means no live session — a close still applies
     * (listener stop / already-idle).
     */
    fun decide(closeGeneration: Long, liveGeneration: Long): CloseDecision {
        return if (liveGeneration != 0L && closeGeneration != liveGeneration) {
            CloseDecision.IgnoreStale
        } else {
            CloseDecision.Apply
        }
    }
}

data class SessionSnapshot(
    val generation: Long,
    val phase: ReceiverPhase,
    val transport: String?,
    val lastSurfaceToken: Any?,
    val decoderResetCount: Int,
    val surfaceReattachCount: Int,
)

/**
 * Adopt/close state machine. Callers must run [adopt] and [close] through
 * [runExclusive] so the two cannot interleave. Production posts both onto
 * the controller handler; tests inject a queue or run immediately.
 */
class SessionLifecycle(
    private val runExclusive: (() -> Unit) -> Unit,
) {
    @Volatile
    var generation: Long = 0L
        private set

    var phase: ReceiverPhase = ReceiverPhase.IDLE
        private set

    var transport: String? = null
        private set

    var lastSurfaceToken: Any? = null
        private set

    var decoderResetCount: Int = 0
        private set

    var surfaceReattachCount: Int = 0
        private set

    fun snapshot(): SessionSnapshot = SessionSnapshot(
        generation = generation,
        phase = phase,
        transport = transport,
        lastSurfaceToken = lastSurfaceToken,
        decoderResetCount = decoderResetCount,
        surfaceReattachCount = surfaceReattachCount,
    )

    fun retainSurface(token: Any?) {
        lastSurfaceToken = token
    }

    fun adopt(generation: Long, transport: String) {
        runExclusive { adoptLocked(generation, transport) }
    }

    fun close(generation: Long): Boolean {
        var applied = false
        runExclusive {
            applied = closeLocked(generation)
        }
        return applied
    }

    fun clearGeneration() {
        runExclusive {
            generation = 0L
            phase = ReceiverPhase.IDLE
            transport = null
        }
    }

    private fun adoptLocked(generation: Long, transport: String) {
        this.generation = generation
        this.phase = ReceiverPhase.STREAMING
        this.transport = transport
        decoderResetCount++
        surfaceReattachCount++
    }

    private fun closeLocked(generation: Long): Boolean {
        if (SessionCloseGuard.decide(generation, this.generation) == CloseDecision.IgnoreStale) {
            return false
        }
        decoderResetCount++
        this.phase = ReceiverPhase.IDLE
        this.transport = null
        return true
    }
}
