package org.privatetwo.app.feature.pairing

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.privatetwo.app.core.security.SecureStorage
import org.privatetwo.app.core.signaling.SignalingConnectionState
import org.privatetwo.app.core.util.ProfileImageHelper
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    secureStorage: SecureStorage? = null,
    onPairingCompleted: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    var inputCode by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device Pairing") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            when (val state = uiState) {
                is PairingUiState.Unpaired -> {
                    UnpairedView(
                        inputCode = inputCode,
                        connectionState = connectionState,
                        serverUrl = viewModel.getSignalingUrl(),
                        secureStorage = secureStorage,
                        onUpdateServerUrl = { viewModel.updateSignalingUrl(it) },
                        onReconnect = { viewModel.reconnectSignaling() },
                        onInputCodeChange = { if (it.length <= 6) inputCode = it },
                        onGenerateClick = { viewModel.generatePairingCode() },
                        onEnterClick = { viewModel.enterPairingCode(inputCode) }
                    )
                }
                is PairingUiState.Connecting -> {
                    ConnectingView(
                        message = state.message,
                        onCancelClick = { viewModel.cancelPairing() }
                    )
                }
                is PairingUiState.CodeGenerated -> {
                    CodeGeneratedView(
                        code = state.code,
                        secondsRemaining = state.secondsRemaining,
                        onCancelClick = { viewModel.cancelPairing() }
                    )
                }
                is PairingUiState.VerifyingSas -> {
                    SasVerificationView(
                        sasCode = state.sasCode,
                        peerDeviceId = state.peerDeviceId,
                        onConfirm = { viewModel.confirmSasMatch() },
                        onReject = { viewModel.rejectSasMatch() }
                    )
                }
                is PairingUiState.PairedSuccessfully -> {
                    PairedSuccessfullyView(
                        onContinue = onPairingCompleted
                    )
                }
                is PairingUiState.Error -> {
                    ErrorView(
                        message = state.message,
                        onRetry = { viewModel.cancelPairing() }
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectingView(
    message: String,
    onCancelClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(56.dp),
            strokeWidth = 4.dp,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "Pairing Handshake",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedButton(
            onClick = onCancelClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancel")
        }
    }
}

@Composable
fun SignalingStatusCard(
    connectionState: SignalingConnectionState,
    serverUrl: String,
    onUpdateServerUrl: (String) -> Unit,
    onReconnect: () -> Unit = {}
) {
    var showDialog by remember { mutableStateOf(false) }
    var editUrl by remember { mutableStateOf(serverUrl) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                val (color, label) = when (connectionState) {
                    SignalingConnectionState.CONNECTED -> Color(0xFF4CAF50) to "Connected"
                    SignalingConnectionState.CONNECTING -> Color(0xFFFF9800) to "Connecting..."
                    SignalingConnectionState.DISCONNECTED -> Color(0xFFE53935) to "Disconnected"
                }
                Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
                Column {
                    Text(
                        text = "Signaling: $label",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = serverUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (connectionState == SignalingConnectionState.DISCONNECTED) {
                    IconButton(onClick = onReconnect) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Retry Connection",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                IconButton(onClick = {
                    editUrl = serverUrl
                    showDialog = true
                }) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Edit Signaling Server",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Signaling Server URL") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "For Android Emulator use 10.0.2.2:8088. For physical devices on the same Wi-Fi, use your PC's IP (e.g. 192.168.1.5:8088).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = editUrl,
                        onValueChange = { editUrl = it },
                        label = { Text("WebSocket URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SuggestionChip(
                            onClick = { editUrl = "wss://privatetwo-androidapp.onrender.com" },
                            label = { Text("🌐 Render Cloud (Default)") }
                        )
                        SuggestionChip(
                            onClick = { editUrl = "ws://192.168.1.5:8088" },
                            label = { Text("🏠 Same Wi-Fi (PC)") }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (editUrl.isNotBlank()) {
                        onUpdateServerUrl(editUrl.trim())
                    }
                    showDialog = false
                }) {
                    Text("Save & Reconnect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun UnpairedView(
    inputCode: String,
    connectionState: SignalingConnectionState,
    serverUrl: String,
    secureStorage: SecureStorage? = null,
    onUpdateServerUrl: (String) -> Unit,
    onReconnect: () -> Unit = {},
    onInputCodeChange: (String) -> Unit,
    onGenerateClick: () -> Unit,
    onEnterClick: () -> Unit
) {
    val context = LocalContext.current
    var myName by remember { mutableStateOf(secureStorage?.getMyDisplayName()) }
    var showNameEditDialog by remember { mutableStateOf(false) }
    var tempNameInput by remember(myName) { mutableStateOf(myName ?: "") }

    var profilePicPath by remember { mutableStateOf(secureStorage?.profilePicturePath) }
    var profilePicVersion by remember { mutableIntStateOf(0) }
    val profileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val savedPath = ProfileImageHelper.saveAndOptimizeAvatar(context, it)
            if (savedPath != null) {
                secureStorage?.profilePicturePath = savedPath
                profilePicPath = savedPath
                profilePicVersion++
                Toast.makeText(context, "Profile photo updated", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Could not load image. Please select another.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Time-based Personalized Greeting Banner (Always active, connected or not)
    val greeting = remember(myName) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val displayName = if (!myName.isNullOrBlank()) myName!!.trim() else "User"
        when (hour) {
            in 4..11 -> "Good Morning, $displayName ☀️"
            in 12..16 -> "Good Afternoon, $displayName 🌤️"
            else -> "Good Evening, $displayName 🌙"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Time-based Greeting Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = when {
                                greeting.contains("Morning") -> "☀️"
                                greeting.contains("Afternoon") -> "🌤️"
                                else -> "🌙"
                            },
                            fontSize = 22.sp
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = greeting,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Zero-trace private communication",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 2. User Profile Setup Card (Edit photo & name anytime)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Profile Avatar with Camera badge
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clickable { profileLauncher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        val bitmap = remember(profilePicPath, profilePicVersion) {
                            ProfileImageHelper.loadAvatarBitmap(profilePicPath)
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Profile Picture",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = "Upload Profile Photo",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }
                    }

                    // Camera badge overlay
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(20.dp)
                            .align(Alignment.BottomEnd)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = "Change Photo",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "MY PROFILE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (!myName.isNullOrBlank()) myName!! else "Tap to set name",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Set photo & name anytime • Visible to partner",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = {
                    tempNameInput = myName ?: ""
                    showNameEditDialog = true
                }) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit My Name",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        if (showNameEditDialog) {
            AlertDialog(
                onDismissRequest = { showNameEditDialog = false },
                title = { Text("Set Your Display Name") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Your partner will see this name on their screen.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = tempNameInput,
                            onValueChange = { tempNameInput = it },
                            label = { Text("Display Name") },
                            placeholder = { Text("e.g. Alex, Sam") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val trimmed = tempNameInput.trim()
                        if (trimmed.isNotBlank()) {
                            secureStorage?.setMyDisplayName(trimmed)
                            myName = trimmed
                            Toast.makeText(context, "Display name saved", Toast.LENGTH_SHORT).show()
                        }
                        showNameEditDialog = false
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNameEditDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        SignalingStatusCard(
            connectionState = connectionState,
            serverUrl = serverUrl,
            onUpdateServerUrl = onUpdateServerUrl,
            onReconnect = onReconnect
        )

        // 1-Tap Quick Network Switcher
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val isRender = serverUrl.contains("privatetwo-androidapp.onrender.com")
            FilterChip(
                selected = isRender,
                onClick = { onUpdateServerUrl("wss://privatetwo-androidapp.onrender.com") },
                label = { Text("🌐 Render Cloud", fontSize = 12.sp) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = !isRender,
                onClick = { onUpdateServerUrl("ws://192.168.1.5:8088") },
                label = { Text("🏠 Local Wi-Fi", fontSize = 12.sp) },
                modifier = Modifier.weight(1f)
            )
        }

        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = "Security",
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "Pair Exactly Two Devices",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "PrivateTwo enforces a strict 2-device limit. No third device can ever join or eavesdrop.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Device A: Start Pairing",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Button(
                    onClick = onGenerateClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Generate Pairing Code")
                }
            }
        }

        Text(
            text = "— OR —",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.outline
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Device B: Enter Partner's Code",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = inputCode,
                    onValueChange = onInputCodeChange,
                    label = { Text("6-Digit Code") },
                    placeholder = { Text("123456") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                FilledTonalButton(
                    onClick = onEnterClick,
                    enabled = inputCode.length == 6,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Pair With Device")
                }
            }
        }

        Spacer(modifier = Modifier.height(36.dp))
    }
}

@Composable
fun CodeGeneratedView(
    code: String,
    secondsRemaining: Int,
    onCancelClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = "Lock",
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "Pairing Code",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Text(
                text = code,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(vertical = 20.dp, horizontal = 32.dp)
            )
        }

        Text(
            text = "Enter this single-use code on Device B.\nExpires in $secondsRemaining seconds.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedButton(onClick = onCancelClick) {
            Text("Cancel Pairing")
        }
    }
}

@Composable
fun SasVerificationView(
    sasCode: String,
    peerDeviceId: String,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = "SAS Shield",
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Verify Security Code",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Compare the numbers below with your partner's screen. If they match exactly, cryptographic MITM protection is guaranteed.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Text(
                    text = sasCode,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp
                    ),
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }

            Text(
                text = "Partner Fingerprint: $peerDeviceId",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Confirm Match")
            }

            OutlinedButton(
                onClick = onReject,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Reject / Disconnect")
            }
        }
    }
}

@Composable
fun PairedSuccessfullyView(
    onContinue: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Success",
            modifier = Modifier.size(72.dp),
            tint = Color(0xFF2E7D32)
        )

        Text(
            text = "Device paired successfully",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "All communications with your partner are now strictly end-to-end encrypted.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open Chat")
        }
    }
}

@Composable
fun ErrorView(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = "Error",
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Text(
            text = "Pairing Error",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(onClick = onRetry) {
            Text("Try Again")
        }
    }
}
