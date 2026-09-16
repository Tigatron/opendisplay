package com.terrynamic.opendisplay

import com.terrynamic.opendisplay.protocol.LatencyStats
import com.terrynamic.opendisplay.protocol.Percentiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PercentileTest {
    @Test
    fun empty_isZero() {
        assertEquals(0.0, Percentiles.of(emptyList(), 0.5), 0.0)
    }

    @Test
    fun nearestRank_matchesOfficialReceiver() {
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(3.0, Percentiles.of(values, 0.5), 0.0)
        assertEquals(5.0, Percentiles.of(values, 0.95), 0.0)
        assertEquals(1.0, Percentiles.of(values, 0.0), 0.0)
    }

    @Test
    fun latencyWindow_reportsE2eOnlyWhenOffsetKnown() {
        val stats = LatencyStats(window = 8)
        stats.record(e2eMs = 20.0, phMs = 8.0, decMs = 4.0)
        stats.record(e2eMs = 30.0, phMs = 9.0, decMs = 5.0)
        stats.record(e2eMs = 40.0, phMs = 10.0, decMs = 6.0)
        val unknown = stats.snapshot(offsetKnown = false)
        assertNull(unknown.e2e50)
        assertNull(unknown.e2e95)
        val known = stats.snapshot(offsetKnown = true)
        assertEquals(30.0, known.e2e50!!, 0.0)
        assertEquals(40.0, known.e2e95!!, 0.0)
        assertEquals(9.0, known.ph50, 0.0)
        assertEquals(5.0, known.dec50, 0.0)
    }
}
