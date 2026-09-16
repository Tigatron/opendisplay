package com.terrynamic.opendisplay.protocol

/**
 * Optional `{"cap":<ms>,"snd":<ms>}` stamped by the sender before the first
 * Annex-B start code (PROTOCOL.md §5.1). Parsed by a hand-written scan so a
 * garbage prefix cannot throw through JSONObject.
 */
data class TelemetryPrefix(
    val capMs: Long,
    val sndMs: Long,
)

object TelemetryPrefixParser {
    fun parse(payload: ByteArray, firstStartCode: Int = payload.size): TelemetryPrefix? {
        if (firstStartCode <= 0 || firstStartCode > payload.size) return null
        val cap = scanInteger(payload, firstStartCode, CAP_KEY) ?: return null
        val snd = scanInteger(payload, firstStartCode, SND_KEY) ?: return null
        return TelemetryPrefix(capMs = cap, sndMs = snd)
    }

    private fun scanInteger(payload: ByteArray, end: Int, key: ByteArray): Long? {
        val keyAt = indexOf(payload, end, key) ?: return null
        var i = keyAt + key.size
        while (i < end && isWs(payload[i])) i++
        if (i >= end || payload[i] != COLON) return null
        i++
        while (i < end && isWs(payload[i])) i++
        if (i >= end) return null
        var sign = 1
        if (payload[i] == MINUS) {
            sign = -1
            i++
        }
        if (i >= end || !isDigit(payload[i])) return null
        var value = 0L
        var digits = 0
        while (i < end && isDigit(payload[i])) {
            digits++
            if (digits > 16) return null
            value = value * 10 + (payload[i] - ZERO)
            i++
        }
        return value * sign
    }

    private fun indexOf(payload: ByteArray, end: Int, key: ByteArray): Int? {
        if (key.isEmpty() || key.size > end) return null
        val last = end - key.size
        var i = 0
        while (i <= last) {
            var match = true
            var k = 0
            while (k < key.size) {
                if (payload[i + k] != key[k]) {
                    match = false
                    break
                }
                k++
            }
            if (match) return i
            i++
        }
        return null
    }

    private fun isWs(b: Byte): Boolean {
        val c = b.toInt()
        return c == 0x20 || c == 0x09 || c == 0x0A || c == 0x0D
    }

    private fun isDigit(b: Byte): Boolean {
        val c = b.toInt()
        return c in 0x30..0x39
    }

    private val CAP_KEY = "\"cap\"".toByteArray(Charsets.US_ASCII)
    private val SND_KEY = "\"snd\"".toByteArray(Charsets.US_ASCII)
    private const val COLON: Byte = 0x3A
    private const val MINUS: Byte = 0x2D
    private const val ZERO: Byte = 0x30
}
