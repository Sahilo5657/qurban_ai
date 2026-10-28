package com.chinar.assistant.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class SpeakerVerificationTest {

    @Test
    fun testCosineSimilarityIdenticalVectors() {
        val v = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        val similarity = SpeakerVerificationEngine.cosineSimilarity(v, v)
        assertEquals(1.0f, similarity, 0.0001f)
    }

    @Test
    fun testCosineSimilarityOrthogonalVectors() {
        val a = floatArrayOf(1.0f, 0.0f, 0.0f)
        val b = floatArrayOf(0.0f, 1.0f, 0.0f)
        val similarity = SpeakerVerificationEngine.cosineSimilarity(a, b)
        assertEquals(0.0f, similarity, 0.0001f)
    }

    @Test
    fun testCosineSimilarityOppositeVectors() {
        val a = floatArrayOf(1.0f, 2.0f, 3.0f)
        val b = floatArrayOf(-1.0f, -2.0f, -3.0f)
        val similarity = SpeakerVerificationEngine.cosineSimilarity(a, b)
        assertEquals(-1.0f, similarity, 0.0001f)
    }

    @Test
    fun testCosineSimilarityOwnerMatchVsImpostorRejection() {
        // Enrolled owner template (128-d simulated)
        val enrolled = FloatArray(128) { i -> (i % 7 - 3).toFloat() / 10f }

        // Genuine owner candidate: small acoustic variation (e.g. noise / distance)
        val genuineOwnerSample = FloatArray(128) { i -> enrolled[i] + 0.05f }
        val genuineScore = SpeakerVerificationEngine.cosineSimilarity(enrolled, genuineOwnerSample)

        // Genuine sample should easily exceed calibrated threshold (0.62)
        assertTrue("Genuine score ($genuineScore) should exceed 0.62", genuineScore >= 0.62f)

        // Impostor candidate: completely different speaker embedding distribution
        val impostorSample = FloatArray(128) { i -> ((i * 13) % 11 - 5).toFloat() / 10f }
        val impostorScore = SpeakerVerificationEngine.cosineSimilarity(enrolled, impostorSample)

        // Impostor sample must be well below threshold
        assertTrue("Impostor score ($impostorScore) should be well below 0.62", impostorScore < 0.62f)
    }

    @Test
    fun testComputeAverageTemplate() {
        val sample1 = floatArrayOf(1.0f, 0.0f, 0.0f)
        val sample2 = floatArrayOf(0.0f, 1.0f, 0.0f)
        val sample3 = floatArrayOf(1.0f, 1.0f, 0.0f)

        val avg = SpeakerVerificationEngine.computeAverageTemplate(listOf(sample1, sample2, sample3))
        assertNotNull(avg)
        assertEquals(3, avg!!.size)

        // Check L2 normalization: norm should equal 1.0
        val norm = sqrt((avg[0] * avg[0] + avg[1] * avg[1] + avg[2] * avg[2]).toDouble()).toFloat()
        assertEquals(1.0f, norm, 0.0001f)
    }

    @Test
    fun testValidateAudioQualityValidSpeech() {
        // Generate 1.2s of simulated 16kHz speech with good amplitude
        val numSamples = (16000 * 1.2).toInt()
        val pcm = ByteArray(numSamples * 2)

        for (i in 0 until numSamples) {
            val sampleVal = (8000.0 * Math.sin(2.0 * Math.PI * 440.0 * i / 16000)).toInt().toShort()
            pcm[i * 2] = (sampleVal.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
        }

        val report = SpeakerVerificationEngine.validateAudioQuality(pcm)
        assertTrue(report.isValid)
        assertTrue(report.durationSeconds >= 1.0f)
        assertTrue(report.peakAmplitude > 5000)
        assertNull(report.reason)
    }

    @Test
    fun testValidateAudioQualityRejectsTooShort() {
        // 0.4s is too short for reliable enrollment (< 0.8s)
        val numSamples = (16000 * 0.4).toInt()
        val pcm = ByteArray(numSamples * 2)
        val report = SpeakerVerificationEngine.validateAudioQuality(pcm)

        assertFalse(report.isValid)
        assertTrue(report.reason?.contains("too short", ignoreCase = true) == true)
    }

    @Test
    fun testValidateAudioQualityRejectsTooQuiet() {
        // 1.5s of almost pure silence (amplitude = 50)
        val numSamples = (16000 * 1.5).toInt()
        val pcm = ByteArray(numSamples * 2)

        for (i in 0 until numSamples) {
            val sampleVal = 50.toShort()
            pcm[i * 2] = (sampleVal.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
        }

        val report = SpeakerVerificationEngine.validateAudioQuality(pcm)
        assertFalse(report.isValid)
        assertTrue(report.reason?.contains("too quiet", ignoreCase = true) == true)
    }

    @Test
    fun testParseEmbeddingFromJson() {
        val json = """{"text": "hello", "spk": [0.12, -0.34, 0.56, 0.78], "spk_frames": 42}"""
        val embedding = SpeakerVerificationEngine.parseEmbeddingFromJson(json)

        assertNotNull(embedding)
        assertEquals(4, embedding!!.size)
        assertEquals(0.12f, embedding[0], 0.001f)
        assertEquals(-0.34f, embedding[1], 0.001f)
        assertEquals(0.56f, embedding[2], 0.001f)
        assertEquals(0.78f, embedding[3], 0.001f)
    }

    @Test
    fun testParseEmbeddingFromEmptyJsonReturnsNull() {
        assertNull(SpeakerVerificationEngine.parseEmbeddingFromJson("{}"))
        assertNull(SpeakerVerificationEngine.parseEmbeddingFromJson("""{"text": "no spk"}"""))
        assertNull(SpeakerVerificationEngine.parseEmbeddingFromJson(""))
    }

    @Test
    fun testTrimSilenceRemovesLeadingAndTrailingZeros() {
        // 16000 samples = 1 sec: 0.3s silence, 0.4s tone (amp=5000), 0.3s silence
        val numSamples = 16000
        val pcm = ByteArray(numSamples * 2)

        for (i in 4800 until 11200) {
            val sampleVal = 5000.toShort()
            pcm[i * 2] = (sampleVal.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
        }

        val trimmed = SpeakerVerificationEngine.trimSilence(pcm, threshold = 300, paddingMs = 50)
        // Original was 32,000 bytes. Trimmed should be smaller (approx 6400 samples + 2*800 padding = 8000 samples = 16,000 bytes)
        assertTrue("Trimmed audio (${trimmed.size}B) should be smaller than original (${pcm.size}B)", trimmed.size < pcm.size)
        assertTrue("Trimmed audio should retain speech core", trimmed.size >= 12800)
    }
}
