package com.terrynamic.opendisplay

import com.terrynamic.opendisplay.protocol.PanelReadyGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelReadyGateTest {
    @Test
    fun startsUnknown_andRejectsPlaceholderSizedDefaults() {
        val gate = PanelReadyGate()
        assertFalse(gate.isReady)
        assertEquals(0, gate.wide)
        assertEquals(0, gate.high)
        assertFalse(gate.update(0, 1080, 2f, 600))
        assertFalse(gate.update(1920, 0, 2f, 600))
        assertFalse(gate.isReady)
    }

    @Test
    fun firstValidUpdate_makesReadyAndReportsChange() {
        val gate = PanelReadyGate()
        assertTrue(gate.update(2800, 1752, 1.875f, 600))
        assertTrue(gate.isReady)
        assertEquals(2800, gate.wide)
        assertEquals(1752, gate.high)
        assertFalse(gate.update(2800, 1752, 1.875f, 600))
        assertTrue(gate.update(1752, 2800, 1.875f, 600))
    }

    @Test
    fun shouldBindListener_requiresEnabledReadyAndUnbound() {
        assertFalse(PanelReadyGate.shouldBindListener(listeningEnabled = true, panelReady = false, alreadyBound = false))
        assertFalse(PanelReadyGate.shouldBindListener(listeningEnabled = false, panelReady = true, alreadyBound = false))
        assertFalse(PanelReadyGate.shouldBindListener(listeningEnabled = true, panelReady = true, alreadyBound = true))
        assertTrue(PanelReadyGate.shouldBindListener(listeningEnabled = true, panelReady = true, alreadyBound = false))
    }

    @Test
    fun awaitReady_returnsTrueWhenReadyBeforeDeadline() {
        var now = 0L
        var ready = false
        val sleeps = ArrayList<Long>()
        val ok = PanelReadyGate.awaitReady(
            isReady = { ready },
            timeoutMs = 1_500,
            nowMs = { now },
            sleepMs = { slice ->
                sleeps.add(slice)
                now += slice
                if (now >= 40L) ready = true
            },
        )
        assertTrue(ok)
        assertTrue(sleeps.isNotEmpty())
        assertTrue(sleeps.all { it <= 20L })
    }

    @Test
    fun awaitReady_returnsFalseOnTimeout() {
        var now = 0L
        val ok = PanelReadyGate.awaitReady(
            isReady = { false },
            timeoutMs = 80,
            nowMs = { now },
            sleepMs = { slice -> now += slice },
        )
        assertFalse(ok)
        assertTrue(now >= 80L)
    }
}
