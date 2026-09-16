package com.terrynamic.opendisplay

import com.terrynamic.opendisplay.video.DecodeCeiling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsValuesTest {
    @Test
    fun decodeCeiling_wireForms() {
        assertEquals(DecodeCeiling.Auto, SettingsValues.parseDecodeCeiling("auto"))
        assertEquals(DecodeCeiling.Panel, SettingsValues.parseDecodeCeiling("panel"))
        assertEquals(DecodeCeiling.Off, SettingsValues.parseDecodeCeiling("off"))
        assertEquals(DecodeCeiling.Fhd, SettingsValues.parseDecodeCeiling("1920x1080"))
        assertEquals(DecodeCeiling.Custom(2560, 1440), SettingsValues.parseDecodeCeiling("2560x1440"))
        assertNull(SettingsValues.parseDecodeCeiling("nope"))
        assertNull(SettingsValues.parseDecodeCeiling("8x8"))
    }

    @Test
    fun virtualDesktop_andFlags() {
        assertEquals(VirtualDesktopSize.P100, SettingsValues.parseVirtualDesktop("100"))
        assertEquals(VirtualDesktopSize.P125, SettingsValues.parseVirtualDesktop("125"))
        assertEquals(VirtualDesktopSize.P150, SettingsValues.parseVirtualDesktop("150%"))
        assertNull(SettingsValues.parseVirtualDesktop("200"))
        assertEquals(true, SettingsValues.parseFlag("1"))
        assertEquals(false, SettingsValues.parseFlag("0"))
        assertNull(SettingsValues.parseFlag("maybe"))
    }

    @Test
    fun patch_appliesRecognizedKeys() {
        val current = AppSettings(
            serviceName = "OLD",
            decodeCeiling = DecodeCeiling.Auto,
            virtualDesktop = VirtualDesktopSize.P100,
            showStats = false,
            cursorUdp = true,
        )
        val patch = SettingsValues.parsePatch(
            mapOf(
                "serviceName" to "BUILD.TERRYNAMIC",
                "decodeCeiling" to "1920x1080",
                "virtualDesktop" to "150",
                "statsOverlay" to "1",
                "cursorUdp" to "0",
            ),
        )
        assertTrue(patch.helloRelevant)
        val next = patch.applyTo(current)
        assertEquals("BUILD.TERRYNAMIC", next.serviceName)
        assertEquals(DecodeCeiling.Fhd, next.decodeCeiling)
        assertEquals(VirtualDesktopSize.P150, next.virtualDesktop)
        assertTrue(next.showStats)
        assertFalse(next.cursorUdp)
    }

    @Test
    fun privilegedUids() {
        assertTrue(SettingsValues.isPrivilegedUid(0))
        assertTrue(SettingsValues.isPrivilegedUid(SettingsValues.SHELL_UID))
        assertFalse(SettingsValues.isPrivilegedUid(10123))
    }
}
