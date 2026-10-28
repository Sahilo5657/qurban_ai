package com.chinar.assistant.ai

import android.util.Log
import com.chinar.assistant.audio.RealtimeAudioPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Concrete implementation of LiveVoiceSession using OkHttp WebSocket to stream audio
 * directly to Google's Gemini Multimodal Live API.
 */
class GeminiLiveClient(
    private val scope: CoroutineScope,
    private val context: android.content.Context? = null
) : LiveVoiceSession {

    companion object {
        private const val TAG = "GeminiLiveClient"
    }

    private val _state = MutableStateFlow(LiveSessionState.DISCONNECTED)
    override val state: StateFlow<LiveSessionState> = _state.asStateFlow()

    private val audioPipeline = RealtimeAudioPipeline(scope, context)
    private var webSocket: WebSocket? = null
    private var eventCallback: ((LiveVoiceEvent) -> Unit)? = null

    init {
        audioPipeline.onPlaybackStateChanged = { playing ->
            if (!playing) {
                Log.d(TAG, "AudioPipeline playback finished. Updating session state.")
                if (_state.value == LiveSessionState.STREAMING) {
                    _state.value = LiveSessionState.CONNECTED
                }
                dispatch(LiveVoiceEvent.PlaybackFinished)
            }
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep alive indefinitely for streaming
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val preConnectionAudioQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private val MAX_PRE_CONNECTION_CHUNKS = 50 // Up to ~5.0s of 100ms chunks (160KB)
    private var connectionWatchdogJob: Job? = null

    override fun connect(
        apiKey: String,
        model: String,
        voice: String,
        systemInstruction: String?,
        onEvent: (LiveVoiceEvent) -> Unit
    ) {
        if (_state.value == LiveSessionState.CONNECTING || _state.value == LiveSessionState.CONNECTED) {
            Log.d(TAG, "Already connected or connecting")
            return
        }

        // Clean up any previous socket resources
        try {
            webSocket?.cancel()
        } catch (e: Exception) {
            // ignore
        }
        webSocket = null
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = null
        preConnectionAudioQueue.clear()
        audioPipeline.stopPlayback()
        audioPipeline.stopCapture()

        if (apiKey.isBlank()) {
            _state.value = LiveSessionState.ERROR
            onEvent(LiveVoiceEvent.Error("Gemini API key is missing. Please configure it in Settings."))
            return
        }

        this.eventCallback = onEvent
        _state.value = LiveSessionState.CONNECTING

        // Start connection watchdog: fail closed if connection takes more than 8 seconds
        connectionWatchdogJob = scope.launch(Dispatchers.Main) {
            delay(8000L)
            if (_state.value == LiveSessionState.CONNECTING) {
                Log.w(TAG, "[CONNECT_TIMEOUT] Gemini Live connection timed out after 8000ms")
                audioPipeline.stopCapture()
                disconnect()
                dispatch(LiveVoiceEvent.Error("Connection to Gemini Live timed out. Please check your network connection."))
            }
        }

        // Immediately start audio capture so the user's initial question is not dropped while connecting
        audioPipeline.startCapture { pcmChunk ->
            when (_state.value) {
                LiveSessionState.CONNECTING -> {
                    if (preConnectionAudioQueue.size < MAX_PRE_CONNECTION_CHUNKS) {
                        preConnectionAudioQueue.add(pcmChunk)
                    } else {
                        preConnectionAudioQueue.poll()
                        preConnectionAudioQueue.add(pcmChunk)
                    }
                }
                LiveSessionState.CONNECTED, LiveSessionState.STREAMING, LiveSessionState.INTERRUPTED -> {
                    val audioChunkPayload = GeminiLiveProtocol.buildAudioChunkMessage(pcmChunk)
                    webSocket?.send(audioChunkPayload)
                }
                else -> { /* Disconnected / Error */ }
            }
        }

        val url = GeminiLiveProtocol.buildWebSocketUrl(apiKey)
        val request = Request.Builder().url(url).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected. Sending initial setup payload for model $model...")
                _state.value = LiveSessionState.CONNECTING

                // 1. Send mandatory setup frame
                val agentSystemInstruction = systemInstruction ?: (
                    "You are Qurban, a powerful, voice-first autonomous AI agent operating an Android smartphone for your owner. " +
                    "STRICT 4-LANGUAGE RESTRICTION: You must strictly and exclusively speak, listen, understand, and reply ONLY in 4 languages: English, Pashto, Urdu, and Hindi (including conversational Hinglish and Roman Urdu). Absolutely NEVER use any other language. " +
                    "FAST COMMANDS: For WhatsApp messaging, always execute in a single command using send_whatsapp_message without bouncing between screens or going home. " +
                    "IN-CHAT BOT: When asked to conversate or act like a bot in chat, inspect messages with read_chat_messages, match the sender's language (English->English, Urdu->Urdu, Hinglish->Hinglish, Hindi->Hindi, Pashto->Pashto), and reply using reply_in_chat. " +
                    "Answer concisely and naturally for real-time speech."
                )

                val setupPayload = GeminiLiveProtocol.buildSetupMessage(
                    model = model,
                    voiceName = voice,
                    systemInstruction = agentSystemInstruction,
                    functionDeclarations = com.chinar.assistant.agent.tools.ToolDeclarations.getAllDeclarations()
                )
                Log.d(TAG, "Sending setup frame with ${com.chinar.assistant.agent.tools.ToolDeclarations.getAllDeclarations().size} tools: $setupPayload")
                webSocket.send(setupPayload)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(webSocket, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleIncomingMessage(webSocket, bytes.utf8())
            }

            private fun handleIncomingMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Incoming server message (${text.length} chars): ${text.take(300)}")
                val events = GeminiLiveProtocol.parseServerMessage(text)
                for (event in events) {
                    when (event) {
                        is LiveVoiceEvent.SetupComplete -> {
                            connectionWatchdogJob?.cancel()
                            connectionWatchdogJob = null
                            _state.value = LiveSessionState.CONNECTED
                            val queuedCount = preConnectionAudioQueue.size
                            Log.d(TAG, "Gemini Live setupComplete confirmed. Flushing $queuedCount pre-connection audio chunks...")
                            var sentCount = 0
                            while (preConnectionAudioQueue.isNotEmpty()) {
                                val chunk = preConnectionAudioQueue.poll() ?: break
                                val payload = GeminiLiveProtocol.buildAudioChunkMessage(chunk)
                                webSocket.send(payload)
                                sentCount++
                            }
                            Log.d(TAG, "[MIC_PREBUFFER_FLUSH] Successfully flushed $sentCount audio chunks to WebSocket")
                            dispatch(event)
                        }
                        is LiveVoiceEvent.AudioReceived -> {
                            _state.value = LiveSessionState.STREAMING
                            audioPipeline.enqueuePlaybackChunk(event.pcmData, audioPipeline.getCurrentEpoch())
                            dispatch(event)
                        }
                        is LiveVoiceEvent.Interrupted -> {
                            Log.d(TAG, "Interrupted by user speech (barge-in)")
                            _state.value = LiveSessionState.INTERRUPTED
                            audioPipeline.stopPlaybackAndClear()
                            dispatch(event)
                        }
                        is LiveVoiceEvent.GenerationComplete -> {
                            Log.d(TAG, "GenerationComplete received from model")
                            audioPipeline.signalTurnEnding()
                            dispatch(event)
                        }
                        is LiveVoiceEvent.TurnComplete -> {
                            Log.d(TAG, "TurnComplete received from model")
                            audioPipeline.signalTurnEnding()
                            if (!audioPipeline.isPlaying && _state.value == LiveSessionState.STREAMING) {
                                _state.value = LiveSessionState.CONNECTED
                            }
                            dispatch(event)
                        }
                        is LiveVoiceEvent.VoiceActivity -> {
                            Log.d(TAG, "VoiceActivity detected by server VAD: type=${event.type}, offset=${event.audioOffset}")
                            dispatch(event)
                        }
                        is LiveVoiceEvent.ToolCallReceived -> {
                            dispatch(event)
                        }
                        is LiveVoiceEvent.TextReceived -> {
                            dispatch(event)
                        }
                        is LiveVoiceEvent.PlaybackFinished -> {
                            dispatch(event)
                        }
                        is LiveVoiceEvent.Closed -> {
                            dispatch(event)
                        }
                        is LiveVoiceEvent.Error -> {
                            Log.e(TAG, "Live API server error: ${event.message}")
                            _state.value = LiveSessionState.ERROR
                            audioPipeline.stopPlayback()
                            audioPipeline.stopCapture()
                            dispatch(event)
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
                connectionWatchdogJob?.cancel()
                connectionWatchdogJob = null
                preConnectionAudioQueue.clear()
                audioPipeline.release()
                this@GeminiLiveClient.webSocket = null
                if (code != 1000) {
                    val cleanReason = sanitize(reason)
                    _state.value = LiveSessionState.ERROR
                    dispatch(LiveVoiceEvent.Error("Server closed ($code): $cleanReason"))
                } else {
                    _state.value = LiveSessionState.DISCONNECTED
                    dispatch(LiveVoiceEvent.Closed)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                connectionWatchdogJob?.cancel()
                connectionWatchdogJob = null
                preConnectionAudioQueue.clear()
                audioPipeline.release()
                this@GeminiLiveClient.webSocket = null
                _state.value = LiveSessionState.DISCONNECTED
                dispatch(LiveVoiceEvent.Closed)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val cleanError = sanitize(t.localizedMessage ?: "Network connection failed")
                connectionWatchdogJob?.cancel()
                connectionWatchdogJob = null
                preConnectionAudioQueue.clear()
                audioPipeline.release()
                try {
                    webSocket.cancel()
                } catch (e: Exception) {
                    // ignore
                }
                this@GeminiLiveClient.webSocket = null
                if (t is java.io.IOException && cleanError.equals("closed", ignoreCase = true)) {
                    Log.d(TAG, "WebSocket connection closed by server: $cleanError")
                    _state.value = LiveSessionState.DISCONNECTED
                    dispatch(LiveVoiceEvent.Closed)
                } else {
                    val responseInfo = response?.let { resp ->
                        " (HTTP ${resp.code} ${resp.message})"
                    } ?: ""
                    val finalMsg = "Live API connection failure: $cleanError$responseInfo"
                    Log.e(TAG, finalMsg, t)
                    _state.value = LiveSessionState.ERROR
                    dispatch(LiveVoiceEvent.Error(finalMsg))
                }
            }
        })
    }

    private fun sanitize(input: String?): String {
        if (input == null) return "Unknown"
        return input.replace(Regex("key=[A-Za-z0-9_\\-]+"), "key=[REDACTED]")
    }

    override fun sendAudioChunk(pcmData: ByteArray) {
        val payload = GeminiLiveProtocol.buildAudioChunkMessage(pcmData)
        webSocket?.send(payload)
    }

    override fun sendToolResponse(callId: String, responseJson: String) {
        try {
            val json = JSONObject(responseJson)
            val payload = GeminiLiveProtocol.buildToolResponseMessage(callId, json)
            webSocket?.send(payload)
            Log.d(TAG, "Sent tool response for callId $callId")
        } catch (e: Exception) {
            Log.e(TAG, "Error formatting tool response", e)
        }
    }

    override fun sendText(text: String) {
        val payload = GeminiLiveProtocol.buildTextMessage(text)
        Log.d(TAG, "Sending client text turn: $payload")
        val sent = webSocket?.send(payload)
        Log.d(TAG, "Sent client text turn result: $sent")
    }

    override fun disconnect() {
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = null
        preConnectionAudioQueue.clear()
        _state.value = LiveSessionState.DISCONNECTED
        audioPipeline.release()
        try {
            webSocket?.close(1000, "Normal closure by user")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing WebSocket", e)
        }
        webSocket = null
        dispatch(LiveVoiceEvent.Closed)
    }

    private fun dispatch(event: LiveVoiceEvent) {
        scope.launch(Dispatchers.Main) {
            eventCallback?.invoke(event)
        }
    }
}
