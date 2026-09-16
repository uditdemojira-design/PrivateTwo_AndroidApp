package org.privatetwo.app.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.AEADBadTagException

class CryptoEngineTest {

    @Test
    fun testKeyPairGenerationAndSerialization() {
        val keyPair = CryptoEngine.generateKeyPair()
        assertNotNull(keyPair.public)
        assertNotNull(keyPair.private)
        assertEquals("EC", keyPair.public.algorithm)

        val encoded = CryptoEngine.encodePublicKey(keyPair.public)
        assertTrue(encoded.isNotEmpty())

        val decoded = CryptoEngine.decodePublicKey(encoded)
        assertArrayEquals(keyPair.public.encoded, decoded.encoded)

        val deviceId = CryptoEngine.computeDeviceId(keyPair.public)
        assertEquals(16, deviceId.length)
    }

    @Test
    fun testEcdhKeyAgreementSymmetry() {
        val alicePair = CryptoEngine.generateKeyPair()
        val bobPair = CryptoEngine.generateKeyPair()

        val aliceSecret = CryptoEngine.computeSharedSecret(alicePair.private, bobPair.public)
        val bobSecret = CryptoEngine.computeSharedSecret(bobPair.private, alicePair.public)

        assertNotNull(aliceSecret)
        assertNotNull(bobSecret)
        assertArrayEquals("ECDH shared secrets must be identical", aliceSecret, bobSecret)
    }

    @Test
    fun testHkdfSessionKeyDerivationAndSasMatching() {
        val alicePair = CryptoEngine.generateKeyPair()
        val bobPair = CryptoEngine.generateKeyPair()

        val aliceSecret = CryptoEngine.computeSharedSecret(alicePair.private, bobPair.public)
        val bobSecret = CryptoEngine.computeSharedSecret(bobPair.private, alicePair.public)

        val aliceKeys = CryptoEngine.deriveSessionKeys(
            sharedSecret = aliceSecret,
            localPublicKey = alicePair.public,
            peerPublicKey = bobPair.public,
            isInitiator = true
        )

        val bobKeys = CryptoEngine.deriveSessionKeys(
            sharedSecret = bobSecret,
            localPublicKey = bobPair.public,
            peerPublicKey = alicePair.public,
            isInitiator = false
        )

        assertArrayEquals("Alice outbound must match Bob inbound", aliceKeys.outboundKey, bobKeys.inboundKey)
        assertArrayEquals("Alice inbound must match Bob outbound", aliceKeys.inboundKey, bobKeys.outboundKey)

        assertFalse("Inbound and outbound keys must differ", aliceKeys.outboundKey.contentEquals(aliceKeys.inboundKey))

        assertEquals("SAS codes must match on both devices", aliceKeys.sasCode, bobKeys.sasCode)
        assertTrue("SAS must be in format XXXX-XXXX", aliceKeys.sasCode.matches(Regex("\\d{4}-\\d{4}")))
    }

    @Test
    fun testAesGcmEncryptionDecryptionRoundtrip() {
        val key = ByteArray(32) { (it * 3).toByte() }
        val plaintext = "Strictly private message: Alice and Bob only.".toByteArray(Charsets.UTF_8)
        val aad = "Alice:Bob:1:1726470000000:TEXT".toByteArray(Charsets.UTF_8)

        val encrypted = CryptoEngine.encrypt(key, plaintext, aad)
        assertEquals("Encrypted payload must be exactly plaintext + 28 bytes (12 IV + 16 Tag)", plaintext.size + 28, encrypted.size)

        val decrypted = CryptoEngine.decrypt(key, encrypted, aad)
        assertArrayEquals(plaintext, decrypted)
        assertEquals("Strictly private message: Alice and Bob only.", String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun testAesGcmRejectsTamperedCiphertext() {
        val key = ByteArray(32) { 0x42 }
        val plaintext = "Top secret data".toByteArray(Charsets.UTF_8)
        val aad = "Metadata".toByteArray(Charsets.UTF_8)

        val encrypted = CryptoEngine.encrypt(key, plaintext, aad)

        val tampered = encrypted.clone()
        tampered[15] = (tampered[15].toInt() xor 0xFF).toByte()

        try {
            CryptoEngine.decrypt(key, tampered, aad)
            fail("Decryption must fail on tampered ciphertext")
        } catch (e: Exception) {
            assertTrue("Expected AEADBadTagException or GeneralSecurityException", e is AEADBadTagException || e.cause is AEADBadTagException)
        }
    }

    @Test
    fun testAesGcmRejectsWrongKey() {
        val key1 = ByteArray(32) { 0x01 }
        val key2 = ByteArray(32) { 0x02 }
        val plaintext = "Confidential text".toByteArray(Charsets.UTF_8)

        val encrypted = CryptoEngine.encrypt(key1, plaintext)

        try {
            CryptoEngine.decrypt(key2, encrypted)
            fail("Decryption must fail when using wrong key")
        } catch (e: Exception) {
            assertTrue(e is AEADBadTagException || e.cause is AEADBadTagException)
        }
    }

    @Test
    fun testAesGcmRejectsMismatchedAad() {
        val key = ByteArray(32) { 0x07 }
        val plaintext = "Payload".toByteArray(Charsets.UTF_8)
        val originalAad = "Sender:A".toByteArray(Charsets.UTF_8)
        val forgedAad = "Sender:Eve".toByteArray(Charsets.UTF_8)

        val encrypted = CryptoEngine.encrypt(key, plaintext, originalAad)

        try {
            CryptoEngine.decrypt(key, encrypted, forgedAad)
            fail("Decryption must fail when AAD does not match")
        } catch (e: Exception) {
            assertTrue(e is AEADBadTagException || e.cause is AEADBadTagException)
        }
    }

    @Test
    fun testSha256Integrity() {
        val data = "Chunk verification test payload".toByteArray(Charsets.UTF_8)
        val hash1 = CryptoEngine.computeSha256(data)
        val hash2 = CryptoEngine.computeSha256(data)
        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length)
    }
}
