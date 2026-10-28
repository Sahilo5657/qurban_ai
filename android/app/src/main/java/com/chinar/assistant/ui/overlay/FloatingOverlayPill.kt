package com.chinar.assistant.ui.overlay

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chinar.assistant.R
import com.chinar.assistant.coordinator.AssistantSessionCoordinator
import com.chinar.assistant.ui.ListeningState

/**
 * Modern, non-intrusive floating voice capsule displayed over any active Android application.
 *
 * Provides real-time visual feedback of voice capture, agent tool execution,
 * and spoken responses without covering the screen.
 */
@Composable
fun FloatingOverlayPill(
    coordinator: AssistantSessionCoordinator,
    onDismiss: () -> Unit,
    onExpand: () -> Unit
) {
    val uiState by coordinator.uiState.collectAsState()

    // Pulse animation for avatar & neon glow border
    val infiniteTransition = rememberInfiniteTransition(label = "overlay_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (uiState.listeningState == ListeningState.LISTENING || uiState.listeningState == ListeningState.SPEAKING) 1.15f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    // Dynamic accent color depending on agent & speech state
    val targetBorderColor = when {
        uiState.activeToolStatus != null -> Color(0xFFA6E3A1) // Emerald (Agent Tool Execution)
        uiState.listeningState == ListeningState.LISTENING -> Color(0xFF89B4FA) // Cyan (Listening)
        uiState.listeningState == ListeningState.PROCESSING -> Color(0xFFF9E2AF) // Amber (Reasoning)
        uiState.listeningState == ListeningState.SPEAKING -> Color(0xFFCBA6F7) // Mauve (Speaking)
        uiState.listeningState == ListeningState.ERROR -> Color(0xFFF38BA8) // Red (Error)
        else -> Color(0xFF585B70)
    }

    val animatedBorderColor by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = tween(durationMillis = 300),
        label = "border_color"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = Color(0xF2181825), // Translucent dark glass
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.5.dp,
                    color = animatedBorderColor,
                    shape = RoundedCornerShape(26.dp)
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Chinar Pulsing Avatar
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(Color(0xFF11111B))
                        .border(1.dp, animatedBorderColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.splash_icon),
                        contentDescription = "Qurban Avatar",
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // 2. Info & Live Subtitles
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Qurban",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFCDD6F4)
                        )
                        Spacer(modifier = Modifier.width(6.dp))

                        // Status Badge
                        val badgeText = when {
                            uiState.activeToolStatus != null -> uiState.activeToolStatus!!
                            uiState.listeningState == ListeningState.LISTENING -> "Listening"
                            uiState.listeningState == ListeningState.PROCESSING -> "Thinking..."
                            uiState.listeningState == ListeningState.SPEAKING -> "Speaking"
                            uiState.listeningState == ListeningState.ERROR -> "Error"
                            else -> "Standby"
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = animatedBorderColor.copy(alpha = 0.2f),
                            modifier = Modifier.padding(start = 2.dp)
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = animatedBorderColor,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Live subtitle
                    val subtitleText = when {
                        uiState.activeToolStatus != null -> "Executing whole-phone action..."
                        uiState.listeningState == ListeningState.SPEAKING && uiState.assistantResponse.isNotBlank() ->
                            uiState.assistantResponse.takeLast(70)
                        uiState.recognizedText.isNotBlank() ->
                            uiState.recognizedText.takeLast(70)
                        uiState.errorMessage != null ->
                            uiState.errorMessage!!
                        uiState.listeningState == ListeningState.LISTENING ->
                            "Listening to your voice..."
                        else ->
                            "How can I help you?"
                    }

                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFA6ADC8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 11.5.sp
                    )
                }

                // 3. Action Controls
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Tap to Mute / Stop Speaking
                    if (uiState.listeningState == ListeningState.SPEAKING) {
                        IconButton(
                            onClick = { coordinator.stopSpeaking() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop Speaking",
                                tint = Color(0xFFF38BA8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Quick Lock if trusted session is active
                    if (uiState.isTrustedSessionActive) {
                        IconButton(
                            onClick = { coordinator.lockTrustedSession() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Lock Session",
                                tint = Color(0xFFBAC2DE),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Expand to full app
                    IconButton(
                        onClick = onExpand,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Expand to App",
                            tint = Color(0xFFBAC2DE),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Dismiss / Cancel turn
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Overlay",
                            tint = Color(0xFFBAC2DE),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
