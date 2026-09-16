package org.privatetwo.app.feature.pairing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.privatetwo.app.core.crypto.CryptoEngine
import org.privatetwo.app.core.crypto.DerivedSessionKeys
import org.privatetwo.app.core.security.SecureStorage
import org.privatetwo.app.core.signaling.SignalingClient
import org.privatetwo.app.core.signaling.SignalingEvent
import java.security.PublicKey
import java.security.SecureRandom

sealed class PairingUiState {
    object Unpaired : PairingUiState()
    data class Connecting(val message: String) : PairingUiState()
    data class CodeGenerated(val code: String, val secondsRemaining: Int) : PairingUiState()
    data class VerifyingSas(val sasCode: String, val peerDeviceId: String) : PairingUiState()
    object PairedSuccessfully : PairingUiState()
    data class Error(val message: String) : PairingUiState()
}

class PairingManager(
    private val secureStorage: SecureStorage,
    private val signalingClient: SignalingClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val secureRandom = SecureRandom()

    private val _uiState = MutableStateFlow<PairingUiState>(
        if (secureStorage.isPaired()) PairingUiState.PairedSuccessfully else PairingUiState.Unpaired
    )
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()
    val connectionState = signalingClient.connectionState

    fun getSignalingUrl(): String = signalingClient.serverUrl

    fun updateSignalingUrl(url: String) {
        secureStorage.setSignalingUrl(url)
        signalingClient.updateServerUrl(url)
    }

    private var countdownJob: Job? = null
    private var handshakeRetryJob: Job? = null
    private var pendingPeerPublicKey: PublicKey? = null
    private var pendingSessionKeys: DerivedSessionKeys? = null
    private var isInitiator: Boolean = false
    private var activeGeneratedCode: String? = null
    private var activeJoinCode: String? = null
    private var joinRetryCount: Int = 0

    init {
        scope.launch {
            signalingClient.events.collect { event ->
                handleSignalingEvent(event)
            }
        }
    }

    fun onSocketReconnected() {
        val code = activeGeneratedCode
        if (code != null && _uiState.value is PairingUiState.CodeGenerated) {
            val localDeviceId = secureStorage.getLocalDeviceId()
            signalingClient.registerPairingCode(code, localDeviceId)
        }
        val joinCode = activeJoinCode
        if (joinCode != null && _uiState.value is PairingUiState.Connecting) {
            val localDeviceId = secureStorage.getLocalDeviceId()
            signalingClient.joinPairingCode(joinCode, localDeviceId)
        }
    }

    fun generatePairingCode() {
        if (secureStorage.isPaired()) {
            _uiState.value = PairingUiState.Error("Device is already paired. Max 2 devices allowed.")
            return
        }

        val codeInt = 100_000 + secureRandom.nextInt(900_000)
        val code = codeInt.toString()
        val localDeviceId = secureStorage.getLocalDeviceId()
        isInitiator = true
        activeGeneratedCode = code
        activeJoinCode = null

        signalingClient.connect()
        signalingClient.registerPairingCode(code, localDeviceId)
        startCountdown(code, 180)
    }

    fun enterPairingCode(code: String) {
        if (secureStorage.isPaired()) {
            _uiState.value = PairingUiState.Error("Device is already paired. Max 2 devices allowed.")
            return
        }

        val sanitizedCode = code.replace(" ", "").trim()
        if (sanitizedCode.length != 6 || !sanitizedCode.all { it.isDigit() }) {
            _uiState.value = PairingUiState.Error("Pairing code must be exactly 6 digits.")
            return
        }

        _uiState.value = PairingUiState.Connecting("Connecting to partner with code $sanitizedCode...")
        signalingClient.connect()
        val localDeviceId = secureStorage.getLocalDeviceId()
        isInitiator = false
        activeJoinCode = sanitizedCode
        joinRetryCount = 0

        signalingClient.joinPairingCode(sanitizedCode, localDeviceId)
    }

    fun confirmSasMatch() {
        val peerKey = pendingPeerPublicKey
        val sessionKeys = pendingSessionKeys

        if (peerKey == null || sessionKeys == null) {
            _uiState.value = PairingUiState.Error("Invalid pairing state.")
            return
        }

        val isInit = isInitiator
        secureStorage.savePairedPeer(peerKey, sessionKeys.outboundKey, sessionKeys.inboundKey, isInit)

        val localDeviceId = secureStorage.getLocalDeviceId()
        val peerDeviceId = CryptoEngine.computeDeviceId(peerKey)
        val directSessionId = listOf(localDeviceId, peerDeviceId).sorted().joinToString("_")
        signalingClient.joinDirectSession(directSessionId, localDeviceId, peerDeviceId)

        val confirmMsg = JSONObject().put("type", "SAS_CONFIRMED")
        signalingClient.sendPairHandshake(confirmMsg.toString())

        _uiState.value = PairingUiState.PairedSuccessfully
    }

    fun rejectSasMatch() {
        cancelPairing("Pairing rejected: SAS verification failed.")
    }

    fun cancelPairing(reason: String? = null) {
        countdownJob?.cancel()
        handshakeRetryJob?.cancel()
        activeGeneratedCode = null
        activeJoinCode = null
        joinRetryCount = 0
        pendingPeerPublicKey = null
        pendingSessionKeys = null
        _uiState.value = if (reason != null) PairingUiState.Error(reason) else PairingUiState.Unpaired
    }

    fun unpair() {
        secureStorage.unpairDevice()
        cancelPairing()
        _uiState.value = PairingUiState.Unpaired
    }

    private fun startCountdown(code: String, seconds: Int) {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            for (remaining in seconds downTo 1) {
                _uiState.value = PairingUiState.CodeGenerated(
                    code = "${code.substring(0, 3)} ${code.substring(3, 6)}",
                    secondsRemaining = remaining
                )
                delay(1000)
            }
            _uiState.value = PairingUiState.Error("Pairing code expired. Please generate a new one.")
        }
    }

    private fun handleSignalingEvent(event: SignalingEvent) {
        when (event) {
            is SignalingEvent.PeerJoinedPairing -> {
                countdownJob?.cancel()
                _uiState.value = PairingUiState.Connecting("Partner joined! Exchanging cryptographic keys...")
                sendKeyExchangeOffer()
            }
            is SignalingEvent.PairingAccepted -> {
                _uiState.value = PairingUiState.Connecting("Pairing accepted! Exchanging cryptographic keys...")
            }
            is SignalingEvent.PairHandshakeReceived -> {
                handleHandshakePayload(event.payload)
            }
            is SignalingEvent.PairingCodeExpired -> {
                countdownJob?.cancel()
                _uiState.value = PairingUiState.Error("Pairing code expired.")
            }
            is SignalingEvent.Error -> {
                if (_uiState.value is PairingUiState.CodeGenerated) {
                    // Do not destroy the displayed pairing code on transient socket errors!
                    return
                }
                if (_uiState.value is PairingUiState.Connecting) {
                    if (event.code == "CONNECTION_FAILED" || event.code == "PEER_OFFLINE") {
                        // Transient connection issue during handshake; allow background retry
                        return
                    }
                    if (event.code == "PAIRING_CODE_NOT_FOUND" && joinRetryCount < 2) {
                        joinRetryCount++
                        scope.launch {
                            delay(1500)
                            val joinCode = activeJoinCode
                            if (joinCode != null && _uiState.value is PairingUiState.Connecting) {
                                signalingClient.joinPairingCode(joinCode, secureStorage.getLocalDeviceId())
                            }
                        }
                        return
                    }
                }
                countdownJob?.cancel()
                handshakeRetryJob?.cancel()
                _uiState.value = PairingUiState.Error(event.message)
            }
            else -> Unit
        }
    }

    private fun sendKeyExchangeOffer() {
        handshakeRetryJob?.cancel()
        val localPair = secureStorage.getOrCreateIdentityKeyPair()
        val payload = JSONObject()
            .put("action", "OFFER_KEY")
            .put("publicKey", CryptoEngine.encodePublicKey(localPair.public))
            .put("deviceId", secureStorage.getLocalDeviceId())

        handshakeRetryJob = scope.launch {
            for (attempt in 1..4) {
                if (_uiState.value !is PairingUiState.Connecting) break
                signalingClient.sendPairHandshake(payload.toString())
                delay(2500)
            }
        }
    }

    private fun handleHandshakePayload(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            when (json.optString("action")) {
                "OFFER_KEY" -> {
                    val remotePubBase64 = json.getString("publicKey")
                    val remotePubKey = CryptoEngine.decodePublicKey(remotePubBase64)
                    pendingPeerPublicKey = remotePubKey

                    val localPair = secureStorage.getOrCreateIdentityKeyPair()
                    val answer = JSONObject()
                        .put("action", "ANSWER_KEY")
                        .put("publicKey", CryptoEngine.encodePublicKey(localPair.public))
                        .put("deviceId", secureStorage.getLocalDeviceId())
                    signalingClient.sendPairHandshake(answer.toString())

                    computeKeysAndShowSas(localPair.public, localPair.private, remotePubKey)
                }
                "ANSWER_KEY" -> {
                    handshakeRetryJob?.cancel()
                    val remotePubBase64 = json.getString("publicKey")
                    val remotePubKey = CryptoEngine.decodePublicKey(remotePubBase64)
                    pendingPeerPublicKey = remotePubKey

                    val localPair = secureStorage.getOrCreateIdentityKeyPair()
                    computeKeysAndShowSas(localPair.public, localPair.private, remotePubKey)
                }
                "SAS_CONFIRMED" -> {}
            }
        } catch (e: Exception) {
            cancelPairing("Handshake failed: ${e.localizedMessage}")
        }
    }

    private fun computeKeysAndShowSas(localPub: PublicKey, localPriv: java.security.PrivateKey, remotePub: PublicKey) {
        val sharedSecret = CryptoEngine.computeSharedSecret(localPriv, remotePub)
        val keys = CryptoEngine.deriveSessionKeys(
            sharedSecret = sharedSecret,
            localPublicKey = localPub,
            peerPublicKey = remotePub,
            isInitiator = isInitiator
        )
        pendingSessionKeys = keys

        val peerDeviceId = CryptoEngine.computeDeviceId(remotePub)
        _uiState.value = PairingUiState.VerifyingSas(
            sasCode = keys.sasCode,
            peerDeviceId = peerDeviceId
        )
    }
}
