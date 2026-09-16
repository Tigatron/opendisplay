package com.terrynamic.opendisplay.protocol

import org.json.JSONObject

data class SenderHealth(
    val capFps: Double? = null,
    val encDrops: Int? = null,
    val netDrops: Int? = null,
    val pending: Int? = null,
    val inp50: Double? = null,
    val inp95: Double? = null,
) {
    companion object {
        fun fromPing(obj: JSONObject): SenderHealth = SenderHealth(
            capFps = obj.optFinite("capFps"),
            encDrops = obj.optIntOrNull("encDrops"),
            netDrops = obj.optIntOrNull("netDrops"),
            pending = obj.optIntOrNull("pending"),
            inp50 = obj.optFinite("inp50"),
            inp95 = obj.optFinite("inp95"),
        )

        private fun JSONObject.optFinite(key: String): Double? {
            if (!has(key) || isNull(key)) return null
            val value = optDouble(key, Double.NaN)
            return value.takeIf { it.isFinite() }
        }

        private fun JSONObject.optIntOrNull(key: String): Int? {
            if (!has(key) || isNull(key)) return null
            return optInt(key)
        }
    }
}
