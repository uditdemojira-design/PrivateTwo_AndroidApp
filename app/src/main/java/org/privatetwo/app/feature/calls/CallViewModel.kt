package org.privatetwo.app.feature.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.privatetwo.app.core.database.CallRecordDao
import org.privatetwo.app.core.database.CallRecordEntity
import org.privatetwo.app.core.webrtc.WebRtcCallState
import org.privatetwo.app.core.webrtc.WebRtcSessionManager
import org.webrtc.VideoTrack
import java.util.UUID

class CallViewModel(
    private val webRtcSessionManager: WebRtcSessionManager,
    private val callRecordDao: CallRecordDao
) : ViewModel() {

    val callState: StateFlow<WebRtcCallState> = webRtcSessionManager.callState
    val isIncomingCallVideo: StateFlow<Boolean> = webRtcSessionManager.isIncomingCallVideo
    val isAudioMuted: StateFlow<Boolean> = webRtcSessionManager.isAudioMuted
    val isVideoEnabled: StateFlow<Boolean> = webRtcSessionManager.isVideoEnabled
    val isSpeakerphoneOn: StateFlow<Boolean> = webRtcSessionManager.isSpeakerphoneOn

    val callRecords: StateFlow<List<CallRecordEntity>> = callRecordDao.getAllCallRecordsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()
    val localVideoTrack: StateFlow<VideoTrack?> = webRtcSessionManager.localVideoTrackState

    private val _isEnhanceModeEnabled = MutableStateFlow(false)
    val isEnhanceModeEnabled: StateFlow<Boolean> = _isEnhanceModeEnabled.asStateFlow()

    private val _callDurationSeconds = MutableStateFlow(0L)
    val callDurationSeconds: StateFlow<Long> = _callDurationSeconds.asStateFlow()

    private var durationJob: Job? = null

    private var activeCallStartTime: Long = 0L
    private var activeCallIsVideo: Boolean = false
    private var activeCallIsIncoming: Boolean = false
    private var activeCallConnected: Boolean = false
    private var isTrackingCall: Boolean = false

    init {
        webRtcSessionManager.onRemoteVideoTrackReady = { track ->
            _remoteVideoTrack.value = track
        }

        viewModelScope.launch {
            webRtcSessionManager.callState.collect { state ->
                when (state) {
                    WebRtcCallState.INCOMING_CALL -> {
                        activeCallStartTime = System.currentTimeMillis()
                        activeCallIsVideo = webRtcSessionManager.isIncomingCallVideo.value
                        activeCallIsIncoming = true
                        activeCallConnected = false
                        isTrackingCall = true
                    }
                    WebRtcCallState.CONNECTED -> {
                        activeCallConnected = true
                        startDurationTimer()
                        webRtcSessionManager.optimizeVideoQuality()
                    }
                    WebRtcCallState.IDLE,
                    WebRtcCallState.ENDED -> {
                        _remoteVideoTrack.value = null
                        _isEnhanceModeEnabled.value = false
                        val finalDuration = _callDurationSeconds.value
                        stopDurationTimer()

                        if (isTrackingCall) {
                            isTrackingCall = false
                            val status = when {
                                finalDuration > 0 -> "COMPLETED"
                                activeCallConnected -> "COMPLETED"
                                activeCallIsIncoming -> "MISSED"
                                else -> "UNANSWERED"
                            }
                            val record = CallRecordEntity(
                                id = UUID.randomUUID().toString(),
                                timestamp = if (activeCallStartTime > 0) activeCallStartTime else System.currentTimeMillis(),
                                durationSeconds = finalDuration,
                                callType = if (activeCallIsVideo) "VIDEO" else "AUDIO",
                                callStatus = status,
                                isIncoming = activeCallIsIncoming
                            )
                            viewModelScope.launch {
                                callRecordDao.insertCallRecord(record)
                            }
                        }
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
        activeCallStartTime = System.currentTimeMillis()
        activeCallIsVideo = isVideo
        activeCallIsIncoming = false
        activeCallConnected = false
        isTrackingCall = true
        webRtcSessionManager.startOutgoingCall(isVideo)
    }

    fun acceptIncomingCall(isVideo: Boolean) {
        activeCallStartTime = System.currentTimeMillis()
        activeCallIsVideo = isVideo
        activeCallIsIncoming = true
        activeCallConnected = false
        isTrackingCall = true
        webRtcSessionManager.acceptIncomingCall(isVideo)
    }

    fun rejectIncomingCall() {
        val isVideo = webRtcSessionManager.isIncomingCallVideo.value
        viewModelScope.launch {
            callRecordDao.insertCallRecord(
                CallRecordEntity(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    durationSeconds = 0L,
                    callType = if (isVideo) "VIDEO" else "AUDIO",
                    callStatus = "DECLINED",
                    isIncoming = true
                )
            )
        }
        isTrackingCall = false
        webRtcSessionManager.rejectIncomingCall()
    }

    fun clearCallHistory() {
        viewModelScope.launch {
            callRecordDao.deleteAllCallRecords()
        }
    }

    fun deleteCallRecord(id: String) {
        viewModelScope.launch {
            callRecordDao.deleteCallRecord(id)
        }
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

    fun toggleEnhanceMode(): Boolean {
        val newState = !_isEnhanceModeEnabled.value
        _isEnhanceModeEnabled.value = newState
        return newState
    }

    fun getLocalVideoTrack(): VideoTrack? = webRtcSessionManager.getLocalVideoTrack()

    class Factory(
        private val webRtcSessionManager: WebRtcSessionManager,
        private val callRecordDao: CallRecordDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CallViewModel(webRtcSessionManager, callRecordDao) as T
        }
    }
}
