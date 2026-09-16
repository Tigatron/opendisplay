package com.terrynamic.opendisplay

import com.terrynamic.opendisplay.transport.Demux
import com.terrynamic.opendisplay.transport.FrameKind
import org.junit.Assert.assertEquals
import org.junit.Test

class DemuxTest {
    @Test
    fun shortJsonWithoutNul_isControl() {
        val payload = """{"type":"cursor","v":0}""".toByteArray()
        assertEquals(FrameKind.CONTROL, Demux.classify(payload))
    }

    @Test
    fun jsonWithNul_isVideo() {
        val payload = byteArrayOf('{'.code.toByte(), 0, '}'.code.toByte())
        assertEquals(FrameKind.VIDEO, Demux.classify(payload))
    }

    @Test
    fun longJson_isVideo() {
        val payload = ByteArray(32_768) { '{'.code.toByte() }
        assertEquals(FrameKind.VIDEO, Demux.classify(payload))
    }

    @Test
    fun annexB_isVideoEvenWithTelemetryPrefix() {
        val payload = """{"cap":1}""".toByteArray() + byteArrayOf(0, 0, 0, 1, 0x65)
        assertEquals(FrameKind.VIDEO, Demux.classify(payload))
    }
}
