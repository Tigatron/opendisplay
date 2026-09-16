package com.terrynamic.opendisplay

import com.terrynamic.opendisplay.transport.ListenerGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerGateTest {
    @Test
    fun idleOrWifi_bindsWildcard() {
        assertEquals(
            ListenerGate.WILDCARD,
            ListenerGate.bindHost(ListenerGate.SessionState(hasLiveSession = false)),
        )
        assertEquals(ListenerGate.WILDCARD, ListenerGate.bindHost(null))
        assertEquals(
            ListenerGate.WILDCARD,
            ListenerGate.bindHost(ListenerGate.SessionState(hasLiveSession = true, transport = "wifi")),
        )
        assertEquals(ListenerGate.WILDCARD, ListenerGate.bindHost("wifi"))
    }

    @Test
    fun usbLive_bindsLoopback() {
        assertEquals(
            ListenerGate.LOOPBACK,
            ListenerGate.bindHost(ListenerGate.SessionState(hasLiveSession = true, transport = "usb")),
        )
        assertEquals(ListenerGate.LOOPBACK, ListenerGate.bindHost("usb"))
        assertEquals(ListenerGate.LOOPBACK, ListenerGate.bindHost("USB"))
    }

    @Test
    fun usbEnded_returnsToWildcardImmediately() {
        val live = ListenerGate.SessionState(hasLiveSession = true, transport = "usb")
        assertEquals(ListenerGate.LOOPBACK, ListenerGate.bindHost(live))
        val ended = ListenerGate.SessionState(hasLiveSession = false, transport = "usb")
        assertEquals(ListenerGate.WILDCARD, ListenerGate.bindHost(ended))
    }

    @Test
    fun staleAccept_isRejected() {
        assertTrue(ListenerGate.acceptIsCurrent(4, 4))
        assertFalse(ListenerGate.acceptIsCurrent(3, 4))
        assertFalse(ListenerGate.acceptIsCurrent(4, 5))
    }
}
