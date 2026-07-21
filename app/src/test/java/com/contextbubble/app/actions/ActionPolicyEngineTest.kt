package com.contextbubble.app.actions

import com.contextbubble.app.domain.RiskLevel
import com.contextbubble.app.domain.ScreenContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPolicyEngineTest {
    private val engine = DefaultActionPolicyEngine()

    @Test fun `financial actions are always blocked`() {
        val decision = engine.evaluate(ProposedAction(type = "send", externalWrite = true, financial = true))
        assertEquals(RiskLevel.BLOCKED, decision.risk)
        assertFalse(decision.allowed)
    }

    @Test fun `external writes require confirmation`() {
        val decision = engine.evaluate(ProposedAction(type = "message", externalWrite = true))
        assertEquals(RiskLevel.CONSEQUENTIAL, decision.risk)
        assertTrue(decision.allowed)
        assertTrue(decision.confirmationRequired)
    }

    @Test fun `sensitive context overrides a read only action`() {
        val screen = ScreenContext("auth", 1, sensitive = true, editable = false, capturedAtEpochMs = 1)
        val decision = engine.evaluate(ProposedAction(type = "summarize"), screen)
        assertEquals(RiskLevel.BLOCKED, decision.risk)
    }
}

