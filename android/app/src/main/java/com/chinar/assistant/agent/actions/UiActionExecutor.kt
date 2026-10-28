package com.chinar.assistant.agent.actions

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.chinar.assistant.accessibility.ChinarAccessibilityService
import com.chinar.assistant.agent.observation.ScreenObserver
import com.chinar.assistant.data.ActionResult
import kotlinx.coroutines.delay

/**
 * Executes high-level semantic UI interaction primitives (click, enter text, scroll, back/home)
 * on top of [ScreenObserver] and [ChinarAccessibilityService].
 */
class UiActionExecutor(
    private val screenObserver: ScreenObserver
) {

    companion object {
        private const val TAG = "UiActionExecutor"
    }

    /**
     * Clicks on an interactive element by target [elementId] or [semanticText].
     */
    suspend fun clickElement(elementId: Int? = null, semanticText: String? = null): ActionResult {
        val service = ChinarAccessibilityService.getInstance()
            ?: return ActionResult(false, "Accessibility Service is not enabled. Please enable Qurban in Accessibility Settings.")

        var match = screenObserver.findElement(elementId, semanticText)
        if (match == null) {
            // Re-observe once to handle dynamic changes
            screenObserver.observeCurrentScreen()
            match = screenObserver.findElement(elementId, semanticText)
        }

        if (match == null) {
            val identifier = elementId?.let { "id=$it" } ?: "text='$semanticText'"
            return ActionResult(false, "Could not find element on screen with $identifier")
        }

        val (uiNode, nodeInfo) = match
        val label = uiNode.displayLabel.ifBlank { uiNode.className }

        val clicked = service.performClickOnNode(nodeInfo)
        if (!clicked) {
            return ActionResult(false, "Failed to click element [$elementId] \"$label\"")
        }

        // Bounded delay for UI animations and screen transitions
        delay(400)
        val newScreen = screenObserver.observeCurrentScreen()

        val transitionDesc = if (newScreen.packageName != uiNode.resourceId) {
            "Active screen: ${newScreen.packageName}"
        } else {
            "Clicked \"$label\""
        }

        return ActionResult(
            success = true,
            message = "Clicked \"$label\". $transitionDesc",
            details = mapOf(
                "elementId" to uiNode.id.toString(),
                "label" to label,
                "foregroundPackage" to newScreen.packageName
            )
        )
    }

    /**
     * Injects text into an editable input element by [elementId] or [semanticText].
     */
    suspend fun enterText(
        text: String,
        elementId: Int? = null,
        semanticText: String? = null
    ): ActionResult {
        val service = ChinarAccessibilityService.getInstance()
            ?: return ActionResult(false, "Accessibility Service is not enabled. Please enable Qurban in Accessibility Settings.")

        var match = screenObserver.findElement(elementId, semanticText)
        if (match == null) {
            screenObserver.observeCurrentScreen()
            match = screenObserver.findElement(elementId, semanticText)
        }

        if (match == null) {
            val identifier = elementId?.let { "id=$it" } ?: "text='$semanticText'"
            return ActionResult(false, "Could not find input field with $identifier")
        }

        val (uiNode, nodeInfo) = match
        val label = uiNode.displayLabel.ifBlank { "Input field" }

        val success = service.performSetTextOnNode(nodeInfo, text)
        if (!success) {
            return ActionResult(false, "Failed to enter text into [$elementId] \"$label\"")
        }

        delay(300)
        return ActionResult(
            success = true,
            message = "Entered \"$text\" into $label.",
            details = mapOf("text" to text, "elementId" to uiNode.id.toString())
        )
    }

    /**
     * Performs a scroll or swipe gesture on the screen.
     */
    suspend fun scrollScreen(direction: String): ActionResult {
        val service = ChinarAccessibilityService.getInstance()
            ?: return ActionResult(false, "Accessibility Service is not enabled.")

        val isForward = when (direction.lowercase()) {
            "down", "forward", "next" -> true
            "up", "backward", "previous" -> false
            else -> true
        }

        val success = service.performScroll(isForward)
        delay(400)
        screenObserver.observeCurrentScreen()

        return if (success) {
            ActionResult(true, "Scrolled ${if (isForward) "down" else "up"} screen.")
        } else {
            ActionResult(false, "Could not scroll screen.")
        }
    }

    /**
     * Triggers Android global navigation (Back, Home, Recents).
     */
    suspend fun navigateSystem(action: String): ActionResult {
        val service = ChinarAccessibilityService.getInstance()
            ?: return ActionResult(false, "Accessibility Service is not enabled.")

        val globalAction = when (action.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents", "overview" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> AccessibilityService.GLOBAL_ACTION_BACK
        }

        val success = service.performGlobalNavAction(globalAction)
        delay(400)
        val newScreen = screenObserver.observeCurrentScreen()

        return if (success) {
            ActionResult(true, "Navigated $action. Active app: ${newScreen.packageName}")
        } else {
            ActionResult(false, "Failed to navigate $action")
        }
    }
}
