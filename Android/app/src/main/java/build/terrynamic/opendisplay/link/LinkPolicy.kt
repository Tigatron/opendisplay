package build.terrynamic.opendisplay.link

import android.util.Log
import build.terrynamic.opendisplay.protocol.WireProtocol

/**
 * USB helper heartbeat → hello.addrs. Fresh heartbeat with port 9000
 * yields ["127.0.0.1"]; anything else yields [].
 */
class LinkPolicy(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    data class Snapshot(
        val addrs: List<String>,
        val helperActive: Boolean,
        val helperVersion: String?,
    )

    @Volatile
    private var expiresAtMs: Long = 0

    @Volatile
    private var lastPort: Int = -1

    @Volatile
    private var lastVersion: String? = null

    @Volatile
    private var lastActive: Boolean = false

    fun onHeartbeat(port: Int, helperVersion: String?, ttlMs: Int, atMs: Long = nowMs()): Boolean {
        val ttl = ttlMs.coerceAtLeast(0)
        lastPort = port
        lastVersion = helperVersion
        expiresAtMs = if (port == WireProtocol.DEFAULT_PORT) atMs + ttl else 0
        val active = isActive(atMs)
        val changed = active != lastActive
        if (changed) {
            Log.d(
                WireProtocol.LOG_TAG,
                "usb helper ${if (active) "active" else "inactive"} " +
                    "port=$port version=${helperVersion ?: "-"} ttlMs=$ttl",
            )
            lastActive = active
        }
        return changed
    }

    fun snapshot(atMs: Long = nowMs()): Snapshot {
        val active = isActive(atMs)
        if (active != lastActive) {
            Log.d(WireProtocol.LOG_TAG, "usb helper ${if (active) "active" else "expired"}")
            lastActive = active
        }
        return Snapshot(
            addrs = if (active) listOf("127.0.0.1") else emptyList(),
            helperActive = active,
            helperVersion = lastVersion,
        )
    }

    fun addrs(atMs: Long = nowMs()): List<String> = snapshot(atMs).addrs

    fun isActive(atMs: Long = nowMs()): Boolean {
        return lastPort == WireProtocol.DEFAULT_PORT && atMs < expiresAtMs
    }
}
