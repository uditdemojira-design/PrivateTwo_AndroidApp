package org.privatetwo.app.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.privatetwo.app.core.crypto.CryptoEngine
import org.privatetwo.app.core.crypto.MessageEnvelope
import org.privatetwo.app.core.crypto.MessageType
import org.privatetwo.app.core.crypto.ReplayProtectionValidator
import org.privatetwo.app.core.crypto.ValidationResult
import org.privatetwo.app.core.database.DeliveryStatus
import org.privatetwo.app.core.database.MessageEntity
import org.privatetwo.app.core.database.PrivateTwoDatabase
import org.privatetwo.app.core.security.SecureStorage
import org.privatetwo.app.core.signaling.SignalingClient
import org.privatetwo.app.core.signaling.SignalingEvent
import org.privatetwo.app.core.webrtc.WebRtcSessionManager
import org.privatetwo.app.feature.files.FileTransferManager
import java.io.File
import java.util.Base64
import java.util.UUID

data class ChatMessage(
    val id: String,
    val text: String,
    val timestamp: Long,
    val isIncoming: Boolean,
    val status: DeliveryStatus,
    val messageType: String,
    val mediaLocalPath: String? = null,
    val mediaFileName: String? = null,
    val mediaFileSize: Long = 0L
)

class ChatRepository(
    private val context: Context,
    private val database: PrivateTwoDatabase,
    private val secureStorage: SecureStorage,
    private val signalingClient: SignalingClient,
    private val webRtcSessionManager: WebRtcSessionManager,
    private val fileTransferManager: FileTransferManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val replayValidator = ReplayProtectionValidator()
    private var sequenceCounter: Long = 1

    private val _isPeerTyping = MutableStateFlow(false)
    val isPeerTyping: StateFlow<Boolean> = _isPeerTyping.asStateFlow()
    private var typingResetJob: Job? = null
    private var lastTypingSentTime: Long = 0L

    init {
        webRtcSessionManager.onDataChannelMessage = { bytes ->
            scope.launch {
                handleIncomingRawEnvelope(String(bytes, Charsets.UTF_8))
            }
        }

        scope.launch {
            signalingClient.events.collect { event ->
                if (event is SignalingEvent.E2eeEnvelopeReceived) {
                    handleIncomingRawEnvelope(event.envelopeJson)
                }
            }
        }
    }

    fun getMessagesFlow(): Flow<List<ChatMessage>> {
        return database.messageDao().getAllMessagesFlow().map { entities ->
            entities.map { entity ->
                val decryptedText = decryptLocalContent(entity.encryptedContent, entity.nonce, entity.authTag)
                ChatMessage(
                    id = entity.id,
                    text = decryptedText,
                    timestamp = entity.timestamp,
                    isIncoming = entity.isIncoming,
                    status = entity.deliveryStatus,
                    messageType = entity.messageType,
                    mediaLocalPath = entity.mediaLocalPath,
                    mediaFileName = entity.mediaFileName,
                    mediaFileSize = entity.mediaFileSize
                )
            }
        }
    }

    suspend fun sendMessage(text: String): String = withContext(Dispatchers.IO) {
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val localDeviceId = secureStorage.getLocalDeviceId()
        val peerDeviceId = secureStorage.getPairedPeerDeviceId() ?: throw IllegalStateException("Device not paired")
        val outboundKey = secureStorage.getOutboundSessionKey() ?: throw IllegalStateException("Active session key not established")

        val plaintextBytes = text.toByteArray(Charsets.UTF_8)
        val envelope = MessageEnvelope.pack(
            senderDeviceId = localDeviceId,
            recipientDeviceId = peerDeviceId,
            sequenceNumber = sequenceCounter++,
            messageType = MessageType.TEXT,
            plaintext = plaintextBytes,
            encryptionKey = outboundKey,
            messageId = messageId,
            timestamp = timestamp
        )

        val localKey = getOrCreateDatabaseKey()
        val localEncrypted = CryptoEngine.encrypt(localKey, plaintextBytes)
        val iv = localEncrypted.copyOfRange(0, 12)
        val tag = localEncrypted.copyOfRange(localEncrypted.size - 16, localEncrypted.size)
        val ciphertext = localEncrypted.copyOfRange(12, localEncrypted.size - 16)

        val entity = MessageEntity(
            id = messageId,
            timestamp = timestamp,
            senderDeviceId = localDeviceId,
            recipientDeviceId = peerDeviceId,
            encryptedContent = Base64.getEncoder().encodeToString(ciphertext),
            nonce = Base64.getEncoder().encodeToString(iv),
            authTag = Base64.getEncoder().encodeToString(tag),
            deliveryStatus = DeliveryStatus.SENDING,
            messageType = "TEXT",
            isIncoming = false
        )
        database.messageDao().insertMessage(entity)

        val envelopeJson = envelope.toJson()
        val sentViaP2p = webRtcSessionManager.sendDataChannelMessage(envelopeJson.toByteArray(Charsets.UTF_8))
        if (!sentViaP2p) {
            signalingClient.sendE2eeEnvelope(envelopeJson)
        }

        database.messageDao().updateDeliveryStatus(messageId, DeliveryStatus.SENT)
        try {
            sendTypingIndicator(false)
        } catch (ignored: Exception) {}
        messageId
    }

    suspend fun sendPhoto(photoFile: File): String = withContext(Dispatchers.IO) {
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val localDeviceId = secureStorage.getLocalDeviceId()
        val peerDeviceId = secureStorage.getPairedPeerDeviceId() ?: throw IllegalStateException("Device not paired")

        val optimizedFile = compressImageFile(photoFile)

        val localKey = getOrCreateDatabaseKey()
        val localEncrypted = CryptoEngine.encrypt(localKey, optimizedFile.name.toByteArray(Charsets.UTF_8))
        val iv = localEncrypted.copyOfRange(0, 12)
        val tag = localEncrypted.copyOfRange(localEncrypted.size - 16, localEncrypted.size)
        val ciphertext = localEncrypted.copyOfRange(12, localEncrypted.size - 16)

        val entity = MessageEntity(
            id = messageId,
            timestamp = timestamp,
            senderDeviceId = localDeviceId,
            recipientDeviceId = peerDeviceId,
            encryptedContent = Base64.getEncoder().encodeToString(ciphertext),
            nonce = Base64.getEncoder().encodeToString(iv),
            authTag = Base64.getEncoder().encodeToString(tag),
            deliveryStatus = DeliveryStatus.SENDING,
            messageType = "PHOTO",
            isIncoming = false,
            mediaLocalPath = optimizedFile.absolutePath,
            mediaFileName = optimizedFile.name,
            mediaFileSize = optimizedFile.length()
        )
        database.messageDao().insertMessage(entity)

        fileTransferManager.sendFile(
            file = optimizedFile,
            isPhoto = true,
            transferId = messageId,
            nextSequenceNumber = { sequenceCounter++ }
        ) { envelope ->
            val json = envelope.toJson()
            val sentViaP2p = webRtcSessionManager.sendDataChannelMessage(json.toByteArray(Charsets.UTF_8))
            if (!sentViaP2p) {
                signalingClient.sendE2eeEnvelope(json)
            }
            delay(10)
        }

        database.messageDao().updateDeliveryStatus(messageId, DeliveryStatus.SENT)
        messageId
    }

    suspend fun sendFile(file: File): String = withContext(Dispatchers.IO) {
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val localDeviceId = secureStorage.getLocalDeviceId()
        val peerDeviceId = secureStorage.getPairedPeerDeviceId() ?: throw IllegalStateException("Device not paired")

        val localKey = getOrCreateDatabaseKey()
        val localEncrypted = CryptoEngine.encrypt(localKey, file.name.toByteArray(Charsets.UTF_8))
        val iv = localEncrypted.copyOfRange(0, 12)
        val tag = localEncrypted.copyOfRange(localEncrypted.size - 16, localEncrypted.size)
        val ciphertext = localEncrypted.copyOfRange(12, localEncrypted.size - 16)

        val entity = MessageEntity(
            id = messageId,
            timestamp = timestamp,
            senderDeviceId = localDeviceId,
            recipientDeviceId = peerDeviceId,
            encryptedContent = Base64.getEncoder().encodeToString(ciphertext),
            nonce = Base64.getEncoder().encodeToString(iv),
            authTag = Base64.getEncoder().encodeToString(tag),
            deliveryStatus = DeliveryStatus.SENDING,
            messageType = "FILE",
            isIncoming = false,
            mediaLocalPath = file.absolutePath,
            mediaFileName = file.name,
            mediaFileSize = file.length()
        )
        database.messageDao().insertMessage(entity)

        fileTransferManager.sendFile(
            file = file,
            isPhoto = false,
            transferId = messageId,
            nextSequenceNumber = { sequenceCounter++ }
        ) { envelope ->
            val json = envelope.toJson()
            val sentViaP2p = webRtcSessionManager.sendDataChannelMessage(json.toByteArray(Charsets.UTF_8))
            if (!sentViaP2p) {
                signalingClient.sendE2eeEnvelope(json)
            }
            delay(10)
        }

        database.messageDao().updateDeliveryStatus(messageId, DeliveryStatus.SENT)
        messageId
    }

    private fun compressImageFile(original: File): File {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(original.absolutePath, options)
            val maxDim = 1600
            var sampleSize = 1
            while (options.outWidth / sampleSize > maxDim || options.outHeight / sampleSize > maxDim) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeFile(original.absolutePath, decodeOptions) ?: return original

            val compressedFile = File(context.cacheDir, "comp_${System.currentTimeMillis()}.jpg")
            FileOutputStream(compressedFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            compressedFile
        } catch (e: Exception) {
            original
        }
    }

    suspend fun retryMessage(messageId: String) = withContext(Dispatchers.IO) {
        val entity = database.messageDao().getMessageById(messageId) ?: return@withContext
        val text = decryptLocalContent(entity.encryptedContent, entity.nonce, entity.authTag)
        sendMessage(text)
    }

    suspend fun deleteMessage(messageId: String) = withContext(Dispatchers.IO) {
        database.messageDao().deleteMessage(messageId)
    }

    suspend fun clearConversation() = withContext(Dispatchers.IO) {
        database.messageDao().deleteAllMessages()
        database.transferDao().deleteAllTransfers()
    }

    suspend fun sendTypingIndicator(isTyping: Boolean) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (isTyping && now - lastTypingSentTime < 2000L) {
            return@withContext
        }
        lastTypingSentTime = now
        try {
            val localDeviceId = secureStorage.getLocalDeviceId()
            val peerDeviceId = secureStorage.getPairedPeerDeviceId() ?: return@withContext
            val outboundKey = secureStorage.getOutboundSessionKey() ?: return@withContext

            val payload = isTyping.toString().toByteArray(Charsets.UTF_8)
            val envelope = MessageEnvelope.pack(
                senderDeviceId = localDeviceId,
                recipientDeviceId = peerDeviceId,
                sequenceNumber = sequenceCounter++,
                messageType = MessageType.TYPING_INDICATOR,
                plaintext = payload,
                encryptionKey = outboundKey
            )
            val json = envelope.toJson()
            val sentViaP2p = webRtcSessionManager.sendDataChannelMessage(json.toByteArray(Charsets.UTF_8))
            if (!sentViaP2p) {
                signalingClient.sendE2eeEnvelope(json)
            }
        } catch (ignored: Exception) {}
    }

    private suspend fun handleIncomingRawEnvelope(jsonStr: String) = withContext(Dispatchers.IO) {
        try {
            val envelope = MessageEnvelope.fromJson(jsonStr)
            val expectedSenderId = secureStorage.getPairedPeerDeviceId() ?: return@withContext
            val localDeviceId = secureStorage.getLocalDeviceId()

            val validation = replayValidator.validate(envelope)
            if (validation is ValidationResult.Rejected) {
                return@withContext
            }

            val inboundKey = secureStorage.getInboundSessionKey() ?: return@withContext

            val decryptedBytes = MessageEnvelope.unpack(
                envelope = envelope,
                decryptionKey = inboundKey,
                expectedSenderId = expectedSenderId,
                expectedRecipientId = localDeviceId
            )

            when (envelope.messageType) {
                MessageType.TYPING_INDICATOR -> {
                    val isTyping = String(decryptedBytes, Charsets.UTF_8).toBoolean()
                    _isPeerTyping.value = isTyping
                    typingResetJob?.cancel()
                    if (isTyping) {
                        typingResetJob = scope.launch {
                            delay(3500)
                            _isPeerTyping.value = false
                        }
                    }
                }
                MessageType.TEXT -> {
                    _isPeerTyping.value = false
                    typingResetJob?.cancel()

                    val localKey = getOrCreateDatabaseKey()
                    val localEncrypted = CryptoEngine.encrypt(localKey, decryptedBytes)
                    val iv = localEncrypted.copyOfRange(0, 12)
                    val tag = localEncrypted.copyOfRange(localEncrypted.size - 16, localEncrypted.size)
                    val ciphertext = localEncrypted.copyOfRange(12, localEncrypted.size - 16)

                    val entity = MessageEntity(
                        id = envelope.messageId,
                        timestamp = envelope.timestamp,
                        senderDeviceId = envelope.senderDeviceId,
                        recipientDeviceId = envelope.recipientDeviceId,
                        encryptedContent = Base64.getEncoder().encodeToString(ciphertext),
                        nonce = Base64.getEncoder().encodeToString(iv),
                        authTag = Base64.getEncoder().encodeToString(tag),
                        deliveryStatus = DeliveryStatus.DELIVERED,
                        messageType = "TEXT",
                        isIncoming = true
                    )
                    database.messageDao().insertMessage(entity)

                    sendDeliveryReceipt(envelope.messageId)
                }
                MessageType.DELIVERY_RECEIPT -> {
                    val receiptId = String(decryptedBytes, Charsets.UTF_8)
                    database.messageDao().updateDeliveryStatus(receiptId, DeliveryStatus.DELIVERED)
                }
                MessageType.PHOTO_HEADER, MessageType.FILE_HEADER -> {
                    fileTransferManager.handleIncomingHeader(decryptedBytes)
                }
                MessageType.PHOTO_CHUNK, MessageType.FILE_CHUNK -> {
                    val completed = fileTransferManager.handleIncomingChunk(decryptedBytes)
                    if (completed != null) {
                        val localKey = getOrCreateDatabaseKey()
                        val localEncrypted = CryptoEngine.encrypt(localKey, completed.file.name.toByteArray(Charsets.UTF_8))
                        val iv = localEncrypted.copyOfRange(0, 12)
                        val tag = localEncrypted.copyOfRange(localEncrypted.size - 16, localEncrypted.size)
                        val ciphertext = localEncrypted.copyOfRange(12, localEncrypted.size - 16)

                        val entity = MessageEntity(
                            id = completed.transferId,
                            timestamp = envelope.timestamp,
                            senderDeviceId = envelope.senderDeviceId,
                            recipientDeviceId = envelope.recipientDeviceId,
                            encryptedContent = Base64.getEncoder().encodeToString(ciphertext),
                            nonce = Base64.getEncoder().encodeToString(iv),
                            authTag = Base64.getEncoder().encodeToString(tag),
                            deliveryStatus = DeliveryStatus.DELIVERED,
                            messageType = if (completed.isPhoto) "PHOTO" else "FILE",
                            isIncoming = true,
                            mediaLocalPath = completed.file.absolutePath,
                            mediaFileName = completed.file.name,
                            mediaFileSize = completed.file.length()
                        )
                        database.messageDao().insertMessage(entity)
                        sendDeliveryReceipt(completed.transferId)
                    }
                }
                else -> Unit
            }
        } catch (e: Exception) {
            // Decryption failed or tampered payload: rejected
        }
    }

    private fun sendDeliveryReceipt(originalMessageId: String) {
        val localDeviceId = secureStorage.getLocalDeviceId()
        val peerDeviceId = secureStorage.getPairedPeerDeviceId() ?: return
        val outboundKey = SecureStorage.activeOutboundSessionKey ?: return

        val envelope = MessageEnvelope.pack(
            senderDeviceId = localDeviceId,
            recipientDeviceId = peerDeviceId,
            sequenceNumber = sequenceCounter++,
            messageType = MessageType.DELIVERY_RECEIPT,
            plaintext = originalMessageId.toByteArray(Charsets.UTF_8),
            encryptionKey = outboundKey
        )

        val json = envelope.toJson()
        if (!webRtcSessionManager.sendDataChannelMessage(json.toByteArray(Charsets.UTF_8))) {
            signalingClient.sendE2eeEnvelope(json)
        }
    }

    private fun decryptLocalContent(ciphertextBase64: String, nonceBase64: String, tagBase64: String): String {
        return try {
            val localKey = getOrCreateDatabaseKey()
            val ciphertext = Base64.getDecoder().decode(ciphertextBase64)
            val iv = Base64.getDecoder().decode(nonceBase64)
            val tag = Base64.getDecoder().decode(tagBase64)

            val full = ByteArray(iv.size + ciphertext.size + tag.size)
            System.arraycopy(iv, 0, full, 0, iv.size)
            System.arraycopy(ciphertext, 0, full, iv.size, ciphertext.size)
            System.arraycopy(tag, 0, full, iv.size + ciphertext.size, tag.size)

            val plain = CryptoEngine.decrypt(localKey, full)
            String(plain, Charsets.UTF_8)
        } catch (e: Exception) {
            "[Decryption Error]"
        }
    }

    private fun getOrCreateDatabaseKey(): ByteArray {
        return "PrivateTwoLocalDatabaseKeyAES256".toByteArray(Charsets.UTF_8)
    }
}
