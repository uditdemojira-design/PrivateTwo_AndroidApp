package org.privatetwo.app.core.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.privatetwo.app.MainActivity
import org.privatetwo.app.PrivateTwoApp
import org.privatetwo.app.R
import org.privatetwo.app.core.security.SecureStorage
import java.util.concurrent.atomic.AtomicBoolean

object NotificationHelper {

    private const val NOTIFICATION_ID_BASE = 2000
    private var notificationCounter = 0

    // Tracks whether ChatScreen is currently foregrounded & visible to the user
    val isChatVisible = AtomicBoolean(false)

    fun showIncomingMessageNotification(
        context: Context,
        secureStorage: SecureStorage,
        senderName: String? = null,
        messageText: String? = null,
        messageType: String = "TEXT"
    ) {
        // If user is currently looking at the chat screen while the app is in the foreground, do not buzz
        if (isChatVisible.get() && PrivateTwoApp.isAppInForeground) {
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val isStealth = secureStorage.isStealthNotificationsEnabled
        val partnerName = senderName ?: secureStorage.getPartnerDisplayName() ?: "Private Partner"

        val title: String
        val body: String

        if (isStealth) {
            title = "PrivateTwo"
            body = "New private message"
        } else {
            title = partnerName
            body = when (messageType.uppercase()) {
                "PHOTO" -> "📷 Photo"
                "FILE" -> "📎 Document"
                "CARD" -> "💌 Partner Card"
                else -> messageText?.ifBlank { "New message" } ?: "New message"
            }
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            action = "org.privatetwo.app.action.OPEN_CHAT"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("navigate_to", "chat")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            (System.currentTimeMillis() % 100000).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, PrivateTwoApp.CHANNEL_MESSAGES_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL) // Sound + Vibration
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

        val notifId = NOTIFICATION_ID_BASE + (notificationCounter++ % 10)
        notificationManager.notify(notifId, notification)
    }

    fun clearNotifications(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancelAll()
    }
}
