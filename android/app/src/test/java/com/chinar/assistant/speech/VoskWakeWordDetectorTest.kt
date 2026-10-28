package com.chinar.assistant.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoskWakeWordDetectorTest {

    @Test
    fun testVoskSpeakerModelClassAvailability() {
        val spkClass = Class.forName("org.vosk.SpeakerModel")
        println("Found Vosk speaker class: ${spkClass.name}")
        for (c in spkClass.constructors) {
            println("SpeakerModel constructor: $c")
        }
        for (m in spkClass.declaredMethods) {
            println("SpeakerModel method: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
        }
        val recClass = Class.forName("org.vosk.Recognizer")
        for (c in recClass.constructors) {
            println("Recognizer constructor: $c")
        }
        for (m in recClass.declaredMethods) {
            println("Recognizer method: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
        }
    }

    /**
     * Requirement: Canonical Wake Phrases in completed recognition ("text") must activate:
     * "Qurban", "Haa Qurban", "Hey Qurban", "A Qurban"
     */
    @Test
    fun testParseWakeWordAcceptsFullCanonicalPhrases() {
        // Qurban
        val qurbanResult = VoskWakeWordDetector.parseWakeWord("""{"text" : "qurban"}""")
        assertEquals(WakeWordPhrases.QURBAN, qurbanResult)

        // Haa Qurban
        val haaQurbanResult = VoskWakeWordDetector.parseWakeWord("""{"text" : "haa qurban"}""")
        assertEquals(WakeWordPhrases.HAA_QURBAN, haaQurbanResult)

        // Hey Qurban
        val heyQurbanResult = VoskWakeWordDetector.parseWakeWord("""{"text" : "hey qurban"}""")
        assertEquals(WakeWordPhrases.HEY_QURBAN, heyQurbanResult)

        // A Qurban
        val aQurbanResult = VoskWakeWordDetector.parseWakeWord("""{"text" : "a qurban"}""")
        assertEquals(WakeWordPhrases.A_QURBAN, aQurbanResult)
    }

    /**
     * Phonetic dictionary equivalents mapping to canonical phrases in Vosk en-us model.
     */
    @Test
    fun testParseWakeWordAcceptsSupportedPhoneticEquivalents() {
        // "qorban" / "corban" / "corbin" / "kurban" -> QURBAN
        assertEquals(WakeWordPhrases.QURBAN, VoskWakeWordDetector.parseWakeWord("""{"text" : "qorban"}"""))
        assertEquals(WakeWordPhrases.QURBAN, VoskWakeWordDetector.parseWakeWord("""{"text" : "corban"}"""))
        assertEquals(WakeWordPhrases.QURBAN, VoskWakeWordDetector.parseWakeWord("""{"text" : "corbin"}"""))
        assertEquals(WakeWordPhrases.QURBAN, VoskWakeWordDetector.parseWakeWord("""{"text" : "kurban"}"""))

        // "haa qorban" / "ha corban" / "hah corbin" -> HAA_QURBAN
        assertEquals(WakeWordPhrases.HAA_QURBAN, VoskWakeWordDetector.parseWakeWord("""{"text" : "haa qorban"}"""))
        assertEquals(WakeWordPhrases.HAA_QURBAN, VoskWakeWordDetector.parseWakeWord("""{"text" : "ha corban"}"""))
        assertEquals(WakeWordPhrases.HAA_QURBAN, VoskWakeWordDetector.parseWakeWord("""{"text" : "hah corbin"}"""))

        // "hey corban" / "hey corbin" / "hey qorban" -> HEY_QURBAN
        assertEquals(WakeWordPhrases.HEY_QURBAN, VoskWakeWordDetector.parseWakeWord("""{"text" : "hey corban"}"""))
        assertEquals(WakeWordPhrases.HEY_QURBAN, VoskWakeWordDetector.parseWakeWord("""{"text" : "hey corbin"}"""))
        assertEquals(WakeWordPhrases.HEY_QURBAN, VoskWakeWordDetector.parseWakeWord("""{"text" : "hey qorban"}"""))

        // "a corban" / "ay qurban" / "ay corbin" -> A_QURBAN
        assertEquals(WakeWordPhrases.A_QURBAN, VoskWakeWordDetector.parseWakeWord("""{"text" : "a corban"}"""))
        assertEquals(WakeWordPhrases.A_QURBAN, VoskWakeWordDetector.parseWakeWord("""{"text" : "ay qurban"}"""))
        assertEquals(WakeWordPhrases.A_QURBAN, VoskWakeWordDetector.parseWakeWord("""{"text" : "ay corbin"}"""))
    }

    /**
     * Normalization of punctuation, casing, and spacing.
     */
    @Test
    fun testParseWakeWordNormalization() {
        assertEquals(WakeWordPhrases.HEY_QURBAN, VoskWakeWordDetector.parseWakeWord("""{"text" : "Hey, Qurban!"}"""))
        assertEquals(WakeWordPhrases.QURBAN, VoskWakeWordDetector.parseWakeWord("""{"text" : "QURBAN."}"""))
        assertEquals(WakeWordPhrases.HAA_QURBAN, VoskWakeWordDetector.parseWakeWord("""{"text" : "  haa   qurban  "}"""))
        assertEquals(WakeWordPhrases.A_QURBAN, VoskWakeWordDetector.parseWakeWord("""{"text" : "A Qurban?"}"""))
        assertEquals(WakeWordPhrases.HEY_QURBAN, VoskWakeWordDetector.parseWakeWord("""{"text" : "Hey Corbin!"}"""))
    }

    /**
     * Partial hypotheses (JSON containing "partial") must NEVER activate in production.
     */
    @Test
    fun testParseWakeWordRejectsAllPartialHypothesesByDefault() {
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"partial" : "hey qurban"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"partial" : "qurban"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"partial" : "haa qurban"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"partial" : "a qurban"}"""))

        // Explicit allowPartial flag for diagnostics verification only
        assertEquals(WakeWordPhrases.HEY_QURBAN, VoskWakeWordDetector.parseWakeWord("""{"partial" : "hey qurban"}""", allowPartial = true))
        assertEquals(WakeWordPhrases.QURBAN, VoskWakeWordDetector.parseWakeWord("""{"partial" : "qurban"}""", allowPartial = true))
    }

    /**
     * All "ch", "cha", "chin", "chinar", "china" and unrelated words must be STRICTLY REJECTED.
     */
    @Test
    fun testParseWakeWordOldChinarAndSimilarSoundsStrictlyRejected() {
        // Previous triggers that irritated the user
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "ch"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "cha"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "chin"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "chinar"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "china"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "hey chinar"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "hey china"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "chinara"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "hey chinara"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "parachinar"}"""))

        // Words starting with Ch-
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "chair"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "check"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "chai"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "chat"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "chinese"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "channel"}"""))

        // Embedded in sentences
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "what is qurban"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "hello qurban"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "eid ul azha qurbani"}"""))

        // General conversational speech
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "what is the weather"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "turn on the light"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "hello how are you"}"""))
    }

    /**
     * Recognition partials followed by a valid complete phrase -> activate ONLY when complete phrase is confirmed.
     */
    @Test
    fun testRecognitionPartialsFollowedByCompletedPhrase() {
        // Step 1: Speaker starts "qur..." -> intermediate partial emitted -> NO activation
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"partial" : "qur"}"""))

        // Step 2: Decoder hypothesizes full word mid-speech -> still partial -> NO activation
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"partial" : "qurban"}"""))

        // Step 3: Utterance completes, silence endpoint detected, final completed result emitted -> ACTIVATION!
        val completedResult = VoskWakeWordDetector.parseWakeWord("""{"text" : "qurban"}""")
        assertEquals(WakeWordPhrases.QURBAN, completedResult)
    }

    /**
     * Recognition partials of an unrelated word starting with "Ch" or "Qu" -> NEVER activates.
     */
    @Test
    fun testRecognitionPartialsOfUnrelatedWordNeverActivates() {
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"partial" : "ch"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"partial" : "cha"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "chair"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "[unk]"}"""))
    }

    /**
     * Separate utterances must not combine into a false wake phrase.
     */
    @Test
    fun testSeparateUtterancesDoNotCombineIntoWakePhrase() {
        // Utterance 1: "hey" spoken alone
        val utt1 = VoskWakeWordDetector.parseWakeWord("""{"text" : "hey"}""")
        assertNull(utt1)

        // Utterance 2: "chair" spoken later
        val utt2 = VoskWakeWordDetector.parseWakeWord("""{"text" : "chair"}""")
        assertNull(utt2)
    }

    @Test
    fun testParseWakeWordUnrelatedSpeechIgnored() {
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "what is the weather"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "[unk]"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : ""}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"partial" : "what is the weather"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"partial" : "[unk]"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"partial" : ""}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "hey"}"""))
        assertNull(VoskWakeWordDetector.parseWakeWord("""{"text" : "haa"}"""))
    }

    @Test
    fun testParseWakeWordMalformedJsonHandledGracefully() {
        assertNull(VoskWakeWordDetector.parseWakeWord(""))
        assertNull(VoskWakeWordDetector.parseWakeWord("not json"))
        assertNull(VoskWakeWordDetector.parseWakeWord("{broken json"))
    }

    @Test
    fun testGrammarDefinitionIntegrity() {
        val grammar = VoskWakeWordDetector.WAKE_WORD_GRAMMAR
        // Strictly includes Qurban wake words
        assertTrue(grammar.contains("qurban"))
        assertTrue(grammar.contains("haa qurban"))
        assertTrue(grammar.contains("hey qurban"))
        assertTrue(grammar.contains("a qurban"))
        assertTrue(grammar.contains("corban"))
        assertTrue(grammar.contains("corbin"))
        assertTrue(grammar.contains("[unk]"))

        // Strictly EXCLUDES old "chinar", "china", "chair", "cha", "chin"
        assertFalse(grammar.contains("\"chinar\""))
        assertFalse(grammar.contains("\"china\""))
        assertFalse(grammar.contains("\"chair\""))
        assertFalse(grammar.contains("\"cha\""))
        assertFalse(grammar.contains("\"chin\""))
        assertFalse(grammar.contains("\"chinara\""))
    }
}
