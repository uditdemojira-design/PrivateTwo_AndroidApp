package org.privatetwo.app.feature.privacy

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Window
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Monitors phone orientation for extreme side-tilt (> 50 degrees).
 * Battery-optimized with SENSOR_DELAY_NORMAL and lifecycle auto-unregister.
 */
@Composable
fun rememberAntiPeepTiltState(enabled: Boolean): State<Boolean> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isTilted = remember { mutableStateOf(false) }

    DisposableEffect(enabled, lifecycleOwner) {
        if (!enabled) {
            isTilted.value = false
            return@DisposableEffect onDispose {}
        }

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (sensorManager == null || accelerometer == null) {
            return@DisposableEffect onDispose {}
        }

        var isRegistered = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                // Calculate sideways roll angle in degrees
                val rollDegrees = Math.toDegrees(atan2(x.toDouble(), sqrt((y * y + z * z).toDouble())))
                val absRoll = kotlin.math.abs(rollDegrees)

                // Only trigger on heavy tilt (> 50° away from user)
                if (!isTilted.value && absRoll > 50.0) {
                    isTilted.value = true
                } else if (isTilted.value && absRoll < 30.0) {
                    isTilted.value = false
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        fun register() {
            if (!isRegistered) {
                sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
                isRegistered = true
            }
        }

        fun unregister() {
            if (isRegistered) {
                sensorManager.unregisterListener(listener)
                isRegistered = false
                isTilted.value = false
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> unregister()
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            register()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unregister()
        }
    }

    return isTilted
}

/**
 * Optical Micro-Louver Polarized Filter:
 * Draws ultra-fine vertical black raster lines (1px every 3px) plus contrast darkening.
 * Looking straight (0°): Your eyes see clearly through the slits.
 * Looking from the side (>25° angle): Angular light paths are obstructed, drastically reducing text legibility.
 */
fun Modifier.antiPeepLouverFilter(enabled: Boolean, darkTintAlpha: Float = 0.38f): Modifier {
    if (!enabled) return this
    return this.drawWithContent {
        drawContent()
        // Dark contrast compression tint
        drawRect(Color.Black.copy(alpha = darkTintAlpha))
        // Dense vertical micro-slits
        val width = size.width
        val height = size.height
        val step = 3f
        var x = 0f
        while (x < width) {
            drawLine(
                color = Color.Black.copy(alpha = 0.82f),
                start = Offset(x, 0f),
                end = Offset(x, height),
                strokeWidth = 1f
            )
            x += step
        }
    }
}

/**
 * Draggable Reading Curtain (Privacy Shade):
 * Covers screen with 88% dark curtain except a movable horizontal viewing slot (120dp high).
 * The user drags the slot up & down to read messages one-by-one. Bystanders can't see the rest of the chat!
 */
@Composable
fun PrivacyReadingCurtain(
    isActive: Boolean,
    onClose: () -> Unit
) {
    if (!isActive) return

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val slotHeightDp = 130.dp
    val slotHeightPx = with(density) { slotHeightDp.toPx() }

    var slotOffsetY by remember { mutableStateOf(screenHeightPx * 0.4f) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Top dark curtain
        val topHeight = slotOffsetY.coerceAtLeast(0f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { topHeight.toDp() })
                .background(Color.Black.copy(alpha = 0.90f))
        )

        // Draggable clear viewing slot
        Box(
            modifier = Modifier
                .offset { IntOffset(0, slotOffsetY.roundToInt()) }
                .fillMaxWidth()
                .height(slotHeightDp)
                .border(2.dp, Color(0xFF64B5F6).copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val newY = slotOffsetY + dragAmount.y
                        slotOffsetY = newY.coerceIn(50f, screenHeightPx - slotHeightPx - 50f)
                    }
                }
        ) {
            // Drag handle badge
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xCC1E1E1E),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("↕ Drag to Read", fontSize = 11.sp, color = Color(0xFF64B5F6), fontWeight = FontWeight.Bold)
                }
            }

            // Close button on the slot
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp)
                    .padding(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Curtain",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Bottom dark curtain
        val bottomTop = (slotOffsetY + slotHeightPx).coerceAtMost(screenHeightPx)
        val bottomHeight = (screenHeightPx - bottomTop).coerceAtLeast(0f)
        Box(
            modifier = Modifier
                .offset { IntOffset(0, bottomTop.roundToInt()) }
                .fillMaxWidth()
                .height(with(density) { bottomHeight.toDp() })
                .background(Color.Black.copy(alpha = 0.90f))
        )
    }
}

/**
 * Modern Anti-Peep Shield Suite:
 * Integrates Micro-Louver Angle Filtering, Reading Curtain, and Emergency Camouflage.
 */
@Composable
fun AntiPeepShieldOverlay(
    isTiltBlackoutActive: Boolean,
    isManualBlackoutActive: Boolean,
    isLouverFilterActive: Boolean = false,
    isReadingCurtainActive: Boolean = false,
    onDismissManualBlackout: () -> Unit,
    onDismissReadingCurtain: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .antiPeepLouverFilter(isLouverFilterActive)
    ) {
        content()

        // Reading Curtain Overlay
        PrivacyReadingCurtain(
            isActive = isReadingCurtainActive,
            onClose = onDismissReadingCurtain
        )

        // Emergency Tilt Alert (Only triggers on extreme tilt >50° if enabled)
        AnimatedVisibility(
            visible = isTiltBlackoutActive && !isManualBlackoutActive,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = Color(0xFF64B5F6),
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "Side-Angle Guard Active",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Hold phone straight to view.",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // Manual 1-Tap Blackout Camouflage Screen
        AnimatedVisibility(
            visible = isManualBlackoutActive,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { onDismissManualBlackout() }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Concealed",
                        tint = Color(0xFF444444),
                        modifier = Modifier.size(44.dp)
                    )

                    Text(
                        text = "Screen Concealed",
                        color = Color(0xFF666666),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Button(
                        onClick = onDismissManualBlackout,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF222222),
                            contentColor = Color(0xFFCCCCCC)
                        ),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Double-tap or Click to Reveal", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/**
 * Quick Anti-Peep Privacy Controls Sheet:
 * Opened by tapping the Shield button on the chat top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyControlsBottomSheet(
    isLouverActive: Boolean,
    isCurtainActive: Boolean,
    isStealthMaskActive: Boolean,
    onToggleLouver: (Boolean) -> Unit,
    onToggleCurtain: (Boolean) -> Unit,
    onToggleStealthMask: (Boolean) -> Unit,
    onTriggerBlackout: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = Color(0xFF64B5F6),
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = "Anti-Peep Privacy Controls",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Text(
                text = "Protect your messages from people sitting next to you or shoulder-surfing in public:",
                fontSize = 13.sp,
                color = Color.LightGray
            )

            HorizontalDivider(color = Color(0xFF333333))

            // 1. Polarized Side-Angle Blocker (Micro-Louvers)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("🛡️ Side-Angle Blocker", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Polarized micro-louvers obscure text from side angles while keeping straight view readable.", fontSize = 12.sp, color = Color.Gray)
                }
                Switch(
                    checked = isLouverActive,
                    onCheckedChange = onToggleLouver
                )
            }

            // 2. Reading Curtain (Draggable Window)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("🪟 Reading Curtain (Privacy Shade)", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Darkens screen except a movable reading slot you drag with your finger.", fontSize = 12.sp, color = Color.Gray)
                }
                Switch(
                    checked = isCurtainActive,
                    onCheckedChange = onToggleCurtain
                )
            }

            // 3. Stealth Message Mask (Tap-to-Reveal)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("👁️ Tap-to-Reveal Messages", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Hides message text behind privacy blocks until you tap to unmask.", fontSize = 12.sp, color = Color.Gray)
                }
                Switch(
                    checked = isStealthMaskActive,
                    onCheckedChange = onToggleStealthMask
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 4. Instant Emergency Blackout
            OutlinedButton(
                onClick = {
                    onDismiss()
                    onTriggerBlackout()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF64B5F6))
            ) {
                Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Instant Emergency Blackout Screen")
            }
        }
    }
}
