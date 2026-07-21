package com.contextbubble.app.accessibility

import android.content.Context

sealed interface AutomationCommand {
    data class ClickText(val text: String) : AutomationCommand
    data class SetFocusedText(val text: String) : AutomationCommand
    data object ScrollForward : AutomationCommand
    data object Back : AutomationCommand
    data class OpenApp(val packageName: String) : AutomationCommand
}

data class AutomationResult(
    val success: Boolean,
    val verified: Boolean,
    val detail: String,
)

interface AutomationActuator {
    val available: Boolean
    fun execute(context: Context, command: AutomationCommand): AutomationResult
    fun allowedPackages(context: Context): Set<String> = emptySet()
    fun setPackageAllowed(context: Context, packageName: String, allowed: Boolean) = Unit
}
