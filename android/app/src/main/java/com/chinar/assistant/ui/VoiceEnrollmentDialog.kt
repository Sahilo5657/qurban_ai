package com.chinar.assistant.ui

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.chinar.assistant.data.OwnerVoiceProfileStore
import com.chinar.assistant.speech.AudioQualityReport
import com.chinar.assistant.speech.SpeakerVerificationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 3-Prompt Guided Voice Enrollment Dialog for Sahil Khan.
 *
 * Captures clean, validated speech samples to generate a 128-dimensional Kaldi x-vector
 * biometric template stored securely on-device.
 */
@Composable
fun VoiceEnrollmentDialog(
    onDismiss: () -> Unit,
    onEnrollmentComplete: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val ownerVoiceStore = remember { OwnerVoiceProfileStore(context) }
    val speakerVerifier = remember { SpeakerVerificationEngine(context) }

    val enrollmentPrompts = listOf(
        "\"Hey Qurban, check my schedule.\"",
        "\"Qurban, turn on the flashlight.\"",
        "\"Hey Qurban, how is my thesis going?\""
    )

    var currentStep by remember { mutableStateOf(0) }
    val capturedEmbeddings = remember { mutableStateListOf<FloatArray>() }
    var isRecording by remember { mutableStateOf(false) }
    var recordingProgress by remember { mutableStateOf(0f) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf(false) }
    var isEngineInitializing by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            speakerVerifier.initialize()
        }
        isEngineInitializing = false
    }

    Dialog(
        onDismissRequest = { if (!isRecording) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Owner Voice Enrollment",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (isEngineInitializing) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Initializing on-device speaker model...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (isSuccess) {
                    // Success View
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Enrollment Successful!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Qurban has learned your voice biometrics. It will now respond only to you when wake-word activation is used.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = {
                            onEnrollmentComplete()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Done")
                    }
                } else {
                    // Enrollment In-Progress View
                    Text(
                        text = "Step ${currentStep + 1} of 3",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = { (currentStep + (if (isRecording) recordingProgress else 0f)) / 3f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Please read this phrase aloud:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = enrollmentPrompts[currentStep],
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Feedback Message
                    AnimatedVisibility(visible = feedbackMessage != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = feedbackMessage ?: "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Record Button / Recording Indicator
                    if (isRecording) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                progress = { recordingProgress },
                                modifier = Modifier.size(56.dp),
                                strokeWidth = 5.dp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Listening... Speak naturally",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                feedbackMessage = null
                                isRecording = true
                                recordingProgress = 0f
                                coroutineScope.launch {
                                    val pcm = recordSample(
                                        onProgress = { recordingProgress = it }
                                    )
                                    isRecording = false

                                    if (pcm == null) {
                                        feedbackMessage = "Failed to capture audio. Please ensure microphone permissions are granted."
                                        return@launch
                                    }

                                    // Validate quality with enrollment thresholds (>= 0.8s)
                                    val quality = SpeakerVerificationEngine.validateAudioQuality(
                                        pcm,
                                        minDurationSeconds = SpeakerVerificationEngine.MIN_ENROLLMENT_DURATION_SECONDS
                                    )
                                    if (!quality.isValid) {
                                        feedbackMessage = quality.reason
                                        return@launch
                                    }

                                    // Extract embedding from trimmed speech
                                    val trimmed = SpeakerVerificationEngine.trimSilence(pcm)
                                    val embedding = speakerVerifier.extractEmbedding(trimmed)
                                    if (embedding == null) {
                                        feedbackMessage = "Could not extract voice features. Please try again."
                                        return@launch
                                    }

                                    capturedEmbeddings.add(embedding)

                                    if (currentStep < 2) {
                                        currentStep++
                                    } else {
                                        // Finalize enrollment template
                                        val template = SpeakerVerificationEngine.computeAverageTemplate(capturedEmbeddings)
                                        if (template != null) {
                                            ownerVoiceStore.saveOwnerTemplate(template, sampleCount = 3)
                                            isSuccess = true
                                        } else {
                                            feedbackMessage = "Failed to generate biometric template. Please retry."
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Mic, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Record Phrase ${currentStep + 1}")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    TextButton(
                        onClick = onDismiss,
                        enabled = !isRecording
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

private suspend fun recordSample(onProgress: (Float) -> Unit): ByteArray? = withContext(Dispatchers.IO) {
    var record: AudioRecord? = null
    try {
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, 4096)
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            return@withContext null
        }

        record.startRecording()
        val durationMs = 2200L
        val totalBytes = (sampleRate * 2 * (durationMs / 1000.0)).toInt()
        val buffer = ByteArray(totalBytes)
        var bytesRead = 0
        val chunk = ByteArray(2048)
        val start = SystemClock.elapsedRealtime()

        while (bytesRead < totalBytes) {
            val toRead = minOf(chunk.size, totalBytes - bytesRead)
            val n = record.read(chunk, 0, toRead)
            if (n > 0) {
                System.arraycopy(chunk, 0, buffer, bytesRead, n)
                bytesRead += n
                val elapsed = SystemClock.elapsedRealtime() - start
                withContext(Dispatchers.Main) {
                    onProgress((elapsed.toFloat() / durationMs).coerceIn(0f, 1f))
                }
            } else if (n < 0) {
                break
            }
        }

        if (bytesRead >= 3200) {
            buffer.copyOf(bytesRead)
        } else {
            null
        }
    } catch (e: Exception) {
        Log.e("VoiceEnrollment", "Error recording enrollment sample", e)
        null
    } finally {
        try {
            if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
            record?.release()
        } catch (e: Exception) {
            // ignore
        }
    }
}
