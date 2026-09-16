package build.terrynamic.opendisplay

import build.terrynamic.opendisplay.transport.Framer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class FramingTest {
    @Test
    fun encodeDecode_roundTrip() {
        val payload = "hello".toByteArray()
        val framed = Framer.encode(payload)
        assertEquals(4 + payload.size, framed.size)
        assertEquals(payload.size, Framer.readLength(framed, 0))
        assertArrayEquals(payload, Framer.decodeOne(framed))
    }

    @Test
    fun inbound_minAndMaxAreAccepted() {
        val min = ByteArray(1) { 7 }
        assertArrayEquals(min, Framer.decodeOne(Framer.encode(min)))

        val max = ByteArray(Framer.INBOUND_MAX) { 1 }
        val header = ByteArray(4)
        header[0] = ((Framer.INBOUND_MAX ushr 24) and 0xFF).toByte()
        header[1] = ((Framer.INBOUND_MAX ushr 16) and 0xFF).toByte()
        header[2] = ((Framer.INBOUND_MAX ushr 8) and 0xFF).toByte()
        header[3] = (Framer.INBOUND_MAX and 0xFF).toByte()
        val stream = ByteArrayInputStream(header + max)
        assertEquals(Framer.INBOUND_MAX, Framer.read(stream).size)
    }

    @Test
    fun inbound_zeroAndOversizeAreErrors() {
        val zero = byteArrayOf(0, 0, 0, 0)
        try {
            Framer.decodeOne(zero)
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
        }

        val tooBig = 16 * 1024 * 1024 + 1
        val header = byteArrayOf(
            ((tooBig ushr 24) and 0xFF).toByte(),
            ((tooBig ushr 16) and 0xFF).toByte(),
            ((tooBig ushr 8) and 0xFF).toByte(),
            (tooBig and 0xFF).toByte(),
        )
        try {
            Framer.read(ByteArrayInputStream(header))
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("invalid frame length"))
        }
    }

    @Test
    fun outbound_rejectsZeroAndMaxPlusOne() {
        try {
            Framer.encode(ByteArray(0))
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
        }
        try {
            Framer.write(ByteArrayOutputStream(), ByteArray(Framer.OUTBOUND_MAX + 1))
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
        }
        Framer.write(ByteArrayOutputStream(), ByteArray(Framer.OUTBOUND_MAX) { 9 })
    }

    @Test
    fun stream_reassemblesSplitReads() {
        val a = """{"type":"ping"}""".toByteArray()
        val b = byteArrayOf(0, 0, 0, 1, 0x67, 1)
        val framed = Framer.encode(a) + Framer.encode(b)
        val input = ByteArrayInputStream(framed)
        assertArrayEquals(a, Framer.read(input))
        assertArrayEquals(b, Framer.read(input))
    }
}
