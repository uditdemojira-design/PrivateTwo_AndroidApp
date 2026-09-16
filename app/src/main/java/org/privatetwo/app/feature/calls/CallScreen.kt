package org.privatetwo.app.feature.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.privatetwo.app.core.webrtc.WebRtcCallState
import org.privatetwo.app.core.webrtc.WebRtcSessionManager
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun CallScreen(
    viewModel: CallViewModel,
    webRtcSessionManager: WebRtcSessionManager,
    isVideoCall: Boolean,
    onCallEnded: () -> Unit
) {
    val callState by viewModel.callState.collectAsState()
    val isAudioMuted by viewModel.isAudioMuted.collectAsState()
    val isVideoEnabled by viewModel.isVideoEnabled.collectAsState()
    val isSpeakerphoneOn by viewModel.isSpeakerphoneOn.collectAsState()
    val remoteVideoTrack by viewModel.remoteVideoTrack.collectAsState()
    val localVideoTrack by viewModel.localVideoTrack.collectAsState()
    val callDurationSeconds by viewModel.callDurationSeconds.collectAsState()

    LaunchedEffect(callState) {
        if (callState == WebRtcCallState.ENDED || callState == WebRtcCallState.IDLE) {
            onCallEnded()
        }
    }

    val minutes = callDurationSeconds / 60
    val seconds = callDurationSeconds % 60
    val durationFormatted = "%02d:%02d".format(minutes, seconds)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (isVideoCall && remoteVideoTrack != null) {
            // Connected remote video full screen
            WebRtcVideoView(
                videoTrack = remoteVideoTrack!!,
                eglContext = webRtcSessionManager.eglBase.eglBaseContext,
                modifier = Modifier.fillMaxSize()
            )

            // Local user PIP preview in top-right corner
            if (localVideoTrack != null && isVideoEnabled) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 48.dp, end = 16.dp)
                        .size(110.dp, 160.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.DarkGray)
                ) {
                    WebRtcVideoView(
                        videoTrack = localVideoTrack!!,
                        eglContext = webRtcSessionManager.eglBase.eglBaseContext,
                        isMirror = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        } else if (isVideoCall && localVideoTrack != null && isVideoEnabled) {
            // Caller/Callee local camera full screen preview while waiting for peer video
            WebRtcVideoView(
                videoTrack = localVideoTrack!!,
                eglContext = webRtcSessionManager.eglBase.eglBaseContext,
                isMirror = true,
                modifier = Modifier.fillMaxSize()
            )

            // Semi-transparent status overlay at top
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "Private Partner",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    val statusText = when (callState) {
                        WebRtcCallState.OUTGOING_CALL -> "Calling partner…"
                        WebRtcCallState.CONNECTING -> "Connecting encrypted video…"
                        WebRtcCallState.RECONNECTING -> "Reconnecting…"
                        WebRtcCallState.CONNECTED -> durationFormatted
                        else -> ""
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (callState == WebRtcCallState.RECONNECTING) Color.Yellow else Color.LightGray
                    )
                }
            }
        } else {
            // Audio call or camera disabled: Avatar screen
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(120.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isVideoCall) Icons.Default.Videocam else Icons.Default.Person,
                            contentDescription = "Avatar",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Private Partner",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(8.dp))

                val statusText = when (callState) {
                    WebRtcCallState.OUTGOING_CALL -> "Calling partner…"
                    WebRtcCallState.CONNECTING -> "Connecting encrypted call…"
                    WebRtcCallState.RECONNECTING -> "Reconnecting…"
                    WebRtcCallState.CONNECTED -> durationFormatted
                    else -> ""
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (callState == WebRtcCallState.RECONNECTING) Color.Yellow else Color.LightGray
                )
            }
        }

        if (callState == WebRtcCallState.CONNECTED && isVideoCall) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
            ) {
                Text(
                    text = durationFormatted,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        Surface(
            color = Color.Black.copy(alpha = 0.7f),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp, horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { viewModel.toggleMute() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isAudioMuted) Color.Red else Color.DarkGray
                    ),
                    modifier = Modifier.size(54.dp)
                ) {
                    Icon(
                        imageVector = if (isAudioMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Mute",
                        tint = Color.White
                    )
                }

                IconButton(
                    onClick = { viewModel.toggleSpeaker() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isSpeakerphoneOn) MaterialTheme.colorScheme.primary else Color.DarkGray
                    ),
                    modifier = Modifier.size(54.dp)
                ) {
                    Icon(
                        imageVector = if (isSpeakerphoneOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeDown,
                        contentDescription = "Speaker",
                        tint = Color.White
                    )
                }

                if (isVideoCall) {
                    IconButton(
                        onClick = { viewModel.toggleVideo() },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (!isVideoEnabled) Color.Red else Color.DarkGray
                        ),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Icon(
                            imageVector = if (isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                            contentDescription = "Video Toggle",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = { viewModel.switchCamera() },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.DarkGray
                        ),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlipCameraAndroid,
                            contentDescription = "Switch Camera",
                            tint = Color.White
                        )
                    }
                }

                IconButton(
                    onClick = { viewModel.endCall() },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFD32F2F)),
                    modifier = Modifier.size(60.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "End Call",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun WebRtcVideoView(
    videoTrack: VideoTrack,
    eglContext: org.webrtc.EglBase.Context,
    modifier: Modifier = Modifier,
    isMirror: Boolean = false
) {
    val trackRef = rememberUpdatedState(videoTrack)

    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setMirror(isMirror)
                setEnableHardwareScaler(true)
                trackRef.value.addSink(this)
            }
        },
        update = { renderer ->
            renderer.setMirror(isMirror)
        },
        onRelease = { renderer ->
            try {
                trackRef.value.removeSink(renderer)
                renderer.release()
            } catch (_: Exception) {}
        },
        modifier = modifier
    )
}
