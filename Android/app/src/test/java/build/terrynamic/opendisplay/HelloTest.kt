package build.terrynamic.opendisplay

import build.terrynamic.opendisplay.protocol.ControlMessages
import build.terrynamic.opendisplay.protocol.WireMessage
import build.terrynamic.opendisplay.protocol.WireProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelloTest {
    @Test
    fun requiredFields_alwaysPresent() {
        val json = ControlMessages.hello(
            pixelsWide = 2800,
            pixelsHigh = 1752,
            scale = 2.0f,
            device = "AndroidTablet",
            installId = "TEST-ID",
        )
        assertEquals(WireMessage.HELLO, json.getString("type"))
        assertEquals(2800, json.getInt("pixelsWide"))
        assertEquals(1752, json.getInt("pixelsHigh"))
        assertEquals(2.0, json.getDouble("scale"), 0.001)
        assertEquals("AndroidTablet", json.getString("device"))
        assertEquals("TEST-ID", json.getString("id"))
        assertEquals(WireProtocol.VERSION, json.getInt("pv"))
        assertFalse(json.has("maxEncodeWide"))
        assertFalse(json.has("maxEncodeHigh"))
        assertFalse(json.has("addrs"))
        assertFalse(json.has("cursorPort"))
    }

    @Test
    fun ceilingAndAddrs_presentWhenConfigured() {
        val json = ControlMessages.hello(
            pixelsWide = 2800,
            pixelsHigh = 1752,
            scale = 2.0f,
            device = "AndroidTablet",
            installId = "TEST-ID",
            maxEncodeWide = 1920,
            maxEncodeHigh = 1080,
            addrs = listOf("127.0.0.1"),
        )
        assertEquals(1920, json.getInt("maxEncodeWide"))
        assertEquals(1080, json.getInt("maxEncodeHigh"))
        assertEquals(1, json.getJSONArray("addrs").length())
        assertEquals("127.0.0.1", json.getJSONArray("addrs").getString(0))
    }

    @Test
    fun emptyAddrs_omitted() {
        val json = ControlMessages.hello(
            pixelsWide = 1080,
            pixelsHigh = 2400,
            scale = 2.75f,
            device = "Android",
            installId = "x",
            addrs = emptyList(),
        )
        assertFalse(json.has("addrs"))
        assertTrue(json.has("pv"))
    }
}
