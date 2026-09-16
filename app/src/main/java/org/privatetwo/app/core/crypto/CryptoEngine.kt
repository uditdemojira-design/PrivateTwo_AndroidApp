package org.privatetwo.app.core.crypto

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Core cryptographic engine for PrivateTwo.
 * Implements:
 * - NIST P-256 (secp256r1) EC Key Generation & Key Agreement (ECDH)
 * - RFC 5869 HKDF-SHA-256 Extract and Expand
 * - AES-256-GCM Authenticated Encryption with Associated Data (AEAD)
 * - Deterministic Short Authentication String (SAS) derivation
 * - Device ID generation via SHA-256 fingerprint of public keys
 */
object CryptoEngine {

    private const val EC_CURVE = "secp256r1"
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val AES_KEY_SIZE_BYTES = 32

    private val secureRandom = SecureRandom()

    /**
     * Generates a new NIST P-256 EC KeyPair.
     */
    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(EC_CURVE), secureRandom)
        return kpg.generateKeyPair()
    }

    /**
     * Encodes a public key into Base64 (X.509 format).
     */
    fun encodePublicKey(publicKey: PublicKey): String {
        return Base64.getEncoder().encodeToString(publicKey.encoded)
    }

    /**
     * Decodes an X.509 Base64 encoded public key string.
     */
    fun decodePublicKey(base64Key: String): PublicKey {
        val keyBytes = Base64.getDecoder().decode(base64Key)
        val spec = X509EncodedKeySpec(keyBytes)
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePublic(spec)
    }

    /**
     * Computes a SHA-256 fingerprint of a public key (used as unique Device ID).
     */
    fun computeDeviceId(publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey.encoded)
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Performs ECDH key agreement to derive a raw shared secret.
     */
    fun computeSharedSecret(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peerPublicKey, true)
        return agreement.generateSecret()
    }

    /**
     * Derives session keys and SAS using HKDF-SHA-256 (RFC 5869).
     */
    fun deriveSessionKeys(
        sharedSecret: ByteArray,
        localPublicKey: PublicKey,
        peerPublicKey: PublicKey,
        isInitiator: Boolean
    ): DerivedSessionKeys {
        // Deterministic salt from lexicographically sorted public keys
        val localBytes = localPublicKey.encoded
        val peerBytes = peerPublicKey.encoded
        val salt = if (compareByteArrays(localBytes, peerBytes) < 0) {
            localBytes + peerBytes
        } else {
            peerBytes + localBytes
        }

        // HKDF-Extract: PRK = HMAC-Hash(salt, IKM)
        val prk = hkdfExtract(salt, sharedSecret)

        // HKDF-Expand for keys:
        // Key 1: Initiator to Responder AES-256 key
        val k1 = hkdfExpand(prk, "PrivateTwo-Init-To-Resp-AES256".toByteArray(Charsets.UTF_8), AES_KEY_SIZE_BYTES)
        // Key 2: Responder to Initiator AES-256 key
        val k2 = hkdfExpand(prk, "PrivateTwo-Resp-To-Init-AES256".toByteArray(Charsets.UTF_8), AES_KEY_SIZE_BYTES)
        // Key 3: SAS material (4 bytes = 32-bit int)
        val sasBytes = hkdfExpand(prk, "PrivateTwo-SAS-Verification".toByteArray(Charsets.UTF_8), 4)

        val (outboundKey, inboundKey) = if (isInitiator) {
            Pair(k1, k2)
        } else {
            Pair(k2, k1)
        }

        val sasCode = formatSasCode(sasBytes)

        return DerivedSessionKeys(
            outboundKey = outboundKey,
            inboundKey = inboundKey,
            sasCode = sasCode
        )
    }

    /**
     * Encrypts plaintext using AES-256-GCM.
     * Output format: 12-byte IV + (Ciphertext + 16-byte Auth Tag)
     */
    fun encrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray? = null): ByteArray {
        require(key.size == AES_KEY_SIZE_BYTES) { "AES-256 key must be 32 bytes" }
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        if (associatedData != null && associatedData.isNotEmpty()) {
            cipher.updateAAD(associatedData)
        }

        val ciphertextWithTag = cipher.doFinal(plaintext)
        val output = ByteArray(iv.size + ciphertextWithTag.size)
        System.arraycopy(iv, 0, output, 0, iv.size)
        System.arraycopy(ciphertextWithTag, 0, output, iv.size, ciphertextWithTag.size)
        return output
    }

    /**
     * Decrypts AES-256-GCM payload.
     * Input format: 12-byte IV + (Ciphertext + 16-byte Auth Tag)
     */
    fun decrypt(key: ByteArray, encryptedData: ByteArray, associatedData: ByteArray? = null): ByteArray {
        require(key.size == AES_KEY_SIZE_BYTES) { "AES-256 key must be 32 bytes" }
        require(encryptedData.size > GCM_IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8)) {
            "Payload too short for AES-GCM IV and Tag"
        }

        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        System.arraycopy(encryptedData, 0, iv, 0, iv.size)

        val ciphertextLength = encryptedData.size - iv.size
        val ciphertextWithTag = ByteArray(ciphertextLength)
        System.arraycopy(encryptedData, iv.size, ciphertextWithTag, 0, ciphertextLength)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        if (associatedData != null && associatedData.isNotEmpty()) {
            cipher.updateAAD(associatedData)
        }

        return cipher.doFinal(ciphertextWithTag)
    }

    /**
     * Computes SHA-256 checksum for arbitrary data (used for file chunk integrity).
     */
    fun computeSha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }

    // --- RFC 5869 HKDF primitives ---

    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val key = SecretKeySpec(salt, "HmacSHA256")
        mac.init(key)
        return mac.doFinal(ikm)
    }

    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * 32) { "HKDF length exceeds maximum supported" }
        val mac = Mac.getInstance("HmacSHA256")
        val key = SecretKeySpec(prk, "HmacSHA256")

        val result = ByteArray(length)
        var t = ByteArray(0)
        var generated = 0
        var i = 1

        while (generated < length) {
            mac.init(key)
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()

            val toCopy = Math.min(t.size, length - generated)
            System.arraycopy(t, 0, result, generated, toCopy)
            generated += toCopy
            i++
        }

        return result
    }

    private fun compareByteArrays(a: ByteArray, b: ByteArray): Int {
        val minLen = Math.min(a.size, b.size)
        for (i in 0 until minLen) {
            val cmp = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
            if (cmp != 0) return cmp
        }
        return a.size.compareTo(b.size)
    }

    private fun formatSasCode(sasBytes: ByteArray): String {
        val buffer = ByteBuffer.wrap(sasBytes)
        val value = Math.abs(buffer.int % 100_000_000)
        val str = "%08d".format(value)
        return "${str.substring(0, 4)}-${str.substring(4, 8)}"
    }
}

data class DerivedSessionKeys(
    val outboundKey: ByteArray,
    val inboundKey: ByteArray,
    val sasCode: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DerivedSessionKeys) return false
        return outboundKey.contentEquals(other.outboundKey) &&
                inboundKey.contentEquals(other.inboundKey) &&
                sasCode == other.sasCode
    }

    override fun hashCode(): Int {
        var result = outboundKey.contentHashCode()
        result = 31 * result + inboundKey.contentHashCode()
        result = 31 * result + sasCode.hashCode()
        return result
    }
}
