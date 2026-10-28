package com.chinar.assistant.data

data class ActionResult(
    val success: Boolean,
    val message: String,
    val details: Map<String, String> = emptyMap()
)
