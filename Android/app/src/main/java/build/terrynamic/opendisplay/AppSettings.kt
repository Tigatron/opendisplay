package build.terrynamic.opendisplay

import android.content.Context
import build.terrynamic.opendisplay.protocol.WireProtocol
import build.terrynamic.opendisplay.video.DecodeCeiling

enum class VirtualDesktopSize(val factor: Float, val storageKey: String) {
    P100(1.00f, "100"),
    P125(1.25f, "125"),
    P150(1.50f, "150"),
    ;

    companion object {
        fun fromStorage(value: String?): VirtualDesktopSize =
            entries.firstOrNull { it.storageKey == value } ?: P100
    }
}

data class AppSettings(
    val serviceName: String,
    val decodeCeiling: DecodeCeiling,
    val virtualDesktop: VirtualDesktopSize,
    val showStats: Boolean,
)

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        serviceName = prefs.getString(KEY_NAME, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: WireProtocol.DEFAULT_SERVICE_NAME,
        decodeCeiling = DecodeCeiling.fromStorage(prefs.getString(KEY_CEILING, null)),
        virtualDesktop = VirtualDesktopSize.fromStorage(prefs.getString(KEY_DESKTOP, null)),
        showStats = prefs.getBoolean(KEY_STATS, false),
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_NAME, settings.serviceName.trim().ifEmpty { WireProtocol.DEFAULT_SERVICE_NAME })
            .putString(KEY_CEILING, settings.decodeCeiling.storageKey)
            .putString(KEY_DESKTOP, settings.virtualDesktop.storageKey)
            .putBoolean(KEY_STATS, settings.showStats)
            .apply()
    }

    companion object {
        private const val PREFS = "opendisplay"
        private const val KEY_NAME = "serviceName"
        private const val KEY_CEILING = "decodeCeiling"
        private const val KEY_DESKTOP = "virtualDesktop"
        private const val KEY_STATS = "showStats"
    }
}

object InstallId {
    private const val PREFS = "opendisplay"
    private const val KEY = "installID"

    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY, null)
        if (!existing.isNullOrBlank()) return existing
        val fresh = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY, fresh).apply()
        return fresh
    }

    fun shortId(id: String): String = id.replace("-", "").take(8)
}
