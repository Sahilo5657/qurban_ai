package com.chinar.assistant.actions

import android.content.Context
import com.chinar.assistant.agent.actions.AppLauncher
import com.chinar.assistant.agent.actions.DeviceControls
import com.chinar.assistant.data.ActionResult
import com.chinar.assistant.data.AssistantAction
import com.chinar.assistant.data.IntentResult

class ActionDispatcher(
    private val context: Context? = null,
    private val flashlightAction: ActionHandler = context?.let { FlashlightAction(it) } ?: object : ActionHandler {
        override suspend fun execute(parameters: Map<String, String>): ActionResult =
            ActionResult(false, "Context not available for FlashlightAction.")
    },
    private val appLauncher: AppLauncher? = context?.let { AppLauncher(it) },
    private val deviceControls: DeviceControls? = context?.let { DeviceControls(it) }
) {

    suspend fun dispatch(intent: IntentResult): ActionResult {
        return when (intent.action) {
            AssistantAction.TOGGLE_FLASHLIGHT -> {
                flashlightAction.execute(intent.parameters)
            }
            AssistantAction.OPEN_APP -> {
                val appName = intent.parameters["appName"] ?: ""
                appLauncher?.launchApp(appName) ?: ActionResult(false, "App launcher unavailable.")
            }
            AssistantAction.GET_BATTERY -> {
                deviceControls?.getBatteryStatus() ?: ActionResult(false, "Battery status unavailable.")
            }
            AssistantAction.VOLUME_CONTROL -> {
                val direction = intent.parameters["direction"]
                deviceControls?.controlVolume(direction = direction) ?: ActionResult(false, "Volume control unavailable.")
            }
            AssistantAction.GET_TIME,
            AssistantAction.GET_DATE -> {
                ActionResult(
                    success = true,
                    message = intent.feedbackText
                )
            }
            AssistantAction.SET_TIMER -> {
                ActionResult(
                    success = true,
                    message = intent.feedbackText
                )
            }
            AssistantAction.HELP -> {
                ActionResult(
                    success = true,
                    message = intent.feedbackText
                )
            }
            AssistantAction.UNKNOWN -> {
                ActionResult(
                    success = false,
                    message = intent.feedbackText
                )
            }
            else -> {
                ActionResult(
                    success = false,
                    message = intent.feedbackText
                )
            }
        }
    }
}
