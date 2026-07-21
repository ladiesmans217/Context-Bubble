package com.contextbubble.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class EditableValueTest {
    @Test fun `visible hint is not treated as editable content`() {
        assertEquals("", editableValue("Message", isShowingHintText = true))
    }

    @Test fun `real editable content is preserved`() {
        assertEquals("Earlier text", editableValue("Earlier text", isShowingHintText = false))
    }
}
