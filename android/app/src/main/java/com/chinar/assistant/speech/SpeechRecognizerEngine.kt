package com.chinar.assistant.speech

/**
 * Modular abstraction for speech recognition engines.
 * Keeps recognition behind an interface to allow seamless swapping
 * between Android system ASR and on-device offline engines (e.g., Vosk, Whisper.cpp).
 */
interface SpeechRecognizerEngine {
    val engineName: String
    val isAvailable: Boolean
    val isOfflineCapable: Boolean

    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onRmsChanged: (Float) -> Unit = {}
    )

    fun stopListening()
    fun cancel()
    fun destroy()
}
