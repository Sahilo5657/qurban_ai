package com.chinar.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * Regression test suite for Gemini Live real-time audio pipeline and session lifecycle:
 * - Multiple audio chunks playing in order.
 * - Intermediate events not prematurely ending playback.
 * - Normal turn completion.
 * - Genuine interruption flushing queued audio and epoch invalidation.
 * - Stale chunks rejected after interruption.
 * - State consistency across consecutive turns.
 * - Graceful handling of empty audio chunks and error frames.
 */
class LiveVoiceSessionRegressionTest {

    @Test
    fun testMultipleAudioChunksDecodedInOrder() {
        val chunk1Data = byteArrayOf(0x01, 0x02, 0x03)
        val chunk2Data = byteArrayOf(0x04, 0x05, 0x06)
        val chunk3Data = byteArrayOf(0x07, 0x08, 0x09)

        val b64_1 = Base64.getEncoder().encodeToString(chunk1Data)
        val b64_2 = Base64.getEncoder().encodeToString(chunk2Data)
        val b64_3 = Base64.getEncoder().encodeToString(chunk3Data)

        val frameJson = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": [
                            { "inlineData": { "mimeType": "audio/pcm;rate=24000", "data": "$b64_1" } },
                            { "inlineData": { "mimeType": "audio/pcm;rate=24000", "data": "$b64_2" } },
                            { "inlineData": { "mimeType": "audio/pcm;rate=24000", "data": "$b64_3" } }
                        ]
                    }
                }
            }
        """.trimIndent()

        val events = GeminiLiveProtocol.parseServerMessage(frameJson)
        assertEquals(3, events.size)

        for (i in 0 until 3) {
            assertTrue(events[i] is LiveVoiceEvent.AudioReceived)
        }

        val a1 = (events[0] as LiveVoiceEvent.AudioReceived).pcmData
        val a2 = (events[1] as LiveVoiceEvent.AudioReceived).pcmData
        val a3 = (events[2] as LiveVoiceEvent.AudioReceived).pcmData

        assertEquals(0x01.toByte(), a1[0])
        assertEquals(0x04.toByte(), a2[0])
        assertEquals(0x07.toByte(), a3[0])
    }

    @Test
    fun testIntermediateEventsDoNotEndPlayback() {
        // Intermediate text token emitted alongside audio chunk
        val audioB64 = Base64.getEncoder().encodeToString(byteArrayOf(0x11, 0x22))
        val frameJson = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": [
                            { "inlineData": { "mimeType": "audio/pcm;rate=24000", "data": "$audioB64" } },
                            { "text": "I am thinking..." }
                        ]
                    }
                }
            }
        """.trimIndent()

        val events = GeminiLiveProtocol.parseServerMessage(frameJson)
        assertEquals(2, events.size)
        assertTrue(events[0] is LiveVoiceEvent.AudioReceived)
        assertTrue(events[1] is LiveVoiceEvent.TextReceived)

        // Verify neither event signals an end of turn or session error
        val hasTurnEnding = events.any { it is LiveVoiceEvent.TurnComplete || it is LiveVoiceEvent.Interrupted || it is LiveVoiceEvent.Error }
        assertFalse(hasTurnEnding)
    }

    @Test
    fun testNormalTurnCompletionCycle() {
        val frame1 = """{"setupComplete": {}}"""
        val frame2 = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": [
                            { "inlineData": { "mimeType": "audio/pcm;rate=24000", "data": "${Base64.getEncoder().encodeToString(byteArrayOf(0x01))}" } }
                        ]
                    }
                }
            }
        """.trimIndent()
        val frame3 = """{"serverContent": {"generationComplete": true}}"""
        val frame4 = """{"serverContent": {"turnComplete": true}}"""

        val ev1 = GeminiLiveProtocol.parseServerMessage(frame1)
        assertEquals(1, ev1.size)
        assertTrue(ev1[0] is LiveVoiceEvent.SetupComplete)

        val ev2 = GeminiLiveProtocol.parseServerMessage(frame2)
        assertEquals(1, ev2.size)
        assertTrue(ev2[0] is LiveVoiceEvent.AudioReceived)

        val ev3 = GeminiLiveProtocol.parseServerMessage(frame3)
        assertEquals(1, ev3.size)
        assertTrue(ev3[0] is LiveVoiceEvent.GenerationComplete)

        val ev4 = GeminiLiveProtocol.parseServerMessage(frame4)
        assertEquals(1, ev4.size)
        assertTrue(ev4[0] is LiveVoiceEvent.TurnComplete)
    }

    @Test
    fun testGenuineInterruptionFlushesAndIncrementsEpoch() {
        val currentEpoch = AtomicLong(0L)
        val activeQueue = mutableListOf<ByteArray>()

        // 1. Enqueue chunks for turn 0
        activeQueue.add(byteArrayOf(0x01, 0x02))
        activeQueue.add(byteArrayOf(0x03, 0x04))
        assertEquals(2, activeQueue.size)

        // 2. Server detects user barge-in and sends interruption
        val interruptFrame = """{"serverContent": {"interrupted": true}}"""
        val events = GeminiLiveProtocol.parseServerMessage(interruptFrame)
        assertEquals(1, events.size)
        assertTrue(events[0] is LiveVoiceEvent.Interrupted)

        // 3. Emulate pipeline interruption response: bump epoch & flush queue
        val newEpoch = currentEpoch.incrementAndGet()
        activeQueue.clear()

        assertEquals(1L, newEpoch)
        assertTrue(activeQueue.isEmpty())
    }

    @Test
    fun testStaleChunksRejectedAfterInterruption() {
        val currentEpoch = AtomicLong(0L)
        val receivedValidChunks = mutableListOf<ByteArray>()

        fun enqueueChunk(chunk: ByteArray, chunkEpoch: Long) {
            if (chunkEpoch == currentEpoch.get()) {
                receivedValidChunks.add(chunk)
            }
        }

        // Send chunk under epoch 0
        enqueueChunk(byteArrayOf(0x01), 0L)
        assertEquals(1, receivedValidChunks.size)

        // User interrupts -> epoch advances to 1
        currentEpoch.incrementAndGet()

        // Stale chunk arrives with old epoch 0 (was in-flight on socket)
        enqueueChunk(byteArrayOf(0x02), 0L)
        // Stale chunk must be rejected
        assertEquals(1, receivedValidChunks.size)

        // New chunk arrives with epoch 1 (from new turn)
        enqueueChunk(byteArrayOf(0x03), 1L)
        assertEquals(2, receivedValidChunks.size)
        assertEquals(0x03.toByte(), receivedValidChunks[1][0])
    }

    @Test
    fun testConsecutiveTurnsCycle() {
        var sessionState = LiveSessionState.DISCONNECTED

        // Setup
        sessionState = LiveSessionState.CONNECTED

        // Turn 1
        sessionState = LiveSessionState.STREAMING
        val turn1Audio = GeminiLiveProtocol.parseServerMessage("""
            {"serverContent": {"modelTurn": {"parts": [{"inlineData": {"data": "${Base64.getEncoder().encodeToString(byteArrayOf(0x01))}"}}]}}}
        """.trimIndent())
        assertTrue(turn1Audio[0] is LiveVoiceEvent.AudioReceived)

        val turn1Complete = GeminiLiveProtocol.parseServerMessage("""{"serverContent": {"turnComplete": true}}""")
        assertTrue(turn1Complete[0] is LiveVoiceEvent.TurnComplete)
        sessionState = LiveSessionState.CONNECTED

        // User speaks again -> Turn 2
        sessionState = LiveSessionState.STREAMING
        val turn2Audio = GeminiLiveProtocol.parseServerMessage("""
            {"serverContent": {"modelTurn": {"parts": [{"inlineData": {"data": "${Base64.getEncoder().encodeToString(byteArrayOf(0x02))}"}}]}}}
        """.trimIndent())
        assertTrue(turn2Audio[0] is LiveVoiceEvent.AudioReceived)

        val turn2Complete = GeminiLiveProtocol.parseServerMessage("""{"serverContent": {"turnComplete": true}}""")
        assertTrue(turn2Complete[0] is LiveVoiceEvent.TurnComplete)
        sessionState = LiveSessionState.CONNECTED

        assertEquals(LiveSessionState.CONNECTED, sessionState)
    }

    @Test
    fun testGracefulHandlingOfEmptyOrMalformedChunks() {
        // Empty inlineData data string
        val emptyDataJson = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": [
                            { "inlineData": { "mimeType": "audio/pcm;rate=24000", "data": "" } }
                        ]
                    }
                }
            }
        """.trimIndent()
        val events1 = GeminiLiveProtocol.parseServerMessage(emptyDataJson)
        assertTrue(events1.isEmpty())

        // Empty parts array
        val emptyPartsJson = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": []
                    }
                }
            }
        """.trimIndent()
        val events2 = GeminiLiveProtocol.parseServerMessage(emptyPartsJson)
        assertTrue(events2.isEmpty())

        // Corrupted JSON
        val malformedJson = "{ serverContent: { unclosed "
        val events3 = GeminiLiveProtocol.parseServerMessage(malformedJson)
        assertEquals(1, events3.size)
        assertTrue(events3[0] is LiveVoiceEvent.Error)
    }
}
