package com.chinar.assistant.agent.tools

import android.content.Context
import android.util.Log
import com.chinar.assistant.agent.actions.AppIntegrations
import com.chinar.assistant.agent.actions.AppLauncher
import com.chinar.assistant.agent.actions.DeviceControls
import com.chinar.assistant.agent.actions.UiActionExecutor
import com.chinar.assistant.agent.observation.ScreenObserver
import com.chinar.assistant.data.ActionResult
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Dispatches model-requested tool calls to appropriate interaction layers,
 * provides tool deduplication, bounds execution, and formats structured responses.
 */
class ToolCallDispatcher(
    private val context: Context? = null,
    val screenObserver: ScreenObserver = ScreenObserver(),
    val uiActionExecutor: UiActionExecutor = UiActionExecutor(screenObserver),
    val appLauncher: AppLauncher? = context?.let { AppLauncher(it) },
    val deviceControls: DeviceControls? = context?.let { DeviceControls(it) },
    val appIntegrations: AppIntegrations? = context?.let { AppIntegrations(it) }
) {

    companion object {
        private const val TAG = "ToolCallDispatcher"
        private const val DEDUPLICATION_WINDOW_MS = 30000L
        private const val MAX_DEDUP_CACHE_SIZE = 100
    }

    // Thread-safe LRU cache for callId deduplication
    private val recentCalls = Collections.synchronizedMap(
        object : LinkedHashMap<String, Pair<Long, JSONObject>>(MAX_DEDUP_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Pair<Long, JSONObject>>?): Boolean {
                return size > MAX_DEDUP_CACHE_SIZE
            }
        }
    )

    // Consecutive identical action tracker for loop detection
    private var lastActionSignature: String = ""
    private var consecutiveActionCount: Int = 0

    /**
     * Application-level authorization gate.
     * When configured, MUST return true for any tool call to execute.
     * Model-hallucinated arguments cannot bypass this gate.
     */
    var authorizationGate: (() -> Boolean)? = null

    suspend fun dispatchToolCall(callId: String, functionName: String, argumentsJson: String): JSONObject {
        // 0. Enforce application authorization gate
        if (authorizationGate != null && authorizationGate?.invoke() != true) {
            Log.w(TAG, "[SECURITY_GATE] Blocked tool execution for '$functionName' (callId=$callId): Session not authorized by enrolled owner.")
            return JSONObject().apply {
                put("status", "unauthorized")
                put("error", "Security Policy: Action blocked. Speaker verification required before tool execution.")
            }
        }

        // 1. Check for duplicate tool callback
        val now = System.currentTimeMillis()
        val cached = recentCalls[callId]
        if (cached != null && (now - cached.first) < DEDUPLICATION_WINDOW_MS) {
            Log.w(TAG, "[TOOL_DEDUP] Duplicate tool execution prevented for callId: $callId ($functionName)")
            return cached.second
        }

        val jsonArgs = try {
            JSONObject(argumentsJson)
        } catch (e: Exception) {
            JSONObject()
        }

        // 2. Loop detection check for mutating actions (read-only queries like observe_screen are exempt)
        val isReadOnlyQuery = functionName == "observe_screen" || functionName == "device_query"
        if (!isReadOnlyQuery) {
            val actionSig = "$functionName:${jsonArgs.toString().hashCode()}"
            if (actionSig == lastActionSignature) {
                consecutiveActionCount++
                if (consecutiveActionCount >= 3) {
                    Log.w(TAG, "[LOOP_DETECTED] Mutating action $functionName repeated 3 consecutive times with same arguments")
                    val loopResult = JSONObject().apply {
                        put("status", "loop_detected")
                        put("message", "This action has been executed 3 times in a row without progress. Please inspect the screen or ask the user for guidance.")
                    }
                    recentCalls[callId] = Pair(now, loopResult)
                    return loopResult
                }
            } else {
                lastActionSignature = actionSig
                consecutiveActionCount = 1
            }
        }

        Log.d(TAG, "[TOOL_EXECUTE] Executing tool: '$functionName' (callId=$callId, args=$argumentsJson)")

        // 3. Dispatch to appropriate layer
        val resultJson = try {
            when (functionName) {
                "observe_screen" -> executeObserveScreen()
                "click_element" -> executeClickElement(jsonArgs)
                "enter_text" -> executeEnterText(jsonArgs)
                "scroll_screen" -> executeScrollScreen(jsonArgs)
                "navigate_system" -> executeNavigateSystem(jsonArgs)
                "open_app" -> executeOpenApp(jsonArgs)
                "open_settings" -> executeOpenSettings(jsonArgs)
                "device_query" -> executeDeviceQuery(jsonArgs)
                "device_action" -> executeDeviceAction(jsonArgs)
                "search_media" -> executeSearchMedia(jsonArgs)
                "prepare_communication" -> executePrepareCommunication(jsonArgs)
                "web_search" -> executeWebSearch(jsonArgs)
                "send_whatsapp_message" -> executeSendWhatsAppMessage(jsonArgs)
                "read_chat_messages" -> executeReadChatMessages(jsonArgs)
                "reply_in_chat" -> executeReplyInChat(jsonArgs)
                "change_voice" -> executeChangeVoice(jsonArgs)
                else -> {
                    JSONObject().apply {
                        put("status", "error")
                        put("message", "Unknown tool function: '$functionName'")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[TOOL_ERROR] Uncaught error executing $functionName", e)
            JSONObject().apply {
                put("status", "error")
                put("message", "Execution error: ${e.localizedMessage}")
            }
        }

        // Cache result for deduplication
        recentCalls[callId] = Pair(now, resultJson)
        return resultJson
    }

    private fun executeObserveScreen(): JSONObject {
        val state = screenObserver.observeCurrentScreen()
        return JSONObject().apply {
            put("status", "success")
            put("package", state.packageName)
            put("window", state.windowTitle)
            put("summary", state.toPromptSummary())
            put("elementCount", state.elements.size)
        }
    }

    private suspend fun executeClickElement(args: JSONObject): JSONObject {
        val elementId = if (args.has("element_id") && !args.isNull("element_id")) args.optInt("element_id", -1) else null
        val semanticText = args.optString("semantic_text", "").takeIf { it.isNotBlank() }

        val actionResult = uiActionExecutor.clickElement(elementId, semanticText)
        return toToolResponse(actionResult)
    }

    private suspend fun executeEnterText(args: JSONObject): JSONObject {
        val text = args.optString("text", "")
        val elementId = if (args.has("element_id") && !args.isNull("element_id")) args.optInt("element_id", -1) else null
        val semanticText = args.optString("semantic_text", "").takeIf { it.isNotBlank() }

        val actionResult = uiActionExecutor.enterText(text, elementId, semanticText)
        return toToolResponse(actionResult)
    }

    private suspend fun executeScrollScreen(args: JSONObject): JSONObject {
        val direction = args.optString("direction", "down")
        val actionResult = uiActionExecutor.scrollScreen(direction)
        return toToolResponse(actionResult)
    }

    private suspend fun executeNavigateSystem(args: JSONObject): JSONObject {
        val action = args.optString("action", "back")
        val actionResult = uiActionExecutor.navigateSystem(action)
        return toToolResponse(actionResult)
    }

    private suspend fun executeOpenApp(args: JSONObject): JSONObject {
        val launcher = appLauncher ?: return toToolResponse(ActionResult(false, "App launcher unavailable."))
        val appName = args.optString("app_name", "")
        val actionResult = launcher.launchApp(appName)
        if (actionResult.success) {
            // Briefly pause for app launch animation, then observe its initial screen
            delay(800)
            val newScreen = screenObserver.observeCurrentScreen()
            return JSONObject().apply {
                put("status", "success")
                put("message", actionResult.message)
                put("activePackage", newScreen.packageName)
                put("initialScreenSummary", newScreen.toPromptSummary(maxElements = 15))
            }
        }
        return toToolResponse(actionResult)
    }

    private fun executeOpenSettings(args: JSONObject): JSONObject {
        val controls = deviceControls ?: return toToolResponse(ActionResult(false, "Device controls unavailable."))
        val page = args.optString("page", "general")
        val actionResult = controls.openSettingsPage(page)
        return toToolResponse(actionResult)
    }

    private fun executeDeviceQuery(args: JSONObject): JSONObject {
        val controls = deviceControls ?: return toToolResponse(ActionResult(false, "Device controls unavailable."))
        val queryType = args.optString("query_type", "battery")
        val actionResult = when (queryType.lowercase()) {
            "battery" -> controls.getBatteryStatus()
            "volume" -> controls.controlVolume()
            else -> ActionResult(false, "Unknown query type: $queryType")
        }
        return toToolResponse(actionResult)
    }

    private suspend fun executeDeviceAction(args: JSONObject): JSONObject {
        val controls = deviceControls ?: return toToolResponse(ActionResult(false, "Device controls unavailable."))
        val action = args.optString("action", "")
        val actionResult = when (action.lowercase()) {
            "toggle_flashlight" -> {
                val state = args.optString("state", "").takeIf { it.isNotBlank() }
                controls.toggleFlashlight(state)
            }
            "volume_up" -> controls.controlVolume(direction = "up")
            "volume_down" -> controls.controlVolume(direction = "down")
            "volume_mute" -> controls.controlVolume(direction = "mute")
            else -> ActionResult(false, "Unknown device action: $action")
        }
        return toToolResponse(actionResult)
    }

    private fun executeSearchMedia(args: JSONObject): JSONObject {
        val integrations = appIntegrations ?: return toToolResponse(ActionResult(false, "App integrations unavailable."))
        val query = args.optString("query", "")
        val actionResult = integrations.searchYouTube(query)
        return toToolResponse(actionResult)
    }

    private fun executePrepareCommunication(args: JSONObject): JSONObject {
        val integrations = appIntegrations ?: return toToolResponse(ActionResult(false, "App integrations unavailable."))
        val type = args.optString("type", "sms")
        val recipient = args.optString("recipient", "")
        val messageBody = args.optString("message_body", "").takeIf { it.isNotBlank() }
        val confirmed = args.optBoolean("confirmed", false)

        val actionResult = integrations.prepareCommunication(type, recipient, messageBody, confirmed)
        return toToolResponse(actionResult)
    }

    private fun executeWebSearch(args: JSONObject): JSONObject {
        val query = args.optString("query", "").trim()
        if (query.isBlank()) {
            return JSONObject().apply {
                put("status", "error")
                put("message", "Please specify a search query.")
            }
        }

        // Return structured signal directing Gemini Live to synthesize real-time knowledge
        return JSONObject().apply {
            put("status", "success")
            put("query", query)
            put("guidance", "Search query '$query' received. Synthesize the most current, accurate, and concise real-world knowledge to answer the user.")
        }
    }

    private suspend fun executeSendWhatsAppMessage(args: JSONObject): JSONObject {
        val integrations = appIntegrations ?: return toToolResponse(ActionResult(false, "App integrations unavailable."))
        val recipient = args.optString("recipient", args.optString("contact_name", "")).trim()
        val message = args.optString("message", "").trim()
        val autoSend = args.optBoolean("auto_send", true)

        if (recipient.isBlank()) {
            return toToolResponse(ActionResult(false, "Please specify a contact name or phone number for WhatsApp."))
        }
        if (message.isBlank()) {
            return toToolResponse(ActionResult(false, "Please specify the message text to send."))
        }

        val actionResult = integrations.sendWhatsAppMessage(recipient, message, autoSend)
        return toToolResponse(actionResult)
    }

    private fun executeReadChatMessages(args: JSONObject): JSONObject {
        val integrations = appIntegrations ?: return JSONObject().apply {
            put("status", "error")
            put("message", "App integrations unavailable.")
        }
        val limit = args.optInt("limit", 10)
        return integrations.readChatMessages(limit)
    }

    private suspend fun executeReplyInChat(args: JSONObject): JSONObject {
        val integrations = appIntegrations ?: return toToolResponse(ActionResult(false, "App integrations unavailable."))
        val message = args.optString("message", "").trim()
        val autoSend = args.optBoolean("auto_send", true)

        if (message.isBlank()) {
            return toToolResponse(ActionResult(false, "Please provide the reply message text."))
        }

        val actionResult = integrations.replyInChat(message, autoSend)
        return toToolResponse(actionResult)
    }

    private fun executeChangeVoice(args: JSONObject): JSONObject {
        val voiceName = args.optString("voice_name", "").trim()
        if (voiceName.isBlank()) {
            return toToolResponse(ActionResult(false, "Please specify the voice name to activate."))
        }
        val ctx = context ?: return toToolResponse(ActionResult(false, "Application context unavailable."))
        val prefs = com.chinar.assistant.data.SecurePreferences(ctx)
        val matchedVoice = com.chinar.assistant.data.SecurePreferences.getVoiceOption(voiceName)
        prefs.selectedVoice = matchedVoice.name

        return JSONObject().apply {
            put("status", "success")
            put("message", "Voice personality changed to ${matchedVoice.name} (${matchedVoice.description}).")
            put("voice_name", matchedVoice.name)
            put("description", matchedVoice.description)
        }
    }

    private fun toToolResponse(result: ActionResult): JSONObject {
        return JSONObject().apply {
            put("status", if (result.success) "success" else "error")
            put("message", result.message)
            for ((k, v) in result.details) {
                put(k, v)
            }
        }
    }
}
