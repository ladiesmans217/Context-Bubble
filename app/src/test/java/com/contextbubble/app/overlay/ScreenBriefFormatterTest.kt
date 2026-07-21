package com.contextbubble.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenBriefFormatterTest {
    @Test
    fun `parses overview and markdown bullets into a compact brief`() {
        val brief = parseScreenBrief(
            """
            ## Screen summary
            A browser page is showing an Android article.
            - The headline is visible near the top.
            * A search field is available.
            • The page has a navigation menu.
            """.trimIndent(),
        )

        assertEquals("A browser page is showing an Android article.", brief.overview)
        assertEquals(
            listOf(
                "The headline is visible near the top.",
                "A search field is available.",
                "The page has a navigation menu.",
            ),
            brief.details,
        )
    }

    @Test
    fun `turns an unformatted paragraph into an overview and bullets`() {
        val brief = parseScreenBrief(
            "A chat is open. The latest message asks for a reply. The keyboard is visible.",
        )

        assertEquals("A chat is open.", brief.overview)
        assertEquals(
            listOf("The latest message asks for a reply.", "The keyboard is visible."),
            brief.details,
        )
        assertEquals(
            "A chat is open.\n\n• The latest message asks for a reply.\n\n• The keyboard is visible.",
            brief.asPlainText(),
        )
    }

    @Test
    fun `voice fallback shares screen only for an explicit screen request`() {
        assertTrue(requestsScreenContext("What is shown on this screen?"))
        assertTrue(requestsScreenContext("Bhai ye screen pe kya dikh raha hai?"))
        assertTrue(requestsScreenContext("Summarize this page"))
        assertFalse(requestsScreenContext("Draft a polite reply to Sunil"))
        assertFalse(requestsScreenContext("Remember that my meeting is tomorrow"))
    }
}
