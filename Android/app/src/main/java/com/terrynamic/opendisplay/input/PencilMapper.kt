package com.terrynamic.opendisplay.input

import com.terrynamic.opendisplay.protocol.WireProtocol
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Maps Android stylus [MotionEvent] axes onto PROTOCOL.md §6.1 `pencil` /
 * `proximity`. Stylus button presses (`ACTION_BUTTON_PRESS` /
 * `ACTION_BUTTON_RELEASE`, including the S Pen side button) are ignored
 * for now — the official sender has no button field on `pencil`.
 *
 * Axis conversion:
 * - Android `AXIS_ORIENTATION` is 0 when the stylus points toward the top
 *   of the screen (clockwise positive). UIKit / protocol azimuth is 0 when
 *   the stylus points toward the view's +x (right). So
 *   `azimuth = wrapPi(orientation - π/2)`.
 * - Android `AXIS_TILT` is 0 when perpendicular to the glass and π/2 when
 *   flat. Protocol altitude is π/2 when perpendicular. So
 *   `altitude = π/2 - tilt`.
 */
object PencilMapper {
    sealed class Routed {
        data class Pencil(
            val phase: String,
            val x: Double,
            val y: Double,
            val pressure: Double,
            val azimuth: Double,
            val altitude: Double,
        ) : Routed()

        data class Proximity(
            val entering: Boolean,
            val x: Double,
            val y: Double,
        ) : Routed()

        data class Touch(
            val phase: String,
            val x: Double,
            val y: Double,
        ) : Routed()
    }

    enum class ContactAction { Down, Move, Up, Cancel }
    enum class HoverAction { Enter, Move, Exit }

    fun supportsPencil(senderPv: Int): Boolean =
        senderPv >= WireProtocol.PENCIL_WIRE_VERSION

    fun isStylusTool(toolType: Int): Boolean =
        toolType == TOOL_STYLUS || toolType == TOOL_ERASER

    fun azimuthFromOrientation(orientationRad: Double): Double =
        wrapPi(orientationRad - PI / 2.0)

    fun altitudeFromTilt(tiltRad: Double): Double =
        (PI / 2.0 - tiltRad).coerceIn(0.0, PI / 2.0)

    fun clampPressure(pressure: Float): Double =
        pressure.toDouble().coerceIn(0.0, 1.0)

    fun routeContact(
        senderPv: Int,
        stylus: Boolean,
        action: ContactAction,
        x: Double,
        y: Double,
        pressure: Float,
        orientationRad: Double,
        tiltRad: Double,
    ): Routed {
        if (!stylus || !supportsPencil(senderPv)) {
            return Routed.Touch(phase = touchPhase(action), x = x, y = y)
        }
        return Routed.Pencil(
            phase = pencilPhase(action),
            x = x,
            y = y,
            pressure = clampPressure(pressure),
            azimuth = azimuthFromOrientation(orientationRad),
            altitude = altitudeFromTilt(tiltRad),
        )
    }

    fun routeHover(
        senderPv: Int,
        stylus: Boolean,
        action: HoverAction,
        x: Double,
        y: Double,
        orientationRad: Double,
        tiltRad: Double,
    ): Routed? {
        if (!stylus || !supportsPencil(senderPv)) return null
        return when (action) {
            HoverAction.Enter -> Routed.Proximity(entering = true, x = x, y = y)
            HoverAction.Exit -> Routed.Proximity(entering = false, x = x, y = y)
            HoverAction.Move -> Routed.Pencil(
                phase = "hover",
                x = x,
                y = y,
                pressure = 0.0,
                azimuth = azimuthFromOrientation(orientationRad),
                altitude = altitudeFromTilt(tiltRad),
            )
        }
    }

    private fun pencilPhase(action: ContactAction): String = when (action) {
        ContactAction.Down -> "down"
        ContactAction.Move -> "move"
        ContactAction.Up, ContactAction.Cancel -> "up"
    }

    private fun touchPhase(action: ContactAction): String = when (action) {
        ContactAction.Down -> "began"
        ContactAction.Move -> "moved"
        ContactAction.Up -> "ended"
        ContactAction.Cancel -> "cancelled"
    }

    private fun wrapPi(radians: Double): Double {
        return atan2(sin(radians), cos(radians))
    }

    const val TOOL_STYLUS = 2
    const val TOOL_ERASER = 4
}
