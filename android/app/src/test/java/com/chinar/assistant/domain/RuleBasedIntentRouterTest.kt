package com.chinar.assistant.domain

import com.chinar.assistant.data.AssistantAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RuleBasedIntentRouterTest {

    private lateinit var router: RuleBasedIntentRouter

    @Before
    fun setUp() {
        router = RuleBasedIntentRouter()
    }

    @Test
    fun testTimeQuery() {
        val result1 = router.route("what time is it")
        assertEquals(AssistantAction.GET_TIME, result1.action)
        assertTrue(result1.feedbackText.contains("The current time is"))

        val result2 = router.route("Hey Chinar, what's the time?")
        assertEquals(AssistantAction.GET_TIME, result2.action)

        val result2b = router.route("Hey Qurban, what's the time?")
        assertEquals(AssistantAction.GET_TIME, result2b.action)

        val result2c = router.route("Haa Qurban, what's the time?")
        assertEquals(AssistantAction.GET_TIME, result2c.action)

        val result3 = router.route("current time")
        assertEquals(AssistantAction.GET_TIME, result3.action)
    }

    @Test
    fun testDateQuery() {
        val result1 = router.route("what is today's date")
        assertEquals(AssistantAction.GET_DATE, result1.action)
        assertTrue(result1.feedbackText.startsWith("Today is"))

        val result2 = router.route("what day is today")
        assertEquals(AssistantAction.GET_DATE, result2.action)
    }

    @Test
    fun testFlashlightToggle() {
        val onResult = router.route("turn on the flashlight")
        assertEquals(AssistantAction.TOGGLE_FLASHLIGHT, onResult.action)
        assertEquals("on", onResult.parameters["state"])

        val torchOn = router.route("torch on")
        assertEquals(AssistantAction.TOGGLE_FLASHLIGHT, torchOn.action)
        assertEquals("on", torchOn.parameters["state"])

        val offResult = router.route("turn off flashlight")
        assertEquals(AssistantAction.TOGGLE_FLASHLIGHT, offResult.action)
        assertEquals("off", offResult.parameters["state"])

        val twoWordOff = router.route("turn off the flash light!")
        assertEquals(AssistantAction.TOGGLE_FLASHLIGHT, twoWordOff.action)
        assertEquals("off", twoWordOff.parameters["state"])

        val twoWordOn = router.route("turn on flash light")
        assertEquals(AssistantAction.TOGGLE_FLASHLIGHT, twoWordOn.action)
        assertEquals("on", twoWordOn.parameters["state"])
    }

    @Test
    fun testOpenApp() {
        val ytResult = router.route("open YouTube")
        assertEquals(AssistantAction.OPEN_APP, ytResult.action)
        assertEquals("youtube", ytResult.parameters["appName"])

        val camResult = router.route("launch camera")
        assertEquals(AssistantAction.OPEN_APP, camResult.action)
        assertEquals("camera", camResult.parameters["appName"])

        val settingsResult = router.route("start settings")
        assertEquals(AssistantAction.OPEN_APP, settingsResult.action)
        assertEquals("settings", settingsResult.parameters["appName"])
    }

    @Test
    fun testBatteryStatus() {
        val result = router.route("check battery level")
        assertEquals(AssistantAction.GET_BATTERY, result.action)
    }

    @Test
    fun testVolumeControl() {
        val up = router.route("turn up the volume")
        assertEquals(AssistantAction.VOLUME_CONTROL, up.action)
        assertEquals("up", up.parameters["direction"])

        val down = router.route("volume down")
        assertEquals(AssistantAction.VOLUME_CONTROL, down.action)
        assertEquals("down", down.parameters["direction"])

        val mute = router.route("mute")
        assertEquals(AssistantAction.VOLUME_CONTROL, mute.action)
        assertEquals("mute", mute.parameters["direction"])
    }

    @Test
    fun testTimer() {
        val timerResult = router.route("set a timer for 10 minutes")
        assertEquals(AssistantAction.SET_TIMER, timerResult.action)
        assertEquals("10", timerResult.parameters["amount"])
        assertEquals("minutes", timerResult.parameters["unit"])
    }

    @Test
    fun testHelp() {
        val helpResult = router.route("help")
        assertEquals(AssistantAction.HELP, helpResult.action)
    }

    @Test
    fun testUnknownAndEmpty() {
        val emptyResult = router.route("   ")
        assertEquals(AssistantAction.UNKNOWN, emptyResult.action)

        val unknownResult = router.route("quantum teleporter engage")
        assertEquals(AssistantAction.UNKNOWN, unknownResult.action)
    }
}
