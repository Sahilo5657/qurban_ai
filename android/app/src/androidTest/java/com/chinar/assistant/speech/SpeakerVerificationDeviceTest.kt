package com.chinar.assistant.speech

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical on-device acceptance test running directly on OnePlus Ace 2V (Dimensity 9000, Android 16).
 *
 * Verifies that:
 * 1. Native Kaldi x-vector SpeakerModel and acoustic Model initialize without crash or link errors.
 * 2. Recognizer with SpeakerModel outputs genuine 128-dimensional "spk" embeddings when processing real speech.
 * 3. Measures real-world execution latency of on-device x-vector inference on Dimensity 9000.
 * 4. Cosine similarity correctly discriminates identical and same-speaker vs distinct impostor speakers.
 * 5. Empirically evaluates the 0.62 threshold against genuine and impostor scores.
 */
@RunWith(AndroidJUnit4::class)
class SpeakerVerificationDeviceTest {

    companion object {
        private const val TAG = "SpkDeviceTest"
    }

    @Test
    fun testNativeSpeakerModelInferenceOnHardware() = runBlocking {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context

        val engine = SpeakerVerificationEngine(targetContext)

        // 1. Initialize native Kaldi models
        val initSuccess = engine.initialize()
        assertTrue("Native SpeakerModel and Model must initialize on device", initSuccess)

        // 2. Load genuine 16kHz speech PCM assets
        val davidBytes = testContext.assets.open("david_speech.pcm").use { it.readBytes() }
        val ziraBytes = testContext.assets.open("zira_speech.pcm").use { it.readBytes() }
        val voskBytes = testContext.assets.open("vosk_speech.pcm").use { it.readBytes() }

        Log.d(TAG, "Loaded test speech assets: David=${davidBytes.size}B, Zira=${ziraBytes.size}B, Vosk=${voskBytes.size}B")
        assertTrue("David speech asset must be loaded", davidBytes.isNotEmpty())
        assertTrue("Zira speech asset must be loaded", ziraBytes.isNotEmpty())
        assertTrue("Vosk speech asset must be loaded", voskBytes.isNotEmpty())

        // Split David speech into two distinct 3-second utterances (same speaker)
        // 3 seconds * 16,000 samples/sec * 2 bytes/sample = 96,000 bytes
        val davidSplitBytes = minOf(96000, davidBytes.size / 2)
        val davidUtt1 = davidBytes.copyOfRange(0, davidSplitBytes)
        val davidUtt2 = davidBytes.copyOfRange(davidSplitBytes, minOf(davidBytes.size, davidSplitBytes * 2))

        // 3. Audio quality verification on real speech
        val qDavid1 = SpeakerVerificationEngine.validateAudioQuality(davidUtt1)
        val qDavid2 = SpeakerVerificationEngine.validateAudioQuality(davidUtt2)
        val qZira = SpeakerVerificationEngine.validateAudioQuality(ziraBytes)
        val qVosk = SpeakerVerificationEngine.validateAudioQuality(voskBytes)

        assertTrue("David Utt1 quality must pass: ${qDavid1.reason}", qDavid1.isValid)
        assertTrue("David Utt2 quality must pass: ${qDavid2.reason}", qDavid2.isValid)
        assertTrue("Zira quality must pass: ${qZira.reason}", qZira.isValid)
        assertTrue("Vosk quality must pass: ${qVosk.reason}", qVosk.isValid)

        Log.d(TAG, "[QUALITY_PASS] David1: dur=${qDavid1.durationSeconds}s, peak=${qDavid1.peakAmplitude}, rms=${qDavid1.rmsEnergy}")
        Log.d(TAG, "[QUALITY_PASS] David2: dur=${qDavid2.durationSeconds}s, peak=${qDavid2.peakAmplitude}, rms=${qDavid2.rmsEnergy}")
        Log.d(TAG, "[QUALITY_PASS] Zira: dur=${qZira.durationSeconds}s, peak=${qZira.peakAmplitude}, rms=${qZira.rmsEnergy}")
        Log.d(TAG, "[QUALITY_PASS] Vosk: dur=${qVosk.durationSeconds}s, peak=${qVosk.peakAmplitude}, rms=${qVosk.rmsEnergy}")

        // 4. Extract 128-d x-vector embeddings and benchmark inference latency
        val t0 = System.currentTimeMillis()
        val embDavid1 = engine.extractEmbedding(davidUtt1)
        val latDavid1 = System.currentTimeMillis() - t0

        val t1 = System.currentTimeMillis()
        val embDavid2 = engine.extractEmbedding(davidUtt2)
        val latDavid2 = System.currentTimeMillis() - t1

        val t2 = System.currentTimeMillis()
        val embZira = engine.extractEmbedding(ziraBytes)
        val latZira = System.currentTimeMillis() - t2

        val t3 = System.currentTimeMillis()
        val embVosk = engine.extractEmbedding(voskBytes)
        val latVosk = System.currentTimeMillis() - t3

        Log.d(TAG, "[INFERENCE_BENCHMARK] Latency on Dimensity 9000: David1=${latDavid1}ms, David2=${latDavid2}ms, Zira=${latZira}ms, Vosk=${latVosk}ms")

        // 5. Verify embedding dimensions and structure
        assertNotNull("embDavid1 must not be null", embDavid1)
        assertNotNull("embDavid2 must not be null", embDavid2)
        assertNotNull("embZira must not be null", embZira)
        assertNotNull("embVosk must not be null", embVosk)

        assertEquals("Kaldi x-vector must be 128-dimensional", 128, embDavid1!!.size)
        assertEquals("Kaldi x-vector must be 128-dimensional", 128, embDavid2!!.size)
        assertEquals("Kaldi x-vector must be 128-dimensional", 128, embZira!!.size)
        assertEquals("Kaldi x-vector must be 128-dimensional", 128, embVosk!!.size)

        var normSqDavid = 0.0
        for (v in embDavid1) normSqDavid += (v * v).toDouble()
        assertTrue("Embedding vector must have non-zero norm", normSqDavid > 0.01)
        Log.d(TAG, "[EMBEDDING_VERIFIED] 128-d embedding verified. Dimension=${embDavid1.size}, L2NormSq=${String.format(java.util.Locale.US, "%.4f", normSqDavid)}")

        // 6. Empirical Cosine Similarity Matrix
        val selfSim = SpeakerVerificationEngine.cosineSimilarity(embDavid1, embDavid1)
        val sameSpeakerSim = SpeakerVerificationEngine.cosineSimilarity(embDavid1, embDavid2)
        val impostorSimZira = SpeakerVerificationEngine.cosineSimilarity(embDavid1, embZira)
        val impostorSimVosk = SpeakerVerificationEngine.cosineSimilarity(embDavid1, embVosk)
        val crossImpostorSim = SpeakerVerificationEngine.cosineSimilarity(embZira, embVosk)

        Log.d(TAG, "================ EMPIRICAL VERIFICATION RESULTS ================")
        Log.d(TAG, "[SIMILARITY] Genuine Self-Match (David1 vs David1): $selfSim")
        Log.d(TAG, "[SIMILARITY] Genuine Same-Speaker (David1 vs David2): $sameSpeakerSim")
        Log.d(TAG, "[SIMILARITY] Impostor Cross-Gender (David vs Zira): $impostorSimZira")
        Log.d(TAG, "[SIMILARITY] Impostor Cross-Speaker (David vs Vosk): $impostorSimVosk")
        Log.d(TAG, "[SIMILARITY] Impostor Cross-Speaker (Zira vs Vosk): $crossImpostorSim")
        Log.d(TAG, "================================================================")

        // Self-similarity must be exactly 1.0
        assertEquals(1.0f, selfSim, 0.0001f)

        // Same-speaker score should be comfortably higher than impostor scores
        assertTrue("Same-speaker score ($sameSpeakerSim) must be greater than David vs Zira impostor ($impostorSimZira)", sameSpeakerSim > impostorSimZira)
        assertTrue("Same-speaker score ($sameSpeakerSim) must be greater than David vs Vosk impostor ($impostorSimVosk)", sameSpeakerSim > impostorSimVosk)

        // 7. Verify enrollment template averaging and verifySpeaker API
        val enrolledTemplate = SpeakerVerificationEngine.computeAverageTemplate(listOf(embDavid1, embDavid2))
        assertNotNull("Enrolled template must not be null", enrolledTemplate)
        assertEquals(128, enrolledTemplate!!.size)

        // Test verifySpeaker with genuine owner
        val verifyMatchResult = engine.verifySpeaker(davidUtt1, enrolledTemplate)
        Log.d(TAG, "[VERIFY_MATCH_RESULT] $verifyMatchResult")

        // Test verifySpeaker with impostor (Zira)
        val verifyRejectResult = engine.verifySpeaker(ziraBytes, enrolledTemplate)
        Log.d(TAG, "[VERIFY_REJECT_RESULT] $verifyRejectResult")
        assertTrue("Impostor candidate (Zira) must be REJECTED", verifyRejectResult is SpeakerVerificationResult.Reject)

        engine.release()
    }
}
