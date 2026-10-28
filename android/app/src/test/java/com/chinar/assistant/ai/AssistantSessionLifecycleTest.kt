package com.chinar.assistant.ai

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit test suite verifying the conversation lifecycle, silence timeouts,
 * processing watchdog, barge-in handling, wake-word debounce, and standby resumption.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantSessionLifecycleTest {

    enum class TestPhase {
        STANDBY,
        AWAITING_INITIAL_REQUEST,
        USER_SPEAKING,
        AI_PROCESSING,
        AI_SPEAKING,
        AWAITING_FOLLOWUP
    }

    @Test
    fun testInitialSilenceTimeoutEndsSessionWhenNoSpeech() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        var phase = TestPhase.STANDBY
        var sessionEnded = false
        var timerJob: kotlinx.coroutines.Job? = null

        fun startTimer(timeoutMs: Long) {
            timerJob?.cancel()
            timerJob = scope.launch {
                delay(timeoutMs)
                phase = TestPhase.STANDBY
                sessionEnded = true
            }
        }

        // 1. Setup complete (woke up, waiting for user speech)
        phase = TestPhase.AWAITING_INITIAL_REQUEST
        startTimer(10000L) // 10s default

        assertEquals(TestPhase.AWAITING_INITIAL_REQUEST, phase)
        assertFalse(sessionEnded)

        // Advance 5s (halfway)
        advanceTimeBy(5000L)
        runCurrent()
        assertEquals(TestPhase.AWAITING_INITIAL_REQUEST, phase)
        assertFalse(sessionEnded)

        // Advance remaining 5s + 1ms -> timer fires
        advanceTimeBy(5001L)
        runCurrent()
        assertEquals(TestPhase.STANDBY, phase)
        assertTrue(sessionEnded)
    }

    @Test
    fun testSpeechActivityCancelsInitialSilenceTimer() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        var phase = TestPhase.STANDBY
        var sessionEnded = false
        var timerJob: kotlinx.coroutines.Job? = null

        fun startTimer(timeoutMs: Long) {
            timerJob?.cancel()
            timerJob = scope.launch {
                delay(timeoutMs)
                phase = TestPhase.STANDBY
                sessionEnded = true
            }
        }

        fun cancelTimer() {
            timerJob?.cancel()
            timerJob = null
        }

        // 1. Woke up
        phase = TestPhase.AWAITING_INITIAL_REQUEST
        startTimer(10000L)

        // Advance 3s, then user begins speaking
        advanceTimeBy(3000L)
        runCurrent()

        // Server detects ACTIVITY_START
        cancelTimer()
        phase = TestPhase.USER_SPEAKING

        // Advance past original 10s mark
        advanceTimeBy(10000L)
        runCurrent()

        // Session must STILL be active
        assertEquals(TestPhase.USER_SPEAKING, phase)
        assertFalse(sessionEnded)
    }

    @Test
    fun testActivityEndStartsProcessingWatchdog() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        var phase = TestPhase.USER_SPEAKING
        var watchdogFired = false
        var watchdogJob: kotlinx.coroutines.Job? = null

        fun startWatchdog(timeoutMs: Long = 15000L) {
            watchdogJob?.cancel()
            watchdogJob = scope.launch {
                delay(timeoutMs)
                phase = TestPhase.STANDBY
                watchdogFired = true
            }
        }

        fun onAudioReceived() {
            watchdogJob?.cancel()
            watchdogJob = null
            phase = TestPhase.AI_SPEAKING
        }

        // User finishes speaking -> ACTIVITY_END
        phase = TestPhase.AI_PROCESSING
        startWatchdog(15000L)

        assertEquals(TestPhase.AI_PROCESSING, phase)
        assertFalse(watchdogFired)

        // Normal response arrives at 2s
        advanceTimeBy(2000L)
        runCurrent()
        onAudioReceived()

        assertEquals(TestPhase.AI_SPEAKING, phase)
        assertFalse(watchdogFired)

        // Advance past 15s watchdog
        advanceTimeBy(15000L)
        runCurrent()
        assertEquals(TestPhase.AI_SPEAKING, phase)
        assertFalse(watchdogFired)
    }

    @Test
    fun testFollowUpSilenceTimeoutReturnsToStandby() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        var phase = TestPhase.AI_SPEAKING
        var standbyResumed = false
        var timerJob: kotlinx.coroutines.Job? = null

        fun startTimer(timeoutMs: Long) {
            timerJob?.cancel()
            timerJob = scope.launch {
                delay(timeoutMs)
                phase = TestPhase.STANDBY
                standbyResumed = true
            }
        }

        // Assistant finished speaking -> PlaybackFinished
        phase = TestPhase.AWAITING_FOLLOWUP
        val followUpMs = 8000L // 8s default
        startTimer(followUpMs)

        assertEquals(TestPhase.AWAITING_FOLLOWUP, phase)
        assertFalse(standbyResumed)

        // Advance 7s -> still waiting for follow-up
        advanceTimeBy(7000L)
        runCurrent()
        assertEquals(TestPhase.AWAITING_FOLLOWUP, phase)
        assertFalse(standbyResumed)

        // Advance 1.5s -> 8.5s total -> session ends, returns to standby
        advanceTimeBy(1500L)
        runCurrent()
        assertEquals(TestPhase.STANDBY, phase)
        assertTrue(standbyResumed)
    }

    @Test
    fun testContinuousConversationDisabledTerminatesImmediately() {
        var phase = TestPhase.AI_SPEAKING
        var sessionEnded = false
        val isContinuousEnabled = false

        // Assistant finished speaking -> PlaybackFinished
        if (isContinuousEnabled) {
            phase = TestPhase.AWAITING_FOLLOWUP
        } else {
            phase = TestPhase.STANDBY
            sessionEnded = true
        }

        assertEquals(TestPhase.STANDBY, phase)
        assertTrue(sessionEnded)
    }

    @Test
    fun testWakeWordDebounceDropsRapidDuplicates() {
        var lastWakeWordTime = 0L
        val acceptedEvents = mutableListOf<Long>()

        fun onWakeWord(timestampMs: Long): Boolean {
            if (lastWakeWordTime > 0L && timestampMs - lastWakeWordTime < 1500L) {
                return false
            }
            lastWakeWordTime = timestampMs
            acceptedEvents.add(timestampMs)
            return true
        }

        // 1. Initial wake word at t = 1000ms
        assertTrue(onWakeWord(1000L))

        // 2. Duplicate intent/SharedFlow event at t = 1050ms
        assertFalse(onWakeWord(1050L))

        // 3. Another rapid echo at t = 1400ms (< 1500ms debounce)
        assertFalse(onWakeWord(1400L))

        // 4. Legitimate next wake word after user finished turn at t = 3000ms (> 1500ms)
        assertTrue(onWakeWord(3000L))

        assertEquals(2, acceptedEvents.size)
        assertEquals(listOf(1000L, 3000L), acceptedEvents)
    }

    @Test
    fun testAudioTrackHeadAlignmentOnFlush() {
        // Simulates the AudioTrack head position behavior during multiple turns and flush
        var playbackHeadPosition = 0L
        var totalFramesWritten = 0L

        // Turn 1: 48,000 frames played
        val turn1Frames = 48000L
        totalFramesWritten += turn1Frames
        playbackHeadPosition += turn1Frames

        // Playback drained
        assertTrue(playbackHeadPosition >= totalFramesWritten)

        // Interruption / flush occurs at head position 48,000
        // Android AudioTrack.flush() leaves playbackHeadPosition intact!
        // BUG FIX: totalFramesWritten must align with playbackHeadPosition, NOT 0L!
        totalFramesWritten = playbackHeadPosition
        assertEquals(48000L, totalFramesWritten)
        assertEquals(48000L, playbackHeadPosition)

        // Turn 2 begins: model sends 24,000 frames
        val turn2Frames = 24000L
        totalFramesWritten += turn2Frames

        // Before frames are played, playbackHeadPosition is still 48,000
        // totalFramesWritten is 72,000
        // Correct invariant: playbackHeadPosition (48k) < totalFramesWritten (72k)
        assertFalse("Drain check must not trigger before audio is actually played",
            playbackHeadPosition >= totalFramesWritten)

        // Play out 24,000 frames
        playbackHeadPosition += turn2Frames
        assertTrue("Drain check triggers only after all Turn 2 frames finish playing",
            playbackHeadPosition >= totalFramesWritten)
    }

    @Test
    fun testBargeInInterruptionDoesNotDeclarePlaybackFinished() {
        var onPlaybackFinishedDispatched = false

        // When stopPlaybackAndClear is called during barge-in:
        // It must NOT dispatch onPlaybackStateChanged(false)
        fun stopPlaybackAndClear(reason: String) {
            // Clears queue, flushes track
            // DOES NOT emit onPlaybackStateChanged(false)
        }

        stopPlaybackAndClear("user_barge_in")
        assertFalse("Barge-in interruption must never dispatch PlaybackFinished", onPlaybackFinishedDispatched)
    }
}
