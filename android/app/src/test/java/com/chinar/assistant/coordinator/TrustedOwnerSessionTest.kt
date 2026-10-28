package com.chinar.assistant.coordinator

import com.chinar.assistant.agent.tools.ToolCallDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests validating the Trusted Owner Session architecture for Chinar:
 * 1. Initial wake-up requires verification when enrolled.
 * 2. Successful verification establishes trusted session.
 * 3. Consecutive wake-ups reuse trusted session without repeating biometric verification.
 * 4. Normal standby transitions preserve the trusted session.
 * 5. Explicit locking revokes authorization and requires re-verification on next wake-up.
 * 6. ToolCallDispatcher authorization gate blocks sensitive tools when locked.
 * 7. Inactivity sliding timeout expires stale sessions.
 */
class TrustedOwnerSessionTest {

    private lateinit var stateMachine: ConversationStateMachine

    // Simulated session manager mirroring AssistantSessionCoordinator logic
    private val _isTrustedSessionActive = MutableStateFlow(false)
    val isTrustedSessionActive: StateFlow<Boolean> = _isTrustedSessionActive.asStateFlow()
    private var trustedSessionEstablishedAtMs: Long = 0L
    private var lastAuthorizedActivityAtMs: Long = 0L
    private val sessionInactivityTimeoutMs = 4 * 60 * 60 * 1000L // 4 hours

    private var isOwnerOnlyVerificationEnabled = true
    private var isOwnerEnrolled = true
    private var currentTimeMs = 1_000_000L

    private fun isOwnerSessionAuthorized(): Boolean {
        if (!isOwnerOnlyVerificationEnabled) return true
        if (!isOwnerEnrolled) return true

        val isActive = _isTrustedSessionActive.value
        if (!isActive) return false

        val now = currentTimeMs
        if (lastAuthorizedActivityAtMs > 0L && (now - lastAuthorizedActivityAtMs) > sessionInactivityTimeoutMs) {
            lockTrustedSession()
            return false
        }
        return true
    }

    private fun establishTrustedSession(score: Float) {
        trustedSessionEstablishedAtMs = currentTimeMs
        lastAuthorizedActivityAtMs = currentTimeMs
        _isTrustedSessionActive.value = true
    }

    private fun lockTrustedSession() {
        _isTrustedSessionActive.value = false
        trustedSessionEstablishedAtMs = 0L
        lastAuthorizedActivityAtMs = 0L
        stateMachine.resetToStandby()
    }

    @Before
    fun setUp() {
        currentTimeMs = 1_000_000L
        _isTrustedSessionActive.value = false
        trustedSessionEstablishedAtMs = 0L
        lastAuthorizedActivityAtMs = 0L
        isOwnerOnlyVerificationEnabled = true
        isOwnerEnrolled = true

        stateMachine = ConversationStateMachine(clock = { currentTimeMs }) { _, _, _ -> }
    }

    @Test
    fun testInitialWakeUpRequiresVerification() {
        assertFalse("Initial session must be unverified/locked", isOwnerSessionAuthorized())

        val isAuthorized = isOwnerSessionAuthorized()
        val requiresVerification = isOwnerOnlyVerificationEnabled && isOwnerEnrolled && !isAuthorized
        assertTrue("Initial wake-up must require verification", requiresVerification)

        val accepted = stateMachine.onWakeWordDetected("hey chinar", requiresVerification)
        assertTrue(accepted)
        assertEquals(AssistantState.VERIFYING_OWNER, stateMachine.currentState)
        assertFalse(stateMachine.isOwnerVerified)
    }

    @Test
    fun testOwnerVerificationMatchEstablishesTrustedSession() {
        // Trigger initial wake-up
        stateMachine.onWakeWordDetected("hey chinar", ownerVerificationRequired = true)
        val sid = stateMachine.currentSessionId

        // Simulate speaker verification match
        val verified = stateMachine.onOwnerVerificationCompleted(sid, isMatch = true, score = 0.88f)
        assertTrue(verified)
        assertEquals(AssistantState.AWAITING_OWNER_COMMAND, stateMachine.currentState)
        assertTrue(stateMachine.isOwnerVerified)

        // Establish trusted session
        establishTrustedSession(0.88f)
        assertTrue(isTrustedSessionActive.value)
        assertTrue(isOwnerSessionAuthorized())
    }

    @Test
    fun testConsecutiveWakeUpsReuseTrustedSessionWithoutReVerification() {
        // 1. Initial verification & session establishment
        stateMachine.onWakeWordDetected("hey chinar", ownerVerificationRequired = true)
        stateMachine.onOwnerVerificationCompleted(stateMachine.currentSessionId, isMatch = true, score = 0.85f)
        establishTrustedSession(0.85f)

        // Turn completes normally
        currentTimeMs += 5000L
        stateMachine.resetToStandby()
        assertEquals(AssistantState.STANDBY, stateMachine.currentState)
        assertTrue("Trusted session must survive return to standby", isTrustedSessionActive.value)

        // 2. Second wake-up: 30 seconds later
        currentTimeMs += 30_000L
        val isAuthorized2 = isOwnerSessionAuthorized()
        assertTrue("Session must remain authorized for 2nd wake-up", isAuthorized2)
        val requiresVerification2 = isOwnerOnlyVerificationEnabled && isOwnerEnrolled && !isAuthorized2
        assertFalse("Second wake-up must NOT require biometric verification", requiresVerification2)

        val accepted2 = stateMachine.onWakeWordDetected("hey chinar", requiresVerification2)
        assertTrue(accepted2)
        assertEquals(AssistantState.AWAITING_OWNER_COMMAND, stateMachine.currentState)
        assertTrue("Turn must immediately be owner-verified", stateMachine.isOwnerVerified)

        // 3. Third wake-up: 5 minutes later
        stateMachine.resetToStandby()
        currentTimeMs += 300_000L
        val isAuthorized3 = isOwnerSessionAuthorized()
        assertTrue(isAuthorized3)
        val requiresVerification3 = isOwnerOnlyVerificationEnabled && isOwnerEnrolled && !isAuthorized3
        assertFalse("Third wake-up must NOT require verification", requiresVerification3)

        val accepted3 = stateMachine.onWakeWordDetected("chinar", requiresVerification3)
        assertTrue(accepted3)
        assertEquals(AssistantState.AWAITING_OWNER_COMMAND, stateMachine.currentState)
        assertTrue(stateMachine.isOwnerVerified)
    }

    @Test
    fun testExplicitLockRevokesAuthorizationAndRequiresReVerification() {
        // 1. Establish session
        stateMachine.onWakeWordDetected("hey chinar", ownerVerificationRequired = true)
        stateMachine.onOwnerVerificationCompleted(stateMachine.currentSessionId, isMatch = true, score = 0.90f)
        establishTrustedSession(0.90f)
        assertTrue(isOwnerSessionAuthorized())

        // 2. User explicitly locks Chinar
        lockTrustedSession()
        assertFalse("Session must be inactive after explicit lock", isTrustedSessionActive.value)
        assertFalse("Authorization must be revoked after explicit lock", isOwnerSessionAuthorized())
        assertEquals(AssistantState.STANDBY, stateMachine.currentState)

        // 3. Next wake-up must require verification again
        currentTimeMs += 5000L
        val isAuthorized = isOwnerSessionAuthorized()
        assertFalse(isAuthorized)
        val requiresVerification = isOwnerOnlyVerificationEnabled && isOwnerEnrolled && !isAuthorized
        assertTrue("Post-lock wake-up must require verification", requiresVerification)

        val accepted = stateMachine.onWakeWordDetected("hey chinar", requiresVerification)
        assertTrue(accepted)
        assertEquals(AssistantState.VERIFYING_OWNER, stateMachine.currentState)
        assertFalse(stateMachine.isOwnerVerified)
    }

    @Test
    fun testToolCallDispatcherAuthorizationGateEnforcesSessionSecurity() = runTest {
        val dispatcher = ToolCallDispatcher(context = null)
        dispatcher.authorizationGate = { isOwnerSessionAuthorized() }

        val callId = "test-call-security-01"
        val func = "web_search"
        val args = "{\"query\":\"weather today\"}"

        // When locked: tool execution is blocked
        assertFalse(isOwnerSessionAuthorized())
        val blockedResult = dispatcher.dispatchToolCall(callId, func, args)
        assertEquals("unauthorized", blockedResult.optString("status"))
        assertTrue(blockedResult.optString("error").contains("Speaker verification required"))

        // When trusted session is established: tool execution is authorized
        establishTrustedSession(0.85f)
        assertTrue(isOwnerSessionAuthorized())

        val allowedCallId = "test-call-security-02"
        val allowedResult = dispatcher.dispatchToolCall(allowedCallId, func, args)
        assertEquals("success", allowedResult.optString("status"))

        // When locked again: tool execution is blocked again
        lockTrustedSession()
        assertFalse(isOwnerSessionAuthorized())

        val blockedCallId2 = "test-call-security-03"
        val blockedAgain = dispatcher.dispatchToolCall(blockedCallId2, func, args)
        assertEquals("unauthorized", blockedAgain.optString("status"))
    }

    @Test
    fun testInactivityTimeoutRevokesAuthorization() {
        // Establish session at T = 1,000,000ms
        establishTrustedSession(0.85f)
        assertTrue(isOwnerSessionAuthorized())

        // Active interaction at T = 1,000,000 + 1 hour (3,600,000ms)
        currentTimeMs += 3_600_000L
        lastAuthorizedActivityAtMs = currentTimeMs
        assertTrue("Session active within 1 hour", isOwnerSessionAuthorized())

        // Inactivity of 4 hours and 1 second (14,400,001ms) past last activity
        currentTimeMs += 14_400_001L
        assertFalse("Session must expire after 4 hours of inactivity", isOwnerSessionAuthorized())
        assertFalse("Session active flag must be cleared", isTrustedSessionActive.value)
    }

    @Test
    fun testOwnerVerificationDisabledBypassesGate() {
        isOwnerOnlyVerificationEnabled = false
        _isTrustedSessionActive.value = false

        // When verification is disabled, authorization passes unconditionally
        assertTrue(isOwnerSessionAuthorized())

        val requiresVerification = isOwnerOnlyVerificationEnabled && isOwnerEnrolled && !isOwnerSessionAuthorized()
        assertFalse(requiresVerification)

        val accepted = stateMachine.onWakeWordDetected("chinar", requiresVerification)
        assertTrue(accepted)
        assertEquals(AssistantState.AWAITING_OWNER_COMMAND, stateMachine.currentState)
        assertTrue(stateMachine.isOwnerVerified)
    }
}
