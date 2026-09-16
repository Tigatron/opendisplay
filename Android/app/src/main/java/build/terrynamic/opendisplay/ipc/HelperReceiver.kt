package build.terrynamic.opendisplay.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import build.terrynamic.opendisplay.ReceiverApp
import build.terrynamic.opendisplay.protocol.WireProtocol

class HelperReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val port = intent.getIntExtra("port", -1)
        val version = intent.getStringExtra("helperVersion")
        val ttlMs = intent.getIntExtra("ttlMs", DEFAULT_TTL_MS)
        Log.d(WireProtocol.LOG_TAG, "USB_TUNNEL heartbeat port=$port ttlMs=$ttlMs")
        try {
            ReceiverApp.instance.controller.onHelperHeartbeat(port, version, ttlMs)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ACTION = "build.terrynamic.opendisplay.USB_TUNNEL"
        const val DEFAULT_TTL_MS = 15_000
    }
}
