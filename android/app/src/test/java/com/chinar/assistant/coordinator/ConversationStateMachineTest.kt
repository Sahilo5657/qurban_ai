package com.chinar.assistant.coordinator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConversationStateMachineTest {

    private lateinit var stateMachine: ConversationStateMachine
    private val transitions = mutableListOf<Triple<AssistantState, AssistantState, SessionContext>>()

    @Before
    fun setUp() {
        transitions.clear()
        stateMachine = ConversationStateMachine { oldState, newState, ctx ->
            transitions.add(Triple(oldState, newState, ctx))
        }
    }

    @Test
    fun testInitialStateIsStandby() {
        assertEquals(AssistantState.STANDBY, stateMachine.currentState)
        assertFalse(stateMachine.isOwnerVerified)
    }

    @Test
    fun testWakeWordWithVerificationRequiredTransitionsToVerifyingOwner() {
        val accepted = stateMachine.onWakeWordDetected("chinar", ownerVerificationRequired = true)
        assertTrue(accepted)
        assertEquals(AssistantState.VERIFYING_OWNER, stateMachine.currentState)
        assertFalse(stateMachine.isOwnerVerified)
        assertEquals("chinar", stateMachine.sessionContext.value.wakeKeyword)
    }

    @Test
    fun testWakeWordWithoutVerificationTransitionsDirectlyToAwaitingCommand() {
        val accepted = stateMachine.onWakeWordDetected("hey chinar", ownerVerificationRequired = false)
        assertTrue(accepted)
        assertEquals(AssistantState.AWAITING_OWNER_COMMAND, stateMachine.currentState)
        assertTrue(stateMachine.isOwnerVerified)
    }

    @Test
    fun testDuplicateWakeWordWithinDebounceWindowIsSuppressed() {
        var currentTime = 1000L
        val sm = ConversationStateMachine(clock = { currentTime }) { _, _, _ -> }
        val first = sm.onWakeWordDetected("chinar", ownerVerificationRequired = false)
        assertTrue(first)

        // Reset to standby to test rapid trigger
        sm.resetToStandby()

        // Immediate repeat at currentTime + 500ms (within 1500ms debounce window)
        currentTime = 1500L
        val second = sm.onWakeWordDetected("chinar", ownerVerificationRequired = false)
        assertFalse(second)

        // Repeat after debounce window (currentTime + 2000ms = 3500L)
        currentTime = 3500L
        val third = sm.onWakeWordDetected("chinar", ownerVerificationRequired = false)
        assertTrue(third)
    }

    @Test
    fun testOwnerVerificationMatchAdvancesToAwaitingCommand() {
        stateMachine.onWakeWordDetected("chinar", ownerVerificationRequired = true)
        val activeSessionId = stateMachine.currentSessionId

        val verified = stateMachine.onOwnerVerificationCompleted(
            sessionId = activeSessionId,
            isMatch = true,
            score = 0.82f
        )

        assertTrue(verified)
        assertEquals(AssistantState.AWAITING_OWNER_COMMAND, stateMachine.currentState)
        assertTrue(stateMachine.isOwnerVerified)
        assertEquals(0.82f, stateMachine.sessionContext.value.verificationScore, 0.001f)
    }

    @Test
    fun testOwnerVerificationFailClosesAndReturnsToStandby() {
        stateMachine.onWakeWordDetected("chinar", ownerVerificationRequired = true)
        val activeSessionId = stateMachine.currentSessionId

        val verified = stateMachine.onOwnerVerificationCompleted(
            sessionId = activeSessionId,
            isMatch = false,
            score = 0.35f
        )

        assertFalse(verified)
        // Must fail closed directly to RETURNING_TO_STANDBY
        assertEquals(AssistantState.RETURNING_TO_STANDBY, stateMachine.currentState)
        assertFalse(stateMachine.isOwnerVerified)
    }

    @Test
    fun testStaleVerificationCallbackIsDiscarded() {
        stateMachine.onWakeWordDetected("chinar", ownerVerificationRequired = true)
        val oldSessionId = "old-stale-session-id"

        val result = stateMachine.onOwnerVerificationCompleted(
            sessionId = oldSessionId,
            isMatch = true,
            score = 0.95f
        )

        assertFalse(result)
        // State remains VERIFYING_OWNER for the active session
        assertEquals(AssistantState.VERIFYING_OWNER, stateMachine.currentState)
    }

    @Test
    fun testFullTurnLifecycleToCompletion() {
        stateMachine.onWakeWordDetected("hey chinar", ownerVerificationRequired = false)
        val sid = stateMachine.currentSessionId

        // Owner starts speaking command
        assertTrue(stateMachine.onOwnerSpeechStarted(sid))
        assertEquals(AssistantState.OWNER_SPEAKING, stateMachine.currentState)

        // Owner finishes speaking command
        assertTrue(stateMachine.onOwnerSpeechEnded(sid))
        assertEquals(AssistantState.AI_PROCESSING, stateMachine.currentState)

        // AI starts speaking
        assertTrue(stateMachine.onAiSpeakingStarted(sid))
        assertEquals(AssistantState.AI_SPEAKING, stateMachine.currentState)

        // AI finishes speaking with continuous conversation disabled -> return to standby
        assertTrue(stateMachine.onAiSpeakingFinished(sid, isContinuousEnabled = false))
        assertEquals(AssistantState.RETURNING_TO_STANDBY, stateMachine.currentState)
    }

    @Test
    fun testContinuousConversationFollowUpTurn() {
        stateMachine.onWakeWordDetected("hey chinar", ownerVerificationRequired = false)
        val sid = stateMachine.currentSessionId

        stateMachine.onOwnerSpeechStarted(sid)
        stateMachine.onOwnerSpeechEnded(sid)
        stateMachine.onAiSpeakingStarted(sid)

        // AI finishes speaking with continuous conversation enabled -> awaits followup
        assertTrue(stateMachine.onAiSpeakingFinished(sid, isContinuousEnabled = true))
        assertEquals(AssistantState.AWAITING_FOLLOWUP, stateMachine.currentState)

        // Followup turn: Owner speaks again
        assertTrue(stateMachine.onOwnerSpeechStarted(sid))
        assertEquals(AssistantState.OWNER_SPEAKING, stateMachine.currentState)
    }

    @Test
    fun testBargeInDuringAiSpeaking() {
        stateMachine.onWakeWordDetected("hey chinar", ownerVerificationRequired = false)
        val sid = stateMachine.currentSessionId

        stateMachine.onOwnerSpeechStarted(sid)
        stateMachine.onOwnerSpeechEnded(sid)
        stateMachine.onAiSpeakingStarted(sid)

        // User barges in
        assertTrue(stateMachine.onUserBargeIn(sid))
        assertEquals(AssistantState.OWNER_SPEAKING, stateMachine.currentState)
    }

    @Test
    fun testSilenceTimeoutTransitionsToStandby() {
        stateMachine.onWakeWordDetected("chinar", ownerVerificationRequired = false)
        val sid = stateMachine.currentSessionId

        assertTrue(stateMachine.onSilenceTimeout(sid, "Awaiting Initial Command"))
        assertEquals(AssistantState.RETURNING_TO_STANDBY, stateMachine.currentState)
    }

    @Test
    fun testCancellationReleasesToStandby() {
        stateMachine.onWakeWordDetected("chinar", ownerVerificationRequired = true)
        val oldSid = stateMachine.currentSessionId

        val ctx = stateMachine.cancel("User clicked overlay dismiss")
        assertEquals(AssistantState.RETURNING_TO_STANDBY, ctx.state)
        assertEquals(AssistantState.RETURNING_TO_STANDBY, stateMachine.currentState)

        // Reset to standby generates new session id
        val resetCtx = stateMachine.resetToStandby()
        assertEquals(AssistantState.STANDBY, resetCtx.state)
        assertNotEquals(oldSid, resetCtx.sessionId)
    }
}
