package com.chinar.assistant.actions

import com.chinar.assistant.data.ActionResult

interface ActionHandler {
    suspend fun execute(parameters: Map<String, String>): ActionResult
}
