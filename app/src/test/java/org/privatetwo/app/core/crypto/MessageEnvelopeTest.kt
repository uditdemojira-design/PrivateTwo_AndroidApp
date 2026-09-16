package org.privatetwo.app.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MessageEnvelopeTest {

    private val testKey = ByteArray(32) { (it + 5).toByte() }
    private val senderId = "sender-device-01"
    private val recipientId = "recipient-dev-02"

    @Test
    fun testEnvelopePackUnpackRoundtrip() {
        val plaintext = "Hello from PrivateTwo secure channel".toByteArray(Charsets.UTF_8)
        val envelope = MessageEnvelope.pack(
            senderDeviceId = senderId,
            recipientDeviceId = recipientId,
            sequenceNumber = 1,
            messageType = MessageType.TEXT,
            plaintext = plaintext,
            encryptionKey = testKey
        )

        val jsonStr = envelope.toJson()
        val parsedEnvelope = MessageEnvelope.fromJson(jsonStr)
        assertEquals(envelope, parsedEnvelope)

        val decrypted = MessageEnvelope.unpack(
            envelope = parsedEnvelope,
            decryptionKey = testKey,
            expectedSenderId = senderId,
            expectedRecipientId = recipientId
        )

        assertArrayEquals(plaintext, decrypted)
        assertEquals("Hello from PrivateTwo secure channel", String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun testEnvelopeRejectsWrongSenderOrRecipient() {
        val plaintext = "Secret payload".toByteArray(Charsets.UTF_8)
        val envelope = MessageEnvelope.pack(
            senderDeviceId = senderId,
            recipientDeviceId = recipientId,
            sequenceNumber = 2,
            messageType = MessageType.TEXT,
            plaintext = plaintext,
            encryptionKey = testKey
        )

        try {
            MessageEnvelope.unpack(envelope, testKey, "impostor-sender", recipientId)
            fail("Must reject envelope with wrong sender")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Sender ID mismatch"))
        }

        try {
            MessageEnvelope.unpack(envelope, testKey, senderId, "wrong-recipient")
            fail("Must reject envelope with wrong recipient")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Recipient ID mismatch"))
        }
    }

    @Test
    fun testReplayProtectionRejectsDuplicateMessageId() {
        val validator = ReplayProtectionValidator()
        val envelope = MessageEnvelope.pack(
            senderDeviceId = senderId,
            recipientDeviceId = recipientId,
            sequenceNumber = 1,
            messageType = MessageType.TEXT,
            plaintext = "msg".toByteArray(),
            encryptionKey = testKey
        )

        val res1 = validator.validate(envelope)
        assertTrue(res1 is ValidationResult.Accepted)

        val res2 = validator.validate(envelope)
        assertTrue(res2 is ValidationResult.Rejected)
        assertTrue((res2 as ValidationResult.Rejected).reason.contains("Duplicate message ID"))
    }

    @Test
    fun testReplayProtectionRejectsStaleSequenceNumber() {
        val validator = ReplayProtectionValidator()

        val env1 = MessageEnvelope.pack(
            senderDeviceId = senderId,
            recipientDeviceId = recipientId,
            sequenceNumber = 5,
            messageType = MessageType.TEXT,
            plaintext = "msg 5".toByteArray(),
            encryptionKey = testKey
        )
        assertTrue(validator.validate(env1) is ValidationResult.Accepted)

        val env2 = MessageEnvelope.pack(
            senderDeviceId = senderId,
            recipientDeviceId = recipientId,
            sequenceNumber = 4,
            messageType = MessageType.TEXT,
            plaintext = "stale msg 4".toByteArray(),
            encryptionKey = testKey
        )
        val res = validator.validate(env2)
        assertTrue(res is ValidationResult.Rejected)
        assertTrue((res as ValidationResult.Rejected).reason.contains("Stale sequence number"))
    }

    @Test
    fun testReplayProtectionRejectsExpiredTimestamp() {
        val validator = ReplayProtectionValidator(maxTimeDriftMs = 60_000L)
        val now = System.currentTimeMillis()

        val expiredEnv = MessageEnvelope.pack(
            senderDeviceId = senderId,
            recipientDeviceId = recipientId,
            sequenceNumber = 1,
            messageType = MessageType.TEXT,
            plaintext = "old msg".toByteArray(),
            encryptionKey = testKey,
            timestamp = now - 120_000L
        )

        val res = validator.validate(expiredEnv, currentTimeMs = now)
        assertTrue(res is ValidationResult.Rejected)
        assertTrue((res as ValidationResult.Rejected).reason.contains("Message expired"))
    }
}
