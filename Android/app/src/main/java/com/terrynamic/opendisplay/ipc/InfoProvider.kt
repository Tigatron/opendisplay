package com.terrynamic.opendisplay.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import com.terrynamic.opendisplay.BuildConfig
import com.terrynamic.opendisplay.ReceiverApp
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
        val columns = arrayOf(
            "id", "pv", "model", "serviceName", "listening", "port", "transport", "appVersion",
        )
        val cursor = MatrixCursor(columns)
        cursor.addRow(
            arrayOf<Any>(
                state?.installId ?: "",
                WireProtocol.VERSION,
                Build.MODEL ?: "",
                state?.registeredName ?: WireProtocol.DEFAULT_SERVICE_NAME,
                if (state?.listening == true) 1 else 0,
                state?.port ?: WireProtocol.DEFAULT_PORT,
                state?.transport ?: "",
                BuildConfig.VERSION_NAME,
            ),
        )
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.com.terrynamic.opendisplay.info"

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("read-only")

    companion object {
        const val AUTHORITY = "com.terrynamic.opendisplay.info"
    }
}
