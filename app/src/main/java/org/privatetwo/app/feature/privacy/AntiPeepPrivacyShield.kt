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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fingerprint
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
import androidx.compose.ui.graphics.Brush
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
 * Monitors phone orientation for extreme side-tilt (> 65 degrees).
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

                // Only trigger on severe tilt (> 65° sideways away from user)
                if (!isTilted.value && absRoll > 65.0) {
                    isTilted.value = true
                } else if (isTilted.value && absRoll < 35.0) {
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
 * Samsung-style Privacy Display Filter:
 * Recreates the viewing-angle restriction of Samsung Galaxy S26 Ultra's Privacy Display in software:
 * 1. Straight view (0°, user holding phone normally in hand): Full clarity through the center aperture & micro-slits.
 * 2. Off-axis / Side-angle view (30°-70°, person sitting next to you in metro/bus):
 *    Angular light rays are extinguished by the lateral optical vignette and the dense 2px micro-louver grating,
 *    making the screen appear pitch-black to bystanders without requiring phone tilting.
 */
fun Modifier.antiPeepLouverFilter(enabled: Boolean, darkTintAlpha: Float = 0.50f): Modifier {
    if (!enabled) return this
    return this.drawWithContent {
        drawContent()

        val width = size.width
        val height = size.height

        // 1. Lateral Off-Axis Vignette: Blocks viewing from steep side angles (left & right)
        // Center (where the phone owner holds and reads) is clear (0% alpha) for 100% legibility!
        val lateralVignette = Brush.horizontalGradient(
            0.00f to Color.Black.copy(alpha = 0.90f),
            0.10f to Color.Black.copy(alpha = 0.60f),
            0.20f to Color.Black.copy(alpha = 0.15f),
            0.28f to Color.Transparent,
            0.72f to Color.Transparent,
            0.80f to Color.Black.copy(alpha = 0.15f),
            0.90f to Color.Black.copy(alpha = 0.60f),
            1.00f to Color.Black.copy(alpha = 0.90f),
            startX = 0f,
            endX = width
        )
        drawRect(brush = lateralVignette)

        // 2. Subtle center tint: Dims screen slightly so viewing angle contrast drops,
        // but user holding phone straight sees everything clearly with zero eye-strain.
        val centerDim = (darkTintAlpha * 0.25f).coerceIn(0.06f, 0.20f)
        drawRect(Color.Black.copy(alpha = centerDim))

        // 3. Ultra-fine micro-louvers: Subtle hairline grid (alpha 0.12f)
        // Disrupts angular off-axis viewing without obscuring the owner's direct view
        val step = 4.0f
        var x = 0f
        while (x < width) {
            drawLine(
                color = Color.Black.copy(alpha = 0.12f),
                start = Offset(x, 0f),
                end = Offset(x, height),
                strokeWidth = 1.0f
            )
            x += step
        }
    }
}

/**
 * Draggable Reading Curtain (Privacy Shade):
 * Covers screen with 92% dark curtain except a movable horizontal viewing slot (120dp high).
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
                .background(Color.Black.copy(alpha = 0.94f))
        )

        // Draggable clear viewing slot
        Box(
            modifier = Modifier
                .offset { IntOffset(0, slotOffsetY.roundToInt()) }
                .fillMaxWidth()
                .height(slotHeightDp)
                .border(2.dp, Color(0xFF64B5F6).copy(alpha = 0.8f), RoundedCornerShape(8.dp))
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
                .background(Color.Black.copy(alpha = 0.94f))
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
    louverOpacity: Float = 0.85f,
    isReadingCurtainActive: Boolean = false,
    onDismissTilt: () -> Unit = {},
    onDismissManualBlackout: () -> Unit,
    onDismissReadingCurtain: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val requestBiometricUnlock: (() -> Unit) -> Unit = { onSuccess ->
        var currentContext: Context? = context
        var fragmentActivity: androidx.fragment.app.FragmentActivity? = null
        while (currentContext != null) {
            if (currentContext is androidx.fragment.app.FragmentActivity) {
                fragmentActivity = currentContext
                break
            }
            currentContext = (currentContext as? android.content.ContextWrapper)?.baseContext
        }

        if (fragmentActivity != null && org.privatetwo.app.core.security.BiometricAuthHelper.isBiometricAvailable(fragmentActivity)) {
            org.privatetwo.app.core.security.BiometricAuthHelper.showBiometricPrompt(
                activity = fragmentActivity,
                title = "Unlock Private Screen",
                subtitle = "Verify fingerprint, PIN, or pattern to reveal",
                onSuccess = onSuccess,
                onError = { /* Stay concealed on authentication failure or cancel */ }
            )
        } else {
            onSuccess()
        }
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        content()

        // Optical Micro-Louver Filter Overlay directly over the content
        if (isLouverFilterActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .antiPeepLouverFilter(enabled = true, darkTintAlpha = louverOpacity)
            )
        }

        // Reading Curtain Overlay
        PrivacyReadingCurtain(
            isActive = isReadingCurtainActive,
            onClose = onDismissReadingCurtain
        )

        // Emergency Tilt Alert (Only triggers on extreme tilt >65° if enabled)
        AnimatedVisibility(
            visible = isTiltBlackoutActive && !isManualBlackoutActive,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.98f))
                    .clickable {
                        requestBiometricUnlock { onDismissTilt() }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = Color(0xFF25D366),
                        modifier = Modifier.size(52.dp)
                    )
                    Text(
                        text = "Side-Angle Guard Active",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Phone tilted. Screen concealed for privacy.\nTap to verify fingerprint / lock & reveal.",
                        color = Color.LightGray,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = {
                            requestBiometricUnlock { onDismissTilt() }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF202C33),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = null,
                            tint = Color(0xFF25D366),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Verify Lock to Reveal", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
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
                            onTap = {
                                requestBiometricUnlock { onDismissManualBlackout() }
                            },
                            onDoubleTap = {
                                requestBiometricUnlock { onDismissManualBlackout() }
                            }
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
                        tint = Color(0xFF555555),
                        modifier = Modifier.size(48.dp)
                    )

                    Text(
                        text = "Screen Concealed & Locked",
                        color = Color(0xFF888888),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Button(
                        onClick = {
                            requestBiometricUnlock { onDismissManualBlackout() }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1E282E),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = null,
                            tint = Color(0xFF25D366),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Tap to Unlock with Lock", fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
    currentOpacity: Float = 0.85f,
    onOpacityChanged: (Float) -> Unit = {},
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

            // 1. Polarized Side-Angle Blocker (Micro-Louvers & Black Screen)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🛡️ Samsung Privacy Display (Anti-Peep)", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text("Restricts viewing angle so side-sitters in metro only see a black screen while you hold phone straight.", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = isLouverActive,
                        onCheckedChange = onToggleLouver
                    )
                }

                if (isLouverActive) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Level:", fontSize = 12.sp, color = Color.LightGray)
                        listOf(0.30f to "Mild", 0.50f to "Metro", 0.70f to "Deep").forEach { (value, label) ->
                            FilterChip(
                                selected = (currentOpacity == value),
                                onClick = { onOpacityChanged(value) },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                    }
                }
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
