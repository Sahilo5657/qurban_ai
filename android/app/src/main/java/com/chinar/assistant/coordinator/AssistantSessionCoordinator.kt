package com.chinar.assistant.coordinator

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.chinar.assistant.actions.ActionDispatcher
import com.chinar.assistant.agent.tools.ToolCallDispatcher
import com.chinar.assistant.ai.GeminiLiveClient
import com.chinar.assistant.ai.LiveVoiceEvent
import com.chinar.assistant.ai.LiveVoiceSession
import com.chinar.assistant.data.AssistantAction
import com.chinar.assistant.data.OwnerProfileStore
import com.chinar.assistant.data.OwnerVoiceProfileStore
import com.chinar.assistant.data.SecurePreferences
import com.chinar.assistant.domain.RuleBasedIntentRouter
import com.chinar.assistant.service.ChinarVoiceService
import com.chinar.assistant.speech.AndroidSpeechRecognizer
import com.chinar.assistant.speech.AndroidTtsEngine
import com.chinar.assistant.speech.SpeakerVerificationEngine
import com.chinar.assistant.speech.SpeakerVerificationResult
import com.chinar.assistant.speech.SpeechRecognizerEngine
import com.chinar.assistant.ui.AssistantUiState
import com.chinar.assistant.ui.ConversationMessage
import com.chinar.assistant.ui.ConversationPhase
import com.chinar.assistant.ui.ListeningState
import com.chinar.assistant.ui.MessageSender
import com.chinar.assistant.ui.overlay.FloatingOverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Decoupled Session Engine for Chinar.
 *
 * Coordinates active voice interactions across both the Full-Screen Activity (MainActivity)
 * and the Floating Assistant Overlay (FloatingOverlayManager).
 *
 * Manages:
 * 1. Live Multimodal Voice WebSocket connection (Gemini Live API) and fallback offline engines.
 * 2. Conversational state, silence timers, and automatic standby resumption.
 * 3. Whole-phone agent tool dispatching and visual state feedback.
 * 4. Mutual exclusion with the 24/7 background wake-word service.
 * 5. Owner-only biometric verification gate (Kaldi x-vectors via Vosk SpeakerModel).
 */
class AssistantSessionCoordinator private constructor(
    val application: Application
) {

    companion object {
        private const val TAG = "SessionCoordinator"

        @Volatile
        private var instance: AssistantSessionCoordinator? = null

        fun getInstance(context: Context): AssistantSessionCoordinator {
            return instance ?: synchronized(this) {
                instance ?: AssistantSessionCoordinator(context.applicationContext as Application).also {
                    instance = it
                }
            }
        }
    }

    val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val securePreferences = SecurePreferences(application)

    val ownerVoiceStore = OwnerVoiceProfileStore(application)
    val ownerProfileStore = OwnerProfileStore(application)
    val speakerVerifier = SpeakerVerificationEngine(application, threshold = securePreferences.ownerVerificationThreshold)
    val micOwnership = MicrophoneOwnershipManager.getInstance()

    val conversationStateMachine = ConversationStateMachine { oldState, newState, ctx ->
        handleStateMachineTransition(oldState, newState, ctx)
    }

    private val _isTrustedSessionActive = MutableStateFlow(false)
    val isTrustedSessionActive: StateFlow<Boolean> = _isTrustedSessionActive.asStateFlow()
    private var trustedSessionEstablishedAtMs: Long = 0L
    private var lastAuthorizedActivityAtMs: Long = 0L

    // Sliding session expiration: 4 hours of complete inactivity
    private val TRUSTED_SESSION_INACTIVITY_TIMEOUT_MS = 4 * 60 * 60 * 1000L

    private val _uiState = MutableStateFlow(
        AssistantUiState(
            hasConfiguredApiKey = securePreferences.hasValidApiKey,
            isWakeWordEnabled = securePreferences.isWakeWordEnabled,
            hasMicPermission = ContextCompat.checkSelfPermission(
                application,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED,
            isTrustedSessionActive = false,
            isOwnerVoiceEnrolled = ownerVoiceStore.isOwnerEnrolled,
            isOwnerOnlyVerificationEnabled = securePreferences.isOwnerOnlyVerificationEnabled
        )
    )
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val router = RuleBasedIntentRouter()
    private val actionDispatcher = ActionDispatcher(application)
    val toolCallDispatcher = ToolCallDispatcher(application).also { dispatcher ->
        // Enforce application-level authorization gate on all whole-phone tools
        dispatcher.authorizationGate = { isOwnerSessionAuthorized() }
    }
    private val speechRecognizer: SpeechRecognizerEngine = AndroidSpeechRecognizer(application)
    private val ttsEngine: AndroidTtsEngine = AndroidTtsEngine(application) { ready ->
        _uiState.update { it.copy(isTtsReady = ready) }
    }
    val liveVoiceSession: LiveVoiceSession = GeminiLiveClient(coordinatorScope, application.applicationContext)

    private var handsFreeSilenceJob: Job? = null
    var conversationPhase: ConversationPhase = ConversationPhase.STANDBY
        private set
    private var lastWakeWordTriggerTime: Long = 0L

    init {
        // Asynchronously initialize SpeakerModel on startup
        coordinatorScope.launch(Dispatchers.IO) {
            speakerVerifier.initialize()
        }

        // Observe wake word events from ChinarVoiceService
        coordinatorScope.launch {
            ChinarVoiceService.wakeWordEvents.collect { keyword ->
                Log.d(TAG, "[WAKE_WORD_EVENT] Wake word '$keyword' received in Coordinator.")
            }
        }

        // Observe service running state
        coordinatorScope.launch {
            ChinarVoiceService.isServiceRunning.collect { isRunning ->
                _uiState.update { it.copy(isWakeWordEnabled = isRunning) }
            }
        }

        // Automatically start wake-word service on startup if enabled in secure preferences
        Log.d(TAG, "[INIT] Wake word check: isWakeWordEnabled=${securePreferences.isWakeWordEnabled}, isOwnerVoiceEnrolled=${securePreferences.isOwnerVoiceEnrolled}")
        if (securePreferences.isWakeWordEnabled && !ChinarVoiceService.isServiceRunning.value) {
            ChinarVoiceService.start(application)
        }
    }

    private fun handleStateMachineTransition(
        oldState: AssistantState,
        newState: AssistantState,
        context: SessionContext
    ) {
        val listeningState = when (newState) {
            AssistantState.STANDBY, AssistantState.RETURNING_TO_STANDBY -> ListeningState.IDLE
            AssistantState.WAKE_WORD_DETECTED, AssistantState.VERIFYING_OWNER -> ListeningState.LISTENING
            AssistantState.AWAITING_OWNER_COMMAND, AssistantState.OWNER_SPEAKING, AssistantState.AWAITING_FOLLOWUP -> ListeningState.LISTENING
            AssistantState.AI_PROCESSING -> ListeningState.PROCESSING
            AssistantState.AI_SPEAKING -> ListeningState.SPEAKING
            AssistantState.ERROR -> ListeningState.ERROR
        }

        val toolStatus = when (newState) {
            AssistantState.VERIFYING_OWNER -> "🔒 Verifying Owner..."
            else -> _uiState.value.activeToolStatus
        }

        _uiState.update {
            it.copy(
                listeningState = listeningState,
                activeToolStatus = toolStatus
            )
        }
    }

    fun startSilenceTimer(phase: ConversationPhase, timeoutMs: Long) {
        cancelSilenceTimer()
        conversationPhase = phase
        val activeSessionId = conversationStateMachine.currentSessionId
        handsFreeSilenceJob = coordinatorScope.launch {
            delay(timeoutMs)
            Log.d(TAG, "[LIFECYCLE] Silence timeout (${timeoutMs}ms) reached in phase $phase without speech. Returning to standby.")
            conversationStateMachine.onSilenceTimeout(activeSessionId, phase.name)
            stopListening()
        }
    }

    fun cancelSilenceTimer() {
        handsFreeSilenceJob?.cancel()
        handsFreeSilenceJob = null
    }

    fun startFromWakeWord(keyword: String = "chinar", triggerAudio: ByteArray? = null) {
        val now = SystemClock.elapsedRealtime()
        if (lastWakeWordTriggerTime > 0L && now - lastWakeWordTriggerTime < 1500L) {
            Log.d(TAG, "[WAKE_WORD_DEBOUNCE] Ignoring duplicate wake word trigger within 1500ms (elapsed=${now - lastWakeWordTriggerTime}ms)")
            ChinarVoiceService.resumeAfterLiveSession(application)
            return
        }
        lastWakeWordTriggerTime = now

        val hasMic = ContextCompat.checkSelfPermission(
            application,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMic) {
            _uiState.update { it.copy(hasMicPermission = true) }
        }

        if (!hasMic || _uiState.value.isLiveSessionActive) {
            Log.d(TAG, "startFromWakeWord skipped (hasMic=$hasMic, isLiveActive=${_uiState.value.isLiveSessionActive})")
            if (!_uiState.value.isLiveSessionActive) {
                ChinarVoiceService.resumeAfterLiveSession(application)
            }
            return
        }

        // Check whether a trusted owner session is currently established and valid
        val isAuthorized = isOwnerSessionAuthorized()
        if (isAuthorized) {
            lastAuthorizedActivityAtMs = System.currentTimeMillis()
        }

        val requiresOwnerVerification = securePreferences.isOwnerOnlyVerificationEnabled &&
                ownerVoiceStore.isOwnerEnrolled &&
                !isAuthorized

        val correlationId = conversationStateMachine.currentSessionId.take(8)
        Log.i(TAG, "[SESSION_$correlationId] WAKE_DETECTED keyword='$keyword', isAuthorized=$isAuthorized, requiresOwnerVerification=$requiresOwnerVerification")

        val accepted = conversationStateMachine.onWakeWordDetected(keyword, requiresOwnerVerification)
        if (!accepted) {
            Log.d(TAG, "[SESSION_$correlationId] [WAKE_WORD_REJECTED] State machine rejected trigger for '$keyword'")
            ChinarVoiceService.resumeAfterLiveSession(application)
            return
        }

        if (requiresOwnerVerification) {
            executeOwnerVerificationFlow(keyword, triggerAudio)
        } else {
            // Trusted owner session active: immediately play acknowledgment tone and start listening
            playAcknowledgmentTone()
            startListening()
        }
    }

    /**
     * Checks whether the current session has valid owner authorization.
     *
     * Security Policy:
     * 1. If owner-only verification is disabled in preferences or owner is not enrolled: returns true.
     * 2. If owner-only verification is enabled: requires an established trusted session that has not expired.
     * 3. Sessions expire after 4 hours of inactivity or upon explicit lock / device restart.
     */
    fun isOwnerSessionAuthorized(): Boolean {
        if (!securePreferences.isOwnerOnlyVerificationEnabled) return true
        if (!ownerVoiceStore.isOwnerEnrolled) return true

        val isActive = _isTrustedSessionActive.value
        if (!isActive) return false

        val now = System.currentTimeMillis()
        if (lastAuthorizedActivityAtMs > 0L && (now - lastAuthorizedActivityAtMs) > TRUSTED_SESSION_INACTIVITY_TIMEOUT_MS) {
            Log.i(TAG, "[TRUSTED_SESSION] Trusted session expired after 4 hours of inactivity. Revoking authorization.")
            lockTrustedSession()
            return false
        }
        return true
    }

    /**
     * Establishes a trusted owner session after successful biometric speaker verification.
     * Authorization persists across subsequent wake-ups, follow-up questions, and normal standby transitions.
     */
    fun establishTrustedSession(score: Float) {
        val now = System.currentTimeMillis()
        trustedSessionEstablishedAtMs = now
        lastAuthorizedActivityAtMs = now
        _isTrustedSessionActive.value = true
        _uiState.update {
            it.copy(
                isTrustedSessionActive = true,
                activeToolStatus = null
            )
        }
        Log.i(TAG, "[TRUSTED_SESSION] Established trusted owner session (score=$score at $now). Authorization will persist across wake-ups and standby.")
    }

    /**
     * Explicitly locks Chinar and clears the trusted session.
     * Once locked, the owner must re-verify their voice before trusted authorization is restored.
     */
    fun lockTrustedSession() {
        Log.i(TAG, "[TRUSTED_SESSION] Explicitly locking Chinar session. Revoking owner authorization.")
        _isTrustedSessionActive.value = false
        trustedSessionEstablishedAtMs = 0L
        lastAuthorizedActivityAtMs = 0L

        cancelSilenceTimer()
        if (_uiState.value.isLiveSessionActive) {
            liveVoiceSession.disconnect()
        }
        speechRecognizer.stopListening()
        ttsEngine.stop()
        conversationStateMachine.resetToStandby()
        FloatingOverlayManager.getInstance(application).hide()
        ChinarVoiceService.resumeAfterLiveSession(application)

        _uiState.update {
            it.copy(
                isTrustedSessionActive = false,
                listeningState = ListeningState.IDLE,
                isLiveSessionActive = false,
                activeToolStatus = "🔒 Locked"
            )
        }
        coordinatorScope.launch {
            delay(1500)
            if (_uiState.value.activeToolStatus == "🔒 Locked") {
                _uiState.update { it.copy(activeToolStatus = null) }
            }
        }
    }

    private fun executeOwnerVerificationFlow(keyword: String, triggerAudio: ByteArray? = null) {
        val sessionId = conversationStateMachine.currentSessionId
        Log.d(TAG, "[OWNER_VERIFICATION_START] Evaluating candidate voice for speaker verification (sessionId=$sessionId, triggerAudioBytes=${triggerAudio?.size ?: 0})")
        _uiState.update { it.copy(activeToolStatus = "🔒 Verifying Owner...") }

        coordinatorScope.launch {
            val enrolledTemplate = ownerVoiceStore.getOwnerTemplate()
            if (enrolledTemplate == null) {
                Log.w(TAG, "[OWNER_VERIFY_FAILED] Missing enrolled template. Failing closed.")
                _isTrustedSessionActive.value = false
                conversationStateMachine.onOwnerVerificationCompleted(sessionId, isMatch = false, score = 0f)
                failClosedAndReturnToStandby()
                return@launch
            }

            // Determine candidate PCM:
            // Check if pre-trigger wake utterance audio has sufficient duration and quality
            val pcmBytes: ByteArray? = if (triggerAudio != null && triggerAudio.isNotEmpty()) {
                val trimmed = SpeakerVerificationEngine.trimSilence(triggerAudio)
                val quality = SpeakerVerificationEngine.validateAudioQuality(
                    trimmed,
                    minDurationSeconds = SpeakerVerificationEngine.MIN_VERIFICATION_DURATION_SECONDS
                )
                if (quality.isValid) {
                    Log.d(TAG, "[VERIFY_AUDIO_SOURCE] Trimmed pre-trigger wake phrase audio valid (${trimmed.size} bytes). Using wake utterance for biometric verification.")
                    trimmed
                } else {
                    Log.d(TAG, "[VERIFY_AUDIO_SOURCE] Using raw pre-trigger audio (${triggerAudio.size} bytes).")
                    triggerAudio
                }
            } else {
                Log.d(TAG, "[VERIFY_AUDIO_SOURCE] Pre-trigger audio absent. Capturing live microphone audio...")
                micOwnership.acquire(MicrophoneOwner.SPEAKER_VERIFIER)
                val livePcm = captureVerificationAudio(durationMs = 1800L)
                micOwnership.release(MicrophoneOwner.SPEAKER_VERIFIER)
                livePcm?.let { SpeakerVerificationEngine.trimSilence(it) }
            }

            if (pcmBytes == null) {
                Log.w(TAG, "[OWNER_VERIFY_FAILED] No audio captured for verification. Failing closed.")
                _isTrustedSessionActive.value = false
                conversationStateMachine.onOwnerVerificationCompleted(sessionId, isMatch = false, score = 0f)
                failClosedAndReturnToStandby()
                return@launch
            }

            // Verify candidate embedding against enrolled template
            val startVerify = SystemClock.elapsedRealtime()
            val result = speakerVerifier.verifySpeaker(pcmBytes, enrolledTemplate)
            val verifyLatency = SystemClock.elapsedRealtime() - startVerify

            when (result) {
                is SpeakerVerificationResult.Match -> {
                    Log.d(TAG, "[OWNER_VERIFIED] Match confirmed: score=${result.score} >= threshold=${result.threshold}, latency=${verifyLatency}ms. Establishing trusted session.")
                    establishTrustedSession(result.score)
                    conversationStateMachine.onOwnerVerificationCompleted(sessionId, isMatch = true, score = result.score)
                    playAcknowledgmentTone()
                    _uiState.update { it.copy(activeToolStatus = null) }
                    startListening()
                }
                is SpeakerVerificationResult.Reject -> {
                    Log.w(TAG, "[OWNER_REJECTED] Impostor or non-owner detected: score=${result.score} < threshold=${result.threshold}, latency=${verifyLatency}ms. Failing closed.")
                    _isTrustedSessionActive.value = false
                    conversationStateMachine.onOwnerVerificationCompleted(sessionId, isMatch = false, score = result.score)
                    _uiState.update { it.copy(activeToolStatus = "🔒 Voice mismatch (${String.format("%.2f", result.score)})") }
                    coordinatorScope.launch {
                        delay(1200)
                        failClosedAndReturnToStandby()
                    }
                }
                is SpeakerVerificationResult.InsufficientQuality -> {
                    Log.w(TAG, "[OWNER_VERIFY_LOW_QUALITY] ${result.reason}, latency=${verifyLatency}ms. Failing closed.")
                    _isTrustedSessionActive.value = false
                    conversationStateMachine.onOwnerVerificationCompleted(sessionId, isMatch = false, score = 0f)
                    _uiState.update { it.copy(activeToolStatus = "⚠️ ${result.reason}") }
                    coordinatorScope.launch {
                        delay(1200)
                        failClosedAndReturnToStandby()
                    }
                }
                is SpeakerVerificationResult.Error -> {
                    Log.e(TAG, "[OWNER_VERIFY_ERROR] ${result.message}, latency=${verifyLatency}ms. Failing closed.")
                    _isTrustedSessionActive.value = false
                    conversationStateMachine.onOwnerVerificationCompleted(sessionId, isMatch = false, score = 0f)
                    failClosedAndReturnToStandby()
                }
            }
        }
    }

    private suspend fun captureVerificationAudio(durationMs: Long): ByteArray? = withContext(Dispatchers.IO) {
        var record: AudioRecord? = null
        try {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, 4096)
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "[VERIFY_CAPTURE] AudioRecord initialization failed")
                return@withContext null
            }

            record.startRecording()
            val totalBytesNeeded = (sampleRate * 2 * (durationMs / 1000.0)).toInt()
            val buffer = ByteArray(totalBytesNeeded)
            var totalRead = 0
            val chunk = ByteArray(2048)
            val startTime = SystemClock.elapsedRealtime()

            while (totalRead < totalBytesNeeded && (SystemClock.elapsedRealtime() - startTime) < (durationMs + 500)) {
                val toRead = minOf(chunk.size, totalBytesNeeded - totalRead)
                val read = record.read(chunk, 0, toRead)
                if (read > 0) {
                    System.arraycopy(chunk, 0, buffer, totalRead, read)
                    totalRead += read
                } else if (read < 0) {
                    break
                }
            }

            if (totalRead >= 3200) {
                buffer.copyOf(totalRead)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "[VERIFY_CAPTURE_ERROR] Error capturing verification PCM", e)
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

    fun resetToStandby() {
        conversationStateMachine.resetToStandby()
        _uiState.update {
            it.copy(
                listeningState = ListeningState.IDLE,
                activeToolStatus = null,
                isLiveSessionActive = false
            )
        }
    }

    private fun failClosedAndReturnToStandby() {
        // Reset state machine to STANDBY so next wake-word trigger is accepted
        resetToStandby()
        FloatingOverlayManager.getInstance(application).hide()
        ChinarVoiceService.resumeAfterLiveSession(application)
        coordinatorScope.launch {
            micOwnership.acquire(MicrophoneOwner.WAKE_WORD_DETECTOR)
        }
    }

    private fun playAcknowledgmentTone() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
            coordinatorScope.launch(Dispatchers.IO) {
                delay(120)
                try {
                    toneGen.release()
                } catch (e: Exception) {
                    // ignore
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed playing acknowledgment tone", e)
        }
    }

    fun openSettings() {
        _uiState.update { it.copy(isSettingsOpen = true) }
    }

    fun closeSettings() {
        _uiState.update { it.copy(isSettingsOpen = false) }
    }

    fun onSettingsSaved() {
        val wasLiveActive = _uiState.value.isLiveSessionActive
        if (wasLiveActive) {
            Log.d(TAG, "Settings saved during active Live session. Reconnecting on next interaction with new voice/settings.")
            liveVoiceSession.disconnect()
        }
        if (securePreferences.isWakeWordEnabled) {
            ChinarVoiceService.start(application)
        } else {
            ChinarVoiceService.stop(application)
        }
        _uiState.update {
            it.copy(
                isSettingsOpen = false,
                hasConfiguredApiKey = securePreferences.hasValidApiKey,
                isWakeWordEnabled = securePreferences.isWakeWordEnabled,
                isLiveSessionActive = false,
                listeningState = ListeningState.IDLE,
                isOwnerVoiceEnrolled = ownerVoiceStore.isOwnerEnrolled,
                isOwnerOnlyVerificationEnabled = securePreferences.isOwnerOnlyVerificationEnabled
            )
        }
    }

    fun updatePermissionStatus(isGranted: Boolean) {
        _uiState.update { it.copy(hasMicPermission = isGranted) }
    }

    fun onMicTapped() {
        when (_uiState.value.listeningState) {
            ListeningState.LISTENING -> {
                stopListening()
            }
            ListeningState.SPEAKING -> {
                stopSpeaking()
            }
            ListeningState.IDLE, ListeningState.ERROR -> {
                startListening()
            }
            ListeningState.PROCESSING -> {
                // Currently parsing/processing, ignore tap to prevent race conditions
            }
        }
    }

    fun startListening() {
        cancelSilenceTimer()
        if (!_uiState.value.hasMicPermission) {
            _uiState.update {
                it.copy(
                    listeningState = ListeningState.ERROR,
                    errorMessage = "Microphone permission is required to listen."
                )
            }
            return
        }

        if (securePreferences.hasValidApiKey) {
            startLiveSession()
        } else {
            startOfflineListening()
        }
    }

    private fun startLiveSession() {
        val correlationId = conversationStateMachine.currentSessionId.take(8)
        val apiKey = securePreferences.geminiApiKey
        if (apiKey.isNullOrBlank()) {
            startOfflineListening()
            return
        }

        ttsEngine.stop()
        ChinarVoiceService.pauseForLiveSession(application)
        coordinatorScope.launch {
            micOwnership.acquire(MicrophoneOwner.LIVE_SESSION, correlationId)
        }

        Log.i(TAG, "[SESSION_$correlationId] COMMAND_LISTENING Starting Gemini Live bidirectional voice session")
        _uiState.update {
            it.copy(
                listeningState = ListeningState.LISTENING,
                isLiveSessionActive = true,
                errorMessage = null,
                activeToolStatus = null
            )
        }

        val ownerProfile = ownerProfileStore.getProfile()
        val activeVoice = securePreferences.getVoiceOption(securePreferences.selectedVoice)
        val systemInstruction = ownerProfile.formatSystemInstructionContext(activeVoice)

        liveVoiceSession.connect(
            apiKey = apiKey,
            model = securePreferences.selectedModel,
            voice = activeVoice.geminiVoiceName,
            systemInstruction = systemInstruction,
            onEvent = { event ->
                handleLiveVoiceEvent(event)
            }
        )
        // Guard against hung connection setup: start a 12s safety timer that gets refreshed upon SetupComplete
        startSilenceTimer(ConversationPhase.AWAITING_INITIAL_REQUEST, 12000L)
    }

    private fun handleLiveVoiceEvent(event: LiveVoiceEvent) {
        val correlationId = conversationStateMachine.currentSessionId.take(8)
        when (event) {
            is LiveVoiceEvent.SetupComplete -> {
                val timeoutMs = securePreferences.initialSilenceTimeoutSeconds * 1000L
                Log.d(TAG, "[SESSION_$correlationId] [LIFECYCLE] LiveVoiceSession setup complete. Ready and listening. Initial silence timeout: ${timeoutMs}ms")
                _uiState.update { it.copy(listeningState = ListeningState.LISTENING, activeToolStatus = null) }
                startSilenceTimer(ConversationPhase.AWAITING_INITIAL_REQUEST, timeoutMs)
            }
            is LiveVoiceEvent.AudioReceived -> {
                if (conversationPhase != ConversationPhase.AI_SPEAKING) {
                    Log.i(TAG, "[SESSION_$correlationId] RESPONSE_RECEIVED (streaming audio chunks)")
                    Log.i(TAG, "[SESSION_$correlationId] TTS_STARTED Realtime audio output playing")
                }
                conversationPhase = ConversationPhase.AI_SPEAKING
                conversationStateMachine.onAiSpeakingStarted(conversationStateMachine.currentSessionId)
                cancelSilenceTimer()
                _uiState.update { it.copy(listeningState = ListeningState.SPEAKING, activeToolStatus = null) }
            }
            is LiveVoiceEvent.Interrupted -> {
                Log.d(TAG, "[SESSION_$correlationId] [LIFECYCLE] Barge-in interruption detected by server VAD")
                conversationPhase = ConversationPhase.USER_SPEAKING
                conversationStateMachine.onUserBargeIn(conversationStateMachine.currentSessionId)
                cancelSilenceTimer()
                _uiState.update { it.copy(listeningState = ListeningState.LISTENING, activeToolStatus = null) }
            }
            is LiveVoiceEvent.GenerationComplete -> {
                Log.d(TAG, "[SESSION_$correlationId] [LIFECYCLE] Generation complete for turn")
            }
            is LiveVoiceEvent.TurnComplete -> {
                Log.d(TAG, "[SESSION_$correlationId] [LIFECYCLE] Model turn complete.")
            }
            is LiveVoiceEvent.PlaybackFinished -> {
                Log.i(TAG, "[SESSION_$correlationId] TTS_COMPLETED Audio playback fully drained")
                conversationStateMachine.onAiSpeakingFinished(
                    conversationStateMachine.currentSessionId,
                    securePreferences.isContinuousConversationEnabled
                )
                if (securePreferences.isContinuousConversationEnabled) {
                    val timeoutMs = securePreferences.followUpSilenceTimeoutSeconds * 1000L
                    Log.d(TAG, "[SESSION_$correlationId] [LIFECYCLE] Audio playback finished. Returning to listening and starting follow-up timer (${timeoutMs}ms).")
                    _uiState.update { it.copy(listeningState = ListeningState.LISTENING, activeToolStatus = null) }
                    startSilenceTimer(ConversationPhase.AWAITING_FOLLOWUP, timeoutMs)
                } else {
                    Log.i(TAG, "[SESSION_$correlationId] STANDBY_READY Audio playback finished. Continuous conversation disabled. Ending session.")
                    stopListening()
                }
            }
            is LiveVoiceEvent.VoiceActivity -> {
                Log.d(TAG, "[SESSION_$correlationId] [LIFECYCLE] VoiceActivity detected: ${event.type}")
                when (event.type.uppercase()) {
                    "ACTIVITY_START" -> {
                        conversationPhase = ConversationPhase.USER_SPEAKING
                        conversationStateMachine.onOwnerSpeechStarted(conversationStateMachine.currentSessionId)
                        cancelSilenceTimer()
                        _uiState.update { it.copy(listeningState = ListeningState.LISTENING, activeToolStatus = null) }
                    }
                    "ACTIVITY_END" -> {
                        Log.i(TAG, "[SESSION_$correlationId] COMMAND_RECEIVED Speech activity ended")
                        Log.i(TAG, "[SESSION_$correlationId] RESPONSE_REQUESTED Waiting for model output")
                        conversationPhase = ConversationPhase.AI_PROCESSING
                        conversationStateMachine.onOwnerSpeechEnded(conversationStateMachine.currentSessionId)
                        _uiState.update { it.copy(listeningState = ListeningState.PROCESSING) }
                        // Start 15s processing watchdog while waiting for model response
                        startSilenceTimer(ConversationPhase.AI_PROCESSING, 15000L)
                    }
                }
            }
            is LiveVoiceEvent.TextReceived -> {
                _uiState.update { current ->
                    val lastMessage = current.conversationHistory.lastOrNull()
                    if (lastMessage != null && lastMessage.sender == MessageSender.CHINAR) {
                        val updatedLast = lastMessage.copy(text = lastMessage.text + event.text)
                        val historyWithoutLast = current.conversationHistory.dropLast(1)
                        current.copy(
                            conversationHistory = historyWithoutLast + updatedLast,
                            assistantResponse = current.assistantResponse + event.text
                        )
                    } else {
                        val newMsg = ConversationMessage(sender = MessageSender.CHINAR, text = event.text)
                        current.copy(
                            conversationHistory = current.conversationHistory + newMsg,
                            assistantResponse = event.text
                        )
                    }
                }
            }
            is LiveVoiceEvent.ToolCallReceived -> {
                cancelSilenceTimer()
                handleToolCall(event)
            }
            is LiveVoiceEvent.Error -> {
                Log.e(TAG, "[SESSION_$correlationId] [LIFECYCLE] LiveVoiceSession error: ${event.message}")
                cancelSilenceTimer()
                conversationPhase = ConversationPhase.STANDBY
                conversationStateMachine.onError(event.message, conversationStateMachine.currentSessionId)
                coordinatorScope.launch { micOwnership.release(MicrophoneOwner.LIVE_SESSION, correlationId) }
                _uiState.update {
                    it.copy(
                        listeningState = ListeningState.ERROR,
                        errorMessage = event.message,
                        isLiveSessionActive = false,
                        activeToolStatus = null
                    )
                }
                // Speak audible error notification in background so user is never left in dark silence
                val spokenError = "I couldn't reach the voice service. Please check your network connection."
                Log.i(TAG, "[SESSION_$correlationId] TTS_STARTED Playing failure announcement aloud")
                ttsEngine.speak(spokenError) {
                    Log.i(TAG, "[SESSION_$correlationId] TTS_COMPLETED Failure announcement finished")
                    stopListening()
                    Log.i(TAG, "[SESSION_$correlationId] STANDBY_READY Returned to standby listening")
                }
            }
            is LiveVoiceEvent.Closed -> {
                cancelSilenceTimer()
                conversationPhase = ConversationPhase.STANDBY
                conversationStateMachine.cancel("Live session closed")
                coordinatorScope.launch { micOwnership.release(MicrophoneOwner.LIVE_SESSION, correlationId) }
                val wasActive = _uiState.value.isLiveSessionActive
                _uiState.update {
                    it.copy(
                        listeningState = ListeningState.IDLE,
                        isLiveSessionActive = false,
                        activeToolStatus = null
                    )
                }
                FloatingOverlayManager.getInstance(application).hide()
                if (wasActive) {
                    ChinarVoiceService.resumeAfterLiveSession(application)
                }
                Log.i(TAG, "[SESSION_$correlationId] STANDBY_READY Live session closed, returned to standby")
            }
        }
    }

    private fun handleToolCall(toolCall: LiveVoiceEvent.ToolCallReceived) {
        coordinatorScope.launch {
            try {
                Log.d(TAG, "[AGENT_TOOL] Processing agent tool call: ${toolCall.functionName} (callId=${toolCall.callId})")
                val friendlyToolName = toolCall.functionName.replace("_", " ").replaceFirstChar { it.uppercase() }
                _uiState.update { it.copy(activeToolStatus = "⚡ $friendlyToolName") }

                val responseObj = toolCallDispatcher.dispatchToolCall(
                    callId = toolCall.callId,
                    functionName = toolCall.functionName,
                    argumentsJson = toolCall.argumentsJson
                )
                liveVoiceSession.sendToolResponse(toolCall.callId, responseObj.toString())

                val msg = responseObj.optString("message", responseObj.optString("status", "Executed"))
                val toolNotice = ConversationMessage(
                    sender = MessageSender.CHINAR,
                    text = "⚡ [Agent: ${toolCall.functionName}] $msg"
                )
                _uiState.update {
                    it.copy(
                        conversationHistory = it.conversationHistory + toolNotice,
                        activeToolStatus = null
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "[AGENT_TOOL] Error executing tool call ${toolCall.functionName}", e)
                val errorObj = JSONObject().apply {
                    put("status", "error")
                    put("message", e.message ?: "Execution failed")
                }
                liveVoiceSession.sendToolResponse(toolCall.callId, errorObj.toString())
                _uiState.update { it.copy(activeToolStatus = null) }
            }
        }
    }

    private fun startOfflineListening() {
        ttsEngine.stop()
        ChinarVoiceService.pauseForLiveSession(application)
        val correlationId = conversationStateMachine.currentSessionId.take(8)
        Log.i(TAG, "[SESSION_$correlationId] COMMAND_LISTENING Starting offline speech recognition")
        coordinatorScope.launch {
            micOwnership.acquire(MicrophoneOwner.COMMAND_RECOGNITION, correlationId)
        }

        _uiState.update {
            it.copy(
                listeningState = ListeningState.LISTENING,
                isLiveSessionActive = false,
                recognizedText = "",
                errorMessage = null,
                audioRms = 0f,
                activeToolStatus = null
            )
        }

        speechRecognizer.startListening(
            onResult = { recognizedText ->
                coordinatorScope.launch {
                    micOwnership.release(MicrophoneOwner.COMMAND_RECOGNITION, correlationId)
                    Log.i(TAG, "[SESSION_$correlationId] COMMAND_RECEIVED '$recognizedText'")
                    processRecognizedSpeech(recognizedText)
                }
            },
            onError = { error ->
                Log.w(TAG, "[SESSION_$correlationId] Offline recognition error: $error")
                coordinatorScope.launch {
                    micOwnership.release(MicrophoneOwner.COMMAND_RECOGNITION, correlationId)
                }
                conversationStateMachine.onError(error, conversationStateMachine.currentSessionId)
                _uiState.update {
                    it.copy(
                        listeningState = ListeningState.ERROR,
                        errorMessage = error,
                        activeToolStatus = null
                    )
                }
                ttsEngine.speak("I didn't catch that. Please try again.") {
                    stopListening()
                }
            },
            onRmsChanged = { rms ->
                _uiState.update { it.copy(audioRms = rms) }
            }
        )
    }

    fun executeTextCommand(commandText: String) {
        val query = commandText.trim()
        if (query.isEmpty()) return

        if (query.equals("lock qurban", ignoreCase = true) ||
            query.equals("lock chinar", ignoreCase = true) ||
            query.equals("lock session", ignoreCase = true) ||
            query.equals("lock assistant", ignoreCase = true)
        ) {
            lockTrustedSession()
            ttsEngine.speak("Qurban session locked. Voice verification will be required on next wake up.") {}
            return
        }

        if (_uiState.value.isLiveSessionActive) {
            val userMsg = ConversationMessage(sender = MessageSender.USER, text = query)
            _uiState.update { it.copy(conversationHistory = it.conversationHistory + userMsg) }
            liveVoiceSession.sendText(query)
        } else {
            processRecognizedSpeech(query)
        }
    }

    fun sendTextMessage(text: String) = executeTextCommand(text)

    private fun processRecognizedSpeech(text: String) {
        val correlationId = conversationStateMachine.currentSessionId.take(8)
        val userQuery = text.trim()
        if (userQuery.isEmpty()) {
            _uiState.update { it.copy(listeningState = ListeningState.IDLE, activeToolStatus = null) }
            FloatingOverlayManager.getInstance(application).hide()
            ChinarVoiceService.resumeAfterLiveSession(application)
            return
        }

        Log.i(TAG, "[SESSION_$correlationId] COMMAND_RECEIVED query='$userQuery'")
        Log.i(TAG, "[SESSION_$correlationId] RESPONSE_REQUESTED Dispatching intent routing")

        _uiState.update {
            it.copy(
                listeningState = ListeningState.PROCESSING,
                recognizedText = userQuery,
                audioRms = 0f
            )
        }

        coordinatorScope.launch {
            val intentResult = router.route(userQuery)
            val actionResult = actionDispatcher.dispatch(intentResult)
            val feedbackText = actionResult.message

            Log.i(TAG, "[SESSION_$correlationId] RESPONSE_RECEIVED (feedback='$feedbackText')")

            val userMessage = ConversationMessage(sender = MessageSender.USER, text = userQuery)
            val assistantMessage = ConversationMessage(sender = MessageSender.CHINAR, text = feedbackText)

            _uiState.update { current ->
                current.copy(
                    listeningState = ListeningState.SPEAKING,
                    assistantResponse = feedbackText,
                    lastAction = intentResult.action,
                    conversationHistory = current.conversationHistory + userMessage + assistantMessage,
                    activeToolStatus = null
                )
            }

            Log.i(TAG, "[SESSION_$correlationId] TTS_STARTED Playing response aloud")
            conversationStateMachine.onAiSpeakingStarted(conversationStateMachine.currentSessionId)
            ttsEngine.speak(feedbackText) {
                coordinatorScope.launch {
                    Log.i(TAG, "[SESSION_$correlationId] TTS_COMPLETED Spoken feedback playback finished")
                    _uiState.update {
                        if (it.listeningState == ListeningState.SPEAKING) {
                            it.copy(listeningState = ListeningState.IDLE)
                        } else {
                            it
                        }
                    }
                    conversationPhase = ConversationPhase.STANDBY
                    conversationStateMachine.onAiSpeakingFinished(conversationStateMachine.currentSessionId, isContinuousEnabled = false)
                    FloatingOverlayManager.getInstance(application).hide()
                    ChinarVoiceService.resumeAfterLiveSession(application)
                    Log.i(TAG, "[SESSION_$correlationId] STANDBY_READY Returned to wake-word standby")
                }
            }
        }
    }

    fun stopSpeaking() {
        val correlationId = conversationStateMachine.currentSessionId.take(8)
        cancelSilenceTimer()
        conversationPhase = ConversationPhase.STANDBY
        conversationStateMachine.cancel("Stop speaking")
        coordinatorScope.launch {
            micOwnership.release(MicrophoneOwner.LIVE_SESSION, correlationId)
            micOwnership.release(MicrophoneOwner.COMMAND_RECOGNITION, correlationId)
        }
        ttsEngine.stop()
        if (_uiState.value.isLiveSessionActive) {
            liveVoiceSession.disconnect()
        }
        _uiState.update { it.copy(listeningState = ListeningState.IDLE) }
        FloatingOverlayManager.getInstance(application).hide()
        ChinarVoiceService.resumeAfterLiveSession(application)
        Log.i(TAG, "[SESSION_$correlationId] STANDBY_READY stopSpeaking completed, resumed standby")
    }

    fun stopListening() {
        val correlationId = conversationStateMachine.currentSessionId.take(8)
        cancelSilenceTimer()
        conversationPhase = ConversationPhase.STANDBY
        conversationStateMachine.cancel("Stop listening")
        coordinatorScope.launch {
            micOwnership.release(MicrophoneOwner.LIVE_SESSION, correlationId)
            micOwnership.release(MicrophoneOwner.COMMAND_RECOGNITION, correlationId)
        }
        val wasLiveActive = _uiState.value.isLiveSessionActive
        _uiState.update {
            it.copy(
                listeningState = ListeningState.IDLE,
                isLiveSessionActive = false,
                activeToolStatus = null,
                audioRms = 0f
            )
        }
        if (wasLiveActive) {
            liveVoiceSession.disconnect()
        }
        speechRecognizer.stopListening()
        ttsEngine.stop()
        FloatingOverlayManager.getInstance(application).hide()
        ChinarVoiceService.resumeAfterLiveSession(application)
        Log.i(TAG, "[SESSION_$correlationId] STANDBY_READY stopListening completed, resumed standby")
    }

    fun clearHistory() {
        _uiState.update {
            it.copy(
                conversationHistory = emptyList(),
                recognizedText = "",
                assistantResponse = "",
                errorMessage = null,
                activeToolStatus = null
            )
        }
    }
}
