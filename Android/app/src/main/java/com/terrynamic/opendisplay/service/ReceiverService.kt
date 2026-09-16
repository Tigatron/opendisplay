package com.terrynamic.opendisplay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.terrynamic.opendisplay.MainActivity
import com.terrynamic.opendisplay.R
import com.terrynamic.opendisplay.ReceiverApp
import com.terrynamic.opendisplay.ReceiverPhase

class ReceiverService : Service() {
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val controller = ReceiverApp.instance.controller
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> controller.sleepSession()
                Intent.ACTION_USER_PRESENT -> controller.resumeFromSleep()
                Intent.ACTION_SCREEN_ON -> {
                    val km = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
                    if (!km.isKeyguardLocked) controller.resumeFromSleep()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, lockReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        startAsForeground()
        ReceiverApp.instance.controller.startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ReceiverApp.instance.controller.shutdown(sendClosing = true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(lockReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val streaming = ReceiverApp.instance.controller.snapshot.phase == ReceiverPhase.STREAMING
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(
                if (streaming) getString(R.string.notification_streaming)
                else getString(R.string.notification_listening),
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(launch)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "OpenDisplay", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val CHANNEL_ID = "opendisplay"
        private const val NOTIF_ID = 9000
        private const val ACTION_STOP = "com.terrynamic.opendisplay.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ReceiverService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReceiverService::class.java))
        }
    }
}
