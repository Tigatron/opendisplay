package com.terrynamic.opendisplay

import com.terrynamic.opendisplay.video.QualcommDecoderHints
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualcommDecoderHintsTest {
    @Test
    fun matches_qtiAndQcomPrefixes() {
        assertTrue(QualcommDecoderHints.matches("c2.qti.avc.decoder"))
        assertTrue(QualcommDecoderHints.matches("OMX.qcom.video.decoder.avc"))
        assertFalse(QualcommDecoderHints.matches("c2.android.avc.decoder"))
        assertFalse(QualcommDecoderHints.matches("OMX.google.h264.decoder"))
    }
}
