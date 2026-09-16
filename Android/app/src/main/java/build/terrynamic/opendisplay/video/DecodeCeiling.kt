package build.terrynamic.opendisplay.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import build.terrynamic.opendisplay.protocol.WireProtocol
import kotlin.math.max

sealed class DecodeCeiling {
    data object Auto : DecodeCeiling()
    data object Panel : DecodeCeiling()
    data object Fhd : DecodeCeiling()
    data class Custom(val width: Int, val height: Int) : DecodeCeiling()
    data object Off : DecodeCeiling()

    val storageKey: String
        get() = when (this) {
            Auto -> "auto"
            Panel -> "panel"
            Fhd -> "fhd"
            is Custom -> "custom:$width:$height"
            Off -> "off"
        }

    companion object {
        fun fromStorage(value: String?): DecodeCeiling {
            if (value.isNullOrBlank()) return Auto
            if (value.startsWith("custom:")) {
                val parts = value.split(":")
                val w = parts.getOrNull(1)?.toIntOrNull()
                val h = parts.getOrNull(2)?.toIntOrNull()
                if (w != null && h != null && w >= 16 && h >= 16) return Custom(w, h)
                return Auto
            }
            return when (value) {
                "panel" -> Panel
                "fhd" -> Fhd
                "off" -> Off
                else -> Auto
            }
        }
    }
}

data class EncodeLimit(val wide: Int, val high: Int)

object DecodeCeilingResolver {
    fun resolve(ceiling: DecodeCeiling, panelWide: Int, panelHigh: Int): EncodeLimit? {
        val panel = even(panelWide) to even(panelHigh)
        return when (ceiling) {
            DecodeCeiling.Off -> null
            DecodeCeiling.Panel -> EncodeLimit(panel.first, panel.second)
            DecodeCeiling.Fhd -> EncodeLimit(1920, 1080)
            is DecodeCeiling.Custom -> EncodeLimit(even(ceiling.width), even(ceiling.height))
            DecodeCeiling.Auto -> {
                val best = largestSixtyFpsPoint()
                val wide = max(panel.first, best?.first ?: 0)
                val high = max(panel.second, best?.second ?: 0)
                EncodeLimit(even(wide), even(high))
            }
        }
    }

    private fun largestSixtyFpsPoint(): Pair<Int, Int>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var best: Pair<Int, Int>? = null
            var bestPixels = 0L
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }) continue
                val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                    ?: continue
                val points = caps.supportedPerformancePoints ?: continue
                for ((w, h) in KNOWN_SIZES) {
                    val target = MediaCodecInfo.VideoCapabilities.PerformancePoint(w, h, 60)
                    if (points.none { it.covers(target) }) continue
                    val pixels = w.toLong() * h.toLong()
                    if (pixels > bestPixels) {
                        bestPixels = pixels
                        best = w to h
                    }
                }
            }
            best
        } catch (e: Exception) {
            Log.w(WireProtocol.LOG_TAG, "performance points unavailable: ${e.message}")
            null
        }
    }

    private val KNOWN_SIZES = listOf(
        7680 to 4320,
        3840 to 2160,
        2560 to 1600,
        2560 to 1440,
        1920 to 1200,
        1920 to 1080,
        1280 to 800,
        1280 to 720,
    )

    private fun even(value: Int): Int {
        val aligned = value and 1.inv()
        return if (aligned < 16) 16 else aligned
    }
}
