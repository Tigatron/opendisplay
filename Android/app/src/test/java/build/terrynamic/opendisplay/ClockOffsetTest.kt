package build.terrynamic.opendisplay

import build.terrynamic.opendisplay.protocol.ClockOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockOffsetTest {
    @Test
    fun discardsNegativeAndHugeRtt() {
        val clock = ClockOffset()
        assertNull(clock.onPong(t1 = 100.0, mt = 50.0, t2 = 90.0))
        assertNull(clock.onPong(t1 = 0.0, mt = 10.0, t2 = 2500.0))
        assertNull(clock.offsetMs)
    }

    @Test
    fun keepsLastFifteenAndUsesMinRtt() {
        val clock = ClockOffset()
        repeat(20) { i ->
            val t1 = i * 10.0
            val rtt = if (i == 17) 2.0 else 20.0
            val t2 = t1 + rtt
            val mt = t1 + rtt / 2 + 40.0
            clock.onPong(t1, mt, t2)
        }
        assertEquals(15, clock.sampleCount)
        assertEquals(40.0, clock.offsetMs!!, 0.001)
    }

    @Test
    fun stampUsesOffsetWhenKnown() {
        val clock = ClockOffset()
        assertNull(clock.stampSenderClock(1000.0))
        clock.onPong(t1 = 0.0, mt = 12.0, t2 = 4.0)
        assertEquals(1010.0, clock.stampSenderClock(1000.0)!!, 0.001)
    }
}
