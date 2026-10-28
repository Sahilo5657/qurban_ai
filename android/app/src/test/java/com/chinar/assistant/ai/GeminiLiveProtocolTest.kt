package com.chinar.assistant.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GeminiLiveProtocolTest {

    @Test
    fun testBuildWebSocketUrl() {
        val apiKey = "test_api_key_12345"
        val url = GeminiLiveProtocol.buildWebSocketUrl(apiKey)

        assertTrue(url.startsWith("wss://generativelanguage.googleapis.com"))
        assertTrue(url.contains("/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"))
        assertTrue(url.endsWith("?key=test_api_key_12345"))
    }

    @Test
    fun testBuildSetupMessage() {
        val setupJsonStr = GeminiLiveProtocol.buildSetupMessage(
            model = "gemini-3.8-live",
            voiceName = "Puck",
            systemInstruction = "You are Chinar.",
            functionDeclarations = listOf(
                JSONObject().apply {
                    put("name", "toggle_flashlight")
                    put("description", "Toggle flashlight")
                }
            )
        )

        val root = JSONObject(setupJsonStr)
        assertTrue(root.has("setup"))

        val setup = root.getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", setup.getString("model"))

        val generationConfig = setup.getJSONObject("generationConfig")
        val modalities = generationConfig.getJSONArray("responseModalities")
        assertEquals("AUDIO", modalities.getString(0))

        val voiceConfig = generationConfig.getJSONObject("speechConfig")
            .getJSONObject("voiceConfig")
            .getJSONObject("prebuiltVoiceConfig")
        assertEquals("Puck", voiceConfig.getString("voiceName"))

        val tools = setup.getJSONArray("tools")
        assertEquals(1, tools.length())
        val declarations = tools.getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals("toggle_flashlight", declarations.getJSONObject(0).getString("name"))

        assertTrue(setup.has("realtimeInputConfig"))
        val realtimeInputConfig = setup.getJSONObject("realtimeInputConfig")
        val activity = realtimeInputConfig.getJSONObject("automaticActivityDetection")
        assertEquals("START_SENSITIVITY_LOW", activity.getString("startOfSpeechSensitivity"))
        assertEquals("END_SENSITIVITY_LOW", activity.getString("endOfSpeechSensitivity"))
        assertEquals(1000, activity.getInt("silenceDurationMs"))
        assertEquals("START_OF_ACTIVITY_INTERRUPTS", realtimeInputConfig.getString("activityHandling"))
    }

    @Test
    fun testBuildAudioChunkMessage() {
        val pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val chunkJsonStr = GeminiLiveProtocol.buildAudioChunkMessage(pcm)

        val root = JSONObject(chunkJsonStr)
        assertTrue(root.has("realtimeInput"))

        val realtimeInput = root.getJSONObject("realtimeInput")
        assertTrue(realtimeInput.has("audio"))

        val audio = realtimeInput.getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))

        val decoded = Base64.getDecoder().decode(audio.getString("data"))
        assertEquals(4, decoded.size)
        assertEquals(0x01.toByte(), decoded[0])
        assertEquals(0x04.toByte(), decoded[3])
    }

    @Test
    fun testParseServerMessageInterruption() {
        val serverJson = """
            {
                "serverContent": {
                    "interrupted": true
                }
            }
        """.trimIndent()

        val events = GeminiLiveProtocol.parseServerMessage(serverJson)
        assertEquals(1, events.size)
        assertTrue(events[0] is LiveVoiceEvent.Interrupted)
    }

    @Test
    fun testParseServerMessageAudioAndText() {
        val rawPcm = byteArrayOf(0x10, 0x20, 0x30)
        val base64 = Base64.getEncoder().encodeToString(rawPcm)

        val serverJson = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": [
                            {
                                "inlineData": {
                                    "mimeType": "audio/pcm;rate=24000",
                                    "data": "$base64"
                                }
                            },
                            {
                                "text": "Hello, I am Chinar."
                            }
                        ]
                    }
                }
            }
        """.trimIndent()

        val events = GeminiLiveProtocol.parseServerMessage(serverJson)
        assertEquals(2, events.size)

        val audioEvent = events[0] as LiveVoiceEvent.AudioReceived
        assertEquals(3, audioEvent.pcmData.size)
        assertEquals(0x10.toByte(), audioEvent.pcmData[0])

        val textEvent = events[1] as LiveVoiceEvent.TextReceived
        assertEquals("Hello, I am Chinar.", textEvent.text)
    }

    @Test
    fun testParseServerMessageToolCall() {
        val serverJson = """
            {
                "toolCall": {
                    "functionCalls": [
                        {
                            "id": "call_12345",
                            "name": "toggle_flashlight",
                            "args": {
                                "state": "on"
                            }
                        }
                    ]
                }
            }
        """.trimIndent()

        val events = GeminiLiveProtocol.parseServerMessage(serverJson)
        assertEquals(1, events.size)

        val toolCall = events[0] as LiveVoiceEvent.ToolCallReceived
        assertEquals("call_12345", toolCall.callId)
        assertEquals("toggle_flashlight", toolCall.functionName)
        assertTrue(toolCall.argumentsJson.contains("\"state\":\"on\""))
    }

    @Test
    fun testBuildToolResponseMessage() {
        val responseJson = JSONObject().apply {
            put("status", "success")
            put("message", "Flashlight turned on")
        }

        val msg = GeminiLiveProtocol.buildToolResponseMessage("call_12345", responseJson)
        val root = JSONObject(msg)
        assertTrue(root.has("toolResponse"))

        val responses = root.getJSONObject("toolResponse").getJSONArray("functionResponses")
        assertEquals(1, responses.length())
        val resp = responses.getJSONObject(0)
        assertEquals("call_12345", resp.getString("id"))
        assertEquals("success", resp.getJSONObject("response").getString("status"))
    }

    @Test
    fun testParseServerMessageSetupComplete() {
        val serverJson = """
            {
                "setupComplete": {}
            }
        """.trimIndent()

        val events = GeminiLiveProtocol.parseServerMessage(serverJson)
        assertEquals(1, events.size)
        assertTrue(events[0] is LiveVoiceEvent.SetupComplete)
    }

    @Test
    fun testParseServerMessageTurnComplete() {
        val serverJson = """
            {
                "serverContent": {
                    "turnComplete": true
                }
            }
        """.trimIndent()

        val events = GeminiLiveProtocol.parseServerMessage(serverJson)
        assertEquals(1, events.size)
        assertTrue(events[0] is LiveVoiceEvent.TurnComplete)
    }

    @Test
    fun testParseServerMessageErrorWithCodeAndStatus() {
        val serverJson = """
            {
                "error": {
                    "code": 404,
                    "message": "Resource not found",
                    "status": "NOT_FOUND"
                }
            }
        """.trimIndent()

        val events = GeminiLiveProtocol.parseServerMessage(serverJson)
        assertEquals(1, events.size)
        val errorEvent = events[0] as LiveVoiceEvent.Error
        assertTrue(errorEvent.message.contains("Resource not found"))
        assertTrue(errorEvent.message.contains("code: 404"))
        assertTrue(errorEvent.message.contains("status: NOT_FOUND"))
    }

    @Test
    fun testParseServerMessageGenerationComplete() {
        val serverJson = """
            {
                "serverContent": {
                    "generationComplete": true
                }
            }
        """.trimIndent()

        val events = GeminiLiveProtocol.parseServerMessage(serverJson)
        assertEquals(1, events.size)
        assertTrue(events[0] is LiveVoiceEvent.GenerationComplete)
    }

    @Test
    fun testParseServerMessageVoiceActivity() {
        val serverJson = """
            {
                "voiceActivity": {
                    "type": "ACTIVITY_START",
                    "audioOffset": "1250ms"
                }
            }
        """.trimIndent()

        val events = GeminiLiveProtocol.parseServerMessage(serverJson)
        assertEquals(1, events.size)
        val va = events[0] as LiveVoiceEvent.VoiceActivity
        assertEquals("ACTIVITY_START", va.type)
        assertEquals("1250ms", va.audioOffset)
    }

    @Test
    fun testParseServerMessageMultipleAudioChunksInOrder() {
        val chunk1Base64 = Base64.getEncoder().encodeToString(byteArrayOf(0x01, 0x02))
        val chunk2Base64 = Base64.getEncoder().encodeToString(byteArrayOf(0x03, 0x04))

        val serverJson = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": [
                            {
                                "inlineData": {
                                    "mimeType": "audio/pcm;rate=24000",
                                    "data": "$chunk1Base64"
                                }
                            },
                            {
                                "inlineData": {
                                    "mimeType": "audio/pcm;rate=24000",
                                    "data": "$chunk2Base64"
                                }
                            }
                        ]
                    }
                }
            }
        """.trimIndent()

        val events = GeminiLiveProtocol.parseServerMessage(serverJson)
        assertEquals(2, events.size)

        val ev1 = events[0] as LiveVoiceEvent.AudioReceived
        assertEquals(0x01.toByte(), ev1.pcmData[0])
        assertEquals(0x02.toByte(), ev1.pcmData[1])

        val ev2 = events[1] as LiveVoiceEvent.AudioReceived
        assertEquals(0x03.toByte(), ev2.pcmData[0])
        assertEquals(0x04.toByte(), ev2.pcmData[1])
    }
}

