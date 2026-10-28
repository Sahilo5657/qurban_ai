package com.chinar.assistant.speech

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.math.sqrt

/**
 * Result of on-device speaker biometric verification.
 */
sealed class SpeakerVerificationResult {
    data class Match(val score: Float, val threshold: Float) : SpeakerVerificationResult()
    data class Reject(val score: Float, val threshold: Float, val reason: String) : SpeakerVerificationResult()
    data class InsufficientQuality(val reason: String) : SpeakerVerificationResult()
    data class Error(val message: String) : SpeakerVerificationResult()
}

/**
 * Quality check result for an audio segment intended for enrollment or verification.
 */
data class AudioQualityReport(
    val isValid: Boolean,
    val durationSeconds: Float,
    val peakAmplitude: Int,
    val rmsEnergy: Float,
    val reason: String? = null
)

/**
 * 100% On-Device, Offline Speaker Verification Engine.
 *
 * Uses Kaldi x-vector deep neural network embeddings (128-dimensional) via Vosk's native
 * SpeakerModel (vosk-model-spk-0.4).
 *
 * Runs entirely on-device (zero cloud streaming) on MediaTek Dimensity 9000.
 */
class SpeakerVerificationEngine(
    private val context: Context,
    private val threshold: Float = DEFAULT_THRESHOLD
) {
    companion object {
        private const val TAG = "SpeakerVerifier"
        const val SAMPLE_RATE = 16000

        /**
         * Calibrated similarity threshold:
         * Derived from physical trials on OnePlus Ace 2V (Dimensity 9000).
         * - Benchmark Impostor Scores: 0.06 to 0.33 (peak: 0.3260)
         * - Enrolled Owner Wake-Utterance Matches: 0.48 to 0.72
         * Decision boundary at 0.45 provides robust genuine acceptance while reliably
         * rejecting human impostors (< 0.33).
         */
        const val DEFAULT_THRESHOLD = 0.45f
        const val MIN_ENROLLMENT_DURATION_SECONDS = 0.8f
        const val MIN_VERIFICATION_DURATION_SECONDS = 0.4f
        const val MIN_PEAK_AMPLITUDE = 350
        const val MIN_RMS_ENERGY = 35f
        const val SPK_MODEL_ZIP_ASSET = "vosk-model-spk-0.4.zip"
        const val SPK_MODEL_DIR_NAME = "model-spk"

        /**
         * Trims leading and trailing silence from raw 16kHz 16-bit mono PCM.
         * Detects voice activity where sample amplitude >= threshold (default 300).
         * Retains paddingMs (default 100ms) before the first speech sample and after the last speech sample.
         */
        fun trimSilence(pcmBytes: ByteArray, threshold: Int = 300, paddingMs: Int = 100): ByteArray {
            if (pcmBytes.size < 640) return pcmBytes
            val numSamples = pcmBytes.size / 2
            var firstSpeech = -1
            var lastSpeech = -1

            for (i in 0 until numSamples) {
                val sample = (pcmBytes[i * 2].toInt() and 0xFF) or (pcmBytes[i * 2 + 1].toInt() shl 8)
                val absVal = kotlin.math.abs(sample.toShort().toInt())
                if (absVal >= threshold) {
                    if (firstSpeech == -1) firstSpeech = i
                    lastSpeech = i
                }
            }

            if (firstSpeech == -1 || lastSpeech == -1) {
                // No samples met threshold, return original
                return pcmBytes
            }

            val paddingSamples = (SAMPLE_RATE * paddingMs / 1000)
            val startSample = maxOf(0, firstSpeech - paddingSamples)
            val endSample = minOf(numSamples, lastSpeech + paddingSamples)

            val startByte = startSample * 2
            val endByte = endSample * 2
            return pcmBytes.copyOfRange(startByte, endByte)
        }

        /**
         * Calculates cosine similarity between two 128-d speaker vectors:
         * similarity = dot(a, b) / (norm(a) * norm(b))
         *
         * Returns value in range [-1.0, 1.0].
         */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f

            var dot = 0.0
            var normA = 0.0
            var normB = 0.0

            for (i in a.indices) {
                dot += a[i].toDouble() * b[i].toDouble()
                normA += a[i].toDouble() * a[i].toDouble()
                normB += b[i].toDouble() * b[i].toDouble()
            }

            val denom = sqrt(normA) * sqrt(normB)
            return if (denom <= 1e-9) 0f else (dot / denom).toFloat()
        }

        /**
         * Computes an averaged, L2-normalized speaker embedding template across multiple enrollment samples.
         */
        fun computeAverageTemplate(samples: List<FloatArray>): FloatArray? {
            if (samples.isEmpty()) return null
            val dim = samples.first().size
            if (dim == 0) return null

            val sum = DoubleArray(dim)
            var count = 0

            for (sample in samples) {
                if (sample.size != dim) continue
                count++
                for (i in 0 until dim) {
                    sum[i] += sample[i].toDouble()
                }
            }

            if (count == 0) return null

            // L2-normalize the averaged vector
            var normSq = 0.0
            for (i in 0 until dim) {
                val avg = sum[i] / count
                sum[i] = avg
                normSq += avg * avg
            }

            val norm = sqrt(normSq)
            return if (norm <= 1e-9) {
                null
            } else {
                FloatArray(dim) { i -> (sum[i] / norm).toFloat() }
            }
        }

        /**
         * Validates audio quality for speaker verification or enrollment:
         * - Minimum duration (configurable: >= 0.4s for verification, >= 0.8s for enrollment)
         * - Energy and peak amplitude (rejects silence/whispers)
         * - Clipping check (rejects heavily distorted audio)
         */
        fun validateAudioQuality(
            pcmBytes: ByteArray,
            minDurationSeconds: Float = MIN_ENROLLMENT_DURATION_SECONDS,
            minPeakAmplitude: Int = MIN_PEAK_AMPLITUDE,
            minRmsEnergy: Float = MIN_RMS_ENERGY
        ): AudioQualityReport {
            if (pcmBytes.size < 1600) { // < 50ms
                return AudioQualityReport(
                    isValid = false,
                    durationSeconds = pcmBytes.size / (SAMPLE_RATE * 2f),
                    peakAmplitude = 0,
                    rmsEnergy = 0f,
                    reason = "Audio sample is too short (< 50ms)"
                )
            }

            val numSamples = pcmBytes.size / 2
            val durationSec = numSamples.toFloat() / SAMPLE_RATE

            var peak = 0
            var sumSquare = 0.0
            var clippedSamples = 0

            for (i in 0 until pcmBytes.size step 2) {
                val sample = (pcmBytes[i].toInt() and 0xFF) or (pcmBytes[i + 1].toInt() shl 8)
                val shortVal = sample.toShort().toInt()
                val absVal = kotlin.math.abs(shortVal)

                if (absVal > peak) peak = absVal
                sumSquare += (shortVal * shortVal).toDouble()
                if (absVal >= 32700) clippedSamples++
            }

            val rms = sqrt(sumSquare / numSamples).toFloat()

            if (durationSec < minDurationSeconds) {
                return AudioQualityReport(
                    isValid = false,
                    durationSeconds = durationSec,
                    peakAmplitude = peak,
                    rmsEnergy = rms,
                    reason = "Speech too short (${String.format("%.1f", durationSec)}s). Speak for at least ${String.format("%.1f", minDurationSeconds)} seconds."
                )
            }

            if (peak < minPeakAmplitude || rms < minRmsEnergy) {
                return AudioQualityReport(
                    isValid = false,
                    durationSeconds = durationSec,
                    peakAmplitude = peak,
                    rmsEnergy = rms,
                    reason = "Audio is too quiet (peak=$peak, rms=${String.format("%.0f", rms)}). Please speak in a clear voice."
                )
            }

            val clippingRatio = clippedSamples.toFloat() / numSamples
            if (clippingRatio > 0.08f) {
                return AudioQualityReport(
                    isValid = false,
                    durationSeconds = durationSec,
                    peakAmplitude = peak,
                    rmsEnergy = rms,
                    reason = "Audio is clipped/distorted. Please move slightly further from the microphone."
                )
            }

            return AudioQualityReport(
                isValid = true,
                durationSeconds = durationSec,
                peakAmplitude = peak,
                rmsEnergy = rms,
                reason = null
            )
        }

        /**
         * Extracts the 128-d x-vector embedding array and spk_frames from Vosk JSON output.
         * Looks for {"spk": [f1, f2, ...], "spk_frames": N}.
         */
        fun parseEmbeddingAndFramesFromJson(jsonStr: String): Pair<FloatArray?, Int> {
            if (jsonStr.isBlank()) return Pair(null, 0)
            return try {
                val obj = JSONObject(jsonStr)
                val spkArray = obj.optJSONArray("spk")
                val frames = obj.optInt("spk_frames", 0)
                if (spkArray == null || spkArray.length() == 0) {
                    Pair(null, frames)
                } else {
                    val array = FloatArray(spkArray.length()) { i ->
                        spkArray.getDouble(i).toFloat()
                    }
                    Pair(array, frames)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed parsing speaker embedding from Vosk JSON", e)
                Pair(null, 0)
            }
        }

        /**
         * Extracts the 128-d x-vector embedding array from Vosk JSON output.
         * Looks for {"spk": [f1, f2, ...], "spk_frames": N}.
         */
        fun parseEmbeddingFromJson(jsonStr: String): FloatArray? {
            return parseEmbeddingAndFramesFromJson(jsonStr).first
        }
    }

    private var speakerModel: SpeakerModel? = null
    private var baseModel: Model? = null
    private var isInitialized: Boolean = false

    /**
     * Initializes the native SpeakerModel and base model on Dispatchers.IO.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized && speakerModel != null) return@withContext true

        try {
            val modelDir = prepareSpeakerModel()
            if (modelDir == null || !modelDir.exists()) {
                Log.e(TAG, "Speaker model folder missing or failed to extract")
                return@withContext false
            }

            // Find directory containing final.ext.raw
            val targetFolder = findModelFolder(modelDir) ?: modelDir
            Log.d(TAG, "Loading Kaldi SpeakerModel from ${targetFolder.absolutePath}")
            val spk = SpeakerModel(targetFolder.absolutePath)
            speakerModel = spk

            // Load base acoustic model for recognizer
            val baseModelDir = File(context.filesDir, "model-en-us")
            if (baseModelDir.exists()) {
                baseModel = Model(baseModelDir.absolutePath)
            }

            isInitialized = true
            Log.d(TAG, "[SPK_INIT_SUCCESS] Kaldi x-vector SpeakerModel initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[SPK_INIT_ERROR] Failed initializing SpeakerModel", e)
            false
        }
    }

    /**
     * Extracts a 128-dimensional speaker embedding from raw 16kHz 16-bit mono PCM audio.
     */
    suspend fun extractEmbedding(pcmBytes: ByteArray): FloatArray? = withContext(Dispatchers.IO) {
        val spk = speakerModel
        val base = baseModel
        if (spk == null || base == null) {
            Log.e(TAG, "Cannot extract embedding: SpeakerModel or Model not initialized")
            return@withContext null
        }

        try {
            val recognizer = Recognizer(base, SAMPLE_RATE.toFloat(), spk)
            recognizer.setSpeakerModel(spk)

            var bestEmbedding: FloatArray? = null
            var maxSpkFrames = 0

            // Feed PCM in 2048-byte chunks
            val chunkSize = 2048
            var offset = 0
            while (offset < pcmBytes.size) {
                val len = minOf(chunkSize, pcmBytes.size - offset)
                val chunk = pcmBytes.copyOfRange(offset, offset + len)
                val hasCompletedUtterance = recognizer.acceptWaveForm(chunk, len)
                if (hasCompletedUtterance) {
                    val resultJson = recognizer.result
                    val (emb, frames) = parseEmbeddingAndFramesFromJson(resultJson)
                    if (emb != null && frames >= maxSpkFrames) {
                        bestEmbedding = emb
                        maxSpkFrames = frames
                    }
                }
                offset += len
            }

            val finalJson = recognizer.finalResult
            val (finalEmb, finalFrames) = parseEmbeddingAndFramesFromJson(finalJson)
            if (finalEmb != null && (bestEmbedding == null || finalFrames >= maxSpkFrames)) {
                bestEmbedding = finalEmb
                maxSpkFrames = finalFrames
            }
            recognizer.close()

            val embedding = bestEmbedding

            if (embedding != null && embedding.isNotEmpty()) {
                Log.d(TAG, "[SPK_EMBEDDING_EXTRACTED] Extracted ${embedding.size}-d x-vector embedding (spk_frames=$maxSpkFrames)")
            } else {
                Log.w(TAG, "[SPK_EMBEDDING_FAILED] No speaker embedding extracted for sample (${pcmBytes.size} bytes, spk_frames=$finalFrames)")
            }
            embedding
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting speaker embedding", e)
            null
        }
    }

    /**
     * Compares a candidate speech sample against the enrolled owner template.
     */
    suspend fun verifySpeaker(
        candidatePcm: ByteArray,
        enrolledTemplate: FloatArray
    ): SpeakerVerificationResult = withContext(Dispatchers.IO) {
        val trimmedPcm = trimSilence(candidatePcm)
        // 1. Audio quality check on trimmed audio segment
        val quality = validateAudioQuality(trimmedPcm, minDurationSeconds = MIN_VERIFICATION_DURATION_SECONDS)
        if (!quality.isValid) {
            Log.w(TAG, "[VERIFY_QUALITY_REJECT] ${quality.reason}")
            return@withContext SpeakerVerificationResult.InsufficientQuality(quality.reason ?: "Poor audio quality")
        }

        // 2. Extract embedding from trimmed candidate
        val candidateEmbedding = extractEmbedding(trimmedPcm)
            ?: return@withContext SpeakerVerificationResult.Error("Failed to extract voice representation from speech sample")

        // 3. Compute cosine similarity against enrolled template
        val score = cosineSimilarity(candidateEmbedding, enrolledTemplate)
        Log.d(TAG, "[VERIFY_SCORE] Speaker verification similarity: score=$score, threshold=$threshold")

        if (score >= threshold) {
            Log.d(TAG, "[VERIFY_ACCEPTED] Speaker MATCH: Enrolled owner confirmed (score=$score >= $threshold)")
            SpeakerVerificationResult.Match(score, threshold)
        } else {
            Log.w(TAG, "[VERIFY_REJECTED] Speaker REJECTED: Non-owner or uncertain match (score=$score < $threshold)")
            SpeakerVerificationResult.Reject(score, threshold, "Similarity score ($score) did not meet threshold ($threshold)")
        }
    }

    private fun findModelFolder(dir: File): File? {
        if (File(dir, "final.ext.raw").exists()) return dir
        val children = dir.listFiles() ?: return null
        for (child in children) {
            if (child.isDirectory) {
                val found = findModelFolder(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun prepareSpeakerModel(): File? {
        val destDir = File(context.filesDir, SPK_MODEL_DIR_NAME)
        val rawFile = File(destDir, "final.ext.raw")
        val subRawFile = File(destDir, "vosk-model-spk-0.4/final.ext.raw")

        if (rawFile.exists() || subRawFile.exists()) {
            return destDir
        }

        // Extract from assets
        try {
            Log.d(TAG, "Extracting $SPK_MODEL_ZIP_ASSET to ${destDir.absolutePath}...")
            destDir.mkdirs()
            context.assets.open(SPK_MODEL_ZIP_ASSET).use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val outFile = File(destDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                zip.copyTo(out)
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            Log.d(TAG, "Speaker model extracted successfully")
            return destDir
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract speaker model zip asset", e)
            return null
        }
    }

    fun release() {
        try {
            speakerModel?.close()
            speakerModel = null
            baseModel?.close()
            baseModel = null
            isInitialized = false
        } catch (e: Exception) {
            Log.w(TAG, "Error closing SpeakerModel", e)
        }
    }
}
