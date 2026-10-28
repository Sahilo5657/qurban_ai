package com.chinar.assistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.chinar.assistant.data.OwnerProfile
import com.chinar.assistant.data.OwnerProfileStore
import com.chinar.assistant.data.OwnerVoiceProfileStore
import com.chinar.assistant.data.SecurePreferences
import com.chinar.assistant.speech.WakeWordPhrases

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    securePreferences: SecurePreferences,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    isTrustedSessionActive: Boolean = false,
    onLockSession: (() -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ownerVoiceStore = remember { OwnerVoiceProfileStore(context) }
    val ownerProfileStore = remember { OwnerProfileStore(context) }

    var apiKey by remember { mutableStateOf(securePreferences.geminiApiKey ?: "") }
    var isApiKeyVisible by remember { mutableStateOf(false) }
    var isWakeWordEnabled by remember { mutableStateOf(securePreferences.isWakeWordEnabled) }
    var isContinuousConversationEnabled by remember { mutableStateOf(securePreferences.isContinuousConversationEnabled) }
    var initialSilenceTimeout by remember { mutableStateOf(securePreferences.initialSilenceTimeoutSeconds.toFloat()) }
    var followUpSilenceTimeout by remember { mutableStateOf(securePreferences.followUpSilenceTimeoutSeconds.toFloat()) }
    var selectedVoice by remember { mutableStateOf(securePreferences.selectedVoice) }
    var selectedModel by remember { mutableStateOf(securePreferences.selectedModel) }

    var isOwnerVoiceEnrolled by remember { mutableStateOf(ownerVoiceStore.isOwnerEnrolled) }
    var isOwnerOnlyVerificationEnabled by remember { mutableStateOf(securePreferences.isOwnerOnlyVerificationEnabled) }
    var showEnrollmentDialog by remember { mutableStateOf(false) }
    var ownerProfile by remember { mutableStateOf(ownerProfileStore.getProfile()) }

    var isAccessibilityEnabled by remember {
        mutableStateOf(com.chinar.assistant.accessibility.ChinarAccessibilityService.isAccessibilityEnabled(context))
    }
    var isAccessibilityRunning by remember {
        mutableStateOf(com.chinar.assistant.accessibility.ChinarAccessibilityService.isAccessibilityRunning())
    }
    var isRestrictedBlocked by remember {
        mutableStateOf(com.chinar.assistant.accessibility.ChinarAccessibilityService.isRestrictedSettingsBlocked(context))
    }
    var isOverlayPermissionEnabled by remember {
        mutableStateOf(com.chinar.assistant.ui.overlay.FloatingOverlayManager.canDrawOverlays(context))
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = com.chinar.assistant.accessibility.ChinarAccessibilityService.isAccessibilityEnabled(context)
                isAccessibilityRunning = com.chinar.assistant.accessibility.ChinarAccessibilityService.isAccessibilityRunning()
                isRestrictedBlocked = com.chinar.assistant.accessibility.ChinarAccessibilityService.isRestrictedSettingsBlocked(context)
                isOverlayPermissionEnabled = com.chinar.assistant.ui.overlay.FloatingOverlayManager.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val availableModels = listOf("gemini-3.8-live")
    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Qurban Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Scrollable Body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                ) {
                    // API Key Section
                    Text(
                        text = "Conversational AI Key",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Enter your Google AI Studio Gemini API key to enable real-time conversational reasoning.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("Gemini API Key") },
                        placeholder = { Text("AIzaSy...") },
                        singleLine = true,
                        visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                                Icon(
                                    imageVector = if (isApiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (isApiKeyVisible) "Hide key" else "Show key"
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { }),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Security Transparency Notice
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp).padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Security Notice: Encrypted locally via Android Keystore. This reduces exposure risk, but on-device keys can still be extracted from memory if a device is compromised.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                lineHeight = 14.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Whole-Phone Agent Accessibility Section
                    Text(
                        text = "Whole-Phone Agent Control",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Allows Qurban to observe app interfaces, click buttons, enter text, and complete multi-step tasks across your phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = when {
                            isAccessibilityRunning -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            isAccessibilityEnabled -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
                            else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = when {
                                        isAccessibilityRunning -> Icons.Default.CheckCircle
                                        isAccessibilityEnabled -> Icons.Default.Info
                                        else -> Icons.Default.Warning
                                    },
                                    contentDescription = null,
                                    tint = when {
                                        isAccessibilityRunning -> MaterialTheme.colorScheme.primary
                                        isAccessibilityEnabled -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.error
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = when {
                                        isAccessibilityRunning -> "Accessibility Service: Enabled & Active"
                                        isAccessibilityEnabled -> "Accessibility Service: Enabled (Pending Connection)"
                                        else -> "Accessibility Service: Not Enabled"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        isAccessibilityRunning -> MaterialTheme.colorScheme.primary
                                        isAccessibilityEnabled -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.error
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = when {
                                    isAccessibilityRunning ->
                                        "Qurban Phone Agent is actively running and permitted to inspect UI, navigate apps, and execute voice actions."
                                    isAccessibilityEnabled ->
                                        "Service is enabled in Android Settings. If actions do not respond, toggle the service Off and On in Accessibility Settings to reconnect."
                                    else ->
                                        "To enable hands-free whole-phone automation, follow these steps:\n" +
                                        "1. Tap 'Open Accessibility' below.\n" +
                                        "2. On OnePlus / ColorOS, select the 'General' tab, then tap 'Downloaded apps'.\n" +
                                        "3. Tap 'Qurban Phone Agent' and turn the switch ON.\n" +
                                        "4. Tap 'Allow' on Android's confirmation prompt."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )

                            if (!isAccessibilityRunning && isRestrictedBlocked) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = "⚠️ Restricted Setting Alert (Android 13+)",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "If Android displays 'Restricted setting: For your security, this setting is currently unavailable':\n" +
                                                "• Tap 'Open App Info' below.\n" +
                                                "• In App Info, tap the top-right menu (⋮).\n" +
                                                "• Tap 'Allow restricted settings', approve phone PIN/biometrics, then return here to enable the service.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        com.chinar.assistant.accessibility.ChinarAccessibilityService.openAccessibilitySettings(context)
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isAccessibilityRunning) "Open Settings" else "Open Accessibility",
                                        maxLines = 1
                                    )
                                }

                                if (!isAccessibilityRunning) {
                                    OutlinedButton(
                                        onClick = {
                                            com.chinar.assistant.accessibility.ChinarAccessibilityService.openAppInfoSettings(context)
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "App Info",
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Floating Overlay Permission Section
                    Text(
                        text = "Floating Assistant Overlay",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Enables Qurban to display a floating voice capsule over YouTube, Browser, and Settings without covering your screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isOverlayPermissionEnabled)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = if (isOverlayPermissionEnabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (isOverlayPermissionEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isOverlayPermissionEnabled) "Display Over Other Apps: Granted" else "Display Over Other Apps: Not Granted",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isOverlayPermissionEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (isOverlayPermissionEnabled)
                                    "Qurban will appear as a compact floating voice pill when activated via wake-word, allowing you to view and control underlying apps simultaneously."
                                else
                                    "When granted, Qurban will appear as a sleek floating capsule instead of taking over the full screen. Tap below to enable in Android settings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = {
                                    val intent = android.content.Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isOverlayPermissionEnabled) "Manage Overlay Permission" else "Enable Display Over Other Apps")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Owner Voice Biometrics Section
                    Text(
                        text = "Owner Voice Biometrics",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Verify speaker identity on-device using Kaldi deep x-vectors. Unrecognized speakers and ambient conversations are rejected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isOwnerVoiceEnrolled)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = if (isOwnerVoiceEnrolled) Icons.Default.VerifiedUser else Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = if (isOwnerVoiceEnrolled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isOwnerVoiceEnrolled) "Owner Voice: Enrolled (Sahil Khan)" else "Owner Voice: Not Enrolled",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isOwnerVoiceEnrolled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (isOwnerVoiceEnrolled)
                                    "Averaged 128-dimensional biometric voice template is securely stored in Android Keystore encrypted storage. Raw audio is never kept."
                                else
                                    "Enroll your voice so Qurban only responds to you. Without enrollment, owner-only protection is inactive.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { showEnrollmentDialog = true },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (isOwnerVoiceEnrolled) "Re-enroll Voice" else "Enroll Voice")
                                }
                                if (isOwnerVoiceEnrolled) {
                                    OutlinedButton(
                                        onClick = {
                                            ownerVoiceStore.deleteOwnerProfile()
                                            isOwnerVoiceEnrolled = false
                                            isOwnerOnlyVerificationEnabled = false
                                            onLockSession?.invoke()
                                        },
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Delete")
                                    }
                                }
                            }

                            if (isOwnerVoiceEnrolled) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isTrustedSessionActive)
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (isTrustedSessionActive) Icons.Default.LockOpen else Icons.Default.Lock,
                                            contentDescription = null,
                                            tint = if (isTrustedSessionActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (isTrustedSessionActive) "Session: Authorized" else "Session: Locked",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isTrustedSessionActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                            )
                                            Text(
                                                text = if (isTrustedSessionActive)
                                                    "Voice verified. Wake-up triggers will not repeat verification."
                                                else
                                                    "Verification required on next wake-up.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 11.sp
                                            )
                                        }
                                        if (isTrustedSessionActive && onLockSession != null) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            OutlinedButton(
                                                onClick = { onLockSession() },
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Lock", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Owner-Only Verification Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enforce Owner-Only Access",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (isOwnerVoiceEnrolled)
                                    "Strict biometric gating. Fails closed on strangers, TV audio, or uncertain matches."
                                else
                                    "Requires voice enrollment before enabling.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isOwnerOnlyVerificationEnabled && isOwnerVoiceEnrolled,
                            enabled = isOwnerVoiceEnrolled,
                            onCheckedChange = { isOwnerOnlyVerificationEnabled = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Owner Profile / Personal Background Section
                    Text(
                        text = "Personal Assistant Profile",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Owner background context used by Qurban for personalized assistance. Kept strictly separate from biometric templates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(text = "Owner: ${ownerProfile.basicIdentity.ownerName} | Assistant: ${ownerProfile.basicIdentity.assistantName}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = "Roots: ${ownerProfile.rootsAndEducation.birthplace}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(text = "Education: ${ownerProfile.rootsAndEducation.university}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(text = "Career: ${ownerProfile.professionalExperience.currentRole}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(text = "Thesis: ${ownerProfile.professionalExperience.thesisTitle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(text = "Goal: ${ownerProfile.currentGoal.primaryGoal}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    ownerProfile = ownerProfileStore.resetToDefault()
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Reset to Defaults", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Supported Wake Phrases Card
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Configured Wake Phrases:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "\"Qurban\" • \"Haa Qurban\" • \"Hey Qurban\" • \"A Qurban\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Hands-Free Wake Word Section
                    Text(
                        text = "Hands-Free Activation",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Listen for \"Qurban\" / \"Hey Qurban\"",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Local keyword spotting. Requires microphone foreground service with persistent status bar notification.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isWakeWordEnabled,
                            onCheckedChange = { isWakeWordEnabled = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // ColorOS Battery Policy Notice
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp).padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "OnePlus / ColorOS Note: For hands-free listening with the screen off, enable 'Allow background activity' in Settings > Battery > App Battery Management > Qurban.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 14.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Continuous Conversation Section
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Continuous Conversation",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Keep microphone open after Qurban responds for follow-up questions without repeating the wake word.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isContinuousConversationEnabled,
                            onCheckedChange = { isContinuousConversationEnabled = it }
                        )
                    }

                    if (isContinuousConversationEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Follow-up Silence Window",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${followUpSilenceTimeout.toInt()}s",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Slider(
                            value = followUpSilenceTimeout,
                            onValueChange = { followUpSilenceTimeout = it },
                            valueRange = 3f..30f,
                            steps = 26
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Initial Request Silence Window",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${initialSilenceTimeout.toInt()}s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = initialSilenceTimeout,
                        onValueChange = { initialSilenceTimeout = it },
                        valueRange = 5f..30f,
                        steps = 24
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Voice Selection
                    Text(
                        text = "Assistant Voice",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Select a conversational voice personality for Gemini Live real-time audio responses.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SecurePreferences.AVAILABLE_VOICES.chunked(2).forEach { rowVoices ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowVoices.forEach { voice ->
                                    FilterChip(
                                        selected = selectedVoice == voice.name,
                                        onClick = { selectedVoice = voice.name },
                                        label = {
                                            Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                                Text(
                                                    text = "${voice.iconEmoji} ${voice.name}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    text = voice.description,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    maxLines = 1,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (rowVoices.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Note: Voice selection applies to new Gemini Live sessions. Offline fallback commands use the device's native TTS engine, which may sound different from Gemini's live generated voice.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                            lineHeight = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Model Selection
                    Text(
                        text = "Real-Time AI Model",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Official Google Multimodal Live API model for low-latency bidirectional voice reasoning.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        availableModels.forEach { model ->
                            FilterChip(
                                selected = selectedModel == model,
                                onClick = { selectedModel = model },
                                label = { Text(model) }
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            securePreferences.geminiApiKey = apiKey
                            securePreferences.isWakeWordEnabled = isWakeWordEnabled
                            securePreferences.isContinuousConversationEnabled = isContinuousConversationEnabled
                            securePreferences.initialSilenceTimeoutSeconds = initialSilenceTimeout.toInt()
                            securePreferences.followUpSilenceTimeoutSeconds = followUpSilenceTimeout.toInt()
                            securePreferences.selectedVoice = selectedVoice
                            securePreferences.selectedModel = selectedModel
                            securePreferences.isOwnerOnlyVerificationEnabled = isOwnerOnlyVerificationEnabled
                            onSaved()
                        }
                    ) {
                        Text("Save Settings")
                    }
                }
            }
        }
    }

    if (showEnrollmentDialog) {
        VoiceEnrollmentDialog(
            onDismiss = { showEnrollmentDialog = false },
            onEnrollmentComplete = {
                isOwnerVoiceEnrolled = ownerVoiceStore.isOwnerEnrolled
                isOwnerOnlyVerificationEnabled = securePreferences.isOwnerOnlyVerificationEnabled
            }
        )
    }
}
