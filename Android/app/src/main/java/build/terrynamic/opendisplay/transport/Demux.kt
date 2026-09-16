package build.terrynamic.opendisplay.transport

import build.terrynamic.opendisplay.protocol.WireProtocol

enum class FrameKind { CONTROL, VIDEO }

object Demux {
    fun classify(payload: ByteArray): FrameKind {
        if (payload.size < WireProtocol.DEMUX_CONTROL_MAX &&
            payload.isNotEmpty() &&
            payload[0] == '{'.code.toByte() &&
            payload.none { it == 0.toByte() }
        ) {
            return FrameKind.CONTROL
        }
        return FrameKind.VIDEO
    }
}
