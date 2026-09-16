package com.terrynamic.opendisplay

import com.terrynamic.opendisplay.video.DecodeCeiling

data class SettingsPatch(
    val serviceName: String? = null,
    val decodeCeiling: DecodeCeiling? = null,
    val virtualDesktop: VirtualDesktopSize? = null,
    val statsOverlay: Boolean? = null,
    val cursorUdp: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = serviceName == null &&
            decodeCeiling == null &&
            virtualDesktop == null &&
            statsOverlay == null &&
            cursorUdp == null

    fun applyTo(current: AppSettings): AppSettings = current.copy(
        serviceName = serviceName ?: current.serviceName,
        decodeCeiling = decodeCeiling ?: current.decodeCeiling,
        virtualDesktop = virtualDesktop ?: current.virtualDesktop,
        showStats = statsOverlay ?: current.showStats,
        cursorUdp = cursorUdp ?: current.cursorUdp,
    )

    val helloRelevant: Boolean
        get() = decodeCeiling != null || virtualDesktop != null
}

object SettingsValues {
    private val SIZE = Regex("""^(\d{2,5})x(\d{2,5})$""")

    fun parseDecodeCeiling(raw: String): DecodeCeiling? {
        val value = raw.trim().lowercase()
        if (value.isEmpty()) return null
        return when (value) {
            "auto" -> DecodeCeiling.Auto
            "panel" -> DecodeCeiling.Panel
            "off" -> DecodeCeiling.Off
            "fhd", "1920x1080" -> DecodeCeiling.Fhd
            else -> {
                val match = SIZE.matchEntire(value) ?: return null
                val w = match.groupValues[1].toInt()
                val h = match.groupValues[2].toInt()
                if (w < 16 || h < 16) return null
                DecodeCeiling.Custom(w, h)
            }
        }
    }

    fun parseVirtualDesktop(raw: String): VirtualDesktopSize? {
        val value = raw.trim().removeSuffix("%")
        return when (value) {
            "100" -> VirtualDesktopSize.P100
            "125" -> VirtualDesktopSize.P125
            "150" -> VirtualDesktopSize.P150
            else -> null
        }
    }

    fun parseFlag(raw: String): Boolean? {
        return when (raw.trim().lowercase()) {
            "1", "true", "on" -> true
            "0", "false", "off" -> false
            else -> null
        }
    }

    fun parseServiceName(raw: String): String? {
        val value = raw.trim()
        return value.takeIf { it.isNotEmpty() }
    }

    fun parsePatch(values: Map<String, String>): SettingsPatch {
        return SettingsPatch(
            serviceName = values["serviceName"]?.let { parseServiceName(it) },
            decodeCeiling = values["decodeCeiling"]?.let { parseDecodeCeiling(it) },
            virtualDesktop = values["virtualDesktop"]?.let { parseVirtualDesktop(it) },
            statsOverlay = values["statsOverlay"]?.let { parseFlag(it) },
            cursorUdp = values["cursorUdp"]?.let { parseFlag(it) },
        )
    }

    fun isPrivilegedUid(uid: Int): Boolean = uid == 0 || uid == SHELL_UID

    const val SHELL_UID = 2000
}
