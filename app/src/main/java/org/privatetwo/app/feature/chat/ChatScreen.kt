package org.privatetwo.app.feature.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import android.media.MediaScannerConnection
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.privatetwo.app.feature.chat.cards.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Shield
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.privatetwo.app.core.database.DeliveryStatus
import org.privatetwo.app.core.signaling.SignalingConnectionState
import org.privatetwo.app.core.notification.NotificationHelper
import org.privatetwo.app.core.security.SecureStorage
import org.privatetwo.app.core.util.ProfileImageHelper
import org.privatetwo.app.feature.privacy.AntiPeepShieldOverlay
import org.privatetwo.app.feature.privacy.PrivacyControlsBottomSheet
import org.privatetwo.app.feature.privacy.rememberAntiPeepTiltState
import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import org.privatetwo.app.core.util.FileUtils
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

private val WhatsAppBackground = Color(0xFF0B141A)
private val WhatsAppOutgoingBubble = Color(0xFF005C4B)
private val WhatsAppIncomingBubble = Color(0xFF202C33)
private val WhatsAppText = Color(0xFFE9EDEF)
private val WhatsAppTime = Color(0xFF8696A0)
private val WhatsAppCheckCyan = Color(0xFF53BDEB)
private val WhatsAppTypingGreen = Color(0xFF25D366)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    partnerDisplayName: String? = null,
    secureStorage: SecureStorage? = null,
    onNavigateBack: () -> Unit,
    onStartAudioCall: () -> Unit,
    onStartVideoCall: () -> Unit,
    onOpenSettings: () -> Unit
) {
    BackHandler(onBack = onNavigateBack)

    val context = LocalContext.current

    DisposableEffect(Unit) {
        NotificationHelper.isChatVisible.set(true)
        NotificationHelper.clearNotifications(context)
        onDispose {
            NotificationHelper.isChatVisible.set(false)
        }
    }

    var isManualBlackoutActive by remember { mutableStateOf(false) }
    var isAntiPeepTiltEnabled by remember { mutableStateOf(secureStorage?.isAntiPeepTiltEnabled ?: false) }
    var isLouverFilterActive by remember { mutableStateOf(secureStorage?.isAntiPeepLouverEnabled ?: false) }
    var isReadingCurtainActive by remember { mutableStateOf(secureStorage?.isAntiPeepShadeEnabled ?: false) }
    var isStealthMaskActive by remember { mutableStateOf(secureStorage?.isStealthMessagesEnabled ?: false) }
    var louverOpacity by remember { mutableStateOf(secureStorage?.privacyFilterOpacity ?: 0.50f) }
    var showPrivacySheet by remember { mutableStateOf(false) }

    // Anti-Peep Tilt Shield Locking: once extreme tilt is triggered, it latches
    // and requires biometric/device unlock to dismiss. Moving the phone upright does not auto-dismiss!
    var isTiltLocked by remember { mutableStateOf(false) }
    val isTiltRaw by rememberAntiPeepTiltState(isAntiPeepTiltEnabled)
    LaunchedEffect(isTiltRaw) {
        if (isTiltRaw) {
            isTiltLocked = true
        }
    }

    val messages by viewModel.messages.collectAsState()
    val signalingState by viewModel.signalingState.collectAsState()
    val isPeerTyping by viewModel.isPeerTyping.collectAsState()
    val isPeerOnline by viewModel.isPeerOnline.collectAsState()
    val peerLastSeenTimestamp by viewModel.peerLastSeenTimestamp.collectAsState()
    val audioSyncEvent by viewModel.audioSyncEvent.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var messagePendingDeletion by remember { mutableStateOf<ChatMessage?>(null) }
    var showCardComposerSheet by remember { mutableStateOf(false) }
    var fullscreenCardData by remember { mutableStateOf<PartnerCardData?>(null) }

    var pendingPhotoFile by remember { mutableStateOf<File?>(null) }
    var isViewOnceSelected by remember { mutableStateOf(false) }
    var activeViewOnceMessage by remember { mutableStateOf<ChatMessage?>(null) }

    val listState = rememberLazyListState()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val inputStream = context.contentResolver.openInputStream(it)
            val tempFile = File(context.cacheDir, "send_photo_${System.currentTimeMillis()}.jpg")
            FileOutputStream(tempFile).use { out -> inputStream?.copyTo(out) }
            pendingPhotoFile = tempFile
            isViewOnceSelected = false
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val originalName = FileUtils.getFileNameFromUri(context, it)
            val inputStream = context.contentResolver.openInputStream(it)
            val tempFile = File(context.cacheDir, "send_${System.currentTimeMillis()}_$originalName")
            FileOutputStream(tempFile).use { out -> inputStream?.copyTo(out) }
            viewModel.sendFile(tempFile)
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val originalName = FileUtils.getFileNameFromUri(context, it) ?: "audio_${System.currentTimeMillis()}.mp3"
            val inputStream = context.contentResolver.openInputStream(it)
            val tempFile = File(context.cacheDir, originalName)
            FileOutputStream(tempFile).use { out -> inputStream?.copyTo(out) }
            viewModel.sendAudio(tempFile)
        }
    }

    var fullScreenPhotoPath by remember { mutableStateOf<String?>(null) }
    var showFullscreenPartnerAvatar by remember { mutableStateOf(false) }
    var openedAvatarFromProfileDialog by remember { mutableStateOf(false) }

    // Partner Info (Synced via E2EE exchange)
    val livePartnerAvatarPath by viewModel.partnerAvatarPath.collectAsState()
    val livePartnerDisplayName by viewModel.partnerDisplayName.collectAsState()

    val effectivePartnerPhoto = livePartnerAvatarPath ?: secureStorage?.partnerProfilePicturePath
    var partnerPhotoVersion by remember { mutableIntStateOf(0) }
    val partnerAvatarBitmap = remember(effectivePartnerPhoto, livePartnerAvatarPath, partnerPhotoVersion) {
        ProfileImageHelper.loadAvatarBitmap(effectivePartnerPhoto)
    }

    var showContactProfileDialog by remember { mutableStateOf(false) }
    val effectivePartnerName = livePartnerDisplayName ?: partnerDisplayName ?: secureStorage?.getPartnerDisplayName() ?: "Private Partner"

    LaunchedEffect(messages.size, isPeerTyping) {
        val totalCount = messages.size + (if (isPeerTyping) 1 else 0)
        if (totalCount > 0) {
            listState.animateScrollToItem(totalCount - 1)
        }
        viewModel.markAllIncomingAsRead()
    }

    AntiPeepShieldOverlay(
        isTiltBlackoutActive = isTiltLocked,
        isManualBlackoutActive = isManualBlackoutActive,
        isLouverFilterActive = isLouverFilterActive,
        louverOpacity = louverOpacity,
        isReadingCurtainActive = isReadingCurtainActive,
        onDismissTilt = { isTiltLocked = false },
        onDismissManualBlackout = { isManualBlackoutActive = false },
        onDismissReadingCurtain = {
            isReadingCurtainActive = false
            secureStorage?.isAntiPeepShadeEnabled = false
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to Home",
                                tint = Color.White
                            )
                        }
                    },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF1E2B33),
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        if (partnerAvatarBitmap != null || !effectivePartnerPhoto.isNullOrBlank()) {
                                            openedAvatarFromProfileDialog = false
                                            showFullscreenPartnerAvatar = true
                                        } else {
                                            showContactProfileDialog = true
                                        }
                                    }
                            ) {
                                if (partnerAvatarBitmap != null) {
                                    Image(
                                        bitmap = partnerAvatarBitmap.asImageBitmap(),
                                        contentDescription = "Partner Avatar",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = "Partner",
                                            tint = Color(0xFF8696A0),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { showContactProfileDialog = true }
                                    .padding(vertical = 2.dp, horizontal = 2.dp)
                            ) {
                                Text(
                                    text = effectivePartnerName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                if (isPeerTyping) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "typing...",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = WhatsAppTypingGreen
                                        )
                                    }
                                } else {
                                    val (dotColor, statusText) = when {
                                        signalingState == SignalingConnectionState.CONNECTING -> Pair(Color(0xFFF57F17), "Connecting…")
                                        signalingState == SignalingConnectionState.DISCONNECTED -> Pair(Color.Gray, "Waiting for network…")
                                        isPeerOnline -> Pair(WhatsAppTypingGreen, "Online")
                                        else -> Pair(Color(0xFF8696A0), formatLastSeen(peerLastSeenTimestamp, isCompact = true))
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = dotColor,
                                            modifier = Modifier.size(6.dp)
                                        ) {}
                                        Text(
                                            text = statusText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isPeerOnline) WhatsAppTypingGreen else WhatsAppTime,
                                            fontWeight = if (isPeerOnline) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    },
                    actions = {
                        // 1-Tap Black Screen Shield Toggle
                        IconButton(
                            onClick = {
                                val next = !isLouverFilterActive
                                isLouverFilterActive = next
                                secureStorage?.isAntiPeepLouverEnabled = next
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = "Anti-Peep Privacy Shield",
                                tint = if (isLouverFilterActive || isReadingCurtainActive || isStealthMaskActive) WhatsAppTypingGreen else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = onStartAudioCall,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Phone, contentDescription = "Audio Call", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = onStartVideoCall,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Videocam, contentDescription = "Video Call", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = { showMenu = !showMenu },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (isLouverFilterActive) "Samsung Privacy Display: ON (${(louverOpacity * 100).toInt()}%)" else "Samsung Privacy Display: OFF") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Shield,
                                        contentDescription = null,
                                        tint = if (isLouverFilterActive) WhatsAppTypingGreen else Color.Unspecified
                                    )
                                },
                                onClick = {
                                    val newState = !isLouverFilterActive
                                    isLouverFilterActive = newState
                                    secureStorage?.isAntiPeepLouverEnabled = newState
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (isReadingCurtainActive) "Reading Curtain: ON" else "Reading Curtain: OFF") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Window,
                                        contentDescription = null,
                                        tint = if (isReadingCurtainActive) WhatsAppTypingGreen else Color.Unspecified
                                    )
                                },
                                onClick = {
                                    val newState = !isReadingCurtainActive
                                    isReadingCurtainActive = newState
                                    secureStorage?.isAntiPeepShadeEnabled = newState
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (isStealthMaskActive) "Tap-to-Reveal (Stealth): ON" else "Tap-to-Reveal: OFF") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.VisibilityOff,
                                        contentDescription = null,
                                        tint = if (isStealthMaskActive) WhatsAppTypingGreen else Color.Unspecified
                                    )
                                },
                                onClick = {
                                    val newState = !isStealthMaskActive
                                    isStealthMaskActive = newState
                                    secureStorage?.isStealthMessagesEnabled = newState
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (isAntiPeepTiltEnabled) "Anti-Peep Tilt Alert: ON" else "Anti-Peep Tilt: OFF") },
                                leadingIcon = {
                                    Icon(
                                        if (isAntiPeepTiltEnabled) Icons.Default.Security else Icons.Default.Visibility,
                                        contentDescription = null,
                                        tint = if (isAntiPeepTiltEnabled) WhatsAppTypingGreen else Color.Unspecified
                                    )
                                },
                                onClick = {
                                    val newState = !isAntiPeepTiltEnabled
                                    isAntiPeepTiltEnabled = newState
                                    secureStorage?.isAntiPeepTiltEnabled = newState
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Anti-Peep Controls Sheet...") },
                                leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showPrivacySheet = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Instant Blackout Screen") },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    isManualBlackoutActive = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Privacy & Security") },
                                leadingIcon = { Icon(Icons.Default.Security, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    onOpenSettings()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear Conversation") },
                                leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showClearDialog = true
                                }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF1F2C34)
                    )
                )
            },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1F2C34))
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // WhatsApp-Style Input Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Left Input Capsule
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = Color(0xFF2A3942),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            // Emoji Toggle Button (😊 <-> ⌨️)
                            IconButton(
                                onClick = { showEmojiPicker = !showEmojiPicker },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    imageVector = if (showEmojiPicker) Icons.Default.Keyboard else Icons.Default.Mood,
                                    contentDescription = "Toggle Emojis",
                                    tint = if (showEmojiPicker) WhatsAppTypingGreen else WhatsAppTime,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            TextField(
                                value = inputText,
                                onValueChange = {
                                    inputText = it
                                    viewModel.onInputTextChanged(it)
                                },
                                placeholder = {
                                    Text(
                                        "Message",
                                        color = WhatsAppTime,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                maxLines = 5,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    cursorColor = WhatsAppTypingGreen,
                                    focusedTextColor = WhatsAppText,
                                    unfocusedTextColor = WhatsAppText
                                )
                            )

                            // Partner Card Icon (Love, Sorry, Thank You, Wishes)
                            IconButton(
                                onClick = { showCardComposerSheet = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.CardGiftcard,
                                    contentDescription = "Send Partner Card",
                                    tint = Color(0xFFFF5252),
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            // Music / Song Icon
                            IconButton(
                                onClick = { audioPickerLauncher.launch("audio/*") },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = "Send Song",
                                    tint = WhatsAppTime,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            // Attachment Clip Icon
                            IconButton(
                                onClick = { filePickerLauncher.launch("*/*") },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.AttachFile,
                                    contentDescription = "Attach File",
                                    tint = WhatsAppTime,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            // Camera / Photo Icon
                            IconButton(
                                onClick = { photoPickerLauncher.launch("image/*") },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.CameraAlt,
                                    contentDescription = "Send Photo",
                                    tint = WhatsAppTime,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    // Floating Circular Send Button
                    Surface(
                        shape = CircleShape,
                        color = WhatsAppTypingGreen,
                        shadowElevation = 3.dp,
                        modifier = Modifier.size(46.dp)
                    ) {
                        IconButton(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    viewModel.sendMessage(inputText)
                                    inputText = ""
                                }
                            },
                            enabled = inputText.isNotBlank()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = Color(0xFF111B21),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // Interactive WhatsApp Emoji Picker with Love Priority
                if (showEmojiPicker) {
                    EmojiPickerView(
                        onEmojiSelected = { emoji ->
                            inputText += emoji
                            viewModel.onInputTextChanged(inputText)
                        },
                        onBackspace = {
                            if (inputText.isNotEmpty()) {
                                inputText = if (inputText.length >= 2 && Character.isSurrogatePair(inputText[inputText.length - 2], inputText[inputText.length - 1])) {
                                    inputText.dropLast(2)
                                } else {
                                    inputText.dropLast(1)
                                }
                                viewModel.onInputTextChanged(inputText)
                            }
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WhatsAppBackground)
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Quick Privacy Strip at the top if ANY anti-peep feature is active
                if (isLouverFilterActive || isReadingCurtainActive || isStealthMaskActive) {
                    Surface(
                        color = Color(0xFF131D24),
                        shadowElevation = 4.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = WhatsAppTypingGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (isLouverFilterActive) "Samsung Privacy Display" else if (isReadingCurtainActive) "Curtain" else "Tap-Reveal",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                if (isLouverFilterActive) {
                                    listOf(0.30f to "Mild", 0.50f to "Metro", 0.70f to "Deep").forEach { (level, label) ->
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (louverOpacity == level) WhatsAppTypingGreen else Color(0xFF24343D),
                                            modifier = Modifier
                                                .clickable {
                                                    louverOpacity = level
                                                    secureStorage?.privacyFilterOpacity = level
                                                }
                                                .padding(horizontal = 2.dp)
                                        ) {
                                            Text(
                                                text = label,
                                                color = if (louverOpacity == level) Color(0xFF111B21) else Color.LightGray,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(
                                    onClick = { showPrivacySheet = true },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = "Settings",
                                        tint = Color.LightGray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        isLouverFilterActive = false
                                        isReadingCurtainActive = false
                                        isStealthMaskActive = false
                                        secureStorage?.isAntiPeepLouverEnabled = false
                                        secureStorage?.isAntiPeepShadeEnabled = false
                                        secureStorage?.isStealthMessagesEnabled = false
                                    },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close Privacy",
                                        tint = Color.LightGray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Chat Messages Container
                Box(modifier = Modifier.weight(1f)) {
                    if (messages.isEmpty() && !isPeerTyping) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFF182229),
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Security,
                                        contentDescription = "Security",
                                        tint = WhatsAppTypingGreen,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Text(
                                        text = "End-to-End Encrypted",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = "Messages and calls are secured with ECDH & AES-256-GCM. No one outside of this chat can read them.",
                                        color = WhatsAppTime,
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp),
                            contentPadding = PaddingValues(vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(messages, key = { it.id }) { message ->
                                MessageBubble(
                                    message = message,
                                    isStealthMaskActive = isStealthMaskActive,
                                    isPrivacyShieldActive = isLouverFilterActive,
                                    onPhotoClick = { fullScreenPhotoPath = it },
                                    onViewOnceClick = { activeViewOnceMessage = it },
                                    onCardClick = { fullscreenCardData = it },
                                    onRetry = { viewModel.retryMessage(message.id) },
                                    onDeleteClick = { messagePendingDeletion = message },
                                    audioSyncEvent = audioSyncEvent,
                                    onSendAudioSync = { action, msgId, fileName, pos ->
                                        viewModel.sendAudioSync(action, msgId, fileName, pos)
                                    }
                                )
                            }

                            if (isPeerTyping) {
                                item(key = "typing_bubble") {
                                    TypingIndicatorBubble()
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text("Clear Conversation?") },
                text = { Text("This will permanently delete all messages and media from your local device.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.clearConversation()
                            showClearDialog = false
                        }
                    ) {
                        Text("Clear", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // WhatsApp-style Delete for Everyone / Delete for Me dialog
        if (messagePendingDeletion != null) {
            val targetMsg = messagePendingDeletion!!
            val isOutgoing = !targetMsg.isIncoming
            AlertDialog(
                onDismissRequest = { messagePendingDeletion = null },
                containerColor = Color(0xFF202C33),
                shape = RoundedCornerShape(16.dp),
                title = {
                    Text(
                        text = "Delete message?",
                        color = WhatsAppText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Text(
                        text = if (isOutgoing) "You can delete this message for everyone or delete it for yourself only." else "Delete this message for yourself?",
                        color = WhatsAppTime,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isOutgoing) {
                            TextButton(
                                onClick = {
                                    viewModel.deleteMessageForEveryone(targetMsg.id)
                                    messagePendingDeletion = null
                                }
                            ) {
                                Text("Delete for everyone", color = WhatsAppTypingGreen, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                        }
                        TextButton(
                            onClick = {
                                viewModel.deleteMessageForMe(targetMsg.id)
                                messagePendingDeletion = null
                            }
                        ) {
                            Text("Delete for me", color = WhatsAppTypingGreen, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                        TextButton(
                            onClick = { messagePendingDeletion = null }
                        ) {
                            Text("Cancel", color = WhatsAppTime, fontSize = 15.sp)
                        }
                    }
                },
                dismissButton = null
            )
        }

        // Card Composer BottomSheet
        if (showCardComposerSheet) {
            PartnerCardBottomSheet(
                onDismiss = { showCardComposerSheet = false },
                onSendCard = { card ->
                    viewModel.sendCard(card.toJson())
                }
            )
        }

        // Fullscreen Interactive Card Celebration Dialog
        if (fullscreenCardData != null) {
            PartnerCardFullscreenDialog(
                card = fullscreenCardData!!,
                onDismiss = { fullscreenCardData = null }
            )
        }

        if (showPrivacySheet) {
            PrivacyControlsBottomSheet(
                isLouverActive = isLouverFilterActive,
                isCurtainActive = isReadingCurtainActive,
                isStealthMaskActive = isStealthMaskActive,
                currentOpacity = louverOpacity,
                onOpacityChanged = {
                    louverOpacity = it
                    secureStorage?.privacyFilterOpacity = it
                },
                onToggleLouver = {
                    isLouverFilterActive = it
                    secureStorage?.isAntiPeepLouverEnabled = it
                },
                onToggleCurtain = {
                    isReadingCurtainActive = it
                    secureStorage?.isAntiPeepShadeEnabled = it
                },
                onToggleStealthMask = {
                    isStealthMaskActive = it
                    secureStorage?.isStealthMessagesEnabled = it
                },
                onTriggerBlackout = {
                    isManualBlackoutActive = true
                },
                onDismiss = { showPrivacySheet = false }
            )
        }

        if (pendingPhotoFile != null) {
            PhotoSendPreviewDialog(
                photoFile = pendingPhotoFile!!,
                isViewOnce = isViewOnceSelected,
                onToggleViewOnce = { isViewOnceSelected = !isViewOnceSelected },
                onDismiss = {
                    pendingPhotoFile?.delete()
                    pendingPhotoFile = null
                },
                onSend = {
                    viewModel.sendPhoto(pendingPhotoFile!!, isViewOnce = isViewOnceSelected)
                    pendingPhotoFile = null
                }
            )
        }

        if (activeViewOnceMessage != null && activeViewOnceMessage?.mediaLocalPath != null) {
            FullscreenPhotoDialog(
                photoPath = activeViewOnceMessage!!.mediaLocalPath!!,
                isViewOnce = true,
                onDismiss = {
                    val msg = activeViewOnceMessage
                    activeViewOnceMessage = null
                    if (msg != null && msg.isIncoming) {
                        viewModel.markViewOnceOpened(msg.id, msg.mediaLocalPath)
                    }
                }
            )
        }

        if (fullScreenPhotoPath != null) {
            FullscreenPhotoDialog(
                photoPath = fullScreenPhotoPath!!,
                isViewOnce = false,
                onDismiss = { fullScreenPhotoPath = null }
            )
        }

        if (showFullscreenPartnerAvatar) {
            FullscreenPhotoDialog(
                photoPath = effectivePartnerPhoto,
                bitmapInput = partnerAvatarBitmap,
                title = effectivePartnerName,
                isViewOnce = false,
                onDismiss = {
                    showFullscreenPartnerAvatar = false
                    if (openedAvatarFromProfileDialog) {
                        openedAvatarFromProfileDialog = false
                        showContactProfileDialog = true
                    }
                }
            )
        }

        if (showContactProfileDialog) {
            ModalBottomSheet(
                onDismissRequest = { showContactProfileDialog = false },
                containerColor = Color(0xFF1F2C34),
                contentColor = Color.White
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Contact info",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        IconButton(
                            onClick = { showContactProfileDialog = false },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color(0xFF8696A0)
                            )
                        }
                    }

                    // PARTNER PROFILE CARD (WhatsApp style)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF111B21))
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Partner Avatar (Tapping expands to full-screen view like WhatsApp)
                            Box(
                                modifier = Modifier
                                    .size(110.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        if (partnerAvatarBitmap != null || !effectivePartnerPhoto.isNullOrBlank()) {
                                            openedAvatarFromProfileDialog = true
                                            showContactProfileDialog = false
                                            showFullscreenPartnerAvatar = true
                                        } else {
                                            Toast.makeText(context, "No profile photo", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFF2A3942),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    if (partnerAvatarBitmap != null) {
                                        Image(
                                            bitmap = partnerAvatarBitmap.asImageBitmap(),
                                            contentDescription = "Partner Photo",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Person,
                                                contentDescription = "Partner Photo",
                                                tint = Color(0xFF8696A0),
                                                modifier = Modifier.size(60.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Partner Name (Read-only, viewer cannot edit partner's name)
                            Text(
                                text = effectivePartnerName,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )

                            // Status Pill (WhatsApp Online / Offline / Last Seen)
                            val (badgeBg, badgeText, badgeColor) = when {
                                signalingState == SignalingConnectionState.CONNECTING -> Triple(Color(0xFF332B10), "Connecting…", Color(0xFFF57F17))
                                signalingState == SignalingConnectionState.DISCONNECTED -> Triple(Color(0xFF262D31), "Waiting for network…", Color(0xFF8696A0))
                                isPeerOnline -> Triple(Color(0xFF103629), "Online", WhatsAppTypingGreen)
                                else -> Triple(Color(0xFF262D31), formatLastSeen(peerLastSeenTimestamp), Color(0xFF8696A0))
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = badgeBg
                            ) {
                                Text(
                                    text = badgeText,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColor,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }

                            // Encryption & Fingerprint Info Box
                            val peerId = secureStorage?.getPairedPeerDeviceId()
                            if (!peerId.isNullOrBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFF1A2329),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Lock,
                                                contentDescription = null,
                                                tint = WhatsAppTypingGreen,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Text(
                                                text = "End-to-End Encrypted (ECDH P-256)",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = WhatsAppTypingGreen,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Text(
                                            text = "Fingerprint: $peerId",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF8696A0),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            // Quick Action Shortcuts: Audio, Video, Privacy Shield
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                // Audio Call
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            showContactProfileDialog = false
                                            onStartAudioCall()
                                        }
                                        .padding(8.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color(0xFF202C33),
                                        modifier = Modifier.size(46.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Phone,
                                                contentDescription = "Audio Call",
                                                tint = WhatsAppTypingGreen,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Audio", style = MaterialTheme.typography.labelSmall, color = Color.White)
                                }

                                // Video Call
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            showContactProfileDialog = false
                                            onStartVideoCall()
                                        }
                                        .padding(8.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color(0xFF202C33),
                                        modifier = Modifier.size(46.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Videocam,
                                                contentDescription = "Video Call",
                                                tint = WhatsAppTypingGreen,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Video", style = MaterialTheme.typography.labelSmall, color = Color.White)
                                }

                                // Privacy Shield Settings
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            showContactProfileDialog = false
                                            showPrivacySheet = true
                                        }
                                        .padding(8.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color(0xFF202C33),
                                        modifier = Modifier.size(46.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Shield,
                                                contentDescription = "Privacy Shield",
                                                tint = WhatsAppTypingGreen,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Shield", style = MaterialTheme.typography.labelSmall, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

/**
 * WhatsApp-style animated bouncing typing bubble.
 */
@Composable
fun TypingIndicatorBubble() {
    val infiniteTransition = rememberInfiniteTransition()

    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 0, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 4.dp,
                bottomEnd = 16.dp
            ),
            color = WhatsAppIncomingBubble,
            shadowElevation = 1.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = WhatsAppTypingGreen.copy(alpha = dot1Alpha),
                    modifier = Modifier.size(7.dp)
                ) {}
                Surface(
                    shape = CircleShape,
                    color = WhatsAppTypingGreen.copy(alpha = dot2Alpha),
                    modifier = Modifier.size(7.dp)
                ) {}
                Surface(
                    shape = CircleShape,
                    color = WhatsAppTypingGreen.copy(alpha = dot3Alpha),
                    modifier = Modifier.size(7.dp)
                ) {}
            }
        }
    }
}

/**
 * Checks if a text message is composed solely of 1-2 love emojis (rendered large like WhatsApp).
 */
fun isSingleOrDoubleEmoji(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    val count = trimmed.codePointCount(0, trimmed.length)
    return count in 1..2 && EmojiData.loveEmojis.any { trimmed.contains(it) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    isStealthMaskActive: Boolean = false,
    isPrivacyShieldActive: Boolean = false,
    onPhotoClick: ((String) -> Unit)? = null,
    onViewOnceClick: ((ChatMessage) -> Unit)? = null,
    onCardClick: ((PartnerCardData) -> Unit)? = null,
    onRetry: () -> Unit,
    onDeleteClick: () -> Unit,
    audioSyncEvent: AudioSyncEvent? = null,
    onSendAudioSync: ((action: String, messageId: String?, fileName: String?, positionMs: Int) -> Unit)? = null
) {
    val isOutgoing = !message.isIncoming
    val alignment = if (isOutgoing) Alignment.End else Alignment.Start
    val context = LocalContext.current

    var isTemporarilyRevealed by remember { mutableStateOf(false) }
    LaunchedEffect(isTemporarilyRevealed) {
        if (isTemporarilyRevealed) {
            kotlinx.coroutines.delay(4500)
            isTemporarilyRevealed = false
        }
    }
    val isHidden = isStealthMaskActive && !isTemporarilyRevealed

    val isLargeEmojiOnly = message.messageType == "TEXT" && isSingleOrDoubleEmoji(message.text)

    val bubbleColor = if (isOutgoing) WhatsAppOutgoingBubble else WhatsAppIncomingBubble
    val textColor = WhatsAppText
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val formattedTime = remember(message.timestamp) { timeFormatter.format(Date(message.timestamp)) }

    var showMessageMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (isLargeEmojiOnly) {
            if (isHidden) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = bubbleColor,
                    modifier = Modifier
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .clickable { isTemporarilyRevealed = true }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = WhatsAppTypingGreen, modifier = Modifier.size(16.dp))
                        Text("🔒 Emoji • Tap to View", color = WhatsAppTime, style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic))
                    }
                }
            } else {
                // Standalone large emoji rendering like WhatsApp
                Column(
                    horizontalAlignment = alignment,
                    modifier = Modifier
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { showMessageMenu = true }
                        )
                ) {
                    Text(
                        text = message.text,
                        fontSize = 44.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = formattedTime,
                            color = WhatsAppTime,
                            style = MaterialTheme.typography.labelSmall
                        )
                        if (isOutgoing) {
                            StatusTick(message.status)
                        }
                    }
                }
            }
        } else {
            // Standard WhatsApp Message Bubble
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isOutgoing) 16.dp else 4.dp,
                    bottomEnd = if (isOutgoing) 4.dp else 16.dp
                ),
                color = bubbleColor,
                shadowElevation = 1.dp,
                modifier = Modifier
                    .widthIn(min = 60.dp, max = 300.dp)
                    .combinedClickable(
                        onClick = {
                            if (isHidden) isTemporarilyRevealed = true
                        },
                        onLongClick = { showMessageMenu = true }
                    )
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    if (isHidden) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .clickable { isTemporarilyRevealed = true }
                                .padding(vertical = 4.dp, horizontal = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = WhatsAppTypingGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (message.messageType == "PHOTO") "📷 Photo • Tap to View" else "🔒 Message • Tap to View",
                                color = WhatsAppTime,
                                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic)
                            )
                        }
                    } else {
                        val isPhoto = (message.messageType == "PHOTO" ||
                            (message.mediaLocalPath != null && (
                                message.mediaLocalPath.endsWith(".jpg", ignoreCase = true) ||
                                message.mediaLocalPath.endsWith(".jpeg", ignoreCase = true) ||
                                message.mediaLocalPath.endsWith(".png", ignoreCase = true) ||
                                message.mediaLocalPath.endsWith(".webp", ignoreCase = true)
                            ))) && message.mediaLocalPath != null

                        if (message.messageType == "VIEW_ONCE_PHOTO" || message.messageType == "VIEW_ONCE_OPENED") {
                            val isViewOnceOpened = message.messageType == "VIEW_ONCE_OPENED" || (message.isIncoming && message.status == DeliveryStatus.READ && message.mediaLocalPath == null)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0x33000000),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (!isViewOnceOpened && message.mediaLocalPath != null) {
                                                onViewOnceClick?.invoke(message)
                                            } else {
                                                Toast.makeText(context, "This view-once photo has expired", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onLongClick = { onDeleteClick() }
                                    )
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    ViewOnceIcon(isOpened = isViewOnceOpened)
                                    Column {
                                        Text(
                                            text = if (isViewOnceOpened) "Opened" else "Photo",
                                            color = if (isViewOnceOpened) WhatsAppTime else WhatsAppText,
                                            fontWeight = if (isViewOnceOpened) FontWeight.Normal else FontWeight.Bold,
                                            fontStyle = if (isViewOnceOpened) FontStyle.Italic else FontStyle.Normal,
                                            fontSize = 15.sp
                                        )
                                        Text(
                                            text = if (isViewOnceOpened) "Photo expired" else if (isOutgoing) "View once photo" else "1-time view • Tap to see",
                                            color = WhatsAppTime,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        } else if (isPhoto) {
                            val bitmap = remember(message.mediaLocalPath) {
                                try {
                                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeFile(message.mediaLocalPath, options)
                                    var sample = 1
                                    while (options.outWidth / sample > 1200 || options.outHeight / sample > 1200) {
                                        sample *= 2
                                    }
                                    BitmapFactory.decodeFile(message.mediaLocalPath, BitmapFactory.Options().apply { inSampleSize = sample })
                                } catch (e: Throwable) {
                                    null
                                }
                            }
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 280.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .combinedClickable(
                                            onClick = {
                                                onPhotoClick?.invoke(message.mediaLocalPath!!)
                                            },
                                            onLongClick = { onDeleteClick() }
                                        )
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        } else if (message.messageType == "CARD") {
                            val cardData = remember(message.text) { PartnerCardData.fromJson(message.text) }
                            if (cardData != null) {
                                PartnerCardBubble(
                                    card = cardData,
                                    onClick = { onCardClick?.invoke(cardData) },
                                    onLongClick = { onDeleteClick() }
                                )
                            }
                        } else if (message.messageType == "AUDIO" || (
                            message.mediaLocalPath != null && (
                                message.mediaLocalPath.endsWith(".mp3", ignoreCase = true) ||
                                message.mediaLocalPath.endsWith(".m4a", ignoreCase = true) ||
                                message.mediaLocalPath.endsWith(".wav", ignoreCase = true) ||
                                message.mediaLocalPath.endsWith(".ogg", ignoreCase = true) ||
                                message.mediaLocalPath.endsWith(".aac", ignoreCase = true) ||
                                (message.mediaFileName ?: "").run {
                                    endsWith(".mp3", ignoreCase = true) ||
                                    endsWith(".m4a", ignoreCase = true) ||
                                    endsWith(".wav", ignoreCase = true) ||
                                    endsWith(".ogg", ignoreCase = true) ||
                                    endsWith(".aac", ignoreCase = true)
                                }
                            )
                        )) {
                            AudioPlayerBubble(
                                filePath = message.mediaLocalPath,
                                fileName = message.mediaFileName ?: "Audio Song",
                                fileSize = message.mediaFileSize,
                                isOutgoing = isOutgoing,
                                messageId = message.id,
                                audioSyncEvent = audioSyncEvent,
                                onSendSync = onSendAudioSync,
                                onLongClick = { onDeleteClick() }
                            )
                        } else if (message.messageType == "FILE" || (message.mediaLocalPath != null && !isPhoto)) {
                            val fileNameLower = (message.mediaFileName ?: message.mediaLocalPath ?: "").lowercase()
                            val isVideo = fileNameLower.endsWith(".mp4") || fileNameLower.endsWith(".mkv") || fileNameLower.endsWith(".webm") || fileNameLower.endsWith(".3gp") || fileNameLower.endsWith(".avi") || fileNameLower.endsWith(".mov")
                            val isAudioFile = fileNameLower.endsWith(".mp3") || fileNameLower.endsWith(".m4a") || fileNameLower.endsWith(".wav") || fileNameLower.endsWith(".ogg") || fileNameLower.endsWith(".aac")
                            val isPdf = fileNameLower.endsWith(".pdf")
                            val fileIcon = when {
                                isVideo -> Icons.Default.PlayCircle
                                isAudioFile -> Icons.Default.MusicNote
                                isPdf -> Icons.Default.PictureAsPdf
                                else -> Icons.AutoMirrored.Filled.InsertDriveFile
                            }
                            val fileSubtitle = when {
                                isVideo -> "${(message.mediaFileSize / 1024).coerceAtLeast(1)} KB • Video • Tap to Play"
                                isAudioFile -> "${(message.mediaFileSize / 1024).coerceAtLeast(1)} KB • Audio • Tap to Play"
                                isPdf -> "${(message.mediaFileSize / 1024).coerceAtLeast(1)} KB • PDF • Tap to Open"
                                else -> "${(message.mediaFileSize / 1024).coerceAtLeast(1)} KB • Tap to open"
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0x22000000),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            FileUtils.openFile(context, message.mediaLocalPath, message.mediaFileName)
                                        },
                                        onLongClick = { onDeleteClick() }
                                    )
                                    .padding(bottom = 6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.padding(8.dp)
                                ) {
                                    Icon(
                                        imageVector = fileIcon,
                                        contentDescription = "Open File",
                                        tint = WhatsAppTypingGreen,
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clickable {
                                                FileUtils.openFile(context, message.mediaLocalPath, message.mediaFileName)
                                            }
                                    )
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                FileUtils.openFile(context, message.mediaLocalPath, message.mediaFileName)
                                            }
                                    ) {
                                        Text(
                                            text = message.mediaFileName ?: "File",
                                            color = WhatsAppText,
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = fileSubtitle,
                                            color = WhatsAppTime,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }

                                    // Direct Download / Save Button
                                    IconButton(
                                        onClick = {
                                            if (message.mediaLocalPath != null) {
                                                try {
                                                    val srcFile = File(message.mediaLocalPath)
                                                    if (srcFile.exists()) {
                                                        val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                                        if (!destDir.exists()) destDir.mkdirs()
                                                        var destName = message.mediaFileName ?: srcFile.name
                                                        if (!destName.contains(".") && srcFile.name.contains(".")) {
                                                            val ext = srcFile.extension
                                                            if (ext.isNotBlank() && !ext.equals("bin", true)) {
                                                                destName += ".$ext"
                                                            }
                                                        }
                                                        val destFile = File(destDir, destName)
                                                        srcFile.copyTo(destFile, overwrite = true)
                                                        MediaScannerConnection.scanFile(
                                                            context,
                                                            arrayOf(destFile.absolutePath),
                                                            null,
                                                            null
                                                        )
                                                        Toast.makeText(context, "Saved to Downloads: ${destFile.name}", Toast.LENGTH_LONG).show()
                                                    } else {
                                                        Toast.makeText(context, "File not available locally", Toast.LENGTH_SHORT).show()
                                                    }
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = "Download File",
                                            tint = WhatsAppTypingGreen,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (message.messageType == "TEXT") {
                            Text(
                                text = message.text,
                                color = textColor,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = formattedTime,
                            color = WhatsAppTime,
                            style = MaterialTheme.typography.labelSmall
                        )

                        if (isOutgoing) {
                            StatusTick(message.status, onRetry = onRetry)
                        }
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showMessageMenu,
            onDismissRequest = { showMessageMenu = false },
            modifier = Modifier.background(Color(0xFF202C33))
        ) {
            DropdownMenuItem(
                text = { Text("Delete message", color = Color(0xFFEF5350)) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF5350)) },
                onClick = {
                    showMessageMenu = false
                    onDeleteClick()
                }
            )
        }
    }
}

@Composable
fun StatusTick(status: DeliveryStatus, onRetry: (() -> Unit)? = null) {
    when (status) {
        DeliveryStatus.SENDING -> {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = "Sending",
                tint = WhatsAppTime,
                modifier = Modifier.size(13.dp)
            )
        }
        DeliveryStatus.SENT -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Sent",
                tint = WhatsAppTime,
                modifier = Modifier.size(14.dp)
            )
        }
        DeliveryStatus.DELIVERED -> {
            // WhatsApp Grey Double Checkmark
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Delivered",
                tint = WhatsAppTime,
                modifier = Modifier.size(15.dp)
            )
        }
        DeliveryStatus.READ -> {
            // WhatsApp Cyan / Blue Double Checkmark
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Read",
                tint = WhatsAppCheckCyan,
                modifier = Modifier.size(15.dp)
            )
        }
        DeliveryStatus.FAILED -> {
            if (onRetry != null) {
                IconButton(
                    onClick = onRetry,
                    modifier = Modifier.size(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Failed - Tap to retry",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "Failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
fun FullscreenPhotoDialog(
    photoPath: String? = null,
    bitmapInput: Bitmap? = null,
    title: String? = null,
    isViewOnce: Boolean = false,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val bitmap = remember(photoPath, bitmapInput) {
        bitmapInput ?: if (!photoPath.isNullOrBlank()) {
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(photoPath, options)
                var sample = 1
                while (options.outWidth / sample > 2048 || options.outHeight / sample > 2048) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(photoPath, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (e: Throwable) {
                null
            }
        } else null
    }

    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Full photo",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                if (scale > 1f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            }
                        },
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    text = "Cannot load image preview",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Top Bar with Close/Back & Save to Gallery Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 16.dp, start = 16.dp, end = 16.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }

                if (!title.isNullOrBlank()) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                if (isViewOnce) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Black.copy(alpha = 0.6f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            ViewOnceIcon(isOpened = false, modifier = Modifier.size(18.dp), tint = WhatsAppTypingGreen)
                            Text("View once photo", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            try {
                                val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                                if (!destDir.exists()) destDir.mkdirs()
                                val destFile = File(destDir, "PrivateTwo_${System.currentTimeMillis()}.jpg")
                                if (photoPath != null && File(photoPath).exists()) {
                                    File(photoPath).copyTo(destFile, overwrite = true)
                                } else if (bitmap != null) {
                                    FileOutputStream(destFile).use { out ->
                                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                                    }
                                }
                                MediaScannerConnection.scanFile(
                                    context,
                                    arrayOf(destFile.absolutePath),
                                    arrayOf("image/jpeg"),
                                    null
                                )
                                Toast.makeText(context, "Saved to Gallery / Pictures!", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WhatsAppTypingGreen)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF111B21), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save to Gallery", color = Color(0xFF111B21), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AudioPlayerBubble(
    filePath: String?,
    fileName: String,
    fileSize: Long,
    isOutgoing: Boolean,
    messageId: String,
    audioSyncEvent: AudioSyncEvent? = null,
    onSendSync: ((action: String, messageId: String?, fileName: String?, positionMs: Int) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var currentProgress by remember { mutableFloatStateOf(0f) }
    var durationMs by remember { mutableIntStateOf(0) }
    var currentPosMs by remember { mutableIntStateOf(0) }
    var isSyncActive by remember { mutableStateOf(false) }
    val mediaPlayer = remember { android.media.MediaPlayer() }

    DisposableEffect(filePath) {
        onDispose {
            try {
                if (mediaPlayer.isPlaying) mediaPlayer.stop()
                mediaPlayer.release()
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            try {
                if (mediaPlayer.isPlaying) {
                    currentPosMs = mediaPlayer.currentPosition
                    if (durationMs > 0) {
                        currentProgress = (currentPosMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    }
                }
            } catch (_: Exception) {}
            kotlinx.coroutines.delay(250)
        }
    }

    // Synchronize playback when partner plays/pauses this song
    LaunchedEffect(audioSyncEvent) {
        val event = audioSyncEvent ?: return@LaunchedEffect
        val isTarget = (event.messageId?.isNotBlank() == true && event.messageId == messageId) ||
                       (event.fileName?.isNotBlank() == true && (
                           event.fileName.equals(fileName, ignoreCase = true) ||
                           fileName.contains(event.fileName, ignoreCase = true) ||
                           event.fileName.contains(fileName, ignoreCase = true)
                       ))
        if (!isTarget) return@LaunchedEffect

        when (event.action) {
            "PLAY" -> {
                if (filePath.isNullOrBlank() || !File(filePath).exists()) {
                    Toast.makeText(context, "Partner is playing: $fileName (Downloading song...)", Toast.LENGTH_SHORT).show()
                    return@LaunchedEffect
                }
                try {
                    if (!mediaPlayer.isPlaying) {
                        mediaPlayer.reset()
                        mediaPlayer.setDataSource(filePath)
                        mediaPlayer.prepare()
                        durationMs = mediaPlayer.duration
                        if (event.positionMs > 0 && event.positionMs < durationMs) {
                            mediaPlayer.seekTo(event.positionMs)
                            currentPosMs = event.positionMs
                        }
                        mediaPlayer.setOnCompletionListener {
                            isPlaying = false
                            isSyncActive = false
                            currentProgress = 0f
                            currentPosMs = 0
                        }
                        mediaPlayer.start()
                        isPlaying = true
                        isSyncActive = true
                    } else {
                        // Keep aligned if drift > 2 seconds
                        if (kotlin.math.abs(mediaPlayer.currentPosition - event.positionMs) > 2000) {
                            mediaPlayer.seekTo(event.positionMs)
                        }
                        isSyncActive = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            "PAUSE" -> {
                try {
                    if (mediaPlayer.isPlaying) {
                        mediaPlayer.pause()
                    }
                    isPlaying = false
                    isSyncActive = false
                } catch (_: Exception) {}
            }
            "STOP" -> {
                try {
                    if (mediaPlayer.isPlaying) {
                        mediaPlayer.stop()
                    }
                    isPlaying = false
                    isSyncActive = false
                    currentProgress = 0f
                    currentPosMs = 0
                } catch (_: Exception) {}
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0x22000000),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = { onLongClick?.invoke() }
            )
            .padding(bottom = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = WhatsAppTypingGreen,
                modifier = Modifier.size(42.dp)
            ) {
                IconButton(onClick = {
                    if (filePath == null) return@IconButton
                    val file = File(filePath)
                    if (!file.exists()) {
                        Toast.makeText(context, "Audio file not found", Toast.LENGTH_SHORT).show()
                        return@IconButton
                    }
                    if (isPlaying) {
                        try {
                            mediaPlayer.pause()
                            isPlaying = false
                            isSyncActive = false
                            onSendSync?.invoke("PAUSE", messageId, fileName, mediaPlayer.currentPosition)
                        } catch (_: Exception) {}
                    } else {
                        try {
                            mediaPlayer.reset()
                            mediaPlayer.setDataSource(filePath)
                            mediaPlayer.prepare()
                            durationMs = mediaPlayer.duration
                            if (currentPosMs > 0 && currentPosMs < durationMs) {
                                mediaPlayer.seekTo(currentPosMs)
                            }
                            mediaPlayer.setOnCompletionListener {
                                isPlaying = false
                                isSyncActive = false
                                currentProgress = 0f
                                currentPosMs = 0
                                onSendSync?.invoke("STOP", messageId, fileName, 0)
                            }
                            mediaPlayer.start()
                            isPlaying = true
                            isSyncActive = true
                            onSendSync?.invoke("PLAY", messageId, fileName, mediaPlayer.currentPosition)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Cannot play audio: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color(0xFF111B21),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = fileName,
                        color = WhatsAppText,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isSyncActive || isPlaying) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = WhatsAppTypingGreen.copy(alpha = 0.2f),
                            modifier = Modifier.padding(start = 6.dp)
                        ) {
                            Text(
                                text = "🎵 Sync",
                                color = WhatsAppTypingGreen,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                if (isSyncActive || isPlaying) {
                    Text(
                        text = "🎵 Listening Together",
                        color = WhatsAppTypingGreen,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { currentProgress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = WhatsAppTypingGreen,
                    trackColor = Color(0x44FFFFFF)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatAudioTime(currentPosMs),
                        color = WhatsAppTime,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = if (durationMs > 0) formatAudioTime(durationMs) else "${(fileSize / 1024).coerceAtLeast(1)} KB",
                        color = WhatsAppTime,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

private fun formatAudioTime(ms: Int): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

/**
 * WhatsApp-style circled '1' View Once badge.
 */
@Composable
fun ViewOnceIcon(
    isOpened: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = if (isOpened) Color(0xFF8696A0) else Color(0xFF25D366)
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .border(
                width = 1.8.dp,
                color = tint,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "1",
            color = tint,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * WhatsApp-style photo sending preview with View-Once toggle.
 */
@Composable
fun PhotoSendPreviewDialog(
    photoFile: File,
    isViewOnce: Boolean,
    onToggleViewOnce: () -> Unit,
    onDismiss: () -> Unit,
    onSend: () -> Unit
) {
    val bitmap = remember(photoFile) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(photoFile.absolutePath, options)
            var sample = 1
            while (options.outWidth / sample > 1200 || options.outHeight / sample > 1200) {
                sample *= 2
            }
            BitmapFactory.decodeFile(photoFile.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) {
            null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B141A))
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                }
                Text(
                    text = if (isViewOnce) "1-Time View Photo" else "Send Photo",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                Spacer(modifier = Modifier.size(48.dp))
            }

            // Image Preview
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 56.dp, bottom = 80.dp)
                )
            }

            // Bottom controls: WhatsApp View-Once toggle pill & Send FAB
            Surface(
                color = Color(0xCC1F2C34),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isViewOnce) Color(0xFF103629) else Color(0xFF2A3942),
                        border = BorderStroke(1.dp, if (isViewOnce) Color(0xFF25D366) else Color.Transparent),
                        modifier = Modifier
                            .clickable { onToggleViewOnce() }
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            ViewOnceIcon(isOpened = false, tint = if (isViewOnce) Color(0xFF25D366) else Color.White)
                            Text(
                                text = if (isViewOnce) "View Once: ON" else "View Once: OFF",
                                color = if (isViewOnce) Color(0xFF25D366) else Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }

                    FloatingActionButton(
                        onClick = onSend,
                        containerColor = Color(0xFF25D366),
                        contentColor = Color(0xFF111B21),
                        shape = CircleShape,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

/**
 * WhatsApp-style "last seen today at 6:45 PM" formatter.
 */
fun formatLastSeen(timestamp: Long, isCompact: Boolean = false): String {
    if (timestamp <= 0L) return "Offline"
    val now = Calendar.getInstance()
    val lastSeen = Calendar.getInstance().apply { timeInMillis = timestamp }

    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    val formattedTime = timeFormat.format(Date(timestamp))

    val isToday = now.get(Calendar.YEAR) == lastSeen.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == lastSeen.get(Calendar.DAY_OF_YEAR)

    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = yesterday.get(Calendar.YEAR) == lastSeen.get(Calendar.YEAR) &&
            yesterday.get(Calendar.DAY_OF_YEAR) == lastSeen.get(Calendar.DAY_OF_YEAR)

    return if (isCompact) {
        when {
            isToday -> "today at $formattedTime"
            isYesterday -> "yesterday at $formattedTime"
            else -> {
                val dateFormat = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
                dateFormat.format(Date(timestamp))
            }
        }
    } else {
        when {
            isToday -> "last seen today at $formattedTime"
            isYesterday -> "last seen yesterday at $formattedTime"
            else -> {
                val dateFormat = SimpleDateFormat("d MMM 'at' h:mm a", Locale.getDefault())
                "last seen ${dateFormat.format(Date(timestamp))}"
            }
        }
    }
}
