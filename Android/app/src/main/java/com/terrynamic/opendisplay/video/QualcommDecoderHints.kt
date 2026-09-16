package com.terrynamic.opendisplay.video

import android.media.MediaFormat

/**
 * Qualcomm c2/OMX AVC decoders can hold the last picture until the next
 * access unit (1-frame output lag). These vendor keys request decode-order
 * low-latency output; the Mac stream has no B-frames.
 */
object QualcommDecoderHints {
    const val VENDOR_LOW_LATENCY = "vendor.qti-ext-dec-low-latency.enable"
    const val VENDOR_PICTURE_ORDER = "vendor.qti-ext-dec-picture-order.enable"

    fun matches(codecName: String): Boolean {
        val name = codecName
        return name.startsWith("c2.qti.") || name.startsWith("OMX.qcom.")
    }

    fun apply(format: MediaFormat) {
        format.setInteger(VENDOR_LOW_LATENCY, 1)
        format.setInteger(VENDOR_PICTURE_ORDER, 1)
    }
}
