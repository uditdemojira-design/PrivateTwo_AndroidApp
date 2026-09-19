package org.privatetwo.app.core.signaling

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class SignalingConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

sealed class SignalingEvent {
    data class PairingCodeRegistered(val code: String, val expiresInSeconds: Int) : SignalingEvent()
    data class PairingCodeExpired(val code: String) : SignalingEvent()
    data class PeerJoinedPairing(val sessionId: String, val peerDeviceId: String) : SignalingEvent()
    data class PairingAccepted(val sessionId: String, val peerDeviceId: String) : SignalingEvent()
    data class PairHandshakeReceived(val payload: String) : SignalingEvent()
    data class PeerConnected(val peerDeviceId: String) : SignalingEvent()
    object PeerDisconnected : SignalingEvent()
    data class SdpOfferReceived(val sdp: String) : SignalingEvent()
    data class SdpAnswerReceived(val sdp: String) : SignalingEvent()
    data class IceCandidateReceived(val sdpMid: String, val sdpMLineIndex: Int, val candidate: String) : SignalingEvent()
    data class CallOfferReceived(val isVideo: Boolean) : SignalingEvent()
    data class CallAnswerReceived(val accepted: Boolean) : SignalingEvent()
    object CallEndReceived : SignalingEvent()
    data class E2eeEnvelopeReceived(val envelopeJson: String) : SignalingEvent()
    data class Error(val code: String, val message: String) : SignalingEvent()
}

/**
 * WebSocket signaling client communicating with the ephemeral signaling server.
 * Never transmits plaintext messages or private keys.
 */
class SignalingClient(
    var serverUrl: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep alive for WebSocket
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var shouldReconnect = true
    private var reconnectAttempt = 0
    private val pendingMessages = mutableListOf<String>()

    private val _connectionState = MutableStateFlow(SignalingConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SignalingConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SignalingEvent> = _events.asSharedFlow()

    var onConnected: (() -> Unit)? = null
    var localDeviceId: String? = null

    companion object {
        fun sanitizeServerUrl(rawUrl: String): String {
            var url = rawUrl.trim().removeSuffix("/")
            if (url.startsWith("https://", ignoreCase = true)) {
                url = "wss://" + url.substring(8)
            } else if (url.startsWith("http://", ignoreCase = true)) {
                url = "ws://" + url.substring(7)
            } else if (!url.startsWith("ws://", ignoreCase = true) && !url.startsWith("wss://", ignoreCase = true)) {
                url = if (url.contains(".trycloudflare.com") || url.contains(".onrender.com") || url.contains(".loca.lt")) {
                    "wss://$url"
                } else {
                    "ws://$url"
                }
            }
            if (url.startsWith("ws://", ignoreCase = true) && (url.contains(".trycloudflare.com") || url.contains(".onrender.com") || url.contains(".loca.lt"))) {
                url = "wss://" + url.substring(5)
            }
            return url.trim().removeSuffix("/")
        }
    }

    fun updateServerUrl(newUrl: String) {
        val sanitized = sanitizeServerUrl(newUrl)
        if (serverUrl == sanitized && _connectionState.value == SignalingConnectionState.CONNECTED) return
        serverUrl = sanitized
        resetReconnectBackoff()
        disconnect()
        connect()
    }

    fun resetReconnectBackoff() {
        reconnectAttempt = 0
        reconnectJob?.cancel()
    }

    fun connect() {
        shouldReconnect = true
        serverUrl = sanitizeServerUrl(serverUrl)
        if (_connectionState.value == SignalingConnectionState.CONNECTED || _connectionState.value == SignalingConnectionState.CONNECTING) return

        _connectionState.value = SignalingConnectionState.CONNECTING
        try {
            val request = Request.Builder().url(serverUrl).build()

            webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _connectionState.value = SignalingConnectionState.CONNECTED
                    reconnectAttempt = 0
                    localDeviceId?.let { devId ->
                        val idMsg = JSONObject().put("type", "IDENTIFY").put("deviceId", devId)
                        webSocket.send(idMsg.toString())
                    }
                    onConnected?.invoke()
                    synchronized(pendingMessages) {
                        for (msg in pendingMessages) {
                            webSocket.send(msg)
                        }
                        pendingMessages.clear()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingMessage(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _connectionState.value = SignalingConnectionState.DISCONNECTED
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _connectionState.value = SignalingConnectionState.DISCONNECTED
                    synchronized(pendingMessages) {
                        pendingMessages.clear()
                    }
                    scope.launch {
                        _events.emit(
                            SignalingEvent.Error(
                                "CONNECTION_FAILED",
                                "Cannot reach signaling server ($serverUrl): ${t.localizedMessage ?: "Connection refused"}"
                            )
                        )
                    }
                    scheduleReconnect()
                }
            })
        } catch (e: Exception) {
            _connectionState.value = SignalingConnectionState.DISCONNECTED
            scope.launch {
                _events.emit(SignalingEvent.Error("INVALID_URL", "Invalid signaling URL: ${e.localizedMessage}"))
            }
        }
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        _connectionState.value = SignalingConnectionState.DISCONNECTED
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            // Exponential backoff to prevent battery drain: 2s -> 4s -> 8s -> 16s -> max 30s
            val delayMs = when (reconnectAttempt) {
                0 -> 2000L
                1 -> 4000L
                2 -> 8000L
                3 -> 16000L
                else -> 30000L
            }
            reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(10)
            delay(delayMs)
            if (isActive && shouldReconnect) {
                connect()
            }
        }
    }

    // --- Message senders ---

    fun registerPairingCode(code: String, deviceId: String) {
        val msg = JSONObject()
            .put("type", "REGISTER_PAIRING_CODE")
            .put("code", code)
            .put("deviceId", deviceId)
        send(msg.toString())
    }

    fun joinPairingCode(code: String, deviceId: String) {
        val msg = JSONObject()
            .put("type", "JOIN_PAIRING_CODE")
            .put("code", code)
            .put("deviceId", deviceId)
        send(msg.toString())
    }

    fun joinDirectSession(sessionId: String, deviceId: String, expectedPeerId: String) {
        val msg = JSONObject()
            .put("type", "JOIN_SESSION")
            .put("sessionId", sessionId)
            .put("deviceId", deviceId)
            .put("expectedPeerId", expectedPeerId)
        send(msg.toString())
    }

    fun sendPairHandshake(payload: String, deviceId: String? = null) {
        val msg = JSONObject()
            .put("type", "PAIR_HANDSHAKE")
            .put("payload", payload)
        val devId = deviceId ?: localDeviceId
        if (devId != null) {
            msg.put("deviceId", devId)
        }
        send(msg.toString())
    }

    fun sendSdpOffer(sdp: String) {
        val msg = JSONObject()
            .put("type", "SDP_OFFER")
            .put("sdp", sdp)
        send(msg.toString())
    }

    fun sendSdpAnswer(sdp: String) {
        val msg = JSONObject()
            .put("type", "SDP_ANSWER")
            .put("sdp", sdp)
        send(msg.toString())
    }

    fun sendIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val msg = JSONObject()
            .put("type", "ICE_CANDIDATE")
            .put("sdpMid", sdpMid)
            .put("sdpMLineIndex", sdpMLineIndex)
            .put("candidate", candidate)
        send(msg.toString())
    }

    fun sendCallOffer(isVideo: Boolean) {
        val msg = JSONObject()
            .put("type", "CALL_OFFER")
            .put("isVideo", isVideo)
        send(msg.toString())
    }

    fun sendCallAnswer(accepted: Boolean) {
        val msg = JSONObject()
            .put("type", "CALL_ANSWER")
            .put("accepted", accepted)
        send(msg.toString())
    }

    fun sendCallEnd() {
        val msg = JSONObject().put("type", "CALL_END")
        send(msg.toString())
    }

    fun sendE2eeEnvelope(envelopeJson: String) {
        val msg = JSONObject()
            .put("type", "E2EE_ENVELOPE")
            .put("envelope", envelopeJson)
        send(msg.toString())
    }

    private fun send(json: String): Boolean {
        var finalJson = json
        val devId = localDeviceId
        if (devId != null && !json.contains("\"deviceId\"")) {
            try {
                val obj = JSONObject(json)
                obj.put("deviceId", devId)
                finalJson = obj.toString()
            } catch (_: Exception) {}
        }
        val ws = webSocket
        if (_connectionState.value == SignalingConnectionState.CONNECTED && ws != null) {
            return ws.send(finalJson)
        } else {
            synchronized(pendingMessages) {
                pendingMessages.add(finalJson)
            }
            if (_connectionState.value == SignalingConnectionState.DISCONNECTED) {
                connect()
            }
            return true
        }
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val event = when (json.optString("type")) {
                "PAIRING_CODE_REGISTERED" -> SignalingEvent.PairingCodeRegistered(
                    json.getString("code"),
                    json.getInt("expiresInSeconds")
                )
                "PAIRING_CODE_EXPIRED" -> SignalingEvent.PairingCodeExpired(json.getString("code"))
                "PEER_JOINED_PAIRING" -> SignalingEvent.PeerJoinedPairing(
                    json.getString("sessionId"),
                    json.getString("peerDeviceId")
                )
                "PAIRING_ACCEPTED" -> SignalingEvent.PairingAccepted(
                    json.getString("sessionId"),
                    json.getString("peerDeviceId")
                )
                "PAIR_HANDSHAKE" -> SignalingEvent.PairHandshakeReceived(json.getString("payload"))
                "PEER_CONNECTED" -> SignalingEvent.PeerConnected(json.getString("peerDeviceId"))
                "PEER_DISCONNECTED" -> SignalingEvent.PeerDisconnected
                "SDP_OFFER" -> SignalingEvent.SdpOfferReceived(json.getString("sdp"))
                "SDP_ANSWER" -> SignalingEvent.SdpAnswerReceived(json.getString("sdp"))
                "ICE_CANDIDATE" -> SignalingEvent.IceCandidateReceived(
                    json.getString("sdpMid"),
                    json.getInt("sdpMLineIndex"),
                    json.getString("candidate")
                )
                "CALL_OFFER" -> SignalingEvent.CallOfferReceived(json.getBoolean("isVideo"))
                "CALL_ANSWER" -> SignalingEvent.CallAnswerReceived(json.getBoolean("accepted"))
                "CALL_END" -> SignalingEvent.CallEndReceived
                "E2EE_ENVELOPE" -> SignalingEvent.E2eeEnvelopeReceived(json.getString("envelope"))
                "ERROR" -> SignalingEvent.Error(json.getString("code"), json.getString("message"))
                else -> null
            }
            if (event != null) {
                _events.tryEmit(event)
            }
        } catch (e: Exception) {
            // Ignore malformed message in production
        }
    }
}
