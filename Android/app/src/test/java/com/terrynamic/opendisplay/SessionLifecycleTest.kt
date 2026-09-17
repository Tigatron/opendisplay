package com.terrynamic.opendisplay

import com.terrynamic.opendisplay.session.CloseDecision
import com.terrynamic.opendisplay.session.SessionCloseGuard
import com.terrynamic.opendisplay.session.SessionLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class SessionLifecycleTest {
    @Test
    fun staleCloseAfterAdopt_keepsStreamingSurfaceAndTransport() {
        val life = SessionLifecycle { it() }
        life.retainSurface("held")
        life.adopt(2, "usb")
        val resetsAfterAdopt = life.decoderResetCount

        assertFalse(life.close(1))

        assertEquals(ReceiverPhase.STREAMING, life.phase)
        assertEquals("usb", life.transport)
        assertEquals(2L, life.generation)
        assertEquals(resetsAfterAdopt, life.decoderResetCount)
        assertEquals("held", life.lastSurfaceToken)
        assertEquals(1, life.surfaceReattachCount)
    }

    @Test
    fun currentClose_resetsToIdle() {
        val life = SessionLifecycle { it() }
        life.retainSurface("held")
        life.adopt(4, "usb")

        assertTrue(life.close(4))

        assertEquals(ReceiverPhase.IDLE, life.phase)
        assertNull(life.transport)
        assertEquals(2, life.decoderResetCount)
        assertEquals("held", life.lastSurfaceToken)
    }

    @Test
    fun closeWhileIdle_stillApplies() {
        val life = SessionLifecycle { it() }
        assertEquals(0L, life.generation)
        assertTrue(life.close(9))
        assertEquals(ReceiverPhase.IDLE, life.phase)
        assertEquals(1, life.decoderResetCount)
        assertEquals(CloseDecision.Apply, SessionCloseGuard.decide(9, 0L))
    }

    @Test
    fun queuedStaleCloseAfterAdopt_doesNotResetNewSession() {
        val queue = ArrayDeque<() -> Unit>()
        val life = SessionLifecycle { queue.add(it) }

        life.adopt(1, "wifi")
        drain(queue)
        life.retainSurface("held")

        life.adopt(2, "usb")
        life.close(1)
        drain(queue)

        assertEquals(ReceiverPhase.STREAMING, life.phase)
        assertEquals("usb", life.transport)
        assertEquals(2, life.decoderResetCount)
        assertEquals("held", life.lastSurfaceToken)
        assertEquals(CloseDecision.IgnoreStale, SessionCloseGuard.decide(1, 2))
    }

    @Test
    fun queuedCurrentCloseThenAdopt_endsStreaming() {
        val queue = ArrayDeque<() -> Unit>()
        val life = SessionLifecycle { queue.add(it) }

        life.adopt(1, "wifi")
        drain(queue)

        life.close(1)
        life.adopt(2, "usb")
        drain(queue)

        assertEquals(ReceiverPhase.STREAMING, life.phase)
        assertEquals("usb", life.transport)
        assertEquals(3, life.decoderResetCount)
    }

    private fun drain(queue: ArrayDeque<() -> Unit>) {
        while (queue.isNotEmpty()) queue.removeFirst().invoke()
    }
}
