package org.privatetwo.app.core.crypto

import org.json.JSONObject
import java.util.Base64
import java.util.Collections
import java.util.UUID

/**
 * Message types supported within the E2EE envelope.
 */
enum class MessageType {
    TEXT,
    PHOTO_HEADER,
    PHOTO_CHUNK,
    FILE_HEADER,
    FILE_CHUNK,
    DELIVERY_RECEIPT,
    SIGNALING
}

/**
 * Versioned encrypted message envelope for PrivateTwo.
 * Enforces authenticated sender/recipient verification, nonce management,
 * and cryptographic integrity.
 */
data class MessageEnvelope(
    val version: Int = 1,
    val messageId: String = UUID.randomUUID().toString(),
    val senderDeviceId: String,
    val recipientDeviceId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sequenceNumber: Long,
    val messageType: MessageType,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val authenticationTag: ByteArray
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("version", version)
        obj.put("messageId", messageId)
        obj.put("senderDeviceId", senderDeviceId)
        obj.put("recipientDeviceId", recipientDeviceId)
        obj.put("timestamp", timestamp)
        obj.put("sequenceNumber", sequenceNumber)
        obj.put("messageType", messageType.name)
        obj.put("nonce", Base64.getEncoder().encodeToString(nonce))
        obj.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))
        obj.put("authTag", Base64.getEncoder().encodeToString(authenticationTag))
        return obj.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): MessageEnvelope {
            val obj = JSONObject(jsonStr)
            return MessageEnvelope(
                version = obj.getInt("version"),
                messageId = obj.getString("messageId"),
                senderDeviceId = obj.getString("senderDeviceId"),
                recipientDeviceId = obj.getString("recipientDeviceId"),
                timestamp = obj.getLong("timestamp"),
                sequenceNumber = obj.getLong("sequenceNumber"),
                messageType = MessageType.valueOf(obj.getString("messageType")),
                nonce = Base64.getDecoder().decode(obj.getString("nonce")),
                ciphertext = Base64.getDecoder().decode(obj.getString("ciphertext")),
                authenticationTag = Base64.getDecoder().decode(obj.getString("authTag"))
            )
        }

        /**
         * Packs plaintext into an encrypted envelope using AES-256-GCM.
         */
        fun pack(
            senderDeviceId: String,
            recipientDeviceId: String,
            sequenceNumber: Long,
            messageType: MessageType,
            plaintext: ByteArray,
            encryptionKey: ByteArray,
            messageId: String = UUID.randomUUID().toString(),
            timestamp: Long = System.currentTimeMillis()
        ): MessageEnvelope {
            // Associated Authenticated Data (AAD) binds message metadata to cipher
            val aad = "$senderDeviceId:$recipientDeviceId:$sequenceNumber:$timestamp:${messageType.name}"
                .toByteArray(Charsets.UTF_8)

            val fullEncrypted = CryptoEngine.encrypt(encryptionKey, plaintext, aad)
            val iv = ByteArray(12)
            System.arraycopy(fullEncrypted, 0, iv, 0, 12)

            val tagLength = 16
            val ciphertextLength = fullEncrypted.size - 12 - tagLength
            val ciphertext = ByteArray(ciphertextLength)
            System.arraycopy(fullEncrypted, 12, ciphertext, 0, ciphertextLength)

            val tag = ByteArray(tagLength)
            System.arraycopy(fullEncrypted, fullEncrypted.size - tagLength, tag, 0, tagLength)

            return MessageEnvelope(
                version = 1,
                messageId = messageId,
                senderDeviceId = senderDeviceId,
                recipientDeviceId = recipientDeviceId,
                timestamp = timestamp,
                sequenceNumber = sequenceNumber,
                messageType = messageType,
                nonce = iv,
                ciphertext = ciphertext,
                authenticationTag = tag
            )
        }

        /**
         * Unpacks and decrypts an encrypted envelope using AES-256-GCM.
         */
        fun unpack(
            envelope: MessageEnvelope,
            decryptionKey: ByteArray,
            expectedSenderId: String,
            expectedRecipientId: String
        ): ByteArray {
            require(envelope.version == 1) { "Unsupported envelope version: ${envelope.version}" }
            require(envelope.senderDeviceId == expectedSenderId) {
                "Sender ID mismatch: expected $expectedSenderId, got ${envelope.senderDeviceId}"
            }
            require(envelope.recipientDeviceId == expectedRecipientId) {
                "Recipient ID mismatch: expected $expectedRecipientId, got ${envelope.recipientDeviceId}"
            }

            val aad = "${envelope.senderDeviceId}:${envelope.recipientDeviceId}:${envelope.sequenceNumber}:${envelope.timestamp}:${envelope.messageType.name}"
                .toByteArray(Charsets.UTF_8)

            val fullEncrypted = ByteArray(envelope.nonce.size + envelope.ciphertext.size + envelope.authenticationTag.size)
            System.arraycopy(envelope.nonce, 0, fullEncrypted, 0, envelope.nonce.size)
            System.arraycopy(envelope.ciphertext, 0, fullEncrypted, envelope.nonce.size, envelope.ciphertext.size)
            System.arraycopy(
                envelope.authenticationTag,
                0,
                fullEncrypted,
                envelope.nonce.size + envelope.ciphertext.size,
                envelope.authenticationTag.size
            )

            return CryptoEngine.decrypt(decryptionKey, fullEncrypted, aad)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEnvelope) return false
        return messageId == other.messageId &&
                senderDeviceId == other.senderDeviceId &&
                recipientDeviceId == other.recipientDeviceId &&
                timestamp == other.timestamp &&
                sequenceNumber == other.sequenceNumber &&
                messageType == other.messageType &&
                nonce.contentEquals(other.nonce) &&
                ciphertext.contentEquals(other.ciphertext) &&
                authenticationTag.contentEquals(other.authenticationTag)
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + senderDeviceId.hashCode()
        result = 31 * result + recipientDeviceId.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + messageType.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + authenticationTag.contentHashCode()
        return result
    }
}

/**
 * Validates incoming message envelopes for freshness and protects against replay attacks.
 */
class ReplayProtectionValidator(
    private val maxTimeDriftMs: Long = 120_000L, // 2 minutes
    private val maxSeenCacheSize: Int = 10_000
) {
    private val seenMessageIds = Collections.synchronizedSet(LinkedHashSet<String>())
    private var lastSeenSequenceNumber: Long = -1

    @Synchronized
    fun validate(envelope: MessageEnvelope, currentTimeMs: Long = System.currentTimeMillis()): ValidationResult {
        // 1. Check duplicate message ID
        if (seenMessageIds.contains(envelope.messageId)) {
            return ValidationResult.Rejected("Duplicate message ID detected (replay attack)")
        }

        // 2. Check timestamp bounds
        val timeDiff = currentTimeMs - envelope.timestamp
        if (timeDiff > maxTimeDriftMs) {
            return ValidationResult.Rejected("Message expired (timestamp drift too high: ${timeDiff}ms)")
        }
        if (timeDiff < -30_000L) { // More than 30s in future
            return ValidationResult.Rejected("Message from the future (timestamp drift: ${timeDiff}ms)")
        }

        // 3. Monotonic sequence check for strict ordering
        if (envelope.sequenceNumber <= lastSeenSequenceNumber) {
            return ValidationResult.Rejected(
                "Stale sequence number ${envelope.sequenceNumber} <= $lastSeenSequenceNumber (possible replay)"
            )
        }

        // Add to cache & evict oldest if needed
        seenMessageIds.add(envelope.messageId)
        if (seenMessageIds.size > maxSeenCacheSize) {
            val iterator = seenMessageIds.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }

        lastSeenSequenceNumber = envelope.sequenceNumber
        return ValidationResult.Accepted
    }

    @Synchronized
    fun reset() {
        seenMessageIds.clear()
        lastSeenSequenceNumber = -1
    }
}

sealed class ValidationResult {
    object Accepted : ValidationResult()
    data class Rejected(val reason: String) : ValidationResult()
}
