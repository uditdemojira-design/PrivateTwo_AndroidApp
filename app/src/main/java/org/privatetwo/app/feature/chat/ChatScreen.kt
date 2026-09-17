package org.privatetwo.app.feature.chat

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.privatetwo.app.core.database.DeliveryStatus
import org.privatetwo.app.core.signaling.SignalingConnectionState
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
    onNavigateBack: () -> Unit,
    onStartAudioCall: () -> Unit,
    onStartVideoCall: () -> Unit,
    onOpenSettings: () -> Unit
) {
    BackHandler(onBack = onNavigateBack)

    val context = LocalContext.current
    val messages by viewModel.messages.collectAsState()
    val signalingState by viewModel.signalingState.collectAsState()
    val isPeerTyping by viewModel.isPeerTyping.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val inputStream = context.contentResolver.openInputStream(it)
            val tempFile = File(context.cacheDir, "send_photo_${System.currentTimeMillis()}.jpg")
            FileOutputStream(tempFile).use { out -> inputStream?.copyTo(out) }
            viewModel.sendPhoto(tempFile)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val inputStream = context.contentResolver.openInputStream(it)
            val tempFile = File(context.cacheDir, "send_file_${System.currentTimeMillis()}.bin")
            FileOutputStream(tempFile).use { out -> inputStream?.copyTo(out) }
            viewModel.sendFile(tempFile)
        }
    }

    LaunchedEffect(messages.size, isPeerTyping) {
        val totalCount = messages.size + (if (isPeerTyping) 1 else 0)
        if (totalCount > 0) {
            listState.animateScrollToItem(totalCount - 1)
        }
    }

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
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF1E2B33),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = WhatsAppTypingGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Column {
                            Text(
                                text = "Private Partner",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
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
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val (dotColor, statusText) = when (signalingState) {
                                        SignalingConnectionState.CONNECTED -> Pair(WhatsAppTypingGreen, "Connected")
                                        SignalingConnectionState.CONNECTING -> Pair(Color(0xFFF57F17), "Connecting…")
                                        SignalingConnectionState.DISCONNECTED -> Pair(Color.Gray, "Offline")
                                    }
                                    Surface(
                                        shape = CircleShape,
                                        color = dotColor,
                                        modifier = Modifier.size(7.dp)
                                    ) {}
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = WhatsAppTime
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onStartAudioCall) {
                        Icon(Icons.Default.Phone, contentDescription = "Audio Call", tint = Color.White)
                    }
                    IconButton(onClick = onStartVideoCall) {
                        Icon(Icons.Default.Videocam, contentDescription = "Video Call", tint = Color.White)
                    }
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = Color.White)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
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
                                    if (showEmojiPicker) showEmojiPicker = false
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
                                    showEmojiPicker = false
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
                            onRetry = { viewModel.retryMessage(message.id) },
                            onDelete = { viewModel.deleteMessage(message.id) }
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
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    val isOutgoing = !message.isIncoming
    val alignment = if (isOutgoing) Alignment.End else Alignment.Start

    val isLargeEmojiOnly = message.messageType == "TEXT" && isSingleOrDoubleEmoji(message.text)

    val bubbleColor = if (isOutgoing) WhatsAppOutgoingBubble else WhatsAppIncomingBubble
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val formattedTime = remember(message.timestamp) { timeFormatter.format(Date(message.timestamp)) }

    var showMessageMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (isLargeEmojiOnly) {
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
                        onClick = {},
                        onLongClick = { showMessageMenu = true }
                    )
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    if (message.messageType == "PHOTO" && message.mediaLocalPath != null) {
                        val bitmap = remember(message.mediaLocalPath) {
                            try {
                                BitmapFactory.decodeFile(message.mediaLocalPath)
                            } catch (e: Exception) {
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
                                    .heightIn(max = 260.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    } else if (message.messageType == "FILE") {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0x22000000),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.InsertDriveFile,
                                    contentDescription = "File",
                                    tint = WhatsAppTypingGreen,
                                    modifier = Modifier.size(28.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = message.mediaFileName ?: "File",
                                        color = WhatsAppText,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${(message.mediaFileSize / 1024).coerceAtLeast(1)} KB",
                                        color = WhatsAppTime,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }

                    if (message.messageType == "TEXT" || (message.messageType != "PHOTO" && message.text != message.mediaFileName)) {
                        Text(
                            text = message.text,
                            color = WhatsAppText,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
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
            onDismissRequest = { showMessageMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Delete message") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = {
                    showMessageMenu = false
                    onDelete()
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
            // WhatsApp Cyan Double Checkmark
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Delivered",
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
