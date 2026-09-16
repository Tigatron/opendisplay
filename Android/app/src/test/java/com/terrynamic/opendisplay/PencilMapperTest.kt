package com.terrynamic.opendisplay

import com.terrynamic.opendisplay.input.PencilMapper
import com.terrynamic.opendisplay.protocol.ControlMessages
import com.terrynamic.opendisplay.protocol.WireMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class PencilMapperTest {
    @Test
    fun axisConversion_matchesProtocolConvention() {
        assertEquals(-PI / 2.0, PencilMapper.azimuthFromOrientation(0.0), 1e-9)
        assertEquals(0.0, PencilMapper.azimuthFromOrientation(PI / 2.0), 1e-9)
        assertEquals(PI / 2.0, PencilMapper.azimuthFromOrientation(PI), 1e-9)
        assertEquals(PI / 2.0, PencilMapper.altitudeFromTilt(0.0), 1e-9)
        assertEquals(0.0, PencilMapper.altitudeFromTilt(PI / 2.0), 1e-9)
        assertEquals(0.0, PencilMapper.clampPressure(-1f), 0.0)
        assertEquals(1.0, PencilMapper.clampPressure(2f), 0.0)
    }

    @Test
    fun gating_degradesBelowPv3AndBeforeWelcome() {
        val down = PencilMapper.routeContact(
            senderPv = 1,
            stylus = true,
            action = PencilMapper.ContactAction.Down,
            x = 0.2,
            y = 0.3,
            pressure = 0.8f,
            orientationRad = 0.0,
            tiltRad = 0.0,
        )
        assertTrue(down is PencilMapper.Routed.Touch)
        assertEquals("began", (down as PencilMapper.Routed.Touch).phase)

        val pencil = PencilMapper.routeContact(
            senderPv = 3,
            stylus = true,
            action = PencilMapper.ContactAction.Down,
            x = 0.2,
            y = 0.3,
            pressure = 0.8f,
            orientationRad = 0.0,
            tiltRad = 0.0,
        )
        assertTrue(pencil is PencilMapper.Routed.Pencil)
        assertEquals("down", (pencil as PencilMapper.Routed.Pencil).phase)
        assertEquals(0.8, pencil.pressure, 1e-6)

        val finger = PencilMapper.routeContact(
            senderPv = 3,
            stylus = false,
            action = PencilMapper.ContactAction.Up,
            x = 0.1,
            y = 0.1,
            pressure = 0f,
            orientationRad = 0.0,
            tiltRad = 0.0,
        )
        assertTrue(finger is PencilMapper.Routed.Touch)
        assertEquals("ended", (finger as PencilMapper.Routed.Touch).phase)
        assertFalse(PencilMapper.supportsPencil(2))
        assertTrue(PencilMapper.supportsPencil(3))
    }

    @Test
    fun hoverProximitySequence() {
        val enter = PencilMapper.routeHover(
            senderPv = 3,
            stylus = true,
            action = PencilMapper.HoverAction.Enter,
            x = 0.4,
            y = 0.5,
            orientationRad = 0.0,
            tiltRad = 0.1,
        )
        val move = PencilMapper.routeHover(
            senderPv = 3,
            stylus = true,
            action = PencilMapper.HoverAction.Move,
            x = 0.41,
            y = 0.52,
            orientationRad = 0.0,
            tiltRad = 0.1,
        )
        val exit = PencilMapper.routeHover(
            senderPv = 3,
            stylus = true,
            action = PencilMapper.HoverAction.Exit,
            x = 0.41,
            y = 0.52,
            orientationRad = 0.0,
            tiltRad = 0.1,
        )
        assertTrue(enter is PencilMapper.Routed.Proximity)
        assertTrue((enter as PencilMapper.Routed.Proximity).entering)
        assertTrue(move is PencilMapper.Routed.Pencil)
        assertEquals("hover", (move as PencilMapper.Routed.Pencil).phase)
        assertFalse((exit as PencilMapper.Routed.Proximity).entering)
        assertNull(
            PencilMapper.routeHover(
                senderPv = 2,
                stylus = true,
                action = PencilMapper.HoverAction.Enter,
                x = 0.1,
                y = 0.1,
                orientationRad = 0.0,
                tiltRad = 0.0,
            ),
        )
    }

    @Test
    fun wireJson_pencilAndProximity() {
        val pencil = ControlMessages.pencil("down", 0.2, 0.3, 0.5, -PI / 2, PI / 2, macClockMs = 9.0)
        assertEquals(WireMessage.PENCIL, pencil.getString("type"))
        assertEquals(0.0, pencil.getDouble("rotation"), 0.0)
        assertEquals(9.0, pencil.getDouble("t"), 0.0)
        val prox = ControlMessages.proximity(true, 0.2, 0.3)
        assertEquals(WireMessage.PROXIMITY, prox.getString("type"))
        assertTrue(prox.getBoolean("entering"))
    }
}
