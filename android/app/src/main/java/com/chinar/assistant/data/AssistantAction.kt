package com.chinar.assistant.data

enum class AssistantAction {
    GET_TIME,
    GET_DATE,
    OPEN_APP,
    TOGGLE_FLASHLIGHT,
    SET_TIMER,
    GET_BATTERY,
    VOLUME_CONTROL,
    HELP,
    UNKNOWN
}

data class IntentResult(
    val action: AssistantAction,
    val parameters: Map<String, String> = emptyMap(),
    val feedbackText: String,
    val isExecutableLocally: Boolean = true
)
