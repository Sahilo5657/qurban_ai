package com.chinar.assistant.ai

import kotlinx.coroutines.flow.StateFlow

/**
 * Connection states for the bidirectional real-time voice streaming session.
 */
enum class LiveSessionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    STREAMING,
    INTERRUPTED,
    ERROR
}

/**
 * Event received from the Gemini Multimodal Live API over the bidirectional WebSocket.
 */
sealed class LiveVoiceEvent {
    /**
     * Raw 24kHz 16-bit mono PCM audio data received from the model to be played via AudioTrack.
     */
    data class AudioReceived(val pcmData: ByteArray) : LiveVoiceEvent()

    /**
     * Model emitted a text transcription or thought token.
     */
    data class TextReceived(val text: String) : LiveVoiceEvent()

    /**
     * User barge-in detected by server VAD: client must immediately stop AudioTrack playback.
     */
    object Interrupted : LiveVoiceEvent()

    /**
     * Model requested a local hardware tool execution.
     */
    data class ToolCallReceived(
        val callId: String,
        val functionName: String,
        val argumentsJson: String
    ) : LiveVoiceEvent()

    /**
     * Server acknowledged and completed session setup configuration.
     */
    object SetupComplete : LiveVoiceEvent()

    /**
     * Model completed its current turn.
     */
    object TurnComplete : LiveVoiceEvent()

    /**
     * Live session encountered an error or network drop.
     */
    data class Error(val message: String) : LiveVoiceEvent()

    /**
     * Model completed generating content for current turn.
     */
    object GenerationComplete : LiveVoiceEvent()

    /**
     * Voice activity detected by server VAD (ACTIVITY_START or ACTIVITY_END).
     */
    data class VoiceActivity(val type: String, val audioOffset: String) : LiveVoiceEvent()

    /**
     * AudioTrack and playback queue have completely drained and finished playing.
     */
    object PlaybackFinished : LiveVoiceEvent()

    /**
     * Session closed normally.
     */
    object Closed : LiveVoiceEvent()
}

/**
 * Architectural interface for the real-time bidirectional voice streaming session
 * (e.g. Gemini Multimodal Live API via WebSockets).
 */
interface LiveVoiceSession {

    val state: StateFlow<LiveSessionState>

    /**
     * Connect to the live voice streaming endpoint using the supplied credentials and parameters.
     */
    fun connect(
        apiKey: String,
        model: String = "gemini-3.8-live",
        voice: String = "Algieba",
        systemInstruction: String? = null,
        onEvent: (LiveVoiceEvent) -> Unit
    )

    /**
     * Stream a chunk of 16kHz 16-bit mono PCM user audio into the live session.
     */
    fun sendAudioChunk(pcmData: ByteArray)

    /**
     * Send the result of a local tool execution back to the model over the live socket.
     */
    fun sendToolResponse(callId: String, responseJson: String)

    /**
     * Send a text message turn into the live session.
     */
    fun sendText(text: String)

    /**
     * Gracefully close the WebSocket session and release audio buffers.
     */
    fun disconnect()
}
