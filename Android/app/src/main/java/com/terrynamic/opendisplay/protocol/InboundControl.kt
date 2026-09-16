package com.terrynamic.opendisplay.protocol

import android.util.Base64
import android.util.Log
import org.json.JSONObject

data class CursorPosition(
    val x: Float,
    val y: Float,
    val visible: Boolean,
    val sequence: Long? = null,
)

data class CursorImage(
    val png: ByteArray,
    val normalizedWidth: Float,
    val normalizedHeight: Float,
    val anchorX: Float,
    val anchorY: Float,
    val pixelWidth: Int,
    val pixelHeight: Int,
)

data class Welcome(
    val pv: Int,
    val min: Int,
)

data class UpdateRequired(
    val message: String,
    val target: String?,
    val store: String?,
)

object InboundControl {
    fun parse(payload: ByteArray): JSONObject? {
        return try {
            val obj = JSONObject(String(payload, Charsets.UTF_8))
            if (obj.optString("type").isEmpty()) null else obj
        } catch (_: Exception) {
            null
        }
    }

    fun cursorPosition(obj: JSONObject): CursorPosition? {
        val visible = obj.optInt("v", 0) != 0
        val sequence = if (obj.has("s") && !obj.isNull("s")) obj.optLong("s") else null
        if (!visible) {
            return CursorPosition(obj.optDouble("x", 0.5).toFloat(), obj.optDouble("y", 0.5).toFloat(), false, sequence)
        }
        if (!obj.has("x") || !obj.has("y")) return null
        val x = obj.optDouble("x")
        val y = obj.optDouble("y")
        if (!x.isFinite() || !y.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0) return null
        return CursorPosition(x.toFloat(), y.toFloat(), true, sequence)
    }

    fun cursorImage(obj: JSONObject): CursorImage? {
        val nw = obj.optDouble("nw")
        val nh = obj.optDouble("nh")
        val ax = obj.optDouble("ax")
        val ay = obj.optDouble("ay")
        val pngB64 = obj.optString("png")
        if (!nw.isFinite() || !nh.isFinite() || !ax.isFinite() || !ay.isFinite()) return null
        if (nw <= 0 || nh <= 0 || ax !in 0.0..1.0 || ay !in 0.0..1.0) return null
        val png = try {
            Base64.decode(pngB64, Base64.DEFAULT)
        } catch (_: Exception) {
            return null
        }
        if (png.size < 24) return null
        if (png[0] != 0x89.toByte() || png[1] != 0x50.toByte() || png[2] != 0x4E.toByte() || png[3] != 0x47.toByte()) {
            return null
        }
        val width = readBe32(png, 16)
        val height = readBe32(png, 20)
        if (width !in 1..512 || height !in 1..512) return null
        return CursorImage(
            png = png,
            normalizedWidth = nw.toFloat(),
            normalizedHeight = nh.toFloat(),
            anchorX = ax.toFloat(),
            anchorY = ay.toFloat(),
            pixelWidth = width,
            pixelHeight = height,
        )
    }

    fun welcome(obj: JSONObject): Welcome? {
        if (!obj.has("pv") || !obj.has("min")) return null
        return Welcome(obj.optInt("pv"), obj.optInt("min"))
    }

    fun updateRequired(obj: JSONObject): UpdateRequired {
        return UpdateRequired(
            message = obj.optString("message", "Update required"),
            target = obj.optString("target").ifEmpty { null },
            store = obj.optString("store").ifEmpty { null },
        )
    }

    fun logUnknownTypeOnce(seen: MutableSet<String>, type: String) {
        if (seen.add(type)) {
            Log.i(WireProtocol.LOG_TAG, "ignoring unknown control type: $type")
        }
    }

    private fun readBe32(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }
}
