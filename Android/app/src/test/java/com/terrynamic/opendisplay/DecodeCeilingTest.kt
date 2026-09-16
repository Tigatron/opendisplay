package com.terrynamic.opendisplay

import com.terrynamic.opendisplay.video.DecodeCeiling
import com.terrynamic.opendisplay.video.DecodeCeilingResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecodeCeilingTest {
    @Test
    fun enlargedDesktop_autoAndPanel_advertisePhysicalPanel() {
        val auto = DecodeCeilingResolver.resolve(DecodeCeiling.Auto, 2800, 1752, desktopFactor = 1.5f)!!
        val panel = DecodeCeilingResolver.resolve(DecodeCeiling.Panel, 2800, 1752, desktopFactor = 1.25f)!!
        assertEquals(2800, auto.wide)
        assertEquals(1752, auto.high)
        assertEquals(2800, panel.wide)
        assertEquals(1752, panel.high)
    }

    @Test
    fun evenPixels_andOffOmits() {
        val custom = DecodeCeilingResolver.resolve(
            DecodeCeiling.Custom(1921, 1081),
            2800,
            1752,
            desktopFactor = 1.5f,
        )!!
        assertEquals(1920, custom.wide)
        assertEquals(1080, custom.high)
        assertNull(DecodeCeilingResolver.resolve(DecodeCeiling.Off, 2800, 1752, 1.5f))
    }

    @Test
    fun panelAt100_isPhysicalPanel() {
        val limit = DecodeCeilingResolver.resolve(DecodeCeiling.Panel, 2800, 1752, 1.0f)!!
        assertEquals(2800, limit.wide)
        assertEquals(1752, limit.high)
    }
}
