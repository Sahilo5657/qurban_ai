package com.chinar.assistant.coordinator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests validating background voice response lifecycle, mutual exclusion of microphone
 * resources, watchdog recoveries, wake idempotency, and headless standby restoration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundVoiceInteractionTest {

    private var testClockMs = 10_000L
    private lateinit var stateMachine: ConversationStateMachine
    private lateinit var micManager: MicrophoneOwnershipManager

    @Before
    fun setUp() = runTest {
        testClockMs = 10_000L
        stateMachine = ConversationStateMachine(clock = { testClockMs }) { _, _, _ -> }
        micManager = MicrophoneOwnershipManager.getInstance()
        micManager.forceRelease("TEST_SETUP")
    }

    @Test
    fun testStandbyWakeWordReleasesBeforeCommandRecognitionAcquires() = runTest {
        // 1. Initial Standby: Wake detector holds mic
        micManager.acquire(MicrophoneOwner.WAKE_WORD_DETECTOR, "WAKE_1")
        assertEquals(MicrophoneOwner.WAKE_WORD_DETECTOR, micManager.currentOwner.value)

        // 2. Wake event detected: Wake detector releases mic
        micManager.release(MicrophoneOwner.WAKE_WORD_DETECTOR, "WAKE_1")
        assertEquals(MicrophoneOwner.NONE, micManager.currentOwner.value)

        // 3. Command recognition acquires mic exclusively
        micManager.acquire(MicrophoneOwner.COMMAND_RECOGNITION, "WAKE_1")
        assertEquals(MicrophoneOwner.COMMAND_RECOGNITION, micManager.currentOwner.value)

        // 4. Verification that two components cannot hold mic simultaneously
        assertNotEquals(MicrophoneOwner.WAKE_WORD_DETECTOR, micManager.currentOwner.value)
    }

    @Test
    fun testLiveSessionAcquiresMicExclusivelyAndReleasesOnComplete() = runTest {
        // Standby to Live Session transition
        micManager.acquire(MicrophoneOwner.WAKE_WORD_DETECTOR, "TEST")
        micManager.release(MicrophoneOwner.WAKE_WORD_DETECTOR, "TEST")

        micManager.acquire(MicrophoneOwner.LIVE_SESSION, "SESSION_101")
        assertEquals(MicrophoneOwner.LIVE_SESSION, micManager.currentOwner.value)

        // Completion releases mic back to STANDBY owner
        micManager.release(MicrophoneOwner.LIVE_SESSION, "SESSION_101")
        assertEquals(MicrophoneOwner.NONE, micManager.currentOwner.value)

        micManager.acquire(MicrophoneOwner.WAKE_WORD_DETECTOR, "STANDBY_RESUME")
        assertEquals(MicrophoneOwner.WAKE_WORD_DETECTOR, micManager.currentOwner.value)
    }

    @Test
    fun testContentionForcesPreviousOwnerRelease() = runTest {
        var revokeInvoked = false
        micManager.acquire(MicrophoneOwner.WAKE_WORD_DETECTOR, "TEST")

        // Live session acquires while wake detector held it -> triggers revoke
        micManager.acquire(MicrophoneOwner.LIVE_SESSION, "TEST") {
            revokeInvoked = true
        }

        assertTrue(revokeInvoked)
        assertEquals(MicrophoneOwner.LIVE_SESSION, micManager.currentOwner.value)
    }

    @Test
    fun testBackgroundWakeEventIdempotencyDuringActiveSession() {
        // 1. Initial wake detected
        val firstWakeAccepted = stateMachine.onWakeWordDetected("hey chinar", ownerVerificationRequired = false)
        assertTrue(firstWakeAccepted)
        assertEquals(AssistantState.AWAITING_OWNER_COMMAND, stateMachine.currentState)

        // 2. While awaiting command, a duplicate wake event is ignored
        val duplicateWakeAccepted = stateMachine.onWakeWordDetected("hey chinar", ownerVerificationRequired = false)
        assertFalse("Duplicate wake must be suppressed while session is already active", duplicateWakeAccepted)
        assertEquals(AssistantState.AWAITING_OWNER_COMMAND, stateMachine.currentState)
    }

    @Test
    fun testCommandRecognitionTimeoutReturnsAssistantToStandby() = runTest {
        stateMachine.onWakeWordDetected("hey chinar", ownerVerificationRequired = false)
        micManager.acquire(MicrophoneOwner.COMMAND_RECOGNITION, "CMD_SESSION")
        assertEquals(AssistantState.AWAITING_OWNER_COMMAND, stateMachine.currentState)

        // Simulate recognition silence timeout
        stateMachine.onSilenceTimeout(stateMachine.currentSessionId, "command_recognition")
        micManager.release(MicrophoneOwner.COMMAND_RECOGNITION, "CMD_SESSION")

        assertEquals(AssistantState.RETURNING_TO_STANDBY, stateMachine.currentState)
        assertEquals(MicrophoneOwner.NONE, micManager.currentOwner.value)

        // Reset to standby allows wake detector to resume
        stateMachine.resetToStandby()
        micManager.acquire(MicrophoneOwner.WAKE_WORD_DETECTOR, "STANDBY")
        assertEquals(AssistantState.STANDBY, stateMachine.currentState)
        assertEquals(MicrophoneOwner.WAKE_WORD_DETECTOR, micManager.currentOwner.value)
    }

    @Test
    fun testGeminiLiveFailureCleansUpAndReturnsToStandby() = runTest {
        stateMachine.onWakeWordDetected("hey chinar", ownerVerificationRequired = false)
        stateMachine.onOwnerSpeechStarted(stateMachine.currentSessionId)
        stateMachine.onOwnerSpeechEnded(stateMachine.currentSessionId)
        micManager.acquire(MicrophoneOwner.LIVE_SESSION, "GEMINI_SESSION")

        assertEquals(AssistantState.AI_PROCESSING, stateMachine.currentState)

        // Simulate connection or processing error
        stateMachine.onError("WebSocket connection failed", stateMachine.currentSessionId)
        micManager.release(MicrophoneOwner.LIVE_SESSION, "GEMINI_SESSION")

        assertEquals(AssistantState.ERROR, stateMachine.currentState)
        assertEquals(MicrophoneOwner.NONE, micManager.currentOwner.value)

        // Clean standby reset
        stateMachine.resetToStandby()
        assertEquals(AssistantState.STANDBY, stateMachine.currentState)
    }

    @Test
    fun testTtsCompletionReturnsToStandby() = runTest {
        stateMachine.onWakeWordDetected("hey chinar", ownerVerificationRequired = false)
        stateMachine.onOwnerSpeechStarted(stateMachine.currentSessionId)
        stateMachine.onOwnerSpeechEnded(stateMachine.currentSessionId)

        // Move to AI speaking
        stateMachine.onAiSpeakingStarted(stateMachine.currentSessionId)
        assertEquals(AssistantState.AI_SPEAKING, stateMachine.currentState)

        // TTS completes playback aloud (continuous conversation disabled)
        stateMachine.onAiSpeakingFinished(stateMachine.currentSessionId, isContinuousEnabled = false)
        assertEquals(AssistantState.RETURNING_TO_STANDBY, stateMachine.currentState)

        stateMachine.resetToStandby()
        assertEquals(AssistantState.STANDBY, stateMachine.currentState)
    }

    @Test
    fun testRepeatedInteractionsDoNotLeakMicrophoneOwnership() = runTest {
        for (i in 1..5) {
            val sessionTag = "CYCLE_$i"

            // Standby wake
            micManager.acquire(MicrophoneOwner.WAKE_WORD_DETECTOR, sessionTag)
            micManager.release(MicrophoneOwner.WAKE_WORD_DETECTOR, sessionTag)

            // Command recognition / Live session
            micManager.acquire(MicrophoneOwner.LIVE_SESSION, sessionTag)
            assertEquals(MicrophoneOwner.LIVE_SESSION, micManager.currentOwner.value)

            // Playback done & standby reset
            micManager.release(MicrophoneOwner.LIVE_SESSION, sessionTag)
            assertEquals(MicrophoneOwner.NONE, micManager.currentOwner.value)

            micManager.acquire(MicrophoneOwner.WAKE_WORD_DETECTOR, sessionTag)
            assertEquals(MicrophoneOwner.WAKE_WORD_DETECTOR, micManager.currentOwner.value)
            micManager.release(MicrophoneOwner.WAKE_WORD_DETECTOR, sessionTag)
        }

        assertEquals(MicrophoneOwner.NONE, micManager.currentOwner.value)
    }

    @Test
    fun testBackgroundRecoveryDoesNotDependOnMainActivity() = runTest {
        // Verify state machine can recover from any stuck state to standby without external lifecycle
        stateMachine.onWakeWordDetected("hey chinar", ownerVerificationRequired = false)
        assertEquals(AssistantState.AWAITING_OWNER_COMMAND, stateMachine.currentState)

        // Simulate 45s service stuck watchdog in background service
        testClockMs += 45_000L
        stateMachine.resetToStandby()
        micManager.forceRelease("WATCHDOG_RECOVERY")

        assertEquals(AssistantState.STANDBY, stateMachine.currentState)
        assertEquals(MicrophoneOwner.NONE, micManager.currentOwner.value)

        // Can wake again headlessly without MainActivity
        val nextWake = stateMachine.onWakeWordDetected("hey chinar", ownerVerificationRequired = false)
        assertTrue("Must be able to wake again in background without activity restart", nextWake)
    }
}
