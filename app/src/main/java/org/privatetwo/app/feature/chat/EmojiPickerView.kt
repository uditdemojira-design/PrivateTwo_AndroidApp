package org.privatetwo.app.feature.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class EmojiCategory(val title: String, val icon: ImageVector) {
    LOVE("Love & Romance", Icons.Default.Favorite),
    SMILEYS("Smileys", Icons.Default.Mood),
    GESTURES("Gestures", Icons.Default.ThumbUp),
    CELEBRATION("Celebration", Icons.Default.Celebration)
}

object EmojiData {
    // Top Priority: Love, romance, hearts, affection, kissing, flowers, cuddles
    val loveEmojis = listOf(
        "❤️", "💖", "💕", "💗", "💓", "💞", "💘", "💌", "💟", "❣️", "💔",
        "🥰", "😍", "😘", "😚", "😙", "😻", "😽", "💋", "💏", "💑", "👩‍❤️‍👨",
        "🌹", "🥀", "🌺", "🌸", "💐", "🍫", "🧸", "💍", "🎁", "✨", "🌟",
        "🫂", "🥺", "🥹", "🤗", "🤍", "🖤", "🤎", "💜", "💙", "💚", "💛", "🧡",
        "👩‍❤️‍👩", "👨‍❤️‍👨", "🏩", "💒", "🎀", "🕯️", "🥂", "🍓", "🍒", "🌙"
    )

    val smileyEmojis = listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "🥲", "☺️", "😊",
        "😇", "😉", "😌", "😋", "😛", "😜", "🤪", "😝", "🤑", "🤭", "🤫",
        "🤔", "🤐", "🤨", "😐", "😑", "😶", "😏", "😒", "🙄", "😬", "🤥",
        "😔", "😪", "🤤", "😴", "😷", "🤒", "🤕", "🤢", "🤮", "🥵", "🥶",
        "🥴", "😵", "🤯", "😎", "🤓", "🧐", "🥳", "🥺", "🤠", "😈", "👿"
    )

    val gestureEmojis = listOf(
        "👍", "👎", "👌", "🤌", "🤏", "✌️", "🤞", "🤟", "🤘", "🤙", "👈",
        "👉", "👆", "👇", "☝️", "👏", "🙌", "👐", "🤲", "🤝", "🙏", "✍️",
        "💪", "🦾", "🤳", "💅", "👋", "🤚", "🖐️", "✋", "🖖", "👊", "✊"
    )

    val celebrationEmojis = listOf(
        "🎉", "🎊", "🎈", "🎂", "🍰", "🧁", "🍾", "🥂", "🍻", "🍺", "🍷",
        "🍸", "🍹", "🥳", "💃", "🕺", "🎶", "🎵", "🎸", "🎤", "🎧", "🎬",
        "🍿", "🚀", "🔥", "💯", "👑", "🏆", "🥇", "⭐", "🌈", "☀️", "🏖️"
    )

    fun getCategoryEmojis(category: EmojiCategory): List<String> {
        return when (category) {
            EmojiCategory.LOVE -> loveEmojis
            EmojiCategory.SMILEYS -> smileyEmojis
            EmojiCategory.GESTURES -> gestureEmojis
            EmojiCategory.CELEBRATION -> celebrationEmojis
        }
    }
}

/**
 * WhatsApp-style bottom Emoji Picker with Priority Love Category.
 */
@Composable
fun EmojiPickerView(
    onEmojiSelected: (String) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Love & Romance is DEFAULT active category
    var selectedCategory by remember { mutableStateOf(EmojiCategory.LOVE) }
    val currentEmojis = remember(selectedCategory) { EmojiData.getCategoryEmojis(selectedCategory) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Category Bar
            TabRow(
                selectedTabIndex = selectedCategory.ordinal,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            ) {
                EmojiCategory.values().forEach { category ->
                    val isSelected = selectedCategory == category
                    val tabColor by animateColorAsState(
                        if (isSelected) {
                            if (category == EmojiCategory.LOVE) Color(0xFFE91E63) else MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        }
                    )

                    Tab(
                        selected = isSelected,
                        onClick = { selectedCategory = category },
                        icon = {
                            Icon(
                                imageVector = category.icon,
                                contentDescription = category.title,
                                tint = tabColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    )
                }
            }

            // Category Title Sub-header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = selectedCategory.title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selectedCategory == EmojiCategory.LOVE) Color(0xFFE91E63) else MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${currentEmojis.size} emojis",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            // Emoji Grid
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 44.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(currentEmojis) { emoji ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onEmojiSelected(emoji) }
                        ) {
                            Text(
                                text = emoji,
                                fontSize = 24.sp
                            )
                        }
                    }
                }

                // WhatsApp-Style Floating Backspace Key at Bottom Right
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                ) {
                    IconButton(
                        onClick = onBackspace,
                        modifier = Modifier.size(42.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Backspace,
                            contentDescription = "Backspace",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
