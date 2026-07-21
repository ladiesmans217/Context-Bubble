package com.contextbubble.app.accessibility

import android.content.Context

class FlavorAutomationActuator : AutomationActuator {
    override val available: Boolean = false

    override fun execute(context: Context, command: AutomationCommand): AutomationResult =
        AutomationResult(false, false, "General UI automation is not included in the Play build")
}

