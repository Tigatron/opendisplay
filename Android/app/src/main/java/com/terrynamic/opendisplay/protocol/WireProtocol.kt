package com.terrynamic.opendisplay.protocol

object WireProtocol {
    const val VERSION = 3
    const val MIN_SUPPORTED_PEER = 1
    const val ASSUMED_WHEN_ABSENT = 1
    const val DEFAULT_PORT = 9000
    const val SERVICE_TYPE = "_opensidecar._tcp."
    const val DEFAULT_SERVICE_NAME = "BUILD.TERRYNAMIC"
    const val KEYFRAME_REASON_BACKPRESSURE = "backpressure"
    const val LOG_TAG = "OpenDisplay"
    const val PING_INTERVAL_MS = 2_000L
    const val WATCHDOG_IDLE_MS = 8_000L
    const val STATS_INTERVAL_MS = 5_000L
    const val NEWCOMER_PARK_MS = 3_000L
    const val MAX_INBOUND_FRAME = 16 * 1024 * 1024
    const val MAX_OUTBOUND_FRAME = (1 shl 20) - 1
    const val DEMUX_CONTROL_MAX = 32_768
}

object WireMessage {
    const val HELLO = "hello"
    const val PING = "ping"
    const val PONG = "pong"
    const val TOUCH = "touch"
    const val SCROLL = "scroll"
    const val KEYFRAME = "kf"
    const val STATS = "stats"
    const val WELCOME = "welcome"
    const val UPDATE_REQUIRED = "updateRequired"
    const val SLEEPING = "sleeping"
    const val CLOSING = "closing"
    const val CURSOR = "cursor"
    const val CURSOR_IMG = "cursorImg"
    const val CURSOR_ACK = "cursorAck"
}
