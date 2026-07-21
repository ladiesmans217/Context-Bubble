package com.contextbubble.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.contextbubble.app.appContainer
import java.util.ArrayDeque

class FlavorAutomationActuator : AutomationActuator {
    override val available: Boolean = true

    override fun execute(context: Context, command: AutomationCommand): AutomationResult {
        if (command is AutomationCommand.OpenApp && context.appContainer.packagePolicy.isBlocked(command.packageName)) {
            return AutomationResult(false, true, "Protected apps cannot be automated")
        }
        if (command !is AutomationCommand.OpenApp && AccessibilityBridge.state.value.blocked) {
            return AutomationResult(false, true, "Automation is disabled on this protected screen")
        }
        val targetPackage = when (command) {
            is AutomationCommand.OpenApp -> command.packageName
            else -> AccessibilityBridge.state.value.foregroundPackage
        }
        if (targetPackage == null || targetPackage !in allowedPackages(context)) {
            return AutomationResult(false, true, "App is not in the Lab automation allowlist")
        }
        val service = AccessibilityBridge.automationService()
            ?: return AutomationResult(false, false, "Screen access is not connected")
        return when (command) {
            is AutomationCommand.OpenApp -> openApp(context, command.packageName)
            is AutomationCommand.ClickText -> clickText(service, command.text)
            is AutomationCommand.SetFocusedText -> setFocusedText(service, command.text)
            AutomationCommand.ScrollForward -> scroll(service)
            AutomationCommand.Back -> {
                val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                AutomationResult(success, success, if (success) "Back performed" else "Back was rejected")
            }
        }
    }

    override fun allowedPackages(context: Context): Set<String> =
        context.getSharedPreferences(ALLOWLIST_PREFERENCES, Context.MODE_PRIVATE)
            .getStringSet(ALLOWLIST_KEY, emptySet())
            .orEmpty()
            .toSet()

    override fun setPackageAllowed(context: Context, packageName: String, allowed: Boolean) {
        if (context.appContainer.packagePolicy.isHardBlocked(packageName)) return
        val preferences = context.getSharedPreferences(ALLOWLIST_PREFERENCES, Context.MODE_PRIVATE)
        val packages = preferences.getStringSet(ALLOWLIST_KEY, emptySet()).orEmpty().toMutableSet()
        if (allowed) packages += packageName else packages -= packageName
        preferences.edit().putStringSet(ALLOWLIST_KEY, packages).apply()
    }

    private fun openApp(context: Context, packageName: String): AutomationResult {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return AutomationResult(false, false, "App is not launchable")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return AutomationResult(true, false, "App launch requested; verify the foreground package before continuing")
    }

    private fun clickText(service: ContextAccessibilityService, text: String): AutomationResult {
        val node = boundedNodes(service.rootInActiveWindow).firstOrNull {
            it.text?.toString()?.equals(text, ignoreCase = true) == true ||
                it.contentDescription?.toString()?.equals(text, ignoreCase = true) == true
        } ?: return AutomationResult(false, false, "No matching visible control")
        val clickable = generateSequence(node) { it.parent }.take(5).firstOrNull { it.isClickable }
            ?: return AutomationResult(false, false, "Matching text is not clickable")
        val success = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return AutomationResult(success, false, if (success) "Click requested; next state must be verified" else "Control rejected click")
    }

    private fun setFocusedText(service: ContextAccessibilityService, text: String): AutomationResult {
        val focus = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return AutomationResult(false, false, "No focused field")
        if (!focus.isEditable || focus.isPassword) return AutomationResult(false, false, "Focused field is protected or not editable")
        val success = focus.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
        )
        return AutomationResult(success, success && focus.text?.toString() == text, if (success) "Text set" else "Field rejected text")
    }

    private fun scroll(service: ContextAccessibilityService): AutomationResult {
        val scrollable = boundedNodes(service.rootInActiveWindow).firstOrNull { it.isScrollable }
            ?: return AutomationResult(false, false, "No scrollable surface")
        val success = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        return AutomationResult(success, false, if (success) "Scroll requested; next state must be verified" else "Surface rejected scroll")
    }

    private fun boundedNodes(root: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (root == null) return@sequence
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        var count = 0
        while (queue.isNotEmpty() && count++ < 80) {
            val node = queue.removeFirst()
            yield(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
    }

    private companion object {
        const val ALLOWLIST_PREFERENCES = "lab_automation"
        const val ALLOWLIST_KEY = "allowed_packages"
    }
}
