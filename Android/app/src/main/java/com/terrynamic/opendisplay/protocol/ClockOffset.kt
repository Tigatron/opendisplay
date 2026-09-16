package com.terrynamic.opendisplay.protocol

/**
 * NTP-style clock offset from receiver ping / sender pong (PROTOCOL.md §8.1).
 * Keeps the last [window] samples and uses the minimum-RTT offset.
 */
class ClockOffset(
    private val window: Int = 15,
) {
    data class Sample(val rtt: Double, val offset: Double)

    private val samples = ArrayDeque<Sample>()

    var offsetMs: Double? = null
        private set

    val sampleCount: Int get() = samples.size

    fun onPong(t1: Double, mt: Double, t2: Double): Sample? {
        val rtt = t2 - t1
        if (rtt < 0 || rtt >= 2000) return null
        val offset = mt - (t1 + t2) / 2.0
        val sample = Sample(rtt, offset)
        samples.addLast(sample)
        while (samples.size > window) samples.removeFirst()
        offsetMs = samples.minBy { it.rtt }.offset
        return sample
    }

    fun reset() {
        samples.clear()
        offsetMs = null
    }

    fun stampSenderClock(nowMs: Double): Double? {
        val offset = offsetMs ?: return null
        return nowMs + offset
    }
}
