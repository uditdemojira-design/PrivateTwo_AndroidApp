package org.privatetwo.app.core.signaling

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.privatetwo.app.MainActivity
import org.privatetwo.app.PrivateTwoApp
import org.privatetwo.app.R

/**
 * Foreground Service that keeps the WebSocket connection alive when the user minimizes
 * the app or is using other apps (YouTube, Instagram, Chrome, etc.), ensuring prompt
 * reception of messages and WebRTC call offers without relying on Google FCM.
 */
class SignalingKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        // Ensure signaling client remains connected
        val app = application as? PrivateTwoApp
        if (app != null && app.secureStorage.isPaired()) {
            val client = app.signalingClient
            if (client.connectionState.value == SignalingConnectionState.DISCONNECTED) {
                client.connect()
            }
        }

        return START_STICKY
    }

    private fun startAsForeground() {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, PrivateTwoApp.CHANNEL_SERVICE_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("PrivateTwo")
            .setContentText("Encrypted connection active")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(SERVICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        private const val SERVICE_NOTIFICATION_ID = 1001

        fun start(context: Context) {
            try {
                val intent = Intent(context, SignalingKeepAliveService::class.java)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                android.util.Log.e("KeepAliveService", "Failed to start keep alive service", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, SignalingKeepAliveService::class.java)
                context.stopService(intent)
            } catch (_: Exception) {}
        }
    }
}
