package com.contextbubble.app.accessibility

import org.junit.Assert.assertFalse
import org.junit.Test

class FlavorAutomationIsolationTest {
    @Test
    fun playActuatorIsUnavailable() {
        assertFalse(FlavorAutomationActuator().available)
    }
}
