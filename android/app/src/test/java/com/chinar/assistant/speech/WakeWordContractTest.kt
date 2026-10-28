package com.chinar.assistant.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordContractTest {

    @Test
    fun testWakeWordPhrasesContract() {
        assertEquals("qurban", WakeWordPhrases.QURBAN)
        assertEquals("haa qurban", WakeWordPhrases.HAA_QURBAN)
        assertEquals("hey qurban", WakeWordPhrases.HEY_QURBAN)
        assertEquals("a qurban", WakeWordPhrases.A_QURBAN)

        assertTrue(WakeWordPhrases.ALL.contains("qurban"))
        assertTrue(WakeWordPhrases.ALL.contains("haa qurban"))
        assertTrue(WakeWordPhrases.ALL.contains("hey qurban"))
        assertTrue(WakeWordPhrases.ALL.contains("a qurban"))
    }

    @Test
    fun testWakeWordPhraseMatchingNormalization() {
        val testInputs = listOf(
            "Qurban" to "qurban",
            "Haa Qurban" to "haa qurban",
            "Hey Qurban" to "hey qurban",
            "A Qurban" to "a qurban",
            "HEY QURBAN!" to "hey qurban",
            "  haa qurban  " to "haa qurban"
        )

        for ((rawInput, expectedMatchedKeyword) in testInputs) {
            val normalized = rawInput.trim().lowercase().replace(Regex("[!?,.]+$"), "")
            assertTrue(
                "Input '$rawInput' normalized to '$normalized' should match WakeWordPhrases",
                WakeWordPhrases.ALL.contains(normalized)
            )
            assertEquals(expectedMatchedKeyword, normalized)
        }
    }
}
