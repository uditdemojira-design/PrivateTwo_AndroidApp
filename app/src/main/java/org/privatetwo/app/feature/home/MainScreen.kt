package org.privatetwo.app.feature.home

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import org.privatetwo.app.core.util.ProfileImageHelper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.privatetwo.app.core.security.SecureStorage
import org.privatetwo.app.core.signaling.SignalingClient
import org.privatetwo.app.core.signaling.SignalingConnectionState
import java.io.File
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import org.privatetwo.app.core.database.CallRecordEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    secureStorage: SecureStorage,
    signalingClient: SignalingClient,
    partnerDisplayName: String? = null,
    callRecords: List<CallRecordEntity> = emptyList(),
    onClearCallHistory: () -> Unit = {},
    onDeleteCallRecord: (String) -> Unit = {},
    onNameUpdated: (String) -> Unit = {},
    onOpenChat: () -> Unit,
    onStartAudioCall: () -> Unit,
    onStartVideoCall: () -> Unit,
    onOpenSettings: () -> Unit,
    onUnpairAndPair: () -> Unit
) {
    val context = LocalContext.current
    val connectionState by signalingClient.connectionState.collectAsState()
    val peerDeviceId = remember { secureStorage.getPairedPeerDeviceId() ?: "Unknown Partner" }

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var selectedCallForDetail by remember { mutableStateOf<CallRecordEntity?>(null) }
    var showStartCallBottomSheet by remember { mutableStateOf(false) }

    val missedCallCount = remember(callRecords) {
        callRecords.count { it.isIncoming && (it.callStatus == "MISSED" || it.callStatus == "DECLINED") }
    }

    var showUnpairDialog by remember { mutableStateOf(false) }

    // My Display Name and Profile Picture
    var myName by remember { mutableStateOf(secureStorage.getMyDisplayName()) }
    var showMyNameEditDialog by remember { mutableStateOf(false) }
    var tempMyNameInput by remember(myName) { mutableStateOf(myName ?: "") }

    var profilePicPath by remember { mutableStateOf(secureStorage.profilePicturePath) }
    var profilePicVersion by remember { mutableIntStateOf(0) }
    val profileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val savedPath = ProfileImageHelper.saveAndOptimizeAvatar(context, it)
            if (savedPath != null) {
                secureStorage.profilePicturePath = savedPath
                profilePicPath = savedPath
                profilePicVersion++
                onNameUpdated(myName ?: "")
                Toast.makeText(context, "Profile photo updated", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Could not load image. Please select another.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Partner Name & Avatar
    val storedPartnerName = secureStorage.getPartnerDisplayName()
    val effectivePartnerName = partnerDisplayName ?: storedPartnerName ?: "Private Partner"
    var currentPartnerName by remember(effectivePartnerName) { mutableStateOf(effectivePartnerName) }
    var showPartnerNameEditDialog by remember { mutableStateOf(false) }
    var tempPartnerNameInput by remember { mutableStateOf("") }

    var partnerPicPath by remember { mutableStateOf(secureStorage.partnerProfilePicturePath ?: secureStorage.profilePicturePath) }
    val partnerProfileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val savedPath = ProfileImageHelper.saveAndOptimizeAvatar(context, it)
            if (savedPath != null) {
                secureStorage.partnerProfilePicturePath = savedPath
                partnerPicPath = savedPath
                profilePicVersion++
                Toast.makeText(context, "Partner photo updated", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Could not load image. Please select another.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Chat Lock status
    var isChatLockActive by remember { mutableStateOf(secureStorage.isChatLockEnabled) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Security,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = "PrivateTwo",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Zero-Trace 1-to-1 Channel",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // WhatsApp-Style Tab Row (Chats & Calls)
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = Color(0xFF25D366),
                    indicator = { tabPositions ->
                        if (selectedTabIndex < tabPositions.size) {
                            Box(
                                Modifier
                                    .tabIndicatorOffset(tabPositions[selectedTabIndex])
                                    .height(3.dp)
                                    .background(
                                        Color(0xFF25D366),
                                        RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
                                    )
                            )
                        }
                    }
                ) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Chat,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    "Chats",
                                    fontWeight = if (selectedTabIndex == 0) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        },
                        selectedContentColor = Color(0xFF25D366),
                        unselectedContentColor = Color(0xFF8696A0)
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.Phone,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    "Calls",
                                    fontWeight = if (selectedTabIndex == 1) FontWeight.Bold else FontWeight.Medium
                                )
                                if (missedCallCount > 0) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color(0xFF25D366),
                                        modifier = Modifier.padding(start = 2.dp)
                                    ) {
                                        Text(
                                            text = "$missedCallCount",
                                            color = Color(0xFF111B21),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        },
                        selectedContentColor = Color(0xFF25D366),
                        unselectedContentColor = Color(0xFF8696A0)
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectedTabIndex == 1) {
                FloatingActionButton(
                    onClick = { showStartCallBottomSheet = true },
                    containerColor = Color(0xFF25D366),
                    contentColor = Color(0xFF111B21)
                ) {
                    Icon(Icons.Default.Phone, contentDescription = "Start New Call")
                }
            }
        }
    ) { paddingValues ->
        if (selectedTabIndex == 0) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Time-based Personalized Greeting Banner
            val greeting = remember(myName) {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val displayName = if (!myName.isNullOrBlank()) myName!!.trim() else "User"
                when (hour) {
                    in 4..11 -> "Good Morning, $displayName ☀️"
                    in 12..16 -> "Good Afternoon, $displayName 🌤️"
                    else -> "Good Evening, $displayName 🌙"
                }
            }

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
                            text = "End-to-end encrypted session active",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Live Status Card
            ConnectionStatusCard(connectionState = connectionState)

            // User Profile Header (My Name & Avatar)
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
                    // Profile Picture with Tap to Change
                    Box(
                        modifier = Modifier
                            .size(64.dp)
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
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                        }

                        // Camera badge icon overlay
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(22.dp)
                                .align(Alignment.BottomEnd)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.CameraAlt,
                                    contentDescription = "Change Photo",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(13.dp)
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
                            text = myName ?: "Tap to set name",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Tap photo to change avatar",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = {
                        tempMyNameInput = myName ?: ""
                        showMyNameEditDialog = true
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

            // Partner Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "PAIRED PARTNER",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Partner Photo with tap to change
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clickable { partnerProfileLauncher.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                val partnerBmp = remember(partnerPicPath, profilePicVersion) {
                                    ProfileImageHelper.loadAvatarBitmap(partnerPicPath)
                                }
                                if (partnerBmp != null) {
                                    Image(
                                        bitmap = partnerBmp.asImageBitmap(),
                                        contentDescription = "Partner Photo",
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
                                            contentDescription = "Partner Photo",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }
                            }

                            // Camera badge icon overlay
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
                                        contentDescription = "Change Partner Photo",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Partner Display Name",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = currentPartnerName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Tap photo to set contact avatar",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(onClick = {
                            tempPartnerNameInput = currentPartnerName
                            showPartnerNameEditDialog = true
                        }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit Partner Name",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = "Partner Fingerprint ID",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = peerDeviceId,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "End-to-End Encrypted (ECDH P-256 + AES-256-GCM)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Chat Lock Option Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Fingerprint,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                text = "Lock Chat (Fingerprint / PIN)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Prompt phone password or fingerprint when opening chat",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = isChatLockActive,
                        onCheckedChange = {
                            isChatLockActive = it
                            secureStorage.isChatLockEnabled = it
                        }
                    )
                }
            }

            // Main Primary Action: Open Chat
            Card(
                onClick = onOpenChat,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        ) {
                            val partnerBmp = remember(partnerPicPath, profilePicVersion) {
                                ProfileImageHelper.loadAvatarBitmap(partnerPicPath)
                            }
                            if (partnerBmp != null) {
                                Image(
                                    bitmap = partnerBmp.asImageBitmap(),
                                    contentDescription = "Partner Avatar",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Chat,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Open 1-to-1 Chat",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                if (isChatLockActive) {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = "Locked",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (isChatLockActive) "Protected with phone password / fingerprint" else "Messages, audio songs, photos & files",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Open Chat",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Quick Call Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Phone,
                    title = "Voice Call",
                    subtitle = "Encrypted Audio",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = onStartAudioCall
                )
                QuickActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Videocam,
                    title = "Video Call",
                    subtitle = "Peer-to-Peer HD",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = onStartVideoCall
                )
            }

            // Device Management & Pairing Initial Phase
            Text(
                text = "DEVICE MANAGEMENT",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )

            OutlinedCard(
                onClick = { showUnpairDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.LinkOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "Pair New / Different Device",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Switch device or return to initial pairing phase",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            OutlinedCard(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "Privacy & Security Controls",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "FLAG_SECURE, Biometric lock & Key IDs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Essential bottom padding for small screens to scroll freely past system navigation bar
            Spacer(modifier = Modifier.height(72.dp))
        }
    } else {
        CallHistoryContent(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            callRecords = callRecords,
            partnerName = currentPartnerName,
            partnerPicPath = partnerPicPath,
            profilePicVersion = profilePicVersion,
            onStartAudioCall = onStartAudioCall,
            onStartVideoCall = onStartVideoCall,
            onClearHistory = { showClearHistoryDialog = true },
            onCallClick = { call -> selectedCallForDetail = call }
        )
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            icon = {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("Clear Call Log?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Do you want to clear your entire audio and video call history? This action cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearHistoryDialog = false
                        onClearCallHistory()
                        Toast.makeText(context, "Call history cleared", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    selectedCallForDetail?.let { call ->
        val isVideo = call.callType.equals("VIDEO", ignoreCase = true)
        val isMissed = call.isIncoming && (call.callStatus == "MISSED" || call.callStatus == "DECLINED")

        AlertDialog(
            onDismissRequest = { selectedCallForDetail = null },
            icon = {
                Surface(
                    shape = CircleShape,
                    color = if (isMissed) Color(0xFFE53935).copy(alpha = 0.15f) else Color(0xFF25D366).copy(alpha = 0.15f),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.Phone,
                            contentDescription = null,
                            tint = if (isMissed) Color(0xFFE53935) else Color(0xFF25D366),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            },
            title = {
                Text(
                    text = if (isVideo) "Video Call Info" else "Voice Call Info",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Contact info row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(38.dp)
                        ) {
                            val bmp = remember(partnerPicPath, profilePicVersion) {
                                ProfileImageHelper.loadAvatarBitmap(partnerPicPath)
                            }
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                        Column {
                            Text(
                                text = currentPartnerName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (call.isIncoming) "Incoming Call" else "Outgoing Call",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Date and Time
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Time:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = formatCallFullDateTime(call.timestamp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Duration
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Duration:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val durationText = if (call.durationSeconds > 0) {
                            formatCallDuration(call.durationSeconds)
                        } else {
                            "0s (Not Connected)"
                        }
                        Text(
                            text = durationText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Status:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val statusDisplay = when {
                            call.callStatus == "MISSED" -> "Missed Call"
                            call.callStatus == "DECLINED" -> "Declined"
                            call.callStatus == "UNANSWERED" -> "Unanswered"
                            else -> "Completed"
                        }
                        Text(
                            text = statusDisplay,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isMissed) Color(0xFFE53935) else Color(0xFF25D366)
                        )
                    }

                    // Security notice
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color(0xFF25D366),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "End-to-End Encrypted via WebRTC DTLS-SRTP",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedCallForDetail = null
                        if (isVideo) onStartVideoCall() else onStartAudioCall()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
                ) {
                    Icon(
                        imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.Phone,
                        contentDescription = null,
                        tint = Color(0xFF111B21),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Call Again", color = Color(0xFF111B21), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            val callId = call.id
                            selectedCallForDetail = null
                            onDeleteCallRecord(callId)
                            Toast.makeText(context, "Call entry deleted", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Delete Log", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { selectedCallForDetail = null }) {
                        Text("Close")
                    }
                }
            }
        )
    }

    if (showStartCallBottomSheet) {
        AlertDialog(
            onDismissRequest = { showStartCallBottomSheet = false },
            icon = {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF25D366).copy(alpha = 0.15f),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Phone,
                            contentDescription = null,
                            tint = Color(0xFF25D366),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            },
            title = {
                Text(
                    text = "Call $currentPartnerName",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = "Start a 1-to-1 secure encrypted peer-to-peer call.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            showStartCallBottomSheet = false
                            onStartAudioCall()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Phone,
                            contentDescription = null,
                            tint = Color(0xFF111B21),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Voice", color = Color(0xFF111B21), fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            showStartCallBottomSheet = false
                            onStartVideoCall()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Videocam,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Video", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showStartCallBottomSheet = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showUnpairDialog) {
        AlertDialog(
            onDismissRequest = { showUnpairDialog = false },
            title = { Text("Connect New Device?") },
                text = {
                    Text(
                        "PrivateTwo strictly enforces a 1-to-1 device policy. To pair with another device or re-enter the initial pairing phase, the current pairing session will be reset."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showUnpairDialog = false
                            onUnpairAndPair()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Reset & Pair Device")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUnpairDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showMyNameEditDialog) {
            AlertDialog(
                onDismissRequest = { showMyNameEditDialog = false },
                icon = {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = {
                    Text(
                        text = "Edit Your Name",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "This is the name your partner will see on their screen:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = tempMyNameInput,
                            onValueChange = { tempMyNameInput = it },
                            label = { Text("Your Display Name") },
                            placeholder = { Text("e.g. Rahul, Priya, Alex") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val trimmed = tempMyNameInput.trim()
                        if (trimmed.isNotBlank()) {
                            secureStorage.setMyDisplayName(trimmed)
                            myName = trimmed
                            onNameUpdated(trimmed)
                        }
                        showMyNameEditDialog = false
                    }) {
                        Text("Save Name")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showMyNameEditDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showPartnerNameEditDialog) {
            AlertDialog(
                onDismissRequest = { showPartnerNameEditDialog = false },
                title = {
                    Text(
                        text = "Edit Partner Name",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Set a custom display name for your contact:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = tempPartnerNameInput,
                            onValueChange = { tempPartnerNameInput = it },
                            label = { Text("Partner Name") },
                            placeholder = { Text("e.g. Udit, Partner") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val trimmed = tempPartnerNameInput.trim()
                        if (trimmed.isNotBlank()) {
                            secureStorage.setPartnerDisplayName(trimmed)
                            currentPartnerName = trimmed
                            Toast.makeText(context, "Partner name updated", Toast.LENGTH_SHORT).show()
                        }
                        showPartnerNameEditDialog = false
                    }) {
                        Text("Save Name")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPartnerNameEditDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun ConnectionStatusCard(connectionState: SignalingConnectionState) {
    val (dotColor, statusTitle, statusSubtitle) = when (connectionState) {
        SignalingConnectionState.CONNECTED -> Triple(
            Color(0xFF2E7D32),
            "Partner Online & Connected",
            "Real-time encrypted signaling channel active"
        )
        SignalingConnectionState.CONNECTING -> Triple(
            Color(0xFFF57F17),
            "Connecting to Signaling Server...",
            "Establishing WebSocket channel"
        )
        SignalingConnectionState.DISCONNECTED -> Triple(
            Color.Gray,
            "Signaling Offline",
            "Waiting for server connection"
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = dotColor.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = dotColor,
                modifier = Modifier.size(12.dp)
            ) {}
            Column {
                Text(
                    text = statusTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = statusSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun QuickActionCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    containerColor: Color,
    iconTint: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = iconTint.copy(alpha = 0.15f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun CallHistoryContent(
    modifier: Modifier = Modifier,
    callRecords: List<CallRecordEntity>,
    partnerName: String,
    partnerPicPath: String?,
    profilePicVersion: Int,
    onStartAudioCall: () -> Unit,
    onStartVideoCall: () -> Unit,
    onClearHistory: () -> Unit,
    onCallClick: (CallRecordEntity) -> Unit
) {
    if (callRecords.isEmpty()) {
        Column(
            modifier = modifier
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(48.dp))
            Surface(
                shape = CircleShape,
                color = Color(0xFF25D366).copy(alpha = 0.12f),
                modifier = Modifier.size(90.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PhoneCallback,
                        contentDescription = null,
                        tint = Color(0xFF25D366),
                        modifier = Modifier.size(46.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "No call history yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Voice and video calls made directly to your partner will appear here with zero logs on the server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(28.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = onStartAudioCall,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.Phone,
                        contentDescription = null,
                        tint = Color(0xFF111B21),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Voice Call",
                        color = Color(0xFF111B21),
                        fontWeight = FontWeight.Bold
                    )
                }
                OutlinedButton(
                    onClick = onStartVideoCall,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.Videocam,
                        contentDescription = null,
                        tint = Color(0xFF25D366),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Video Call",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // Quick Call Header Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(46.dp)
                            ) {
                                val bmp = remember(partnerPicPath, profilePicVersion) {
                                    ProfileImageHelper.loadAvatarBitmap(partnerPicPath)
                                }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Person,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                }
                            }
                            Column {
                                Text(
                                    text = partnerName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Direct Encrypted Line",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF25D366)
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalIconButton(
                                onClick = onStartAudioCall,
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color(0xFF25D366).copy(alpha = 0.15f),
                                    contentColor = Color(0xFF25D366)
                                )
                            ) {
                                Icon(Icons.Default.Phone, contentDescription = "Voice Call")
                            }
                            FilledTonalIconButton(
                                onClick = onStartVideoCall,
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color(0xFF25D366).copy(alpha = 0.15f),
                                    contentColor = Color(0xFF25D366)
                                )
                            ) {
                                Icon(Icons.Default.Videocam, contentDescription = "Video Call")
                            }
                        }
                    }
                }
            }

            // Section Label "Recent" with Clear History action
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Recent",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onClearHistory) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Clear Call Log",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Clear Log",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Call Records Items
            items(callRecords, key = { it.id }) { call ->
                CallHistoryItem(
                    call = call,
                    partnerName = partnerName,
                    partnerPicPath = partnerPicPath,
                    profilePicVersion = profilePicVersion,
                    onClick = { onCallClick(call) },
                    onCallBack = {
                        if (call.callType.equals("VIDEO", ignoreCase = true)) {
                            onStartVideoCall()
                        } else {
                            onStartAudioCall()
                        }
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }

            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

@Composable
fun CallHistoryItem(
    call: CallRecordEntity,
    partnerName: String,
    partnerPicPath: String?,
    profilePicVersion: Int,
    onClick: () -> Unit,
    onCallBack: () -> Unit
) {
    val isMissed = call.isIncoming && (call.callStatus == "MISSED" || call.callStatus == "DECLINED")
    val isVideo = call.callType.equals("VIDEO", ignoreCase = true)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f)
        ) {
            // Avatar
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(50.dp)
            ) {
                val bmp = remember(partnerPicPath, profilePicVersion) {
                    ProfileImageHelper.loadAvatarBitmap(partnerPicPath)
                }
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                // Name (Red if missed call, WhatsApp style!)
                Text(
                    text = partnerName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isMissed) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Direction Icon + Details
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // WhatsApp style call direction arrow
                    when {
                        isMissed -> {
                            Icon(
                                Icons.Default.CallMissed,
                                contentDescription = "Missed Call",
                                tint = Color(0xFFE53935),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        call.isIncoming -> {
                            Icon(
                                Icons.Default.CallReceived,
                                contentDescription = "Incoming Call",
                                tint = Color(0xFF25D366),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        else -> {
                            Icon(
                                Icons.Default.CallMade,
                                contentDescription = "Outgoing Call",
                                tint = Color(0xFF25D366),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    val dateStr = remember(call.timestamp) { formatCallTimestamp(call.timestamp) }
                    val durationStr = remember(call.durationSeconds) { formatCallDuration(call.durationSeconds) }
                    val statusText = when {
                        isMissed -> "Missed"
                        call.callStatus == "UNANSWERED" -> "Unanswered"
                        durationStr.isNotBlank() -> durationStr
                        else -> "Connected"
                    }

                    Text(
                        text = "$dateStr • $statusText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // WhatsApp-style quick callback action icon on right
        IconButton(
            onClick = onCallBack,
            modifier = Modifier.size(44.dp)
        ) {
            Icon(
                imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.Phone,
                contentDescription = if (isVideo) "Video Call" else "Voice Call",
                tint = Color(0xFF25D366),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

fun formatCallTimestamp(timestamp: Long): String {
    val now = Calendar.getInstance()
    val callCalendar = Calendar.getInstance().apply { timeInMillis = timestamp }
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    val formattedTime = timeFormat.format(Date(timestamp))

    return when {
        now.get(Calendar.YEAR) == callCalendar.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == callCalendar.get(Calendar.DAY_OF_YEAR) -> {
            "Today, $formattedTime"
        }
        now.get(Calendar.YEAR) == callCalendar.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) - callCalendar.get(Calendar.DAY_OF_YEAR) == 1 -> {
            "Yesterday, $formattedTime"
        }
        else -> {
            val dateFormat = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
            dateFormat.format(Date(timestamp))
        }
    }
}

fun formatCallDuration(seconds: Long): String {
    if (seconds <= 0) return ""
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}

fun formatCallFullDateTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("EEEE, d MMMM yyyy 'at' h:mm:ss a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

