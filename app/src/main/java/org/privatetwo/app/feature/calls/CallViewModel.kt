package org.privatetwo.app.feature.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.privatetwo.app.core.webrtc.WebRtcCallState
import org.privatetwo.app.core.webrtc.WebRtcSessionManager
import org.webrtc.VideoTrack

class CallViewModel(
    private val webRtcSessionManager: WebRtcSessionManager
) : ViewModel() {

    val callState: StateFlow<WebRtcCallState> = webRtcSessionManager.callState
    val isAudioMuted: StateFlow<Boolean> = webRtcSessionManager.isAudioMuted
    val isVideoEnabled: StateFlow<Boolean> = webRtcSessionManager.isVideoEnabled
    val isSpeakerphoneOn: StateFlow<Boolean> = webRtcSessionManager.isSpeakerphoneOn

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()
    val localVideoTrack: StateFlow<VideoTrack?> = webRtcSessionManager.localVideoTrackState

    private val _callDurationSeconds = MutableStateFlow(0L)
    val callDurationSeconds: StateFlow<Long> = _callDurationSeconds.asStateFlow()

    private var durationJob: Job? = null

    init {
        webRtcSessionManager.onRemoteVideoTrackReady = { track ->
            _remoteVideoTrack.value = track
        }

        viewModelScope.launch {
            webRtcSessionManager.callState.collect { state ->
                when (state) {
                    WebRtcCallState.CONNECTED -> startDurationTimer()
                    WebRtcCallState.IDLE,
                    WebRtcCallState.ENDED -> {
                        _remoteVideoTrack.value = null
                        stopDurationTimer()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun startDurationTimer() {
        durationJob?.cancel()
        _callDurationSeconds.value = 0L
        durationJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _callDurationSeconds.value += 1
            }
        }
    }

    private fun stopDurationTimer() {
        durationJob?.cancel()
        _callDurationSeconds.value = 0L
    }

    fun startOutgoingCall(isVideo: Boolean) {
        webRtcSessionManager.startOutgoingCall(isVideo)
    }

    fun acceptIncomingCall(isVideo: Boolean) {
        webRtcSessionManager.acceptIncomingCall(isVideo)
    }

    fun rejectIncomingCall() {
        webRtcSessionManager.rejectIncomingCall()
    }

    fun endCall() {
        webRtcSessionManager.endCall()
    }

    fun toggleMute() {
        webRtcSessionManager.toggleAudioMute()
    }

    fun toggleVideo() {
        webRtcSessionManager.toggleVideoEnabled()
    }

    fun switchCamera() {
        webRtcSessionManager.switchCamera()
    }

    fun toggleSpeaker() {
        webRtcSessionManager.toggleSpeakerphone()
    }

    fun getLocalVideoTrack(): VideoTrack? = webRtcSessionManager.getLocalVideoTrack()

    class Factory(private val webRtcSessionManager: WebRtcSessionManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CallViewModel(webRtcSessionManager) as T
        }
    }
}
