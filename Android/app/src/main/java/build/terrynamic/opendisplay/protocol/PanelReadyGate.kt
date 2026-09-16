package build.terrynamic.opendisplay.protocol

/**
 * Tracks whether the physical panel size is known. A hello must never be
 * built from placeholder 1920x1080; the listener stays down until [isReady].
 */
class PanelReadyGate {
    @Volatile
    var wide: Int = 0
        private set

    @Volatile
    var high: Int = 0
        private set

    @Volatile
    var scale: Float = 1f
        private set

    @Volatile
    var smallestWidthDp: Int = 0
        private set

    val isReady: Boolean get() = wide > 0 && high > 0

    /**
     * @return true when this is the first valid size or the size changed.
     */
    fun update(wide: Int, high: Int, scale: Float, smallestWidthDp: Int): Boolean {
        if (wide <= 0 || high <= 0) return false
        val first = !isReady
        val changed = this.wide != wide || this.high != high
        this.wide = wide
        this.high = high
        this.scale = scale
        this.smallestWidthDp = smallestWidthDp
        return first || changed
    }

    companion object {
        const val HELLO_WAIT_MS = 1_500L

        fun shouldBindListener(
            listeningEnabled: Boolean,
            panelReady: Boolean,
            alreadyBound: Boolean,
        ): Boolean = listeningEnabled && panelReady && !alreadyBound

        fun awaitReady(
            isReady: () -> Boolean,
            timeoutMs: Long = HELLO_WAIT_MS,
            nowMs: () -> Long,
            sleepMs: (Long) -> Unit,
        ): Boolean {
            val deadline = nowMs() + timeoutMs
            while (!isReady()) {
                val remaining = deadline - nowMs()
                if (remaining <= 0L) return false
                sleepMs(minOf(20L, remaining))
            }
            return true
        }
    }
}
