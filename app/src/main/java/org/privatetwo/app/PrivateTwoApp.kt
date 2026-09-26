package org.privatetwo.app

import android.app.Application
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import org.privatetwo.app.core.notification.NotificationHelper
import org.privatetwo.app.core.database.PrivateTwoDatabase
import org.privatetwo.app.core.security.SecureStorage
import org.privatetwo.app.core.signaling.SignalingClient
import org.privatetwo.app.core.signaling.SignalingKeepAliveService
import org.privatetwo.app.core.webrtc.WebRtcSessionManager
import org.privatetwo.app.feature.chat.ChatRepository
import org.privatetwo.app.feature.files.FileTransferManager
import org.privatetwo.app.feature.pairing.PairingManager

class PrivateTwoApp : Application() {

    lateinit var secureStorage: SecureStorage
        private set
    lateinit var database: PrivateTwoDatabase
        private set
    lateinit var signalingClient: SignalingClient
        private set
    lateinit var webRtcSessionManager: WebRtcSessionManager
        private set
    lateinit var pairingManager: PairingManager
        private set
    lateinit var chatRepository: ChatRepository
        private set
    lateinit var fileTransferManager: FileTransferManager
        private set

    override fun onCreate() {
        super.onCreate()

        secureStorage = SecureStorage(this)
        database = PrivateTwoDatabase.getInstance(this)
        val defaultUrl = getDefaultSignalingUrl()
        val initialSignalingUrl = secureStorage.getSignalingUrl(defaultUrl)
        signalingClient = SignalingClient(initialSignalingUrl)
        signalingClient.localDeviceId = secureStorage.getLocalDeviceId()
        webRtcSessionManager = WebRtcSessionManager(this, signalingClient)
        pairingManager = PairingManager(secureStorage, signalingClient)
        fileTransferManager = FileTransferManager(this, database, secureStorage)
        chatRepository = ChatRepository(this, database, secureStorage, signalingClient, webRtcSessionManager, fileTransferManager)

        signalingClient.onConnected = {
            if (secureStorage.isPaired()) {
                val localId = secureStorage.getLocalDeviceId()
                val peerId = secureStorage.getPairedPeerDeviceId()
                if (peerId != null) {
                    val directSessionId = listOf(localId, peerId).sorted().joinToString("_")
                    signalingClient.joinDirectSession(directSessionId, localId, peerId)
                }
            } else {
                pairingManager.onSocketReconnected()
            }
        }

        createNotificationChannels()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var resumedCount = 0

            override fun onActivityResumed(activity: Activity) {
                resumedCount++
                isAppInForeground = true
            }

            override fun onActivityPaused(activity: Activity) {
                resumedCount--
                if (resumedCount <= 0) {
                    isAppInForeground = false
                    NotificationHelper.isChatVisible.set(false)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Auto-connect to signaling server immediately on app launch
        signalingClient.connect()

        if (secureStorage.isPaired()) {
            SignalingKeepAliveService.start(this)
        }
    }

    private fun getDefaultSignalingUrl(): String {
        return BuildConfig.DEFAULT_SIGNALING_URL.ifBlank {
            "wss://privatetwo-androidapp.onrender.com"
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val msgChannel = NotificationChannel(
                CHANNEL_MESSAGES_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_desc)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }

            val callChannel = NotificationChannel(
                CHANNEL_CALLS_ID,
                getString(R.string.notification_call_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming Encrypted WebRTC Calls"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }

            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE_ID,
                "Connection Keep-Alive",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains encrypted background connection for incoming messages and calls"
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(msgChannel)
            notificationManager.createNotificationChannel(callChannel)
            notificationManager.createNotificationChannel(serviceChannel)
        }
    }

    companion object {
        const val CHANNEL_MESSAGES_ID = "privatetwo_messages"
        const val CHANNEL_CALLS_ID = "privatetwo_calls"
        const val CHANNEL_SERVICE_ID = "privatetwo_service"

        @Volatile
        var isAppInForeground: Boolean = false
            private set
    }
}
