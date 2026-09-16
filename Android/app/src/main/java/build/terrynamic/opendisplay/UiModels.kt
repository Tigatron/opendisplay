package build.terrynamic.opendisplay

import android.graphics.Bitmap
import build.terrynamic.opendisplay.protocol.WireProtocol

enum class ReceiverPhase {
    IDLE,
    STREAMING,
    BLOCKED,
}

data class CursorUi(
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val visible: Boolean = false,
    val bitmap: Bitmap? = null,
    val normalizedWidth: Float = 0f,
    val normalizedHeight: Float = 0f,
    val anchorX: Float = 0f,
    val anchorY: Float = 0f,
)

data class UiState(
    val phase: ReceiverPhase = ReceiverPhase.IDLE,
    val status: String = "Starting",
    val serviceName: String = WireProtocol.DEFAULT_SERVICE_NAME,
    val registeredName: String = WireProtocol.DEFAULT_SERVICE_NAME,
    val installId: String = "",
    val installIdShort: String = "",
    val port: Int = WireProtocol.DEFAULT_PORT,
    val listening: Boolean = false,
    val usbHelperActive: Boolean = false,
    val transport: String? = null,
    val showStats: Boolean = false,
    val statsText: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val cursor: CursorUi = CursorUi(),
    val updateMessage: String? = null,
    val settings: AppSettings = AppSettings(
        serviceName = WireProtocol.DEFAULT_SERVICE_NAME,
        decodeCeiling = build.terrynamic.opendisplay.video.DecodeCeiling.Auto,
        virtualDesktop = VirtualDesktopSize.P100,
        showStats = false,
    ),
    val senderTooOld: Boolean = false,
)
