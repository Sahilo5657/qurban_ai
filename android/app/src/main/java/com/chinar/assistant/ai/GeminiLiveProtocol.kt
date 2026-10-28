package com.chinar.assistant.ai

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Protocol serializer and deserializer for the Gemini Multimodal Live API over WebSockets.
 *
 * Conforms to the official Google BidiGenerateContent protocol specifications:
 * - Model: models/gemini-3.8-live
 * - Audio Input: 16kHz 16-bit mono PCM, base64 encoded chunks in realtimeInput.audio
 * - Audio Output: 24kHz 16-bit mono PCM, base64 encoded chunks in serverContent.modelTurn.parts
 * - Interruption / Barge-in: serverContent.interrupted == true
 * - In-band tool calling: toolCall.functionCalls[] / toolResponse.functionResponses[]
 */
object GeminiLiveProtocol {

    const val DEFAULT_MODEL = "models/gemini-3.8-live"
    const val LIVE_API_HOST = "generativelanguage.googleapis.com"
    const val LIVE_API_PATH = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    const val INPUT_SAMPLE_RATE = 16000
    const val OUTPUT_SAMPLE_RATE = 24000
    const val INPUT_MIME_TYPE = "audio/pcm;rate=16000"
    const val OUTPUT_MIME_TYPE = "audio/pcm;rate=24000"

    /**
     * Builds the WebSocket endpoint URL with the API key query parameter.
     */
    fun buildWebSocketUrl(apiKey: String): String {
        return "wss://$LIVE_API_HOST$LIVE_API_PATH?key=${apiKey.trim()}"
    }

    /**
     * Builds the mandatory initial setup frame sent immediately upon WebSocket connection.
     */
    fun buildSetupMessage(
        model: String = DEFAULT_MODEL,
        voiceName: String = "Algieba",
        systemInstruction: String = "You are Qurban, a voice-first personal AI assistant on an Android smartphone. Keep responses concise, natural, and helpful for real-time speech.",
        functionDeclarations: List<JSONObject> = emptyList()
    ): String {
        val normalizedModel = if (model.startsWith("models/")) model else "models/$model"

        val setupObj = JSONObject().apply {
            put("model", normalizedModel)

            val generationConfig = JSONObject().apply {
                put("responseModalities", JSONArray().apply { put("AUDIO") })
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", voiceName)
                        })
                    })
                })
            }
            put("generationConfig", generationConfig)

            if (systemInstruction.isNotBlank()) {
                val partsArray = JSONArray().apply {
                    put(JSONObject().apply { put("text", systemInstruction) })
                }
                put("systemInstruction", JSONObject().apply { put("parts", partsArray) })
            }

            if (functionDeclarations.isNotEmpty()) {
                val toolsArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("functionDeclarations", JSONArray(functionDeclarations))
                    })
                }
                put("tools", toolsArray)
            }

            val realtimeInputConfig = JSONObject().apply {
                put("automaticActivityDetection", JSONObject().apply {
                    put("disabled", false)
                    put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                    put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                    put("prefixPaddingMs", 100)
                    put("silenceDurationMs", 1000)
                })
                put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
            }
            put("realtimeInputConfig", realtimeInputConfig)
        }

        return JSONObject().apply { put("setup", setupObj) }.toString()
    }

    /**
     * Builds a real-time input audio chunk message containing 16kHz PCM audio.
     */
    fun buildAudioChunkMessage(pcmBytes: ByteArray): String {
        val base64Audio = Base64.getEncoder().encodeToString(pcmBytes)

        val audioObj = JSONObject().apply {
            put("mimeType", INPUT_MIME_TYPE)
            put("data", base64Audio)
        }

        val realtimeInput = JSONObject().apply {
            put("audio", audioObj)
        }

        return JSONObject().apply { put("realtimeInput", realtimeInput) }.toString()
    }

    /**
     * Builds a text message turn for client input.
     */
    fun buildTextMessage(text: String): String {
        val partObj = JSONObject().apply { put("text", text) }
        val turnsObj = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply { put(partObj) })
        }
        val clientContent = JSONObject().apply {
            put("turns", JSONArray().apply { put(turnsObj) })
            put("turnComplete", true)
        }
        return JSONObject().apply { put("clientContent", clientContent) }.toString()
    }

    /**
     * Builds a tool response message to return the result of a local Android action.
     */
    fun buildToolResponseMessage(callId: String, responseJson: JSONObject): String {
        val functionResponse = JSONObject().apply {
            put("id", callId)
            put("response", responseJson)
        }

        val toolResponse = JSONObject().apply {
            put("functionResponses", JSONArray().apply { put(functionResponse) })
        }

        return JSONObject().apply { put("toolResponse", toolResponse) }.toString()
    }

    /**
     * Parses an incoming server JSON WebSocket frame into typed LiveVoiceEvent(s).
     */
    fun parseServerMessage(jsonString: String): List<LiveVoiceEvent> {
        val events = mutableListOf<LiveVoiceEvent>()
        try {
            val root = JSONObject(jsonString)

            // 1. Check for setupComplete confirmation from server
            if (root.has("setupComplete")) {
                events.add(LiveVoiceEvent.SetupComplete)
            }

            // 2. Check for serverContent
            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")

                // A. Parse model turn parts (audio and/or text) FIRST so audio chunks are queued before turn completion
                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)

                            // Check audio inline data
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val base64Data = inlineData.optString("data")
                                if (base64Data.isNotEmpty()) {
                                    val pcmData = Base64.getDecoder().decode(base64Data)
                                    events.add(LiveVoiceEvent.AudioReceived(pcmData))
                                }
                            }

                            // Check text parts
                            if (part.has("text")) {
                                val text = part.getString("text")
                                if (text.isNotEmpty()) {
                                    events.add(LiveVoiceEvent.TextReceived(text))
                                }
                            }
                        }
                    }
                }

                // B. Check for user interruption / barge-in
                if (serverContent.optBoolean("interrupted", false)) {
                    events.add(LiveVoiceEvent.Interrupted)
                }

                // C. Check for generation completion (model finished token emission) AFTER audio parts
                if (serverContent.optBoolean("generationComplete", false)) {
                    events.add(LiveVoiceEvent.GenerationComplete)
                }

                // D. Check for model turn completion AFTER audio parts
                if (serverContent.optBoolean("turnComplete", false)) {
                    events.add(LiveVoiceEvent.TurnComplete)
                }
            }

            // 3. Check for voiceActivity (server-side VAD activity start / end)
            if (root.has("voiceActivity")) {
                val va = root.getJSONObject("voiceActivity")
                val type = va.optString("type", "")
                val audioOffset = va.optString("audioOffset", "")
                events.add(LiveVoiceEvent.VoiceActivity(type, audioOffset))
            }

            // 4. Check for toolCall
            if (root.has("toolCall")) {
                val toolCall = root.getJSONObject("toolCall")
                val functionCalls = toolCall.optJSONArray("functionCalls")
                if (functionCalls != null) {
                    for (i in 0 until functionCalls.length()) {
                        val call = functionCalls.getJSONObject(i)
                        val id = call.optString("id", "")
                        val name = call.optString("name", "")
                        val argsObj = call.optJSONObject("args") ?: JSONObject()
                        events.add(
                            LiveVoiceEvent.ToolCallReceived(
                                callId = id,
                                functionName = name,
                                argumentsJson = argsObj.toString()
                            )
                        )
                    }
                }
            }

            // 4. Check for errors
            if (root.has("error")) {
                val errorObj = root.getJSONObject("error")
                val message = errorObj.optString("message", "Unknown error from Gemini Live API")
                val code = errorObj.optInt("code", 0)
                val status = errorObj.optString("status", "")
                val details = buildString {
                    append(message)
                    if (code != 0 || status.isNotEmpty()) {
                        append(" [")
                        if (code != 0) append("code: $code")
                        if (code != 0 && status.isNotEmpty()) append(", ")
                        if (status.isNotEmpty()) append("status: $status")
                        append("]")
                    }
                }
                events.add(LiveVoiceEvent.Error(details))
            }

        } catch (e: Exception) {
            events.add(LiveVoiceEvent.Error("Failed to parse Gemini Live server frame: ${e.message}"))
        }

        return events
    }
}
