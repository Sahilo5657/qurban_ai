package com.chinar.assistant.speech

/**
 * Architectural interface for on-device wake-word detection.
 *
 * Implementations process raw audio streams (e.g. 16kHz 16-bit mono PCM) looking for activation
 * phrases ("Chinar", "Hey Chinar") entirely locally on the device without cloud streaming.
 *
 * Note: Reliability on physical hardware (e.g. Vosk vs Porcupine) must be verified through
 * device tests across open-app, backgrounded, and screen-off states.
 */
interface WakeWordDetector {

    /**
     * Whether the detector is actively capturing and processing audio.
     */
    val isListening: Boolean

    /**
     * Start continuous listening for wake words on-device.
     *
     * @param onWakeWord Callback invoked when an activation phrase is detected with keyword and confidence.
     * @param onError Callback invoked when an audio capture or recognition error occurs.
     */
    fun startListening(
        onWakeWord: (keyword: String, confidence: Float) -> Unit,
        onError: (errorMessage: String) -> Unit
    )

    /**
     * Temporarily pause or stop listening (e.g. during active conversation or user toggle).
     */
    fun stopListening()

    /**
     * Release hardware microphone and native resources.
     */
    fun release()
}

/**
 * Supported activation phrases recognized by Qurban:
 * "Qurban", "Haa Qurban", "Hey Qurban", "A Qurban".
 */
object WakeWordPhrases {
    const val QURBAN = "qurban"
    const val HAA_QURBAN = "haa qurban"
    const val HEY_QURBAN = "hey qurban"
    const val A_QURBAN = "a qurban"

    // Backward-compatible aliases for internal systems
    const val CHINAR = QURBAN
    const val HEY_CHINAR = HEY_QURBAN
    const val CHINARA = QURBAN
    const val HEY_CHINARA = HEY_QURBAN

    val ALL = listOf(QURBAN, HAA_QURBAN, HEY_QURBAN, A_QURBAN)
}
