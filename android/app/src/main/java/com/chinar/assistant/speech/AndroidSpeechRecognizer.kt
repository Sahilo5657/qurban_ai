package com.chinar.assistant.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class AndroidSpeechRecognizer(
    private val context: Context
) : SpeechRecognizerEngine {

    companion object {
        private const val TAG = "AndroidSpeechRecognizer"
    }

    override val engineName: String = "Android System SpeechRecognizer"

    override val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    // Unverified offline capability: We do not claim offline support until verified on-device
    override val isOfflineCapable: Boolean = false

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening: Boolean = false

    override fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onRmsChanged: (Float) -> Unit
    ) {
        if (!isAvailable) {
            onError("Speech recognition service is not available on this device.")
            return
        }

        try {
            cancel() // Cancel any prior session

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "onReadyForSpeech")
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "onBeginningOfSpeech")
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        onRmsChanged(rmsdB)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d(TAG, "onEndOfSpeech")
                        isListening = false
                    }

                    override fun onError(errorCode: Int) {
                        isListening = false
                        val errorMessage = mapErrorCode(errorCode)
                        Log.w(TAG, "SpeechRecognizer error: $errorCode ($errorMessage)")
                        onError(errorMessage)
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: ""
                        if (text.isNotEmpty()) {
                            onResult(text)
                        } else {
                            onError("No speech recognized. Please try again.")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                val defaultTag = Locale.getDefault().toLanguageTag()
                val allowedTags = listOf("en-US", "ur-PK", "hi-IN", "ps-AF")
                val activeTag = if (allowedTags.any { defaultTag.startsWith(it.take(2), ignoreCase = true) }) {
                    defaultTag
                } else {
                    "en-US"
                }
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, activeTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, activeTag)
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "ur-PK", "hi-IN", "ps-AF"))
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }

            speechRecognizer?.startListening(intent)
            isListening = true
            Log.d(TAG, "startListening called successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            isListening = false
            onError("Speech recognition error: ${e.localizedMessage ?: "Unknown failure"}")
        }
    }

    override fun stopListening() {
        if (isListening) {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping listener", e)
            } finally {
                isListening = false
            }
        }
    }

    override fun cancel() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error canceling speechRecognizer", e)
        } finally {
            speechRecognizer = null
            isListening = false
        }
    }

    override fun destroy() {
        cancel()
    }

    private fun mapErrorCode(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error. Please check microphone."
            SpeechRecognizer.ERROR_CLIENT -> "Client application error."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
            SpeechRecognizer.ERROR_NETWORK -> "Network connection required for online speech recognition."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network operation timed out."
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized. Please speak clearly."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service is currently busy. Please wait a moment."
            SpeechRecognizer.ERROR_SERVER -> "Server recognition error."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected before timeout."
            else -> "Speech recognition encountered an unexpected error ($errorCode)."
        }
    }
}
