package org.privatetwo.app

import android.Manifest
import android.content.Intent
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
import org.privatetwo.app.core.signaling.SignalingKeepAliveService
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
import kotlinx.coroutines.delay

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.privatetwo.app.core.signaling.SignalingConnectionState
import org.privatetwo.app.core.updater.AppUpdateManager
import org.privatetwo.app.core.updater.UpdateInfo
import android.widget.Toast
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private val app by lazy { application as PrivateTwoApp }
    private val pendingNavRoute = mutableStateOf<String?>(null)

    private val startupPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    private var pendingCallAction: (() -> Unit)? = null
    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            pendingCallAction?.invoke()
        }
        pendingCallAction = null
    }

    private val pairingViewModel: PairingViewModel by viewModels {
        PairingViewModel.Factory(app.pairingManager)
    }

    private val chatViewModel: ChatViewModel by viewModels {
        ChatViewModel.Factory(app.chatRepository, app.fileTransferManager, app.signalingClient)
    }

    private val callViewModel: CallViewModel by viewModels {
        CallViewModel.Factory(app.webRtcSessionManager, app.database.callRecordDao())
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

        intent?.getStringExtra("navigate_to")?.let {
            pendingNavRoute.value = it
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

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) {
                AppUpdateManager.launchInstallerIfReady(this)
            }
        }
        if (app.secureStorage.isPaired()) {
            SignalingKeepAliveService.start(this)
        }
        // Auto-reconnect signaling immediately if disconnected when app returns to foreground
        if (app.signalingClient.connectionState.value == SignalingConnectionState.DISCONNECTED) {
            app.signalingClient.resetReconnectBackoff()
            app.signalingClient.connect()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("navigate_to")?.let { target ->
            pendingNavRoute.value = target
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

                    // Automatic In-App OTA Update Checker
                    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
                    var isDownloadingUpdate by remember { mutableStateOf(false) }
                    var updateDownloadProgress by remember { mutableFloatStateOf(0f) }
                    val coroutineScope = rememberCoroutineScope()

                    LaunchedEffect(Unit) {
                        try {
                            val update = AppUpdateManager.checkForUpdate(BuildConfig.VERSION_CODE)
                            if (update != null) {
                                availableUpdate = update
                            }
                        } catch (_: Exception) {}
                    }

                    if (availableUpdate != null) {
                        val update = availableUpdate!!
                        AlertDialog(
                            onDismissRequest = {
                                if (!isDownloadingUpdate) availableUpdate = null
                            },
                            containerColor = Color(0xFF202C33),
                            titleContentColor = Color.White,
                            textContentColor = Color(0xFFD1D7DB),
                            icon = {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Update",
                                    tint = Color(0xFF25D366),
                                    modifier = Modifier.size(36.dp)
                                )
                            },
                            title = {
                                Text(
                                    text = "New Update Available! (${update.versionName})",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                            },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        text = update.releaseNotes,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFFD1D7DB)
                                    )
                                    if (isDownloadingUpdate) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Downloading update: ${(updateDownloadProgress * 100).toInt()}%",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color(0xFF25D366),
                                            fontWeight = FontWeight.Bold
                                        )
                                        LinearProgressIndicator(
                                            progress = { updateDownloadProgress },
                                            modifier = Modifier.fillMaxWidth().height(6.dp),
                                            color = Color(0xFF25D366),
                                            trackColor = Color(0x44FFFFFF)
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        if (updateDownloadProgress >= 1f) {
                                            AppUpdateManager.launchInstallerIfReady(this@MainActivity)
                                        } else if (!isDownloadingUpdate) {
                                            isDownloadingUpdate = true
                                            coroutineScope.launch {
                                                AppUpdateManager.downloadAndInstallApk(
                                                    context = this@MainActivity,
                                                    downloadUrl = update.downloadUrl,
                                                    onProgress = { progress ->
                                                        updateDownloadProgress = progress
                                                    },
                                                    onError = { errorMsg ->
                                                        isDownloadingUpdate = false
                                                        Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                                                    }
                                                )
                                            }
                                        }
                                    },
                                    enabled = !isDownloadingUpdate || updateDownloadProgress >= 1f,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
                                ) {
                                    Text(
                                        text = when {
                                            updateDownloadProgress >= 1f -> "Install Update"
                                            isDownloadingUpdate -> "Downloading..."
                                            else -> "Update Now"
                                        },
                                        color = Color(0xFF111B21),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            dismissButton = {
                                if (!isDownloadingUpdate || updateDownloadProgress >= 1f) {
                                    TextButton(onClick = { availableUpdate = null }) {
                                        Text("Later", color = Color(0xFF8696A0))
                                    }
                                }
                            }
                        )
                    }

                    // One-time Name Setup Prompt at the root level (shown once on initial entry if name doesn't exist)
                    var showNamePromptDialog by remember {
                        mutableStateOf(app.secureStorage.getMyDisplayName().isNullOrBlank() && !app.secureStorage.hasPromptedName())
                    }

                    if (showNamePromptDialog) {
                        var nameInput by remember { mutableStateOf("") }
                        AlertDialog(
                            onDismissRequest = {
                                app.secureStorage.setHasPromptedName(true)
                                showNamePromptDialog = false
                            },
                            icon = {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp)
                                )
                            },
                            title = {
                                Text(
                                    text = "Enter Your Name",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleLarge
                                )
                            },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        text = "Enter a name to display inside chat & calls. Your partner will see this name on their screen.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    OutlinedTextField(
                                        value = nameInput,
                                        onValueChange = { nameInput = it },
                                        label = { Text("Display Name") },
                                        placeholder = { Text("e.g. Rahul, Priya") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        val trimmed = nameInput.trim()
                                        if (trimmed.isNotBlank()) {
                                            app.secureStorage.setMyDisplayName(trimmed)
                                            chatViewModel.sendNameExchange()
                                        }
                                        app.secureStorage.setHasPromptedName(true)
                                        showNamePromptDialog = false
                                    }
                                ) {
                                    Text("Save Name")
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = {
                                        app.secureStorage.setHasPromptedName(true)
                                        showNamePromptDialog = false
                                    }
                                ) {
                                    Text("Skip")
                                }
                            }
                        )
                    }

                    val callState by callViewModel.callState.collectAsState()
                    val livePartnerName by chatViewModel.partnerDisplayName.collectAsState()

                    val navRoute by pendingNavRoute
                    LaunchedEffect(navRoute) {
                        val target = navRoute ?: return@LaunchedEffect
                        delay(120) // Ensure NavController and NavHost have completed initial setup
                        if (isPaired) {
                            if (target == "chat" && app.secureStorage.isChatLockEnabled) {
                                BiometricAuthHelper.showBiometricPrompt(
                                    activity = this@MainActivity,
                                    title = "Unlock Private Chat",
                                    subtitle = "Verify fingerprint or phone password to open chat",
                                    onSuccess = {
                                        navController.navigate(target) {
                                            launchSingleTop = true
                                        }
                                        pendingNavRoute.value = null
                                    },
                                    onError = {
                                        pendingNavRoute.value = null
                                    }
                                )
                            } else {
                                navController.navigate(target) {
                                    launchSingleTop = true
                                }
                                pendingNavRoute.value = null
                            }
                        } else {
                            pendingNavRoute.value = null
                        }
                    }

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
                                secureStorage = app.secureStorage,
                                onPairingCompleted = {
                                    SignalingKeepAliveService.start(this@MainActivity)
                                    navController.navigate("main") {
                                        popUpTo("pairing") { inclusive = true }
                                    }
                                    navController.navigate("chat")
                                }
                            )
                        }

                        composable("main") {
                            val callRecords by callViewModel.callRecords.collectAsState()
                            MainScreen(
                                secureStorage = app.secureStorage,
                                signalingClient = app.signalingClient,
                                partnerDisplayName = livePartnerName,
                                callRecords = callRecords,
                                onClearCallHistory = { callViewModel.clearCallHistory() },
                                onDeleteCallRecord = { id -> callViewModel.deleteCallRecord(id) },
                                onNameUpdated = {
                                    chatViewModel.sendNameExchange()
                                },
                                onOpenChat = {
                                    if (app.secureStorage.isChatLockEnabled) {
                                        BiometricAuthHelper.showBiometricPrompt(
                                            activity = this@MainActivity,
                                            title = "Unlock Private Chat",
                                            subtitle = "Verify fingerprint or phone password to open chat",
                                            onSuccess = {
                                                navController.navigate("chat")
                                            },
                                            onError = {}
                                        )
                                    } else {
                                        navController.navigate("chat")
                                    }
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
                                    SignalingKeepAliveService.stop(this@MainActivity)
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
                                partnerDisplayName = livePartnerName,
                                secureStorage = app.secureStorage,
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
                                partnerDisplayName = livePartnerName,
                                isAntiPeepTiltEnabled = app.secureStorage.isAntiPeepTiltEnabled,
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
                                partnerDisplayName = livePartnerName,
                                isAntiPeepTiltEnabled = app.secureStorage.isAntiPeepTiltEnabled,
                                onCallEnded = {
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable("incoming_call") {
                            val isIncomingVideo by callViewModel.isIncomingCallVideo.collectAsState()
                            IncomingCallScreen(
                                isVideo = isIncomingVideo,
                                partnerDisplayName = livePartnerName,
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
                                    SignalingKeepAliveService.stop(this@MainActivity)
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
