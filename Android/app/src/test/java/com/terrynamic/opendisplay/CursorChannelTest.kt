package com.terrynamic.opendisplay

import com.terrynamic.opendisplay.cursor.CursorChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorChannelTest {
    @Test
    fun mixedTcpUdp_dedupesBySharedSequence() {
        val ch = CursorChannel()
        assertTrue(ch.onUdp("10.0.0.1", 40000, 1).apply)
        assertFalse(ch.onTcp(1).apply)
        assertTrue(ch.onTcp(2).apply)
        assertFalse(ch.onUdp("10.0.0.1", 40000, 2).apply)
        assertTrue(ch.onUdp("10.0.0.1", 40000, 5).apply)
        assertEquals(2, ch.cursorLost)
        assertEquals(3, ch.cursorUpdates)
        assertTrue(ch.onTcp(null).apply)
        assertEquals(4, ch.cursorUpdates)
    }

    @Test
    fun flowSwitch_resetsSequenceAndAcksOnce() {
        val ch = CursorChannel()
        val first = ch.onUdp("10.0.0.1", 40000, 10)
        assertTrue(first.apply)
        assertTrue(first.sendAck)
        val same = ch.onUdp("10.0.0.1", 40000, 11)
        assertTrue(same.apply)
        assertFalse(same.sendAck)
        val switched = ch.onUdp("10.0.0.8", 40001, 1)
        assertTrue(switched.apply)
        assertTrue(switched.sendAck)
        assertEquals(1, ch.lastSequence)
        val third = ch.onUdp("10.0.0.8", 40001, 2)
        assertFalse(third.sendAck)
    }

    @Test
    fun tcpWithoutSeq_appliesUnconditionally() {
        val ch = CursorChannel()
        ch.onUdp("10.0.0.1", 1, 4)
        assertTrue(ch.onTcp(null).apply)
        assertEquals(4, ch.lastSequence)
        assertFalse(ch.onTcp(3).apply)
        assertTrue(ch.onTcp(5).apply)
    }

    @Test
    fun helloField_byTransportAndSetting() {
        assertEquals(9001, CursorChannel.advertisedPort(true, false, 9001))
        assertNull(CursorChannel.advertisedPort(true, true, 9001))
        assertNull(CursorChannel.advertisedPort(false, false, 9001))
        assertNull(CursorChannel.advertisedPort(true, false, null))
    }

    @Test
    fun resetSession_clearsFlowAndCounts() {
        val ch = CursorChannel()
        ch.onUdp("10.0.0.1", 9, 3)
        ch.resetSession()
        assertEquals(0, ch.lastSequence)
        assertEquals(0, ch.cursorUpdates)
        assertEquals(0, ch.cursorLost)
        assertNull(ch.currentRemote)
        val again = ch.onUdp("10.0.0.1", 9, 1)
        assertTrue(again.sendAck)
    }
}
