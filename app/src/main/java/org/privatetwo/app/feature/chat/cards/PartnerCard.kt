package org.privatetwo.app.feature.chat.cards

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject

enum class CardCategory(val label: String, val emoji: String, val defaultTitle: String) {
    LOVE("Love", "❤️", "I Love You Always"),
    SORRY("Sorry", "🥺", "I'm Really Sorry"),
    THANK_YOU("Thank You", "🌸", "Thank You, My Love"),
    SPECIAL("Special", "✨", "Thinking Of You")
}

enum class CardTheme(val themeName: String, val colors: List<Color>) {
    ROSE_CRIMSON("Rose", listOf(Color(0xFFE91E63), Color(0xFFFF5252), Color(0xFF880E4F))),
    SUNSET_GLOW("Sunset", listOf(Color(0xFFFF7E5F), Color(0xFFFEB47B))),
    DEEP_PURPLE("Cosmic", listOf(Color(0xFF7B1FA2), Color(0xFF512DA8), Color(0xFF311B92))),
    INDIGO_SORRY("Moonlit", listOf(Color(0xFF3949AB), Color(0xFF5C6BC0), Color(0xFF283593))),
    EMERALD_MINT("Mint", listOf(Color(0xFF00897B), Color(0xFF004D40), Color(0xFF00BFA5))),
    GOLDEN_AMBER("Gold", listOf(Color(0xFFFF8F00), Color(0xFFFFB300), Color(0xFFE65100)))
}

data class PartnerCardData(
    val category: CardCategory,
    val title: String,
    val message: String,
    val theme: CardTheme,
    val emoji: String
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("category", category.name)
            put("title", title)
            put("message", message)
            put("theme", theme.name)
            put("emoji", emoji)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): PartnerCardData? {
            return try {
                val json = JSONObject(jsonStr)
                val catName = json.optString("category", CardCategory.LOVE.name)
                val category = CardCategory.values().find { it.name == catName } ?: CardCategory.LOVE
                val title = json.optString("title", "I Love You")
                val message = json.optString("message", "")
                val themeName = json.optString("theme", CardTheme.ROSE_CRIMSON.name)
                val theme = CardTheme.values().find { it.name == themeName } ?: CardTheme.ROSE_CRIMSON
                val emoji = json.optString("emoji", category.emoji)
                PartnerCardData(category, title, message, theme, emoji)
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Interactive Card bubble rendered directly in the Chat timeline.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PartnerCardBubble(
    card: PartnerCardData,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val gradientBrush = remember(card.theme) {
        Brush.linearGradient(card.theme.colors)
    }

    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Surface(
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 4.dp,
        modifier = Modifier
            .widthIn(min = 230.dp, max = 290.dp)
            .padding(vertical = 4.dp)
            .combinedClickable(
                onClick = { onClick() },
                onLongClick = { onLongClick?.invoke() }
            )
    ) {
        Box(
            modifier = Modifier
                .background(gradientBrush)
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Category Header Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Black.copy(alpha = 0.28f)
                    ) {
                        Text(
                            text = "${card.category.emoji} ${card.category.label.uppercase()}",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    Text(
                        text = card.emoji,
                        fontSize = 24.sp,
                        modifier = Modifier.scale(pulseScale)
                    )
                }

                // Card Title
                Text(
                    text = card.title,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 22.sp
                )

                // Message text
                if (card.message.isNotBlank()) {
                    Text(
                        text = "\"${card.message}\"",
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 13.sp,
                        fontStyle = FontStyle.Italic,
                        lineHeight = 18.sp
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Footer hint
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = "💌 Tap to expand",
                        color = Color.White.copy(alpha = 0.70f),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

/**
 * Fullscreen celebration popup when user taps a Card.
 */
@Composable
fun PartnerCardFullscreenDialog(
    card: PartnerCardData,
    onDismiss: () -> Unit
) {
    val gradientBrush = remember(card.theme) {
        Brush.linearGradient(card.theme.colors)
    }

    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                shadowElevation = 16.dp,
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .clickable(enabled = false) {}
            ) {
                Box(
                    modifier = Modifier
                        .background(gradientBrush)
                        .padding(24.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Close button at top right
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Black.copy(alpha = 0.25f)
                            ) {
                                Text(
                                    text = "${card.category.emoji} ${card.category.label}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }

                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }

                        // Big pulsating emoji
                        Text(
                            text = card.emoji,
                            fontSize = 64.sp,
                            modifier = Modifier.scale(pulseScale)
                        )

                        // Title
                        Text(
                            text = card.title,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        // Message Note
                        if (card.message.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color.Black.copy(alpha = 0.20f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "\"${card.message}\"",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontStyle = FontStyle.Italic,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 22.sp,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "❤️ Sent with love to you",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * BottomSheet to compose and customize a card before sending.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartnerCardBottomSheet(
    onDismiss: () -> Unit,
    onSendCard: (PartnerCardData) -> Unit
) {
    var selectedCategory by remember { mutableStateOf(CardCategory.LOVE) }
    var selectedTheme by remember { mutableStateOf(CardTheme.ROSE_CRIMSON) }
    var title by remember { mutableStateOf(selectedCategory.defaultTitle) }
    var message by remember { mutableStateOf("") }
    var selectedEmoji by remember { mutableStateOf(selectedCategory.emoji) }

    // Predefined suggestions
    val titleSuggestions = remember(selectedCategory) {
        when (selectedCategory) {
            CardCategory.LOVE -> listOf(
                "I Love You Always ❤️",
                "You Mean Everything 💖",
                "Thinking Of You ✨",
                "Forever & Always 🔐",
                "My Favorite Person 🌸"
            )
            CardCategory.SORRY -> listOf(
                "I'm Really Sorry 🥺",
                "Please Forgive Me 🙏",
                "Didn't Mean To Hurt You 💔",
                "Can We Make Up? 🤗",
                "Hate Fighting With You 🫂"
            )
            CardCategory.THANK_YOU -> listOf(
                "Thank You, My Love 🌸",
                "Grateful For You ✨",
                "You Made My Day ☀️",
                "Best Partner Ever 🏆",
                "So Blessed To Have You 💖"
            )
            CardCategory.SPECIAL -> listOf(
                "Good Morning Sunshine ☀️",
                "Sweet Dreams My Love 🌙",
                "Happy Birthday Babe! 🎂",
                "Happy Anniversary 🥂",
                "Sending Warm Hugs 🫂"
            )
        }
    }

    val noteSuggestions = remember(selectedCategory) {
        when (selectedCategory) {
            CardCategory.LOVE -> listOf(
                "Every moment with you feels like a dream come true.",
                "Just wanted to remind you how deeply and truly I love you.",
                "No matter how busy life gets, my heart is always with you."
            )
            CardCategory.SORRY -> listOf(
                "I feel terrible for upsetting you. You mean the world to me.",
                "I was wrong and I'm genuinely sorry. Please give me a warm hug?",
                "I never want to see you upset. Let's talk and make things right."
            )
            CardCategory.THANK_YOU -> listOf(
                "Thank you for always being my rock, supporting and loving me unconditionally.",
                "Your kindness and love always melt my heart. Thank you for everything!",
                "You make my life so beautiful just by being in it."
            )
            CardCategory.SPECIAL -> listOf(
                "Wishing you the brightest, most wonderful day filled with smiles!",
                "Rest well tonight, my love. Dream of all the happy moments we share.",
                "Celebrating you today and every day. You deserve the best in life!"
            )
        }
    }

    val emojiList = remember(selectedCategory) {
        when (selectedCategory) {
            CardCategory.LOVE -> listOf("❤️", "💖", "💕", "💘", "🌹", "💍", "🥰", "💌")
            CardCategory.SORRY -> listOf("🥺", "💔", "🙏", "🫂", "😢", "🩹", "😔")
            CardCategory.THANK_YOU -> listOf("🌸", "✨", "💐", "🎁", "☀️", "🌟", "💖")
            CardCategory.SPECIAL -> listOf("🎂", "🥂", "☀️", "🌙", "🎉", "🧸", "👑", "🌈")
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF182229),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "💌 Create Partner Card",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF8696A0))
                }
            }

            // 1. Live Preview of Card
            Text(
                text = "CARD PREVIEW",
                color = Color(0xFF25D366),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )

            PartnerCardBubble(
                card = PartnerCardData(
                    category = selectedCategory,
                    title = title.ifBlank { selectedCategory.defaultTitle },
                    message = message,
                    theme = selectedTheme,
                    emoji = selectedEmoji
                ),
                onClick = {}
            )

            // 2. Category Selector Chips
            Text(
                text = "1. CHOOSE CATEGORY",
                color = Color(0xFF8696A0),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CardCategory.values().forEach { category ->
                    val isSelected = category == selectedCategory
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            selectedCategory = category
                            title = category.defaultTitle
                            selectedEmoji = category.emoji
                            selectedTheme = when (category) {
                                CardCategory.LOVE -> CardTheme.ROSE_CRIMSON
                                CardCategory.SORRY -> CardTheme.INDIGO_SORRY
                                CardCategory.THANK_YOU -> CardTheme.EMERALD_MINT
                                CardCategory.SPECIAL -> CardTheme.DEEP_PURPLE
                            }
                        },
                        label = { Text("${category.emoji} ${category.label}") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF25D366),
                            selectedLabelColor = Color(0xFF111B21),
                            containerColor = Color(0xFF202C33),
                            labelColor = Color.White
                        )
                    )
                }
            }

            // 3. Card Title (Input + Suggestions)
            Text(
                text = "2. CARD TITLE",
                color = Color(0xFF8696A0),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 15.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF25D366),
                    unfocusedBorderColor = Color(0xFF8696A0),
                    focusedLabelColor = Color(0xFF25D366),
                    unfocusedLabelColor = Color(0xFF8696A0),
                    cursorColor = Color(0xFF25D366)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                titleSuggestions.forEach { suggestion ->
                    SuggestionChip(
                        onClick = { title = suggestion },
                        label = { Text(suggestion, fontSize = 11.sp) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color(0xFF202C33),
                            labelColor = Color.White
                        )
                    )
                }
            }

            // 4. Personal Message / Note
            Text(
                text = "3. PERSONAL NOTE (OPTIONAL)",
                color = Color(0xFF8696A0),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("Your Message") },
                maxLines = 4,
                textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 14.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF25D366),
                    unfocusedBorderColor = Color(0xFF8696A0),
                    focusedLabelColor = Color(0xFF25D366),
                    unfocusedLabelColor = Color(0xFF8696A0),
                    cursorColor = Color(0xFF25D366)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                noteSuggestions.forEach { note ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF202C33),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { message = note }
                    ) {
                        Text(
                            text = "\"$note\"",
                            color = Color(0xFFD1D7DB),
                            fontSize = 12.sp,
                            fontStyle = FontStyle.Italic,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }

            // 5. Theme / Background Color Gradient
            Text(
                text = "4. CARD THEME COLOR",
                color = Color(0xFF8696A0),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CardTheme.values().forEach { theme ->
                    val isSelected = theme == selectedTheme
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(theme.colors))
                            .clickable { selectedTheme = theme }
                            .then(
                                if (isSelected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // 6. Emoji Picker
            Text(
                text = "5. CARD EMOJI",
                color = Color(0xFF8696A0),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                emojiList.forEach { emoji ->
                    val isSelected = emoji == selectedEmoji
                    Surface(
                        shape = CircleShape,
                        color = if (isSelected) Color(0xFF25D366) else Color(0xFF202C33),
                        modifier = Modifier
                            .size(40.dp)
                            .clickable { selectedEmoji = emoji }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = emoji, fontSize = 20.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Send Button
            Button(
                onClick = {
                    val finalCard = PartnerCardData(
                        category = selectedCategory,
                        title = title.ifBlank { selectedCategory.defaultTitle },
                        message = message,
                        theme = selectedTheme,
                        emoji = selectedEmoji
                    )
                    onSendCard(finalCard)
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = Color(0xFF111B21), modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Send Card to Partner 💌",
                    color = Color(0xFF111B21),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}
