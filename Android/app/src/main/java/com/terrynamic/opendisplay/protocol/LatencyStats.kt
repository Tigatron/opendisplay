package com.terrynamic.opendisplay.protocol

/**
 * Rolling ~300-frame windows for phone-side latency.
 *
 * `e2e = renderedAtPhoneMs - (cap - offset)` with Mac clock = phone + offset.
 * `ph` is phone processing (arrive → render, including decode).
 * `dec` is codec residence (queuedToCodec → render).
 */
class LatencyStats(
    private val window: Int = WINDOW,
) {
    data class Snapshot(
        val e2e50: Double?,
        val e2e95: Double?,
        val ph50: Double,
        val ph95: Double,
        val dec50: Double,
        val offsetKnown: Boolean,
        val samples: Int,
    )

    private val lock = Any()
    private val e2e = ArrayDeque<Double>()
    private val ph = ArrayDeque<Double>()
    private val dec = ArrayDeque<Double>()

    fun record(e2eMs: Double?, phMs: Double, decMs: Double) {
        synchronized(lock) {
            if (e2eMs != null && e2eMs > -50.0 && e2eMs < 5_000.0) {
                push(e2e, e2eMs)
            }
            push(ph, phMs)
            push(dec, decMs)
        }
    }

    fun snapshot(offsetKnown: Boolean): Snapshot {
        synchronized(lock) {
            val haveE2e = offsetKnown && e2e.isNotEmpty()
            return Snapshot(
                e2e50 = if (haveE2e) Percentiles.of(e2e.toList(), 0.5) else null,
                e2e95 = if (haveE2e) Percentiles.of(e2e.toList(), 0.95) else null,
                ph50 = Percentiles.of(ph.toList(), 0.5),
                ph95 = Percentiles.of(ph.toList(), 0.95),
                dec50 = Percentiles.of(dec.toList(), 0.5),
                offsetKnown = offsetKnown,
                samples = ph.size,
            )
        }
    }

    fun reset() {
        synchronized(lock) {
            e2e.clear()
            ph.clear()
            dec.clear()
        }
    }

    private fun push(deque: ArrayDeque<Double>, value: Double) {
        deque.addLast(value)
        while (deque.size > window) deque.removeFirst()
    }

    companion object {
        const val WINDOW = 300
    }
}
