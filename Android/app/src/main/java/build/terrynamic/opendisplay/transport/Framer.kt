package build.terrynamic.opendisplay.transport

import build.terrynamic.opendisplay.protocol.WireProtocol
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-prefixed framing: [u32 BE length][payload].
 * Inbound payload must be 1..16 MiB; outbound 1..2^20-1.
 */
object Framer {
    const val INBOUND_MIN = 1
    const val INBOUND_MAX = WireProtocol.MAX_INBOUND_FRAME
    const val OUTBOUND_MIN = 1
    const val OUTBOUND_MAX = WireProtocol.MAX_OUTBOUND_FRAME

    fun encode(payload: ByteArray): ByteArray {
        requireOutbound(payload.size)
        val out = ByteArray(4 + payload.size)
        writeLength(out, 0, payload.size)
        System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }

    fun write(output: OutputStream, payload: ByteArray, header: ByteArray = ByteArray(4)) {
        requireOutbound(payload.size)
        writeLength(header, 0, payload.size)
        output.write(header, 0, 4)
        output.write(payload)
        output.flush()
    }

    fun read(input: InputStream, header: ByteArray = ByteArray(4), onBytes: (Int) -> Unit = {}): ByteArray {
        readFully(input, header, 0, 4, onBytes)
        val length = readLength(header, 0)
        if (length < INBOUND_MIN || length > INBOUND_MAX) {
            throw IOException("invalid frame length: $length")
        }
        val payload = ByteArray(length)
        readFully(input, payload, 0, length, onBytes)
        return payload
    }

    fun decodeOne(bytes: ByteArray): ByteArray {
        if (bytes.size < 4) throw IOException("truncated frame header")
        val length = readLength(bytes, 0)
        if (length < INBOUND_MIN || length > INBOUND_MAX) {
            throw IOException("invalid frame length: $length")
        }
        if (bytes.size < 4 + length) throw IOException("truncated frame payload")
        return bytes.copyOfRange(4, 4 + length)
    }

    private fun requireOutbound(length: Int) {
        if (length < OUTBOUND_MIN || length > OUTBOUND_MAX) {
            throw IOException("invalid outbound frame length: $length")
        }
    }

    private fun writeLength(dest: ByteArray, offset: Int, length: Int) {
        dest[offset] = ((length ushr 24) and 0xFF).toByte()
        dest[offset + 1] = ((length ushr 16) and 0xFF).toByte()
        dest[offset + 2] = ((length ushr 8) and 0xFF).toByte()
        dest[offset + 3] = (length and 0xFF).toByte()
    }

    fun readLength(src: ByteArray, offset: Int): Int {
        return ((src[offset].toInt() and 0xFF) shl 24) or
            ((src[offset + 1].toInt() and 0xFF) shl 16) or
            ((src[offset + 2].toInt() and 0xFF) shl 8) or
            (src[offset + 3].toInt() and 0xFF)
    }

    private fun readFully(
        input: InputStream,
        dest: ByteArray,
        offset: Int,
        length: Int,
        onBytes: (Int) -> Unit,
    ) {
        var off = offset
        val end = offset + length
        while (off < end) {
            val n = input.read(dest, off, end - off)
            if (n < 0) throw EOFException("connection closed while reading (${off - offset}/$length)")
            if (n > 0) onBytes(n)
            off += n
        }
    }
}
