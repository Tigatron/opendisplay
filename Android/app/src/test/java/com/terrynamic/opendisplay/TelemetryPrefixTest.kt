package com.terrynamic.opendisplay

import com.terrynamic.opendisplay.protocol.TelemetryPrefixParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryPrefixTest {
    @Test
    fun present_readsBothIntegers() {
        val prefix = """{"cap":1710000000123,"snd":1710000000456}""".toByteArray()
        val payload = prefix + START
        val parsed = TelemetryPrefixParser.parse(payload, prefix.size)!!
        assertEquals(1710000000123L, parsed.capMs)
        assertEquals(1710000000456L, parsed.sndMs)
    }

    @Test
    fun absent_returnsNull() {
        assertNull(TelemetryPrefixParser.parse(START, 0))
        assertNull(TelemetryPrefixParser.parse(START, START.size))
    }

    @Test
    fun garbage_returnsNull() {
        val payload = "not-json at all".toByteArray() + START
        assertNull(TelemetryPrefixParser.parse(payload, payload.size - START.size))
    }

    @Test
    fun missingSnd_returnsNull() {
        val prefix = """{"cap":12}""".toByteArray()
        assertNull(TelemetryPrefixParser.parse(prefix + START, prefix.size))
    }

    @Test
    fun whitespaceAndOrder_tolerated() {
        val prefix = """{ "snd" : 88 , "cap" : 77 }""".toByteArray()
        val parsed = TelemetryPrefixParser.parse(prefix, prefix.size)!!
        assertEquals(77L, parsed.capMs)
        assertEquals(88L, parsed.sndMs)
    }

    companion object {
        private val START = byteArrayOf(0, 0, 0, 1, 0x65)
    }
}
