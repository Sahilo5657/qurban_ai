package com.chinar.assistant.domain

import com.chinar.assistant.data.AssistantAction
import com.chinar.assistant.data.IntentResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RuleBasedIntentRouter {

    // Thread-safe LRU cache for parsed static intents to minimize parsing and matching latency
    private val intentCache = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, IntentResult>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntentResult>?): Boolean = size > 100
        }
    )

    fun route(rawQuery: String): IntentResult {
        val query = rawQuery.trim().lowercase(Locale.ROOT)
            .replace(Regex("^(hey|ok|hello|haa|ha|a)?\\s*(qurban|chinar)[,]?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[!?,.]+$"), "")
            .trim()

        if (query.isBlank()) {
            return IntentResult(
                action = AssistantAction.UNKNOWN,
                feedbackText = "I didn't catch that. Please speak a command."
            )
        }

        // Check cache for instant hit
        val cached = intentCache[query]
        if (cached != null) {
            if (cached.action == AssistantAction.GET_TIME) {
                val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                return cached.copy(feedbackText = "The current time is $timeStr.")
            }
            if (cached.action == AssistantAction.GET_DATE) {
                val dateStr = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
                return cached.copy(feedbackText = "Today is $dateStr.")
            }
            return cached
        }

        val result = computeRoute(query, rawQuery)
        intentCache[query] = result
        return result
    }

    private fun computeRoute(query: String, rawQuery: String): IntentResult {

        // Time queries
        if (query.matches(Regex(".*\\b(what\\s+time\\s+is\\s+it|current\\s+time|tell\\s+me\\s+the\\s+time|what's\\s+the\\s+time|time\\s+now|time)\\b.*"))) {
            val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            return IntentResult(
                action = AssistantAction.GET_TIME,
                feedbackText = "The current time is $timeStr."
            )
        }

        // Date queries
        if (query.matches(Regex(".*\\b(what\\s+is\\s+today's\\s+date|what\\s+day\\s+is\\s+today|what's\\s+the\\s+date|today's\\s+date|current\\s+date|what\\s+date\\s+is\\s+it|date)\\b.*"))) {
            val dateStr = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
            return IntentResult(
                action = AssistantAction.GET_DATE,
                feedbackText = "Today is $dateStr."
            )
        }

        // Flashlight / Torch (supports both 'flashlight' and 'flash light', 'torch')
        val flashlightOnMatch = query.matches(Regex(".*\\b(turn\\s+on\\s+(the\\s+)?(flash\\s*light|torch)|(flash\\s*light|torch)\\s+on)\\b.*"))
        if (flashlightOnMatch) {
            return IntentResult(
                action = AssistantAction.TOGGLE_FLASHLIGHT,
                parameters = mapOf("state" to "on"),
                feedbackText = "Turning on the flashlight."
            )
        }
        val flashlightOffMatch = query.matches(Regex(".*\\b(turn\\s+off\\s+(the\\s+)?(flash\\s*light|torch)|(flash\\s*light|torch)\\s+off)\\b.*"))
        if (flashlightOffMatch) {
            return IntentResult(
                action = AssistantAction.TOGGLE_FLASHLIGHT,
                parameters = mapOf("state" to "off"),
                feedbackText = "Turning off the flashlight."
            )
        }

        // App Launching: "open <app>" or "launch <app>"
        val openAppRegex = Regex("(?:open|launch|start)\\s+([a-zA-Z0-9\\s]+)")
        val openMatch = openAppRegex.find(query)
        if (openMatch != null) {
            val appName = openMatch.groupValues[1].trim()
            if (appName.isNotEmpty()) {
                return IntentResult(
                    action = AssistantAction.OPEN_APP,
                    parameters = mapOf("appName" to appName),
                    feedbackText = "Opening $appName."
                )
            }
        }

        // Battery queries
        if (query.matches(Regex(".*\\b(battery\\s+level|battery\\s+status|how\\s+much\\s+battery|battery\\s+percentage|check\\s+battery)\\b.*"))) {
            return IntentResult(
                action = AssistantAction.GET_BATTERY,
                feedbackText = "Checking battery status."
            )
        }

        // Volume control
        if (query.matches(Regex(".*\\b(volume\\s+up|increase\\s+volume|turn\\s+up\\s+(the\\s+)?volume|louder)\\b.*"))) {
            return IntentResult(
                action = AssistantAction.VOLUME_CONTROL,
                parameters = mapOf("direction" to "up"),
                feedbackText = "Increasing volume."
            )
        }
        if (query.matches(Regex(".*\\b(volume\\s+down|decrease\\s+volume|turn\\s+down\\s+(the\\s+)?volume|quieter)\\b.*"))) {
            return IntentResult(
                action = AssistantAction.VOLUME_CONTROL,
                parameters = mapOf("direction" to "down"),
                feedbackText = "Decreasing volume."
            )
        }
        if (query.matches(Regex(".*\\b(mute|silence\\s+volume|turn\\s+off\\s+sound)\\b.*"))) {
            return IntentResult(
                action = AssistantAction.VOLUME_CONTROL,
                parameters = mapOf("direction" to "mute"),
                feedbackText = "Muting audio."
            )
        }

        // Timer
        val timerRegex = Regex("(?:set\\s+a?\\s*timer\\s+for|timer\\s+for|timer)\\s+(\\d+)\\s*(minutes?|seconds?|hours?)?")
        val timerMatch = timerRegex.find(query)
        if (timerMatch != null) {
            val amount = timerMatch.groupValues[1]
            val rawUnit = timerMatch.groupValues[2].ifEmpty { "minutes" }
            val unit = if (rawUnit.endsWith("s")) rawUnit else "${rawUnit}s"
            return IntentResult(
                action = AssistantAction.SET_TIMER,
                parameters = mapOf("amount" to amount, "unit" to unit),
                feedbackText = "Setting a timer for $amount $unit."
            )
        }

        // Help
        if (query.matches(Regex(".*\\b(help|what\\s+can\\s+you\\s+do|commands|supported\\s+actions)\\b.*"))) {
            return IntentResult(
                action = AssistantAction.HELP,
                feedbackText = "You can ask me for the time, date, battery level, to turn the flashlight on or off, to open an app like YouTube, or to set a timer."
            )
        }

        // Fallback
        return IntentResult(
            action = AssistantAction.UNKNOWN,
            feedbackText = "I don't know how to handle: \"$rawQuery\" yet. Try asking for the time, flashlight, or to open an app."
        )
    }
}
