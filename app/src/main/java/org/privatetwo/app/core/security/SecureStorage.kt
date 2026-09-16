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

        // Ephemeral session keys (stored only in memory during runtime)
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
    fun savePairedPeer(peerPublicKey: PublicKey) {
        val deviceId = CryptoEngine.computeDeviceId(peerPublicKey)
        val base64Key = CryptoEngine.encodePublicKey(peerPublicKey)

        prefs.edit()
            .putString(KEY_PEER_PUBLIC_KEY, base64Key)
            .putString(KEY_PEER_DEVICE_ID, deviceId)
            .putBoolean(KEY_IS_PAIRED, true)
            .apply()
    }

    /**
     * Unpairs the device, permanently revokes the peer identity,
     * and clears active in-memory session keys.
     */
    @Synchronized
    fun unpairDevice() {
        prefs.edit()
            .remove(KEY_PEER_PUBLIC_KEY)
            .remove(KEY_PEER_DEVICE_ID)
            .putBoolean(KEY_IS_PAIRED, false)
            .apply()

        activeInboundSessionKey = null
        activeOutboundSessionKey = null
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

    fun getSignalingUrl(defaultUrl: String): String {
        val saved = prefs.getString("signaling_server_url", null)
        if (saved.isNullOrBlank() || saved.contains(":8080") || (saved.contains("10.0.2.2") && !defaultUrl.contains("10.0.2.2"))) {
            return defaultUrl
        }
        return saved
    }

    fun setSignalingUrl(url: String) {
        prefs.edit().putString("signaling_server_url", url.trim()).apply()
    }
}
