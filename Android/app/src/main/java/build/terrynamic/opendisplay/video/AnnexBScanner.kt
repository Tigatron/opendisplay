package build.terrynamic.opendisplay.video

/**
 * Zero-copy 4-byte start-code scan. An optional JSON telemetry prefix before
 * the first start code is ignored (PROTOCOL.md §5.1).
 */
object AnnexBScanner {
    data class NalRange(
        val type: Int,
        val start: Int,
        val end: Int,
    )

    data class Scan(
        val firstStartCode: Int,
        val nalus: List<NalRange>,
        val hasSps: Boolean,
        val hasPps: Boolean,
        val hasIdr: Boolean,
    )

    fun scan(data: ByteArray): Scan {
        val nalus = ArrayList<NalRange>(8)
        var naluStart = -1
        var firstSc = -1
        var i = 0
        var hasSps = false
        var hasPps = false
        var hasIdr = false
        while (i + 4 <= data.size) {
            if (data[i] == 0.toByte() &&
                data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() &&
                data[i + 3] == 1.toByte()
            ) {
                if (firstSc < 0) firstSc = i
                if (naluStart >= 0 && naluStart < i) {
                    val type = data[naluStart].toInt() and 0x1F
                    nalus.add(NalRange(type, naluStart, i))
                    when (type) {
                        7 -> hasSps = true
                        8 -> hasPps = true
                        5 -> hasIdr = true
                    }
                }
                naluStart = i + 4
                i += 4
            } else {
                i++
            }
        }
        if (naluStart >= 0 && naluStart < data.size) {
            val type = data[naluStart].toInt() and 0x1F
            nalus.add(NalRange(type, naluStart, data.size))
            when (type) {
                7 -> hasSps = true
                8 -> hasPps = true
                5 -> hasIdr = true
            }
        }
        return Scan(firstSc, nalus, hasSps, hasPps, hasIdr)
    }

    fun split(data: ByteArray): Pair<ByteArray?, List<ByteArray>> {
        val result = scan(data)
        if (result.firstStartCode < 0) return null to emptyList()
        val nalus = result.nalus.map { data.copyOfRange(it.start, it.end) }
        val prefix = if (result.firstStartCode > 0) data.copyOfRange(0, result.firstStartCode) else null
        return prefix to nalus
    }

    fun containsNalType(data: ByteArray, nalType: Int): Boolean {
        if (nalType !in 0..31) return false
        var i = 0
        while (i + 4 < data.size) {
            if (data[i] == 0.toByte() &&
                data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() &&
                data[i + 3] == 1.toByte()
            ) {
                if ((data[i + 4].toInt() and 0x1F) == nalType) return true
                i += 4
            } else {
                i++
            }
        }
        return false
    }
}
