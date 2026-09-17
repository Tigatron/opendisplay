package com.terrynamic.opendisplay

import android.app.Application
import android.app.KeyguardManager
import android.os.PowerManager

/** Real device wake/lock snapshot. Unlock requires both interactive and no keyguard. */
data class DeviceWakeState(
    val interactive: Boolean,
    val keyguardLocked: Boolean,
) {
    val unlocked: Boolean get() = interactive && !keyguardLocked
}

fun interface DeviceWakeProbe {
    fun snapshot(): DeviceWakeState

    companion object {
        fun system(app: Application): DeviceWakeProbe = DeviceWakeProbe {
            val pm = app.getSystemService(PowerManager::class.java)
            val km = app.getSystemService(KeyguardManager::class.java)
            DeviceWakeState(
                interactive = pm?.isInteractive == true,
                keyguardLocked = km?.isKeyguardLocked ?: true,
            )
        }
    }
}
