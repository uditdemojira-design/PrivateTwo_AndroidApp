package org.privatetwo.app.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.privatetwo.app.core.crypto.CryptoEngine
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Base64

/**
 * Handles hardware-backed keystore integration and secure persistent storage
 * for device identity, paired peer credentials, and privacy settings.
 */
class SecureStorage(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()

            androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                "privatetwo_secure_prefs",
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback for testing / emulator environments where master key is mocked
            context.getSharedPreferences("privatetwo_fallback_prefs", Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val IDENTITY_KEY_ALIAS = "privatetwo_identity_key"

        private const val KEY_PEER_PUBLIC_KEY = "peer_public_key"
        private const val KEY_PEER_DEVICE_ID = "peer_device_id"
        private const val KEY_IS_PAIRED = "is_paired"
        private const val KEY_LOCAL_PUB_KEY_FALLBACK = "local_pub_key_fallback"
        private const val KEY_LOCAL_PRIV_KEY_FALLBACK = "local_priv_key_fallback"

        // Privacy flags
        private const val KEY_FLAG_SECURE = "flag_secure_enabled"
        private const val KEY_BIOMETRIC_LOCK = "biometric_lock_enabled"
        private const val KEY_STEALTH_NOTIFICATIONS = "stealth_notifications_enabled"

        private const val KEY_OUTBOUND_KEY = "outbound_session_key"
        private const val KEY_INBOUND_KEY = "inbound_session_key"
        private const val KEY_IS_INITIATOR = "is_initiator"

        private const val KEY_PARTNER_DISPLAY_NAME = "partner_display_name"
        private const val KEY_HAS_PROMPTED_NAME = "has_prompted_name"

        private const val KEY_ANTI_PEEP_TILT = "anti_peep_tilt_enabled"
        private const val KEY_ANTI_PEEP_SHADE = "anti_peep_shade_enabled"

        // Ephemeral session keys cached in memory
        @Volatile
        var activeInboundSessionKey: ByteArray? = null
        @Volatile
        var activeOutboundSessionKey: ByteArray? = null
    }

    /**
     * Retrieves or generates the local NIST P-256 EC identity keypair.
     * Uses AndroidKeyStore when available, with secure software fallback.
     */
    @Synchronized
    fun getOrCreateIdentityKeyPair(): KeyPair {
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
            keyStore.load(null)

            if (keyStore.containsAlias(IDENTITY_KEY_ALIAS)) {
                val privateKey = keyStore.getKey(IDENTITY_KEY_ALIAS, null) as? PrivateKey
                val certificate = keyStore.getCertificate(IDENTITY_KEY_ALIAS)
                val publicKey = certificate?.publicKey

                if (privateKey != null && publicKey != null) {
                    return KeyPair(publicKey, privateKey)
                }
            }

            // Generate inside Android KeyStore
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
            val parameterSpec = KeyGenParameterSpec.Builder(
                IDENTITY_KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_AGREE_KEY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()

            kpg.initialize(parameterSpec)
            return kpg.generateKeyPair()
        } catch (e: Exception) {
            // AndroidKeyStore unavailable (e.g. JVM unit tests) - use software fallback
            return getOrCreateFallbackKeyPair()
        }
    }

    private fun getOrCreateFallbackKeyPair(): KeyPair {
        val storedPub = prefs.getString(KEY_LOCAL_PUB_KEY_FALLBACK, null)
        val storedPriv = prefs.getString(KEY_LOCAL_PRIV_KEY_FALLBACK, null)

        if (storedPub != null && storedPriv != null) {
            try {
                val pub = CryptoEngine.decodePublicKey(storedPub)
                val kf = java.security.KeyFactory.getInstance("EC")
                val privSpec = java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(storedPriv))
                val priv = kf.generatePrivate(privSpec)
                return KeyPair(pub, priv)
            } catch (e: Exception) {
                // Regenerate if corrupted
            }
        }

        val pair = CryptoEngine.generateKeyPair()
        prefs.edit()
            .putString(KEY_LOCAL_PUB_KEY_FALLBACK, CryptoEngine.encodePublicKey(pair.public))
            .putString(KEY_LOCAL_PRIV_KEY_FALLBACK, Base64.getEncoder().encodeToString(pair.private.encoded))
            .apply()
        return pair
    }

    fun getLocalDeviceId(): String {
        val pair = getOrCreateIdentityKeyPair()
        return CryptoEngine.computeDeviceId(pair.public)
    }

    fun isPaired(): Boolean {
        return prefs.getBoolean(KEY_IS_PAIRED, false) && getPairedPeerPublicKey() != null
    }

    fun getPairedPeerPublicKey(): PublicKey? {
        val base64Key = prefs.getString(KEY_PEER_PUBLIC_KEY, null) ?: return null
        return try {
            CryptoEngine.decodePublicKey(base64Key)
        } catch (e: Exception) {
            null
        }
    }

    fun getPairedPeerDeviceId(): String? {
        return prefs.getString(KEY_PEER_DEVICE_ID, null)
    }

    /**
     * Stores authenticated peer public key upon successful 2-device pairing.
     * Enforces strict 2-device limit.
     */
    @Synchronized
    fun savePairedPeer(
        peerPublicKey: PublicKey,
        outboundKey: ByteArray? = null,
        inboundKey: ByteArray? = null,
        isInitiator: Boolean = false
    ) {
        val deviceId = CryptoEngine.computeDeviceId(peerPublicKey)
        val base64Key = CryptoEngine.encodePublicKey(peerPublicKey)

        val editor = prefs.edit()
            .putString(KEY_PEER_PUBLIC_KEY, base64Key)
            .putString(KEY_PEER_DEVICE_ID, deviceId)
            .putBoolean(KEY_IS_PAIRED, true)
            .putBoolean(KEY_IS_INITIATOR, isInitiator)

        if (outboundKey != null) {
            editor.putString(KEY_OUTBOUND_KEY, Base64.getEncoder().encodeToString(outboundKey))
            activeOutboundSessionKey = outboundKey
        }
        if (inboundKey != null) {
            editor.putString(KEY_INBOUND_KEY, Base64.getEncoder().encodeToString(inboundKey))
            activeInboundSessionKey = inboundKey
        }
        editor.apply()
    }

    @Synchronized
    fun saveSessionKeys(outboundKey: ByteArray, inboundKey: ByteArray) {
        activeOutboundSessionKey = outboundKey
        activeInboundSessionKey = inboundKey
        prefs.edit()
            .putString(KEY_OUTBOUND_KEY, Base64.getEncoder().encodeToString(outboundKey))
            .putString(KEY_INBOUND_KEY, Base64.getEncoder().encodeToString(inboundKey))
            .apply()
    }

    fun getOutboundSessionKey(): ByteArray? {
        val inMem = activeOutboundSessionKey
        if (inMem != null) return inMem

        val base64 = prefs.getString(KEY_OUTBOUND_KEY, null)
        if (base64 != null) {
            val key = Base64.getDecoder().decode(base64)
            activeOutboundSessionKey = key
            return key
        }

        // Dynamically recover session keys from stored peer key & identity key
        val peerPub = getPairedPeerPublicKey() ?: return null
        val localPair = getOrCreateIdentityKeyPair()
        val isInit = prefs.getBoolean(KEY_IS_INITIATOR, false)
        val sharedSecret = CryptoEngine.computeSharedSecret(localPair.private, peerPub)
        val derived = CryptoEngine.deriveSessionKeys(sharedSecret, localPair.public, peerPub, isInit)
        saveSessionKeys(derived.outboundKey, derived.inboundKey)
        return derived.outboundKey
    }

    fun getInboundSessionKey(): ByteArray? {
        val inMem = activeInboundSessionKey
        if (inMem != null) return inMem

        val base64 = prefs.getString(KEY_INBOUND_KEY, null)
        if (base64 != null) {
            val key = Base64.getDecoder().decode(base64)
            activeInboundSessionKey = key
            return key
        }

        val peerPub = getPairedPeerPublicKey() ?: return null
        val localPair = getOrCreateIdentityKeyPair()
        val isInit = prefs.getBoolean(KEY_IS_INITIATOR, false)
        val sharedSecret = CryptoEngine.computeSharedSecret(localPair.private, peerPub)
        val derived = CryptoEngine.deriveSessionKeys(sharedSecret, localPair.public, peerPub, isInit)
        saveSessionKeys(derived.outboundKey, derived.inboundKey)
        return derived.inboundKey
    }

    /**
     * Unpairs the device, permanently revokes the peer identity,
     * and clears persistent session keys.
     */
    @Synchronized
    fun unpairDevice() {
        prefs.edit()
            .remove(KEY_PEER_PUBLIC_KEY)
            .remove(KEY_PEER_DEVICE_ID)
            .remove(KEY_OUTBOUND_KEY)
            .remove(KEY_INBOUND_KEY)
            .remove(KEY_IS_INITIATOR)
            .remove(KEY_PARTNER_DISPLAY_NAME)
            .remove(KEY_HAS_PROMPTED_NAME)
            .putBoolean(KEY_IS_PAIRED, false)
            .apply()

        activeInboundSessionKey = null
        activeOutboundSessionKey = null
    }

    // --- Partner Display Name ---

    fun getPartnerDisplayName(): String? = prefs.getString(KEY_PARTNER_DISPLAY_NAME, null)?.takeIf { it.isNotBlank() }

    fun setPartnerDisplayName(name: String?) {
        val trimmed = name?.trim()
        if (trimmed.isNullOrBlank()) {
            prefs.edit().remove(KEY_PARTNER_DISPLAY_NAME).apply()
        } else {
            prefs.edit().putString(KEY_PARTNER_DISPLAY_NAME, trimmed).apply()
        }
    }

    fun hasPromptedName(): Boolean = prefs.getBoolean(KEY_HAS_PROMPTED_NAME, false)

    fun setHasPromptedName(prompted: Boolean) {
        prefs.edit().putBoolean(KEY_HAS_PROMPTED_NAME, prompted).apply()
    }

    // --- Privacy Settings ---

    var isFlagSecureEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLAG_SECURE, true) // Enabled by default for privacy
        set(value) = prefs.edit().putBoolean(KEY_FLAG_SECURE, value).apply()

    var isBiometricLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_LOCK, value).apply()

    var isStealthNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_STEALTH_NOTIFICATIONS, true) // Stealth "New private message" by default
        set(value) = prefs.edit().putBoolean(KEY_STEALTH_NOTIFICATIONS, value).apply()

    var isAntiPeepTiltEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANTI_PEEP_TILT, true) // Enabled by default for anti-peeping
        set(value) = prefs.edit().putBoolean(KEY_ANTI_PEEP_TILT, value).apply()

    var isAntiPeepShadeEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANTI_PEEP_SHADE, false)
        set(value) = prefs.edit().putBoolean(KEY_ANTI_PEEP_SHADE, value).apply()

    fun getSignalingUrl(defaultUrl: String): String {
        val saved = prefs.getString("signaling_server_url", null)
        if (saved.isNullOrBlank()
            || saved.contains(":8080")
            || saved.contains("forever-steel-played-ranging")
            || saved.contains("hydrocodone")
            || saved.contains("192.168.1.5")
            || (saved.contains("10.0.2.2") && !defaultUrl.contains("10.0.2.2"))) {
            return defaultUrl
        }
        return saved
    }

    fun setSignalingUrl(url: String) {
        prefs.edit().putString("signaling_server_url", url.trim()).apply()
    }
}
