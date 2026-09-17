package org.privatetwo.app.core.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.privatetwo.app.BuildConfig
import org.privatetwo.app.core.signaling.SignalingClient
import org.privatetwo.app.core.signaling.SignalingEvent
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.Executors

enum class WebRtcCallState {
    IDLE,
    OUTGOING_CALL,
    INCOMING_CALL,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ENDED
}

/**
 * Manages WebRTC PeerConnection, AudioTrack, VideoTrack, Camera2 capturer,
 * and RTC DataChannel for P2P end-to-end communication.
 */
class WebRtcSessionManager(
    private val context: Context,
    private val signalingClient: SignalingClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val executor = Executors.newSingleThreadExecutor()

    val eglBase: EglBase = EglBase.create()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private val _localVideoTrackState = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrackState: StateFlow<VideoTrack?> = _localVideoTrackState.asStateFlow()

    private var remoteVideoTrack: VideoTrack? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    @Suppress("DEPRECATION")
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _callState = MutableStateFlow(WebRtcCallState.IDLE)
    val callState: StateFlow<WebRtcCallState> = _callState.asStateFlow()

    private val _isAudioMuted = MutableStateFlow(false)
    val isAudioMuted: StateFlow<Boolean> = _isAudioMuted.asStateFlow()

    private val _isVideoEnabled = MutableStateFlow(true)
    val isVideoEnabled: StateFlow<Boolean> = _isVideoEnabled.asStateFlow()

    private val _isSpeakerphoneOn = MutableStateFlow(false)
    val isSpeakerphoneOn: StateFlow<Boolean> = _isSpeakerphoneOn.asStateFlow()

    var onDataChannelMessage: ((ByteArray) -> Unit)? = null
    var onRemoteVideoTrackReady: ((VideoTrack) -> Unit)? = null

    init {
        initializePeerConnectionFactory()
        listenToSignalingEvents()
    }

    private fun initializePeerConnectionFactory() {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val options = PeerConnectionFactory.Options()

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            true, /* enableIntelVp8Encoder */
            true  /* enableH264HighProfile */
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private val pendingIceCandidates = Collections.synchronizedList(mutableListOf<IceCandidate>())
    private var pendingRemoteSdpOffer: String? = null
    private var isWaitingForRemoteOfferToAnswer: Boolean = false
    private var isVideoCallSession: Boolean = false

    private fun getIceServers(): List<PeerConnection.IceServer> {
        val iceServers = mutableListOf<PeerConnection.IceServer>()

        // Primary externalized STUN server
        val stunUrl = BuildConfig.STUN_SERVER
        if (stunUrl.isNotBlank()) {
            iceServers.add(PeerConnection.IceServer.builder(stunUrl).createIceServer())
        }

        // Redundant Google public STUN servers for robust NAT traversal
        iceServers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer())
        iceServers.add(PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer())

        // Optional TURN server
        val turnUrl = BuildConfig.TURN_SERVER
        val turnUser = BuildConfig.TURN_USERNAME
        val turnPass = BuildConfig.TURN_CREDENTIAL
        if (turnUrl.isNotBlank()) {
            val turnBuilder = PeerConnection.IceServer.builder(turnUrl)
            if (turnUser.isNotBlank() && turnPass.isNotBlank()) {
                turnBuilder.setUsername(turnUser)
                turnBuilder.setPassword(turnPass)
            }
            iceServers.add(turnBuilder.createIceServer())
        }

        return iceServers
    }

    private fun createPeerConnection(): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(getIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

        return peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                signalingClient.sendIceCandidate(
                    candidate.sdpMid ?: "",
                    candidate.sdpMLineIndex,
                    candidate.sdp
                )
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        _callState.value = WebRtcCallState.CONNECTED
                        optimizeVideoQuality()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> {
                        _callState.value = WebRtcCallState.RECONNECTING
                        restartIce()
                    }
                    PeerConnection.IceConnectionState.CLOSED -> {
                        _callState.value = WebRtcCallState.ENDED
                    }
                    else -> Unit
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

            override fun onAddStream(stream: MediaStream?) {}

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(channel: DataChannel) {
                setupDataChannel(channel)
            }

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                receiver?.track()?.let { track ->
                    when (track) {
                        is VideoTrack -> {
                            remoteVideoTrack = track
                            scope.launch {
                                onRemoteVideoTrackReady?.invoke(track)
                            }
                        }
                        is AudioTrack -> {
                            track.setEnabled(true)
                            track.setVolume(1.0)
                        }
                        else -> {}
                    }
                }
            }
        })
    }

    private fun setupDataChannel(channel: DataChannel) {
        dataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {}

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                onDataChannelMessage?.invoke(bytes)
            }
        })
    }

    fun sendDataChannelMessage(data: ByteArray): Boolean {
        val channel = dataChannel
        if (channel != null && channel.state() == DataChannel.State.OPEN) {
            val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), true)
            return channel.send(buffer)
        }
        return false
    }

    // --- Audio and Video Tracks Setup ---

    fun startLocalMedia(enableVideo: Boolean) {
        // Audio constraints with echo cancellation, auto gain, and noise suppression
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            optional.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAudioMirroring", "false"))
        }
        audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("ARDAMSa0", audioSource)
        localAudioTrack?.setEnabled(true)

        // Video
        if (enableVideo) {
            val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) {
                val cam2 = Camera2Enumerator(context)
                if (cam2.deviceNames.isNotEmpty()) cam2 else Camera1Enumerator(true)
            } else {
                Camera1Enumerator(true)
            }
            val deviceNames = enumerator.deviceNames
            val cameraName = deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: deviceNames.firstOrNull { enumerator.isBackFacing(it) }
                ?: deviceNames.firstOrNull()

            if (cameraName != null) {
                try {
                    cameraCapturer = enumerator.createCapturer(cameraName, null)
                    surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
                    videoSource = peerConnectionFactory?.createVideoSource(cameraCapturer!!.isScreencast)
                    cameraCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
                    
                    // High-quality tiered resolution fallback (HD 720p down to safe VGA)
                    val targetTiers = listOf(
                        Triple(1280, 720, 30),
                        Triple(960, 540, 30),
                        Triple(640, 480, 30),
                        Triple(480, 360, 30),
                        Triple(320, 240, 15)
                    )
                    var captureStarted = false
                    for (tier in targetTiers) {
                        try {
                            cameraCapturer?.startCapture(tier.first, tier.second, tier.third)
                            android.util.Log.d("WebRtcManager", "Camera capture active at ${tier.first}x${tier.second}@${tier.third}fps")
                            captureStarted = true
                            break
                        } catch (_: Exception) {}
                    }
                    if (!captureStarted) {
                        try {
                            cameraCapturer?.startCapture(640, 480, 30)
                        } catch (_: Exception) {}
                    }

                    val track = peerConnectionFactory?.createVideoTrack("ARDAMSv0", videoSource)
                    track?.setEnabled(true)
                    localVideoTrack = track
                    _localVideoTrackState.value = track
                } catch (e: Exception) {
                    android.util.Log.e("WebRtcManager", "Error initializing camera", e)
                }
            }
        }
    }

    fun startOutgoingCall(isVideo: Boolean) {
        isVideoCallSession = isVideo
        _callState.value = WebRtcCallState.OUTGOING_CALL
        executor.execute {
            configureAudioRouting(isVideo)
            startLocalMedia(isVideo)
            peerConnection = createPeerConnection()

            localAudioTrack?.let { peerConnection?.addTrack(it, listOf("ARDAMS")) }
            localVideoTrack?.let { peerConnection?.addTrack(it, listOf("ARDAMS")) }

            val dcInit = DataChannel.Init()
            val dc = peerConnection?.createDataChannel("privatetwo-data", dcInit)
            dc?.let { setupDataChannel(it) }

            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideo) "true" else "false"))
            }

            peerConnection?.createOffer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(desc: SessionDescription) {
                    peerConnection?.setLocalDescription(object : SdpObserverAdapter() {
                        override fun onSetSuccess() {
                            signalingClient.sendCallOffer(isVideo)
                            signalingClient.sendSdpOffer(desc.description)
                        }
                    }, desc)
                }
            }, constraints)
        }
    }

    fun acceptIncomingCall(isVideo: Boolean) {
        isVideoCallSession = isVideo
        _callState.value = WebRtcCallState.CONNECTING
        executor.execute {
            configureAudioRouting(isVideo)
            startLocalMedia(isVideo)

            if (peerConnection == null) {
                peerConnection = createPeerConnection()
            }
            localAudioTrack?.let { peerConnection?.addTrack(it, listOf("ARDAMS")) }
            localVideoTrack?.let { peerConnection?.addTrack(it, listOf("ARDAMS")) }

            val offerSdp = pendingRemoteSdpOffer
            if (offerSdp != null) {
                applyOfferAndCreateAnswer(offerSdp, isVideo)
            } else {
                isWaitingForRemoteOfferToAnswer = true
            }
        }
    }

    private fun applyOfferAndCreateAnswer(offerSdp: String, isVideo: Boolean) {
        if (peerConnection == null) {
            peerConnection = createPeerConnection()
        }
        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                drainPendingIceCandidates()
                signalingClient.sendCallAnswer(true)

                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideo) "true" else "false"))
                }

                peerConnection?.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        peerConnection?.setLocalDescription(object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                signalingClient.sendSdpAnswer(desc.description)
                            }
                        }, desc)
                    }
                }, constraints)
            }
        }, remoteDesc)
    }

    fun rejectIncomingCall() {
        signalingClient.sendCallAnswer(false)
        endCall()
    }

    fun endCall() {
        _callState.value = WebRtcCallState.ENDED
        signalingClient.sendCallEnd()
        cleanupMedia()
        _callState.value = WebRtcCallState.IDLE
    }

    fun restartIce() {
        executor.execute {
            peerConnection?.restartIce()
        }
    }

    fun toggleAudioMute(): Boolean {
        val newMuted = !_isAudioMuted.value
        localAudioTrack?.setEnabled(!newMuted)
        _isAudioMuted.value = newMuted
        return newMuted
    }

    fun toggleVideoEnabled(): Boolean {
        val newEnabled = !_isVideoEnabled.value
        localVideoTrack?.setEnabled(newEnabled)
        _isVideoEnabled.value = newEnabled
        return newEnabled
    }

    fun switchCamera() {
        cameraCapturer?.switchCamera(null)
    }

    fun optimizeVideoQuality() {
        try {
            peerConnection?.senders?.forEach { sender ->
                if (sender.track() is VideoTrack) {
                    val params = sender.parameters
                    if (params != null && params.encodings.isNotEmpty()) {
                        for (encoding in params.encodings) {
                            encoding.minBitrateBps = 400_000
                            encoding.maxBitrateBps = 2_500_000
                            encoding.maxFramerate = 30
                        }
                        sender.parameters = params
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("WebRtcManager", "Could not set video sender bitrate parameters", e)
        }
    }

    private var audioFocusRequest: AudioFocusRequest? = null

    @Suppress("DEPRECATION")
    fun applySpeakerphoneRouting(enableSpeaker: Boolean) {
        try {
            _isSpeakerphoneOn.value = enableSpeaker
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (enableSpeaker) {
                    val speakerDevice = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speakerDevice != null) {
                        audioManager.setCommunicationDevice(speakerDevice)
                    } else {
                        audioManager.isSpeakerphoneOn = true
                    }
                } else {
                    val earpieceDevice = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    }
                    if (earpieceDevice != null) {
                        audioManager.setCommunicationDevice(earpieceDevice)
                    } else {
                        audioManager.clearCommunicationDevice()
                        audioManager.isSpeakerphoneOn = false
                    }
                }
            } else {
                audioManager.isSpeakerphoneOn = enableSpeaker
            }
        } catch (e: Exception) {
            android.util.Log.e("WebRtcManager", "Error applying speakerphone routing", e)
        }
    }

    @Suppress("DEPRECATION")
    fun toggleSpeakerphone(): Boolean {
        val newSpeaker = !_isSpeakerphoneOn.value
        applySpeakerphoneRouting(newSpeaker)
        return newSpeaker
    }

    @Suppress("DEPRECATION")
    private fun configureAudioRouting(isVideo: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { /* maintain audio focus */ }
                    .build()
                audioFocusRequest = focusReq
                audioManager.requestAudioFocus(focusReq)
            } else {
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }

            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isMicrophoneMute = false
            applySpeakerphoneRouting(isVideo)
        } catch (e: Exception) {
            android.util.Log.e("WebRtcManager", "Error configuring audio routing", e)
        }
    }

    fun getLocalVideoTrack(): VideoTrack? = localVideoTrack
    fun getRemoteVideoTrack(): VideoTrack? = remoteVideoTrack

    private fun listenToSignalingEvents() {
        scope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is SignalingEvent.CallOfferReceived -> {
                        _callState.value = WebRtcCallState.INCOMING_CALL
                    }
                    is SignalingEvent.CallAnswerReceived -> {
                        if (event.accepted) {
                            _callState.value = WebRtcCallState.CONNECTING
                        } else {
                            endCall()
                        }
                    }
                    is SignalingEvent.CallEndReceived -> {
                        cleanupMedia()
                        _callState.value = WebRtcCallState.IDLE
                    }
                    is SignalingEvent.SdpOfferReceived -> {
                        handleRemoteSdpOffer(event.sdp)
                    }
                    is SignalingEvent.SdpAnswerReceived -> {
                        handleRemoteSdpAnswer(event.sdp)
                    }
                    is SignalingEvent.IceCandidateReceived -> {
                        handleRemoteIceCandidate(event.sdpMid, event.sdpMLineIndex, event.candidate)
                    }
                    is SignalingEvent.PeerDisconnected -> {
                        if (_callState.value != WebRtcCallState.IDLE) {
                            _callState.value = WebRtcCallState.RECONNECTING
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun handleRemoteSdpOffer(sdp: String) {
        executor.execute {
            pendingRemoteSdpOffer = sdp
            if (isWaitingForRemoteOfferToAnswer) {
                isWaitingForRemoteOfferToAnswer = false
                applyOfferAndCreateAnswer(sdp, isVideoCallSession)
            }
        }
    }

    private fun handleRemoteSdpAnswer(sdp: String) {
        executor.execute {
            val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
            peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    drainPendingIceCandidates()
                }
            }, remoteDesc)
        }
    }

    private fun handleRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidateStr: String) {
        executor.execute {
            val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateStr)
            val pc = peerConnection
            if (pc != null && pc.remoteDescription != null) {
                pc.addIceCandidate(candidate)
            } else {
                pendingIceCandidates.add(candidate)
            }
        }
    }

    private fun drainPendingIceCandidates() {
        val pc = peerConnection ?: return
        synchronized(pendingIceCandidates) {
            for (candidate in pendingIceCandidates) {
                pc.addIceCandidate(candidate)
            }
            pendingIceCandidates.clear()
        }
    }

    @Suppress("DEPRECATION")
    private fun cleanupMedia() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                audioManager.abandonAudioFocus(null)
            }
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isMicrophoneMute = false
            audioManager.isSpeakerphoneOn = false
            _isSpeakerphoneOn.value = false

            pendingRemoteSdpOffer = null
            isWaitingForRemoteOfferToAnswer = false
            synchronized(pendingIceCandidates) {
                pendingIceCandidates.clear()
            }

            cameraCapturer?.stopCapture()
            cameraCapturer?.dispose()
            cameraCapturer = null

            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null

            localVideoTrack?.dispose()
            localVideoTrack = null
            _localVideoTrackState.value = null

            videoSource?.dispose()
            videoSource = null

            localAudioTrack?.dispose()
            localAudioTrack = null

            audioSource?.dispose()
            audioSource = null

            dataChannel?.close()
            dataChannel?.dispose()
            dataChannel = null

            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
        } catch (e: Exception) {
            // Ignore during cleanup
        }
    }
}

open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
