package org.privatetwo.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import org.privatetwo.app.core.database.PrivateTwoDatabase
import org.privatetwo.app.core.security.SecureStorage
import org.privatetwo.app.core.signaling.SignalingClient
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

            notificationManager.createNotificationChannel(msgChannel)
            notificationManager.createNotificationChannel(callChannel)
        }
    }

    companion object {
        const val CHANNEL_MESSAGES_ID = "privatetwo_messages"
        const val CHANNEL_CALLS_ID = "privatetwo_calls"
    }
}
