package com.terrynamic.opendisplay.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Process
import android.util.Log
import com.terrynamic.opendisplay.BuildConfig
import com.terrynamic.opendisplay.ReceiverApp
import com.terrynamic.opendisplay.SettingsValues
import com.terrynamic.opendisplay.protocol.WireProtocol

class InfoProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val state = try {
            ReceiverApp.instance.controller.snapshot
        } catch (_: Exception) {
            null
        }
        val settings = state?.settings
        val columns = arrayOf(
            "id", "pv", "model", "serviceName", "listening", "port", "transport", "appVersion",
            "decodeCeiling", "virtualDesktop", "statsOverlay", "cursorUdp",
        )
        val cursor = MatrixCursor(columns)
        cursor.addRow(
            arrayOf<Any>(
                state?.installId ?: "",
                WireProtocol.VERSION,
                Build.MODEL ?: "",
                settings?.serviceName ?: state?.registeredName ?: WireProtocol.DEFAULT_SERVICE_NAME,
                if (state?.listening == true) 1 else 0,
                state?.port ?: WireProtocol.DEFAULT_PORT,
                state?.transport ?: "",
                BuildConfig.VERSION_NAME,
                settings?.decodeCeiling?.wireKey ?: "auto",
                settings?.virtualDesktop?.storageKey ?: "100",
                if (settings?.showStats == true) 1 else 0,
                if (settings?.cursorUdp != false) 1 else 0,
            ),
        )
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.com.terrynamic.opendisplay.info"

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("insert is not supported")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("delete is not supported")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val uid = Binder.getCallingUid()
        if (!SettingsValues.isPrivilegedUid(uid) && uid != Process.SHELL_UID) {
            Log.w(WireProtocol.LOG_TAG, "settings update rejected uid=$uid")
            return 0
        }
        if (values == null || values.size() == 0) return 0
        val map = linkedMapOf<String, String>()
        for (key in values.keySet()) {
            val raw = values.getAsString(key) ?: values.get(key)?.toString() ?: continue
            map[key] = raw
        }
        val patch = SettingsValues.parsePatch(map)
        if (patch.isEmpty) {
            Log.w(WireProtocol.LOG_TAG, "settings update ignored — no recognized keys")
            return 0
        }
        return try {
            val applied = ReceiverApp.instance.controller.applyShellPatch(patch)
            if (applied) {
                Log.i(
                    WireProtocol.LOG_TAG,
                    "settings update applied uid=$uid keys=${map.keys.joinToString()}",
                )
                1
            } else {
                0
            }
        } catch (e: Exception) {
            Log.w(WireProtocol.LOG_TAG, "settings update failed: ${e.message}")
            0
        }
    }

    companion object {
        const val AUTHORITY = "com.terrynamic.opendisplay.info"
    }
}
