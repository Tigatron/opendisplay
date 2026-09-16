package com.terrynamic.opendisplay.protocol

import org.json.JSONArray
import org.json.JSONObject

object ControlMessages {

    fun hello(
        pixelsWide: Int,
        pixelsHigh: Int,
        scale: Float,
        device: String,
        installId: String,
        protocolVersion: Int = WireProtocol.VERSION,
        maxEncodeWide: Int? = null,
        maxEncodeHigh: Int? = null,
        addrs: List<String> = emptyList(),
    ): JSONObject = JSONObject()
        .put("type", WireMessage.HELLO)
        .put("pixelsWide", pixelsWide)
        .put("pixelsHigh", pixelsHigh)
        .put("scale", scale.toDouble())
        .put("device", device)
        .put("id", installId)
        .put("pv", protocolVersion)
        .also { json ->
            if (maxEncodeWide != null && maxEncodeHigh != null) {
                json.put("maxEncodeWide", maxEncodeWide)
                json.put("maxEncodeHigh", maxEncodeHigh)
            }
            if (addrs.isNotEmpty()) {
                json.put("addrs", JSONArray(addrs))
            }
        }

    fun touch(
        phase: String,
        x: Double,
        y: Double,
        macClockMs: Double? = null,
    ): JSONObject = JSONObject()
        .put("type", WireMessage.TOUCH)
        .put("phase", phase)
        .put("x", x)
        .put("y", y)
        .also { if (macClockMs != null) it.put("t", macClockMs) }

    fun scroll(dx: Double, dy: Double): JSONObject = JSONObject()
        .put("type", WireMessage.SCROLL)
        .put("dx", dx)
        .put("dy", dy)

    fun ping(tMs: Double): JSONObject = JSONObject()
        .put("type", WireMessage.PING)
        .put("t", tMs)

    fun keyframeRequest(reason: String? = null): JSONObject = JSONObject()
        .put("type", WireMessage.KEYFRAME)
        .also { if (reason != null) it.put("reason", reason) }

    fun stats(
        transport: String,
        fps: Double,
        mbps: Double,
        ph50: Double,
        ph95: Double,
        dec50: Double,
        stalls: Int,
        queue: Int,
        drops: Int,
        offsetKnown: Boolean,
        e2e50: Double? = null,
        e2e95: Double? = null,
    ): JSONObject = JSONObject()
        .put("type", WireMessage.STATS)
        .put("transport", transport)
        .put("fps", fps)
        .put("mbps", mbps)
        .put("ph50", ph50)
        .put("ph95", ph95)
        .put("dec50", dec50)
        .put("stalls", stalls)
        .put("queue", queue)
        .put("drops", drops)
        .put("offsetKnown", offsetKnown)
        .also { json ->
            if (offsetKnown && e2e50 != null && e2e95 != null) {
                json.put("e2e50", e2e50)
                json.put("e2e95", e2e95)
            }
        }

    fun sleeping(): JSONObject = JSONObject().put("type", WireMessage.SLEEPING)

    fun closing(): JSONObject = JSONObject().put("type", WireMessage.CLOSING)
}
