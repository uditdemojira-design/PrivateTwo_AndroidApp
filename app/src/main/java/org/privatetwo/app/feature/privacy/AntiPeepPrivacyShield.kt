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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.sqrt

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Monitors the phone's physical orientation angle.
 * If the device is tilted sideways (> 26 degrees) towards a bystander,
 * it instantly flags tilt detection to black out the display.
 *
 * Battery Optimized:
 * - Only listens when Activity is in RESUMED state (screen on & in foreground).
 * - Immediately unregisters when paused/screen turned off.
 * - Uses SENSOR_DELAY_NORMAL (~5 Hz) instead of high-frequency polling.
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

                // Hysteresis: enter blackout at 26°, leave blackout when straightened below 18°
                if (!isTilted.value && absRoll > 26.0) {
                    isTilted.value = true
                } else if (isTilted.value && absRoll < 18.0) {
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
 * Full-screen Blackout Overlay providing shoulder-surfing and side-angle protection.
 * - Blackout Mode: Screen turns completely pitch black so anyone looking from the side
 *   or from afar sees a powered-off screen.
 * - Double tap or tap on the unlock button restores viewing.
 */
@Composable
fun AntiPeepShieldOverlay(
    isTiltBlackoutActive: Boolean,
    isManualBlackoutActive: Boolean,
    onDismissManualBlackout: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        content()

        // Tilt-Triggered Auto Blackout Screen
        AnimatedVisibility(
            visible = isTiltBlackoutActive && !isManualBlackoutActive,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF1E1E1E),
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Anti-Peep Shield",
                                tint = Color(0xFF66BB6A),
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    Text(
                        text = "Anti-Peep Shield Active",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Side-angle tilt detected.\nHold phone straight towards you to view conversation.",
                        color = Color(0xFFAAAAAA),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
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
                        contentDescription = "Screen Concealed",
                        tint = Color(0xFF333333),
                        modifier = Modifier.size(48.dp)
                    )

                    Text(
                        text = "Screen Concealed",
                        color = Color(0xFF555555),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )

                    Button(
                        onClick = onDismissManualBlackout,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF222222),
                            contentColor = Color(0xFFBBBBBB)
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
