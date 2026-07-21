package com.contextbubble.app.actions

import com.contextbubble.app.domain.RiskLevel
import com.contextbubble.app.domain.ScreenContext

data class ProposedAction(
    val type: String,
    val targetPackage: String? = null,
    val externalWrite: Boolean = false,
    val destructive: Boolean = false,
    val financial: Boolean = false,
    val credentialRelated: Boolean = false,
)

data class PolicyDecision(
    val risk: RiskLevel,
    val allowed: Boolean,
    val confirmationRequired: Boolean,
    val reason: String,
)

interface ActionPolicyEngine {
    fun evaluate(action: ProposedAction, screen: ScreenContext? = null): PolicyDecision
}

class DefaultActionPolicyEngine : ActionPolicyEngine {
    override fun evaluate(action: ProposedAction, screen: ScreenContext?): PolicyDecision {
        if (screen?.sensitive == true || action.financial || action.credentialRelated) {
            return PolicyDecision(RiskLevel.BLOCKED, allowed = false, confirmationRequired = false, reason = "Protected financial or authentication context")
        }
        if (action.destructive || action.externalWrite) {
            return PolicyDecision(RiskLevel.CONSEQUENTIAL, allowed = true, confirmationRequired = true, reason = "An external change requires an exact preview")
        }
        val readOnly = action.type in READ_ONLY_TYPES
        return PolicyDecision(
            risk = if (readOnly) RiskLevel.READ_ONLY else RiskLevel.LOW,
            allowed = true,
            confirmationRequired = false,
            reason = if (readOnly) "User-triggered read-only operation" else "Low-risk local operation",
        )
    }

    private companion object {
        val READ_ONLY_TYPES = setOf("read", "summarize", "copy", "search", "open")
    }
}

