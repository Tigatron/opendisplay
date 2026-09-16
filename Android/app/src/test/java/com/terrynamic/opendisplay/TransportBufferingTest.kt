package com.terrynamic.opendisplay

import com.terrynamic.opendisplay.transport.TransportBuffering
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportBufferingTest {
    @Test
    fun queueDepth_usbIsShallowerThanWifi() {
        assertEquals(3, TransportBuffering.queueFrames("usb"))
        assertEquals(3, TransportBuffering.queueFrames("USB"))
        assertEquals(8, TransportBuffering.queueFrames("wifi"))
        assertEquals(8, TransportBuffering.queueFrames("other"))
    }

    @Test
    fun listenBuffer_isTheWifiBudget() {
        assertEquals(TransportBuffering.WIFI_RECEIVE_BUFFER, TransportBuffering.LISTEN_RECEIVE_BUFFER)
        assertTrue(TransportBuffering.WIFI_RECEIVE_BUFFER > TransportBuffering.USB_RECEIVE_BUFFER)
        assertEquals(12 * 1024 * 1024, TransportBuffering.QUEUE_BYTES_CAP)
    }
}
