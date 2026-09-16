package build.terrynamic.opendisplay.video

object SpsParser {
    fun parseDimensions(sps: ByteArray): Pair<Int, Int>? {
        return try {
            val reader = ExpGolomb(ebspToRbsp(sps))
            reader.skipBits(8)
            val profileIdc = reader.readBits(8)
            reader.skipBits(8)
            reader.readBits(8)
            reader.readUE()

            var chromaFormatIdc = 1
            var separateColourPlane = false
            if (profileIdc in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
                chromaFormatIdc = reader.readUE()
                if (chromaFormatIdc !in 0..3) return null
                if (chromaFormatIdc == 3) {
                    separateColourPlane = reader.readBit() == 1
                }
                reader.readUE()
                reader.readUE()
                reader.skipBits(1)
                if (reader.readBit() == 1) {
                    val count = if (chromaFormatIdc != 3) 8 else 12
                    repeat(count) {
                        if (reader.readBit() == 1) {
                            val size = if (it < 6) 16 else 64
                            var lastScale = 8
                            var nextScale = 8
                            repeat(size) {
                                if (nextScale != 0) {
                                    val delta = reader.readSE()
                                    nextScale = (lastScale + delta + 256) % 256
                                }
                                lastScale = if (nextScale == 0) lastScale else nextScale
                            }
                        }
                    }
                }
            }

            reader.readUE()
            val pocType = reader.readUE()
            when (pocType) {
                0 -> reader.readUE()
                1 -> {
                    reader.skipBits(1)
                    reader.readSE()
                    reader.readSE()
                    val n = reader.readUE()
                    repeat(n) { reader.readSE() }
                }
            }
            reader.readUE()
            reader.skipBits(1)
            val wMbs = reader.readUE().toLong() + 1L
            val hMap = reader.readUE().toLong() + 1L
            val frameMbsOnly = reader.readBit()
            if (frameMbsOnly == 0) reader.skipBits(1)
            reader.skipBits(1)

            var width = wMbs * 16L
            var height = hMap * 16L * (2 - frameMbsOnly)

            if (reader.readBit() == 1) {
                val l = reader.readUE().toLong()
                val r = reader.readUE().toLong()
                val t = reader.readUE().toLong()
                val b = reader.readUE().toLong()
                val chromaArrayType = if (separateColourPlane) 0 else chromaFormatIdc
                val (subWidthC, subHeightC) = when (chromaArrayType) {
                    0 -> 1L to 1L
                    1 -> 2L to 2L
                    2 -> 2L to 1L
                    3 -> 1L to 1L
                    else -> return null
                }
                val cropUnitX = subWidthC
                val cropUnitY = subHeightC * (2 - frameMbsOnly)
                width -= (l + r) * cropUnitX
                height -= (t + b) * cropUnitY
            }
            if (width in 16L..7680L && height in 16L..4320L) {
                width.toInt() to height.toInt()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun ebspToRbsp(ebsp: ByteArray): ByteArray {
        if (ebsp.size < 2) return ebsp
        val rbsp = ByteArray(ebsp.size)
        var output = 0
        var zeroCount = 0
        for (i in ebsp.indices) {
            val value = ebsp[i].toInt() and 0xFF
            val isPreventionByte =
                i > 0 &&
                    zeroCount >= 2 &&
                    value == 0x03 &&
                    i + 1 < ebsp.size &&
                    (ebsp[i + 1].toInt() and 0xFF) <= 0x03
            if (isPreventionByte) {
                zeroCount = 0
                continue
            }
            rbsp[output++] = ebsp[i]
            zeroCount = if (value == 0) zeroCount + 1 else 0
        }
        return rbsp.copyOf(output)
    }
}

private class ExpGolomb(data: ByteArray) {
    private val bytes = data
    private var bitPos = 0

    fun readBit(): Int {
        if (bitPos >= bytes.size * 8) throw IllegalStateException("unexpected end of SPS")
        val b = bytes[bitPos ushr 3].toInt() and 0xFF
        val v = (b shr (7 - (bitPos and 7))) and 1
        bitPos++
        return v
    }

    fun readBits(n: Int): Int {
        require(n in 0..31)
        var v = 0
        repeat(n) { v = (v shl 1) or readBit() }
        return v
    }

    fun skipBits(n: Int) {
        require(n >= 0)
        repeat(n) { readBit() }
    }

    fun readUE(): Int {
        var zeros = 0
        while (readBit() == 0) {
            zeros++
            if (zeros >= 31) throw IllegalStateException("Exp-Golomb value is too large")
        }
        if (zeros == 0) return 0
        return ((1 shl zeros) or readBits(zeros)) - 1
    }

    fun readSE(): Int {
        val v = readUE()
        val sign = ((v and 1) shl 1) - 1
        return ((v + 1) ushr 1) * sign
    }
}
