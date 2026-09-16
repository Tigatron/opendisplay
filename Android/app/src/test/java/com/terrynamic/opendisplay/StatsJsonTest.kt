package com.terrynamic.opendisplay

import com.terrynamic.opendisplay.protocol.ControlMessages
import com.terrynamic.opendisplay.protocol.WireMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsJsonTest {
    @Test
    fun shape_omitsE2eWhenOffsetUnknown() {
        val json = ControlMessages.stats(
            transport = "wifi",
            fps = 59.8,
            mbps = 12.3,
            ph50 = 8.0,
            ph95 = 14.0,
            dec50 = 4.0,
            stalls = 1,
            queue = 2,
            drops = 3,
            offsetKnown = false,
            e2e50 = 20.0,
            e2e95 = 40.0,
        )
        assertEquals(WireMessage.STATS, json.getString("type"))
        assertEquals("wifi", json.getString("transport"))
        assertEquals(59.8, json.getDouble("fps"), 0.001)
        assertEquals(12.3, json.getDouble("mbps"), 0.001)
        assertEquals(8.0, json.getDouble("ph50"), 0.001)
        assertEquals(14.0, json.getDouble("ph95"), 0.001)
        assertEquals(4.0, json.getDouble("dec50"), 0.001)
        assertEquals(1, json.getInt("stalls"))
        assertEquals(2, json.getInt("queue"))
        assertEquals(3, json.getInt("drops"))
        assertFalse(json.getBoolean("offsetKnown"))
        assertFalse(json.has("e2e50"))
        assertFalse(json.has("e2e95"))
        assertEquals(0, json.getInt("cursorUpdates"))
        assertEquals(0, json.getInt("cursorLost"))
    }

    @Test
    fun shape_includesE2eWhenOffsetKnown() {
        val json = ControlMessages.stats(
            transport = "usb",
            fps = 60.0,
            mbps = 40.0,
            ph50 = 5.0,
            ph95 = 7.0,
            dec50 = 3.0,
            stalls = 0,
            queue = 1,
            drops = 0,
            offsetKnown = true,
            e2e50 = 18.0,
            e2e95 = 27.0,
        )
        assertTrue(json.getBoolean("offsetKnown"))
        assertEquals(18.0, json.getDouble("e2e50"), 0.001)
        assertEquals(27.0, json.getDouble("e2e95"), 0.001)
    }
}
