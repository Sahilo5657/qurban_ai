package com.chinar.assistant.actions

import com.chinar.assistant.data.ActionResult
import com.chinar.assistant.data.AssistantAction
import com.chinar.assistant.data.IntentResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionDispatcherTest {

    private class FakeFlashlightAction(
        val expectedResult: ActionResult = ActionResult(true, "Flashlight toggled.")
    ) : ActionHandler {
        var lastReceivedParameters: Map<String, String>? = null

        override suspend fun execute(parameters: Map<String, String>): ActionResult {
            lastReceivedParameters = parameters
            return expectedResult
        }
    }

    @Test
    fun testDispatchFlashlightOn() = runTest {
        val fakeFlashlight = FakeFlashlightAction(ActionResult(true, "Flashlight turned on."))
        val dispatcher = ActionDispatcher(flashlightAction = fakeFlashlight)

        val intent = IntentResult(
            action = AssistantAction.TOGGLE_FLASHLIGHT,
            parameters = mapOf("state" to "on"),
            feedbackText = "Turning on flashlight."
        )

        val result = dispatcher.dispatch(intent)

        assertTrue(result.success)
        assertEquals("Flashlight turned on.", result.message)
        assertEquals("on", fakeFlashlight.lastReceivedParameters?.get("state"))
    }

    @Test
    fun testDispatchTimeAndDate() = runTest {
        val fakeFlashlight = FakeFlashlightAction()
        val dispatcher = ActionDispatcher(flashlightAction = fakeFlashlight)

        val timeIntent = IntentResult(
            action = AssistantAction.GET_TIME,
            feedbackText = "The current time is 2:30 PM."
        )
        val timeResult = dispatcher.dispatch(timeIntent)
        assertTrue(timeResult.success)
        assertEquals("The current time is 2:30 PM.", timeResult.message)

        val dateIntent = IntentResult(
            action = AssistantAction.GET_DATE,
            feedbackText = "Today is Sunday."
        )
        val dateResult = dispatcher.dispatch(dateIntent)
        assertTrue(dateResult.success)
        assertEquals("Today is Sunday.", dateResult.message)
    }

    @Test
    fun testDispatchUnknown() = runTest {
        val fakeFlashlight = FakeFlashlightAction()
        val dispatcher = ActionDispatcher(flashlightAction = fakeFlashlight)

        val unknownIntent = IntentResult(
            action = AssistantAction.UNKNOWN,
            feedbackText = "I don't know that command."
        )
        val result = dispatcher.dispatch(unknownIntent)
        assertFalse(result.success)
        assertEquals("I don't know that command.", result.message)
    }
}
