package com.contextbubble.app.accessibility

import com.contextbubble.app.domain.FocusedTargetToken
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetValidationTest {
    private val token = FocusedTargetToken("com.chat", 7, "message", "Editor", 3, 3, "hello".hashCode())

    @Test fun `unchanged target validates`() {
        assertTrue(matchesFocusedTarget(token, FocusedNodeDescriptor("com.chat", 7, "message", "Editor", "hello".hashCode())))
    }

    @Test fun `app window or text changes invalidate insertion`() {
        assertFalse(matchesFocusedTarget(token, FocusedNodeDescriptor("com.mail", 7, "message", "Editor", "hello".hashCode())))
        assertFalse(matchesFocusedTarget(token, FocusedNodeDescriptor("com.chat", 8, "message", "Editor", "hello".hashCode())))
        assertFalse(matchesFocusedTarget(token, FocusedNodeDescriptor("com.chat", 7, "message", "Editor", "changed".hashCode())))
    }

    @Test fun `sensitive words require boundaries but longer phrases remain blocked`() {
        assertTrue(looksSensitive(listOf("Enter OTP")))
        assertTrue(looksSensitive(listOf("credit card number")))
        assertFalse(looksSensitive(listOf("Pinterest profile")))
        assertFalse(looksSensitive(listOf("spin class")))
    }
}
