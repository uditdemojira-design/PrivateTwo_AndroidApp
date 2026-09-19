package org.privatetwo.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.privatetwo.app.core.security.SecureStorage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    secureStorage: SecureStorage,
    onNavigateBack: () -> Unit,
    onUnpaired: () -> Unit,
    onToggleFlagSecure: (Boolean) -> Unit
) {
    var flagSecure by remember { mutableStateOf(secureStorage.isFlagSecureEnabled) }
    var biometricLock by remember { mutableStateOf(secureStorage.isBiometricLockEnabled) }
    var stealthNotifications by remember { mutableStateOf(secureStorage.isStealthNotificationsEnabled) }
    var showUnpairConfirm by remember { mutableStateOf(false) }

    val localDeviceId = remember { secureStorage.getLocalDeviceId() }
    val peerDeviceId = remember { secureStorage.getPairedPeerDeviceId() ?: "Not Paired" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy & Security") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "SECURITY CONTROLS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            SettingSwitchItem(
                icon = Icons.Default.VisibilityOff,
                title = "Screenshot & Recent Apps Shield",
                subtitle = "Enforces FLAG_SECURE to block screen capture and conceal message previews in recent apps.",
                checked = flagSecure,
                onCheckedChange = {
                    flagSecure = it
                    secureStorage.isFlagSecureEnabled = it
                    onToggleFlagSecure(it)
                }
            )

            SettingSwitchItem(
                icon = Icons.Default.Fingerprint,
                title = "Biometric / App Lock",
                subtitle = "Require fingerprint, face unlock, or device PIN to access conversation.",
                checked = biometricLock,
                onCheckedChange = {
                    biometricLock = it
                    secureStorage.isBiometricLockEnabled = it
                }
            )

            SettingSwitchItem(
                icon = Icons.Default.NotificationsOff,
                title = "Stealth Notifications",
                subtitle = "Always show generic 'New private message' notification with zero sender or content preview.",
                checked = stealthNotifications,
                onCheckedChange = {
                    stealthNotifications = it
                    secureStorage.isStealthNotificationsEnabled = it
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "ANTI-PEEP PRIVACY SHIELD",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            var antiPeepLouver by remember { mutableStateOf(secureStorage.isAntiPeepLouverEnabled) }
            var antiPeepShade by remember { mutableStateOf(secureStorage.isAntiPeepShadeEnabled) }
            var stealthMessages by remember { mutableStateOf(secureStorage.isStealthMessagesEnabled) }
            var antiPeepTilt by remember { mutableStateOf(secureStorage.isAntiPeepTiltEnabled) }

            SettingSwitchItem(
                icon = Icons.Default.Security,
                title = "Side-Angle Blocker (Micro-Louvers)",
                subtitle = "Draws polarized micro-louvers that block off-axis visibility (>25°), preventing bystanders next to you from reading.",
                checked = antiPeepLouver,
                onCheckedChange = {
                    antiPeepLouver = it
                    secureStorage.isAntiPeepLouverEnabled = it
                }
            )

            SettingSwitchItem(
                icon = Icons.Default.Lock,
                title = "Tap-to-Reveal Messages (Stealth Mode)",
                subtitle = "Conceals all messages behind privacy blocks. Only tapped messages reveal for 4 seconds, guaranteeing 100% privacy.",
                checked = stealthMessages,
                onCheckedChange = {
                    stealthMessages = it
                    secureStorage.isStealthMessagesEnabled = it
                }
            )

            SettingSwitchItem(
                icon = Icons.Default.VisibilityOff,
                title = "Reading Curtain (Privacy Shade)",
                subtitle = "Covers the screen with a dark curtain except a movable reading slot you drag with your finger.",
                checked = antiPeepShade,
                onCheckedChange = {
                    antiPeepShade = it
                    secureStorage.isAntiPeepShadeEnabled = it
                }
            )

            SettingSwitchItem(
                icon = Icons.Default.Fingerprint,
                title = "Emergency Heavy-Tilt Blackout",
                subtitle = "Instantly blacks out screen if phone is tilted heavily (>50°) away from you when someone leans over.",
                checked = antiPeepTilt,
                onCheckedChange = {
                    antiPeepTilt = it
                    secureStorage.isAntiPeepTiltEnabled = it
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "CHAT PERSONALIZATION",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            var partnerName by remember { mutableStateOf(secureStorage.getPartnerDisplayName()) }
            var showEditNameDialog by remember { mutableStateOf(false) }
            var editNameInput by remember { mutableStateOf(partnerName ?: "") }

            OutlinedCard(
                onClick = {
                    editNameInput = partnerName ?: ""
                    showEditNameDialog = true
                },
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
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
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text(
                                text = "Partner Display Name",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = partnerName ?: "Not set (displays 'Private Partner')",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary)
                }
            }

            if (showEditNameDialog) {
                AlertDialog(
                    onDismissRequest = { showEditNameDialog = false },
                    title = { Text("Edit Display Name") },
                    text = {
                        OutlinedTextField(
                            value = editNameInput,
                            onValueChange = { editNameInput = it },
                            label = { Text("Display Name") },
                            placeholder = { Text("e.g. Rahul, Priya") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            val trimmed = editNameInput.trim()
                            if (trimmed.isNotBlank()) {
                                secureStorage.setPartnerDisplayName(trimmed)
                                partnerName = trimmed
                            } else {
                                secureStorage.setPartnerDisplayName(null)
                                partnerName = null
                            }
                            showEditNameDialog = false
                        }) {
                            Text("Save")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showEditNameDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "CRYPTOGRAPHIC IDENTITIES",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Local Device Fingerprint",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = localDeviceId,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Paired Partner Fingerprint",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = peerDeviceId,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Policy: Strictly 1-to-1 (MAX_PAIRED_DEVICES = 2)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "DESTRUCTIVE ZONE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )

            OutlinedButton(
                onClick = { showUnpairConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.LinkOff, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Unpair Device")
            }
        }

        if (showUnpairConfirm) {
            AlertDialog(
                onDismissRequest = { showUnpairConfirm = false },
                title = { Text("Unpair Device?") },
                text = {
                    Text(
                        "Unpairing will immediately invalidate all cryptographic trust, revoke session keys, and permanently delete local conversation data. A new pairing handshake will be required to communicate."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showUnpairConfirm = false
                            secureStorage.unpairDevice()
                            onUnpaired()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Unpair and Wipe Data")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUnpairConfirm = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun SettingSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
