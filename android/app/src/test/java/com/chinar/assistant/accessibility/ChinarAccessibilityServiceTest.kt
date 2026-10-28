package com.chinar.assistant.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ChinarAccessibilityServiceTest {

    @Test
    fun testInitialServiceInstanceIsNull() {
        assertNull(
            "Service instance should be null before Android framework binds it",
            ChinarAccessibilityService.getInstance()
        )
    }

    @Test
    fun testInitialServiceIsNotRunning() {
        assertFalse(
            "Service should not be reported as running before connection",
            ChinarAccessibilityService.isAccessibilityRunning()
        )
    }
}
