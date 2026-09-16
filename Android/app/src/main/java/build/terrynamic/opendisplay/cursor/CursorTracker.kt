package build.terrynamic.opendisplay.cursor

import build.terrynamic.opendisplay.protocol.CursorPosition

/**
 * Per-session cursor sequence tracker (PROTOCOL.md §6.3).
 * TCP frames without `s` apply unconditionally.
 */
class CursorTracker {
    var lastSequence: Long = 0
        private set

    fun reset() {
        lastSequence = 0
    }

    fun accept(position: CursorPosition): Boolean {
        val seq = position.sequence ?: return true
        if (seq <= lastSequence) return false
        lastSequence = seq
        return true
    }
}
