package com.terrynamic.opendisplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceWakeStateTest {
    @Test
    fun unlocked_requiresInteractiveAndNoKeyguard() {
        assertTrue(DeviceWakeState(interactive = true, keyguardLocked = false).unlocked)
        assertFalse(DeviceWakeState(interactive = true, keyguardLocked = true).unlocked)
        assertFalse(DeviceWakeState(interactive = false, keyguardLocked = false).unlocked)
        assertFalse(DeviceWakeState(interactive = false, keyguardLocked = true).unlocked)
    }

    @Test
    fun probe_exposesInjectedSnapshot() {
        val probe = DeviceWakeProbe { DeviceWakeState(interactive = true, keyguardLocked = false) }
        assertTrue(probe.snapshot().unlocked)
    }
}
