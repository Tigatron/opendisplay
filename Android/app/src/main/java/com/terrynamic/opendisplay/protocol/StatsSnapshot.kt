package com.terrynamic.opendisplay.protocol

data class StatsSnapshot(
    val transport: String,
    val fps: Double = 0.0,
    val mbps: Double = 0.0,
    val e2e50: Double? = null,
    val e2e95: Double? = null,
    val ph50: Double = 0.0,
    val ph95: Double = 0.0,
    val dec50: Double = 0.0,
    val stalls: Int = 0,
    val queue: Int = 0,
    val drops: Int = 0,
    val offsetKnown: Boolean = false,
    val streamWide: Int = 0,
    val streamHigh: Int = 0,
    val desktopPtWide: Int = 0,
    val desktopPtHigh: Int = 0,
    val capFps: Double? = null,
    val encDrops: Int? = null,
    val netDrops: Int? = null,
    val pending: Int? = null,
) {
    fun overlayText(): String {
        val e2e = if (e2e50 != null && e2e95 != null) {
            "${e2e50.round1()}/${e2e95.round1()}"
        } else {
            "—"
        }
        return buildString {
            appendLine("transport  $transport")
            appendLine("fps        ${fps.round1()}")
            appendLine("mbps       ${mbps.round2()}")
            appendLine("e2e50/95   $e2e")
            appendLine("ph50       ${ph50.round1()}")
            appendLine("dec50      ${dec50.round1()}")
            appendLine("queue      $queue")
            appendLine("stream     ${streamWide}x${streamHigh}")
            appendLine("desktop    ${desktopPtWide}x${desktopPtHigh}pt")
            appendLine("capFps     ${capFps?.round1() ?: "—"}")
            appendLine("encDrops   ${encDrops ?: "—"}")
            appendLine("netDrops   ${netDrops ?: "—"}")
            append("pending    ${pending ?: "—"}")
        }
    }

    private fun Double.round1(): String = String.format("%.1f", this)
    private fun Double.round2(): String = String.format("%.2f", this)
}
