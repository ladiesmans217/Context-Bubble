package com.contextbubble.app.accessibility

import com.contextbubble.app.domain.FocusedTargetToken

internal data class FocusedNodeDescriptor(
    val packageName: String?,
    val windowId: Int,
    val viewId: String?,
    val className: String?,
    val textFingerprint: Int,
)

internal fun matchesFocusedTarget(token: FocusedTargetToken, node: FocusedNodeDescriptor): Boolean {
    if (node.packageName != token.packageName || node.windowId != token.windowId) return false
    if (token.viewId != null && node.viewId != token.viewId) return false
    if (token.className != null && node.className != token.className) return false
    return node.textFingerprint == token.textFingerprint
}

internal fun looksSensitive(values: List<CharSequence?>): Boolean {
    val descriptor = values.joinToString(" ") { it?.toString().orEmpty() }.lowercase()
    return SENSITIVE_PHRASES.any(descriptor::contains) || SENSITIVE_SHORT_WORDS.containsMatchIn(descriptor)
}

private val SENSITIVE_SHORT_WORDS = Regex("\\b(?:pin|otp|cvv)\\b")
private val SENSITIVE_PHRASES = setOf(
    "password", "passcode", "one time password", "credit card", "debit card",
    "security code", "verification code", "authenticator", "biometric",
)
