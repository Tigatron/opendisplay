package build.terrynamic.opendisplay

import build.terrynamic.opendisplay.video.AnnexBScanner
import build.terrynamic.opendisplay.video.SpsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnexBTest {
    @Test
    fun split_extractsNalusAndPrefix() {
        val prefix = """{"cap":1.0,"snd":2.0}""".toByteArray()
        val sps = byteArrayOf(0x67, 0x42.toByte(), 0x00, 0x1E)
        val slice = byteArrayOf(0x65, 0x01, 0x02, 0x03)
        val payload = prefix + sc() + sps + sc() + slice
        val (meta, nalus) = AnnexBScanner.split(payload)
        assertEquals(String(prefix), String(meta!!))
        assertEquals(2, nalus.size)
        assertTrue(nalus[0].contentEquals(sps))
        assertTrue(nalus[1].contentEquals(slice))
        val scan = AnnexBScanner.scan(payload)
        assertTrue(scan.hasIdr)
        assertFalse(scan.hasPps)
    }

    @Test
    fun split_noPrefix() {
        val nalu = byteArrayOf(0x67, 0x01)
        val (meta, nalus) = AnnexBScanner.split(sc() + nalu)
        assertNull(meta)
        assertEquals(1, nalus.size)
        assertTrue(nalus[0].contentEquals(nalu))
    }

    @Test
    fun containsNalType_handlesTelemetryPrefix() {
        val payload = """{"cap":1}""".toByteArray() + sc() + byteArrayOf(0x67, 1) + sc() + byteArrayOf(0x65, 2)
        assertTrue(AnnexBScanner.containsNalType(payload, 5))
        assertFalse(AnnexBScanner.containsNalType(payload, 1))
    }

    @Test
    fun parseSpsDimensions_handlesEmulationPreventionBytes() {
        val rbsp = syntheticSps(chromaFormatIdc = 1, spsId = 255)
        val ebsp = escapeRbsp(rbsp)
        assertTrue(ebsp.size > rbsp.size)
        assertEquals(32 to 32, SpsParser.parseDimensions(ebsp))
    }

    @Test
    fun parseSpsDimensions_appliesCropUnitsForEachChromaFormat() {
        assertEquals(31 to 31, SpsParser.parseDimensions(syntheticSps(0, cropRight = 1, cropBottom = 1)))
        assertEquals(30 to 30, SpsParser.parseDimensions(syntheticSps(1, cropRight = 1, cropBottom = 1)))
        assertEquals(30 to 31, SpsParser.parseDimensions(syntheticSps(2, cropRight = 1, cropBottom = 1)))
        assertEquals(31 to 31, SpsParser.parseDimensions(syntheticSps(3, cropRight = 1, cropBottom = 1)))
    }

    @Test
    fun parseSpsDimensions_parsesRealCroppedX264Sps() {
        val sps = byteArrayOf(
            0x67, 0x64, 0x00, 0x1f,
            0xac.toByte(), 0xd9.toByte(), 0x40, 0xa0.toByte(),
            0x2f, 0xf9.toByte(), 0x70, 0x11,
            0x00, 0x00, 0x03, 0x00, 0x01,
            0x00, 0x00, 0x03, 0x00, 0x78,
            0x0f, 0x18, 0x31, 0x96.toByte(),
        )
        assertEquals(640 to 360, SpsParser.parseDimensions(sps))
    }

    private fun sc() = byteArrayOf(0, 0, 0, 1)

    private fun syntheticSps(
        chromaFormatIdc: Int,
        spsId: Int = 0,
        cropRight: Int = 0,
        cropBottom: Int = 0,
    ): ByteArray {
        val bits = BitWriter()
        bits.writeBits(0x67, 8)
        bits.writeBits(100, 8)
        bits.writeBits(0, 8)
        bits.writeBits(0, 8)
        bits.writeUE(spsId)
        bits.writeUE(chromaFormatIdc)
        if (chromaFormatIdc == 3) bits.writeBit(0)
        bits.writeUE(0)
        bits.writeUE(0)
        bits.writeBit(0)
        bits.writeBit(0)
        bits.writeUE(0)
        bits.writeUE(0)
        bits.writeUE(0)
        bits.writeUE(1)
        bits.writeBit(0)
        bits.writeUE(1)
        bits.writeUE(1)
        bits.writeBit(1)
        bits.writeBit(1)
        val cropped = cropRight != 0 || cropBottom != 0
        bits.writeBit(if (cropped) 1 else 0)
        if (cropped) {
            bits.writeUE(0)
            bits.writeUE(cropRight)
            bits.writeUE(0)
            bits.writeUE(cropBottom)
        }
        bits.writeBit(0)
        return bits.finishRbsp()
    }

    private fun escapeRbsp(rbsp: ByteArray): ByteArray {
        val out = ArrayList<Byte>(rbsp.size + 8)
        var zeroCount = 0
        for ((index, byte) in rbsp.withIndex()) {
            val value = byte.toInt() and 0xff
            if (index > 0 && zeroCount >= 2 && value <= 0x03) {
                out.add(0x03)
                zeroCount = 0
            }
            out.add(byte)
            zeroCount = if (value == 0) zeroCount + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitWriter {
        private val bits = ArrayList<Int>()
        fun writeBit(value: Int) = bits.add(value and 1)
        fun writeBits(value: Int, count: Int) {
            for (shift in count - 1 downTo 0) writeBit(value ushr shift)
        }
        fun writeUE(value: Int) {
            val codeNum = value + 1
            val bitCount = 32 - Integer.numberOfLeadingZeros(codeNum)
            repeat(bitCount - 1) { writeBit(0) }
            writeBits(codeNum, bitCount)
        }
        fun finishRbsp(): ByteArray {
            writeBit(1)
            while (bits.size % 8 != 0) writeBit(0)
            return ByteArray(bits.size / 8) { byteIndex ->
                var value = 0
                repeat(8) { bitIndex -> value = (value shl 1) or bits[byteIndex * 8 + bitIndex] }
                value.toByte()
            }
        }
    }
}
