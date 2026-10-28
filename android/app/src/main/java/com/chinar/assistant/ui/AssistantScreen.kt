package com.chinar.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.res.painterResource
import com.chinar.assistant.R
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    viewModel: AssistantViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var textInput by remember { mutableStateOf("") }

    // Runtime Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.updatePermissionStatus(isGranted)
    }

    LaunchedEffect(Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.updatePermissionStatus(hasPermission)
    }

    // Auto-scroll conversation to bottom
    LaunchedEffect(uiState.conversationHistory.size) {
        if (uiState.conversationHistory.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(uiState.conversationHistory.size - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.splash_icon),
                                contentDescription = "Qurban Logo",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Qurban",
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            Text(
                                text = "OnePlus Ace 2V",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                actions = {
                    if (uiState.isOwnerVoiceEnrolled && uiState.isOwnerOnlyVerificationEnabled) {
                        IconButton(
                            onClick = {
                                if (uiState.isTrustedSessionActive) {
                                    viewModel.lockTrustedSession()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (uiState.isTrustedSessionActive) Icons.Default.LockOpen else Icons.Default.Lock,
                                contentDescription = if (uiState.isTrustedSessionActive) "Session Authorized (Tap to Lock)" else "Session Locked",
                                tint = if (uiState.isTrustedSessionActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.openSettings() }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Qurban Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (uiState.conversationHistory.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearHistory() }) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Clear conversation",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Error / Permission Notice Banner
            AnimatedVisibility(
                visible = !uiState.hasMicPermission || uiState.errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (!uiState.hasMicPermission) "Microphone access required" else (uiState.errorMessage ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        if (!uiState.hasMicPermission) {
                            TextButton(
                                onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                            ) {
                                Text("Grant", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Conversation Messages Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (uiState.conversationHistory.isEmpty()) {
                    // Empty state / Guide
                    EmptyStateGuide(
                        hasPermission = uiState.hasMicPermission,
                        onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onChipClicked = { viewModel.executeTextCommand(it) }
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        items(uiState.conversationHistory, key = { it.id }) { message ->
                            MessageBubble(message = message)
                        }
                    }
                }
            }

            // Live State Indicator Bar
            ListeningStateBar(uiState = uiState, onStopSpeaking = { viewModel.stopSpeaking() })

            // Quick Suggestions Row
            QuickSuggestionsRow(onSelected = { viewModel.executeTextCommand(it) })

            // Bottom Control Area (Tap-to-talk Mic + Text fallback)
            BottomControlSection(
                uiState = uiState,
                textInput = textInput,
                onTextInputChanged = { textInput = it },
                onSendText = {
                    if (textInput.isNotBlank()) {
                        viewModel.executeTextCommand(textInput)
                        textInput = ""
                    }
                },
                onMicTapped = {
                    if (!uiState.hasMicPermission) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.onMicTapped()
                    }
                }
            )
        }
    }

    if (uiState.isSettingsOpen) {
        SettingsDialog(
            securePreferences = viewModel.securePreferences,
            onDismiss = { viewModel.closeSettings() },
            onSaved = { viewModel.onSettingsSaved() },
            isTrustedSessionActive = uiState.isTrustedSessionActive,
            onLockSession = { viewModel.lockTrustedSession() }
        )
    }
}

@Composable
fun MessageBubble(message: ConversationMessage) {
    val isUser = message.sender == MessageSender.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Surface(
                shape = CircleShape,
                color = Color.Black,
                modifier = Modifier.size(32.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.splash_icon),
                    contentDescription = "Qurban",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = message.text,
                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
fun EmptyStateGuide(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onChipClicked: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Black,
            modifier = Modifier.size(96.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(id = R.drawable.splash_icon),
                    contentDescription = "Qurban Assistant",
                    modifier = Modifier.size(80.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Welcome to Qurban",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Tap the microphone below and speak a command, or select a sample action:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))

        val examples = listOf("What time is it?", "What is today's date?", "Turn on flashlight", "Help")
        examples.forEach { query ->
            OutlinedButton(
                onClick = { onChipClicked(query) },
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(query)
            }
        }
    }
}

@Composable
fun ListeningStateBar(
    uiState: AssistantUiState,
    onStopSpeaking: () -> Unit
) {
    val stateText = if (uiState.isLiveSessionActive) {
        when (uiState.listeningState) {
            ListeningState.IDLE -> "Gemini Live • Ready"
            ListeningState.LISTENING -> "Gemini Live • Listening (Speak naturally)"
            ListeningState.PROCESSING -> "Gemini Live • Processing audio..."
            ListeningState.SPEAKING -> "Gemini Live • Speaking (Speak to interrupt)"
            ListeningState.ERROR -> "Gemini Live • Connection Error"
        }
    } else {
        when (uiState.listeningState) {
            ListeningState.IDLE -> if (uiState.hasConfiguredApiKey) "Gemini Live Ready • Tap mic" else "Offline Rule Mode • Tap mic"
            ListeningState.LISTENING -> "Listening..."
            ListeningState.PROCESSING -> "Analyzing intent..."
            ListeningState.SPEAKING -> "Speaking response..."
            ListeningState.ERROR -> "Error encountered"
        }
    }

    val stateColor by animateColorAsState(
        targetValue = when (uiState.listeningState) {
            ListeningState.LISTENING -> MaterialTheme.colorScheme.primary
            ListeningState.PROCESSING -> MaterialTheme.colorScheme.tertiary
            ListeningState.SPEAKING -> Color(0xFFA6E3A1) // Soft green
            ListeningState.ERROR -> MaterialTheme.colorScheme.error
            ListeningState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "stateColor"
    )

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(stateColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stateText,
                    style = MaterialTheme.typography.labelMedium,
                    color = stateColor,
                    fontWeight = FontWeight.Medium
                )
            }

            if (uiState.listeningState == ListeningState.SPEAKING) {
                TextButton(
                    onClick = onStopSpeaking,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop speaking",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Stop", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun QuickSuggestionsRow(onSelected: (String) -> Unit) {
    val scrollState = rememberScrollState()
    val suggestions = listOf(
        "What time is it?",
        "What is today's date?",
        "Turn on flashlight",
        "Turn off flashlight",
        "Open YouTube",
        "Check battery",
        "Set a timer for 5 minutes",
        "Help"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        suggestions.forEach { suggestion ->
            SuggestionChip(
                onClick = { onSelected(suggestion) },
                label = { Text(suggestion, style = MaterialTheme.typography.labelSmall) },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ),
                border = null
            )
        }
    }
}

@Composable
fun BottomControlSection(
    uiState: AssistantUiState,
    textInput: String,
    onTextInputChanged: (String) -> Unit,
    onSendText: () -> Unit,
    onMicTapped: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Text Input Box (Fallback input)
            OutlinedTextField(
                value = textInput,
                onValueChange = onTextInputChanged,
                placeholder = {
                    Text(
                        text = if (uiState.listeningState == ListeningState.LISTENING) "Listening..." else "Type or tap mic...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                trailingIcon = {
                    if (textInput.isNotBlank()) {
                        IconButton(onClick = onSendText) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send command",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Tap-to-Talk Animated Mic Button
            AnimatedMicButton(
                listeningState = uiState.listeningState,
                audioRms = uiState.audioRms,
                onClick = onMicTapped
            )
        }
    }
}

@Composable
fun AnimatedMicButton(
    listeningState: ListeningState,
    audioRms: Float,
    onClick: () -> Unit
) {
    val isListening = listeningState == ListeningState.LISTENING

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isListening) 1.25f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val buttonColor by animateColorAsState(
        targetValue = when (listeningState) {
            ListeningState.LISTENING -> MaterialTheme.colorScheme.error
            ListeningState.SPEAKING -> Color(0xFFA6E3A1) // Soft green
            ListeningState.PROCESSING -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        label = "buttonColor"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(56.dp)
    ) {
        if (isListening) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(buttonColor.copy(alpha = 0.25f))
            )
        }

        FloatingActionButton(
            onClick = onClick,
            shape = CircleShape,
            containerColor = buttonColor,
            contentColor = when (listeningState) {
                ListeningState.SPEAKING -> Color(0xFF11111B)
                else -> MaterialTheme.colorScheme.onPrimary
            },
            modifier = Modifier.size(52.dp)
        ) {
            when (listeningState) {
                ListeningState.LISTENING -> {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop listening",
                        modifier = Modifier.size(26.dp)
                    )
                }
                ListeningState.PROCESSING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onTertiary,
                        strokeWidth = 2.5.dp
                    )
                }
                ListeningState.SPEAKING -> {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Speaking",
                        modifier = Modifier.size(26.dp)
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Tap to talk",
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}
