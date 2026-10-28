package com.chinar.assistant.coordinator

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Authoritative explicit conversation states for Chinar personal AI assistant.
 *
 * Enforces strict single-state lifecycle:
 * STANDBY -> WAKE_WORD_DETECTED -> VERIFYING_OWNER -> AWAITING_OWNER_COMMAND -> OWNER_SPEAKING
 * -> AI_PROCESSING -> AI_SPEAKING -> AWAITING_FOLLOWUP -> RETURNING_TO_STANDBY -> STANDBY
 */
enum class AssistantState {
    STANDBY,
    WAKE_WORD_DETECTED,
    VERIFYING_OWNER,
    AWAITING_OWNER_COMMAND,
    OWNER_SPEAKING,
    AI_PROCESSING,
    AI_SPEAKING,
    AWAITING_FOLLOWUP,
    RETURNING_TO_STANDBY,
    ERROR
}

/**
 * Encapsulates an active or completed interaction turn with unique session tracking
 * to prevent stale callbacks from previous conversations.
 */
data class SessionContext(
    val sessionId: String = UUID.randomUUID().toString(),
    val state: AssistantState = AssistantState.STANDBY,
    val isOwnerVerified: Boolean = false,
    val wakeKeyword: String? = null,
    val startedAtElapsedMs: Long = System.currentTimeMillis(),
    val verificationScore: Float = 0f,
    val errorMessage: String? = null
)

/**
 * Thread-safe authoritative State Machine governing Chinar's conversational turn lifecycle,
 * owner-verification gates, and mutual exclusion.
 */
class ConversationStateMachine(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onStateChanged: (oldState: AssistantState, newState: AssistantState, context: SessionContext) -> Unit
) {

    companion object {
        private const val TAG = "ConversationSM"
        private const val WAKE_WORD_DEBOUNCE_MS = 1500L
    }

    private val _sessionContext = MutableStateFlow(SessionContext(startedAtElapsedMs = clock()))
    val sessionContext: StateFlow<SessionContext> = _sessionContext.asStateFlow()

    val currentState: AssistantState
        get() = _sessionContext.value.state

    val currentSessionId: String
        get() = _sessionContext.value.sessionId

    val isOwnerVerified: Boolean
        get() = _sessionContext.value.isOwnerVerified

    private var lastWakeWordTriggerElapsedMs: Long = 0L

    @Synchronized
    fun onWakeWordDetected(keyword: String, ownerVerificationRequired: Boolean): Boolean {
        val now = clock()
        if (lastWakeWordTriggerElapsedMs > 0L && (now - lastWakeWordTriggerElapsedMs) < WAKE_WORD_DEBOUNCE_MS) {
            Log.d(TAG, "[WAKE_DEBOUNCE] Ignoring duplicate wake word trigger within ${WAKE_WORD_DEBOUNCE_MS}ms")
            return false
        }

        // Only trigger from STANDBY or RETURNING_TO_STANDBY
        val current = _sessionContext.value.state
        if (current != AssistantState.STANDBY && current != AssistantState.RETURNING_TO_STANDBY && current != AssistantState.ERROR) {
            Log.d(TAG, "[WAKE_IGNORED] Wake word ignored; current state is $current")
            return false
        }

        lastWakeWordTriggerElapsedMs = now
        val newSessionId = UUID.randomUUID().toString()
        val nextState = if (ownerVerificationRequired) {
            AssistantState.VERIFYING_OWNER
        } else {
            AssistantState.AWAITING_OWNER_COMMAND
        }

        val oldState = current
        val newContext = SessionContext(
            sessionId = newSessionId,
            state = nextState,
            isOwnerVerified = !ownerVerificationRequired,
            wakeKeyword = keyword,
            startedAtElapsedMs = now
        )

        _sessionContext.value = newContext
        Log.d(TAG, "[TRANSITION] $oldState -> $nextState (sessionId=$newSessionId, keyword='$keyword', ownerVerificationReq=$ownerVerificationRequired)")
        onStateChanged(oldState, nextState, newContext)
        return true
    }

    @Synchronized
    fun onOwnerVerificationCompleted(
        sessionId: String,
        isMatch: Boolean,
        score: Float
    ): Boolean {
        val current = _sessionContext.value
        if (current.sessionId != sessionId) {
            Log.w(TAG, "[STALE_CALLBACK] Verification result received for stale session $sessionId (active=${current.sessionId})")
            return false
        }

        if (current.state != AssistantState.VERIFYING_OWNER) {
            Log.w(TAG, "[INVALID_TRANSITION] Verification result received in invalid state: ${current.state}")
            return false
        }

        val oldState = current.state
        if (isMatch) {
            val nextState = AssistantState.AWAITING_OWNER_COMMAND
            val updated = current.copy(
                state = nextState,
                isOwnerVerified = true,
                verificationScore = score
            )
            _sessionContext.value = updated
            Log.d(TAG, "[TRANSITION] $oldState -> $nextState: Enrolled Owner VERIFIED (score=$score)")
            onStateChanged(oldState, nextState, updated)
            return true
        } else {
            // Fail closed: Silent transition back to standby, zero cloud streaming
            val nextState = AssistantState.RETURNING_TO_STANDBY
            val updated = current.copy(
                state = nextState,
                isOwnerVerified = false,
                verificationScore = score,
                errorMessage = "Owner verification failed (score=$score)"
            )
            _sessionContext.value = updated
            Log.d(TAG, "[TRANSITION] $oldState -> $nextState: Non-owner or uncertain match rejected (score=$score). Returning to quiet standby.")
            onStateChanged(oldState, nextState, updated)
            return false
        }
    }

    @Synchronized
    fun onOwnerSpeechStarted(sessionId: String): Boolean {
        val current = _sessionContext.value
        if (current.sessionId != sessionId) return false
        if (current.state != AssistantState.AWAITING_OWNER_COMMAND && current.state != AssistantState.AWAITING_FOLLOWUP) {
            return false
        }

        val oldState = current.state
        val nextState = AssistantState.OWNER_SPEAKING
        val updated = current.copy(state = nextState)
        _sessionContext.value = updated
        Log.d(TAG, "[TRANSITION] $oldState -> $nextState")
        onStateChanged(oldState, nextState, updated)
        return true
    }

    @Synchronized
    fun onOwnerSpeechEnded(sessionId: String): Boolean {
        val current = _sessionContext.value
        if (current.sessionId != sessionId) return false
        if (current.state != AssistantState.OWNER_SPEAKING) return false

        val oldState = current.state
        val nextState = AssistantState.AI_PROCESSING
        val updated = current.copy(state = nextState)
        _sessionContext.value = updated
        Log.d(TAG, "[TRANSITION] $oldState -> $nextState")
        onStateChanged(oldState, nextState, updated)
        return true
    }

    @Synchronized
    fun onAiSpeakingStarted(sessionId: String): Boolean {
        val current = _sessionContext.value
        if (current.sessionId != sessionId) return false
        if (current.state != AssistantState.AI_PROCESSING && current.state != AssistantState.OWNER_SPEAKING) {
            return false
        }

        val oldState = current.state
        val nextState = AssistantState.AI_SPEAKING
        val updated = current.copy(state = nextState)
        _sessionContext.value = updated
        Log.d(TAG, "[TRANSITION] $oldState -> $nextState")
        onStateChanged(oldState, nextState, updated)
        return true
    }

    @Synchronized
    fun onAiSpeakingFinished(sessionId: String, isContinuousEnabled: Boolean): Boolean {
        val current = _sessionContext.value
        if (current.sessionId != sessionId) return false
        if (current.state != AssistantState.AI_SPEAKING) return false

        val oldState = current.state
        val nextState = if (isContinuousEnabled) {
            AssistantState.AWAITING_FOLLOWUP
        } else {
            AssistantState.RETURNING_TO_STANDBY
        }

        val updated = current.copy(state = nextState)
        _sessionContext.value = updated
        Log.d(TAG, "[TRANSITION] $oldState -> $nextState (isContinuous=$isContinuousEnabled)")
        onStateChanged(oldState, nextState, updated)
        return true
    }

    @Synchronized
    fun onUserBargeIn(sessionId: String): Boolean {
        val current = _sessionContext.value
        if (current.sessionId != sessionId) return false
        if (current.state != AssistantState.AI_SPEAKING) return false

        val oldState = current.state
        val nextState = AssistantState.OWNER_SPEAKING
        val updated = current.copy(state = nextState)
        _sessionContext.value = updated
        Log.d(TAG, "[TRANSITION] $oldState -> $nextState (Barge-in detected)")
        onStateChanged(oldState, nextState, updated)
        return true
    }

    @Synchronized
    fun onSilenceTimeout(sessionId: String, phaseDescription: String): Boolean {
        val current = _sessionContext.value
        if (current.sessionId != sessionId) return false

        val oldState = current.state
        val nextState = AssistantState.RETURNING_TO_STANDBY
        val updated = current.copy(state = nextState)
        _sessionContext.value = updated
        Log.d(TAG, "[TRANSITION] $oldState -> $nextState (Silence timeout in $phaseDescription)")
        onStateChanged(oldState, nextState, updated)
        return true
    }

    @Synchronized
    fun cancel(reason: String): SessionContext {
        val current = _sessionContext.value
        val oldState = current.state
        val nextState = AssistantState.RETURNING_TO_STANDBY
        val updated = current.copy(
            state = nextState,
            errorMessage = "Cancelled: $reason"
        )
        _sessionContext.value = updated
        Log.d(TAG, "[CANCEL] $oldState -> $nextState (reason: $reason)")
        onStateChanged(oldState, nextState, updated)
        return updated
    }

    @Synchronized
    fun resetToStandby(): SessionContext {
        val current = _sessionContext.value
        val oldState = current.state
        val nextState = AssistantState.STANDBY
        val updated = SessionContext(
            sessionId = UUID.randomUUID().toString(),
            state = nextState,
            isOwnerVerified = false,
            startedAtElapsedMs = clock()
        )
        _sessionContext.value = updated
        Log.d(TAG, "[RESET] $oldState -> $nextState")
        onStateChanged(oldState, nextState, updated)
        return updated
    }

    @Synchronized
    fun onError(errorMessage: String, sessionId: String? = null) {
        val current = _sessionContext.value
        if (sessionId != null && current.sessionId != sessionId) return

        val oldState = current.state
        val nextState = AssistantState.ERROR
        val updated = current.copy(
            state = nextState,
            errorMessage = errorMessage
        )
        _sessionContext.value = updated
        Log.e(TAG, "[ERROR] $oldState -> $nextState: $errorMessage")
        onStateChanged(oldState, nextState, updated)
    }
}
