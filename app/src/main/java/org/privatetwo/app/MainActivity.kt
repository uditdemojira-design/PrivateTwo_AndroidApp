package org.privatetwo.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.privatetwo.app.core.security.BiometricAuthHelper
import org.privatetwo.app.core.webrtc.WebRtcCallState
import org.privatetwo.app.feature.calls.CallScreen
import org.privatetwo.app.feature.calls.CallViewModel
import org.privatetwo.app.feature.calls.IncomingCallScreen
import org.privatetwo.app.feature.chat.ChatScreen
import org.privatetwo.app.feature.chat.ChatViewModel
import org.privatetwo.app.feature.home.MainScreen
import org.privatetwo.app.feature.pairing.PairingScreen
import org.privatetwo.app.feature.pairing.PairingViewModel
import org.privatetwo.app.feature.settings.PrivacySettingsScreen

class MainActivity : FragmentActivity() {

    private val app by lazy { application as PrivateTwoApp }

    private val startupPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    private val pairingViewModel by viewModels<PairingViewModel> {
        PairingViewModel.Factory(app.pairingManager)
    }

    private val chatViewModel by viewModels<ChatViewModel> {
        ChatViewModel.Factory(app.chatRepository, app.fileTransferManager, app.signalingClient)
    }

    private val callViewModel by viewModels<CallViewModel> {
        CallViewModel.Factory(app.webRtcSessionManager)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        updateFlagSecure(app.secureStorage.isFlagSecureEnabled)

        val startupPermissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            startupPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = startupPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            startupPermissionLauncher.launch(missing.toTypedArray())
        }

        if (app.secureStorage.isBiometricLockEnabled) {
            BiometricAuthHelper.showBiometricPrompt(
                activity = this,
                onSuccess = { setupContent() },
                onError = { finish() }
            )
        } else {
            setupContent()
        }
    }

    fun updateFlagSecure(enabled: Boolean) {
        if (enabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun setupContent() {
        setContent {
            PrivateTwoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val isPaired = remember { app.secureStorage.isPaired() }
                    val startDestination = if (isPaired) "main" else "pairing"

                    val callState by callViewModel.callState.collectAsState()

                    LaunchedEffect(callState) {
                        if (callState == WebRtcCallState.INCOMING_CALL) {
                            navController.navigate("incoming_call")
                        }
                    }

                    val context = this@MainActivity
                    var pendingCallAction by remember { mutableStateOf<(() -> Unit)?>(null) }
                    val callPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestMultiplePermissions()
                    ) { permissions ->
                        val hasAudio = permissions[Manifest.permission.RECORD_AUDIO]
                            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                        if (hasAudio) {
                            pendingCallAction?.invoke()
                        }
                        pendingCallAction = null
                    }

                    fun runWithPermissions(needed: List<String>, onGranted: () -> Unit) {
                        val missing = needed.filter {
                            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isEmpty()) {
                            onGranted()
                        } else {
                            pendingCallAction = onGranted
                            callPermissionLauncher.launch(missing.toTypedArray())
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = startDestination
                    ) {
                        composable("pairing") {
                            PairingScreen(
                                viewModel = pairingViewModel,
                                onPairingCompleted = {
                                    navController.navigate("main") {
                                        popUpTo("pairing") { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable("main") {
                            MainScreen(
                                secureStorage = app.secureStorage,
                                signalingClient = app.signalingClient,
                                onOpenChat = {
                                    navController.navigate("chat")
                                },
                                onStartAudioCall = {
                                    runWithPermissions(listOf(Manifest.permission.RECORD_AUDIO)) {
                                        callViewModel.startOutgoingCall(isVideo = false)
                                        navController.navigate("call_audio")
                                    }
                                },
                                onStartVideoCall = {
                                    runWithPermissions(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
                                        callViewModel.startOutgoingCall(isVideo = true)
                                        navController.navigate("call_video")
                                    }
                                },
                                onOpenSettings = {
                                    navController.navigate("settings")
                                },
                                onUnpairAndPair = {
                                    pairingViewModel.unpair()
                                    navController.navigate("pairing") {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable("chat") {
                            ChatScreen(
                                viewModel = chatViewModel,
                                partnerDisplayName = app.secureStorage.getPartnerDisplayName(),
                                onNavigateBack = {
                                    navController.popBackStack()
                                },
                                onStartAudioCall = {
                                    runWithPermissions(listOf(Manifest.permission.RECORD_AUDIO)) {
                                        callViewModel.startOutgoingCall(isVideo = false)
                                        navController.navigate("call_audio")
                                    }
                                },
                                onStartVideoCall = {
                                    runWithPermissions(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
                                        callViewModel.startOutgoingCall(isVideo = true)
                                        navController.navigate("call_video")
                                    }
                                },
                                onOpenSettings = {
                                    navController.navigate("settings")
                                }
                            )
                        }

                        composable("call_audio") {
                            CallScreen(
                                viewModel = callViewModel,
                                webRtcSessionManager = app.webRtcSessionManager,
                                isVideoCall = false,
                                partnerDisplayName = app.secureStorage.getPartnerDisplayName(),
                                onCallEnded = {
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable("call_video") {
                            CallScreen(
                                viewModel = callViewModel,
                                webRtcSessionManager = app.webRtcSessionManager,
                                isVideoCall = true,
                                partnerDisplayName = app.secureStorage.getPartnerDisplayName(),
                                onCallEnded = {
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable("incoming_call") {
                            val isIncomingVideo by callViewModel.isIncomingCallVideo.collectAsState()
                            val partnerDisplayName = app.secureStorage.getPartnerDisplayName()
                            IncomingCallScreen(
                                isVideo = isIncomingVideo,
                                partnerDisplayName = partnerDisplayName,
                                onAccept = {
                                    if (isIncomingVideo) {
                                        runWithPermissions(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
                                            callViewModel.acceptIncomingCall(isVideo = true)
                                            navController.navigate("call_video") {
                                                popUpTo("incoming_call") { inclusive = true }
                                            }
                                        }
                                    } else {
                                        runWithPermissions(listOf(Manifest.permission.RECORD_AUDIO)) {
                                            callViewModel.acceptIncomingCall(isVideo = false)
                                            navController.navigate("call_audio") {
                                                popUpTo("incoming_call") { inclusive = true }
                                            }
                                        }
                                    }
                                },
                                onReject = {
                                    callViewModel.rejectIncomingCall()
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable("settings") {
                            PrivacySettingsScreen(
                                secureStorage = app.secureStorage,
                                onNavigateBack = { navController.popBackStack() },
                                onUnpaired = {
                                    pairingViewModel.unpair()
                                    navController.navigate("pairing") {
                                        popUpTo(0) { inclusive = true }
                                    }
                                },
                                onToggleFlagSecure = { enabled ->
                                    updateFlagSecure(enabled)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PrivateTwoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val darkColors = darkColorScheme(
        primary = Color(0xFF64B5F6),
        onPrimary = Color(0xFF003366),
        primaryContainer = Color(0xFF1976D2),
        onPrimaryContainer = Color.White,
        surface = Color(0xFF1E1E1E),
        background = Color(0xFF121212),
        onSurface = Color(0xFFE0E0E0),
        surfaceVariant = Color(0xFF2C2C2C),
        onSurfaceVariant = Color(0xFFB0B0B0)
    )

    val lightColors = lightColorScheme(
        primary = Color(0xFF1976D2),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE3F2FD),
        onPrimaryContainer = Color(0xFF0D47A1),
        surface = Color.White,
        background = Color(0xFFF8F9FA),
        onSurface = Color(0xFF212121),
        surfaceVariant = Color(0xFFF1F3F4),
        onSurfaceVariant = Color(0xFF5F6368)
    )

    MaterialTheme(
        colorScheme = if (darkTheme) darkColors else lightColors,
        content = content
    )
}
