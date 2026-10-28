package com.chinar.assistant.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTtsEngineTest {

    @Test
    fun testEmptyAndBlankStringsReturnEmpty() {
        assertTrue(AndroidTtsEngine.splitIntoSpeechChunks("").isEmpty())
        assertTrue(AndroidTtsEngine.splitIntoSpeechChunks("   ").isEmpty())
    }

    @Test
    fun testSingleShortSentence() {
        val result = AndroidTtsEngine.splitIntoSpeechChunks("Turning on the flashlight.")
        assertEquals(1, result.size)
        assertEquals("Turning on the flashlight.", result[0])
    }

    @Test
    fun testMultipleEnglishSentencesSplitCleanly() {
        val text = "Opening Clock. I will help you set the alarm for 7:30 AM! Let me know if you need anything else."
        val chunks = AndroidTtsEngine.splitIntoSpeechChunks(text)
        assertEquals(3, chunks.size)
        assertEquals("Opening Clock.", chunks[0])
        assertEquals("I will help you set the alarm for 7:30 AM!", chunks[1])
        assertEquals("Let me know if you need anything else.", chunks[2])
    }

    @Test
    fun testUrduPunctuationChunking() {
        val urduText = "جی جناب۔ میں حاضر ہوں۔ بتائیے کیا حکم ہے؟"
        val chunks = AndroidTtsEngine.splitIntoSpeechChunks(urduText)
        assertEquals(3, chunks.size)
        assertEquals("جی جناب۔", chunks[0])
        assertEquals("میں حاضر ہوں۔", chunks[1])
        assertEquals("بتائیے کیا حکم ہے؟", chunks[2])
    }

    @Test
    fun testHindiPunctuationChunking() {
        val hindiText = "नमस्ते। मैं आपकी सहायता करूँगा। क्या आपको कुछ और चाहिए?"
        val chunks = AndroidTtsEngine.splitIntoSpeechChunks(hindiText)
        assertEquals(3, chunks.size)
        assertEquals("नमस्ते।", chunks[0])
        assertEquals("मैं आपकी सहायता करूँगा।", chunks[1])
        assertEquals("क्या आपको कुछ और चाहिए?", chunks[2])
    }

    @Test
    fun testLongSentenceClauseSplitting() {
        val longSentence = "I have observed your screen, found the WhatsApp application icon, opened the chat, and sent your requested message immediately."
        val chunks = AndroidTtsEngine.splitIntoSpeechChunks(longSentence, maxChunkLength = 50)
        assertTrue(chunks.size >= 2)
        // First chunk should be a compact clause that can start speaking immediately (<50ms)
        assertTrue(chunks[0].length <= 50)
    }
}

