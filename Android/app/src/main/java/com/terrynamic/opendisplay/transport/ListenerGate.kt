package com.terrynamic.opendisplay.transport

/**
 * USB-preferred listen policy. The stock Mac sender remembers every Bonjour
 * entry and will dial both the helper's `SM-X800 (USB)` (loopback) and the
 * receiver's own Wi-Fi advertisement. While a USB session is live, bind
 * loopback only so network dials get ECONNREFUSED and the Mac gives up on
 * Wi-Fi after three refusals. When the USB session ends, bind the wildcard
 * again so Wi-Fi fallback can reconnect within a second.
 *
 * Not tied to the helper heartbeat TTL — that is 15 s and would miss the
 * Mac's 10 s grace window.
 */
object ListenerGate {
    const val WILDCARD = "0.0.0.0"
    const val LOOPBACK = "127.0.0.1"

    data class SessionState(
        val hasLiveSession: Boolean,
        val transport: String? = null,
    )

    fun bindHost(state: SessionState): String {
        if (state.hasLiveSession && state.transport.equals("usb", ignoreCase = true)) {
            return LOOPBACK
        }
        return WILDCARD
    }

    fun bindHost(liveTransport: String?): String =
        bindHost(SessionState(hasLiveSession = liveTransport != null, transport = liveTransport))

    /** Accepts from a listen generation that is no longer current must be dropped. */
    fun acceptIsCurrent(acceptGeneration: Long, listenGeneration: Long): Boolean =
        acceptGeneration == listenGeneration
}
