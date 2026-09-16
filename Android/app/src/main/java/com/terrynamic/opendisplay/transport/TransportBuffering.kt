package com.terrynamic.opendisplay.transport

/**
 * Transport-adaptive buffering.
 *
 * On Android/Linux the accepted socket's `SO_RCVBUF` is copied from the
 * listening `ServerSocket` at `accept()`. Setting `receiveBufferSize` on the
 * accepted socket is too late — the kernel has already allocated the receive
 * queue. The listen socket is therefore sized for the larger Wi-Fi budget
 * (1 MiB). USB (loopback / adb tunnel) does not need that much in-flight
 * data; latency is kept down by a shallower decoder input queue (3 frames)
 * instead of shrinking the listen buffer, which would also starve Wi-Fi
 * sessions sharing the same `ServerSocket`.
 */
object TransportBuffering {
    const val USB_RECEIVE_BUFFER = 256 * 1024
    const val WIFI_RECEIVE_BUFFER = 1024 * 1024
    const val LISTEN_RECEIVE_BUFFER = WIFI_RECEIVE_BUFFER
    const val USB_QUEUE_FRAMES = 3
    const val WIFI_QUEUE_FRAMES = 8
    const val QUEUE_BYTES_CAP = 12 * 1024 * 1024

    fun queueFrames(transport: String): Int =
        if (transport.equals("usb", ignoreCase = true)) USB_QUEUE_FRAMES else WIFI_QUEUE_FRAMES
}
