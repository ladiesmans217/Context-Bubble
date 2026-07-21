package com.contextbubble.app.accessibility

import android.graphics.Bitmap
import com.contextbubble.app.domain.FocusedTargetToken
import com.contextbubble.app.domain.ScreenContext
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AccessibilityState(
    val connected: Boolean = false,
    val foregroundPackage: String? = null,
    val blocked: Boolean = false,
    val focusedEditable: Boolean = false,
    val focusedSensitive: Boolean = false,
)

object AccessibilityBridge {
    private var serviceRef = WeakReference<ContextAccessibilityService>(null)
    private val mutableState = MutableStateFlow(AccessibilityState())
    val state: StateFlow<AccessibilityState> = mutableState.asStateFlow()

    internal fun attach(service: ContextAccessibilityService) {
        serviceRef = WeakReference(service)
        mutableState.value = mutableState.value.copy(connected = true)
    }

    internal fun detach(service: ContextAccessibilityService) {
        if (serviceRef.get() === service) serviceRef.clear()
        mutableState.value = AccessibilityState()
    }

    internal fun update(state: AccessibilityState) {
        mutableState.value = state.copy(connected = true)
    }

    fun captureTarget(): FocusedTargetToken? = serviceRef.get()?.captureTarget()
    fun captureScreenContext(): ScreenContext? = serviceRef.get()?.captureScreenContext()
    fun insertText(token: FocusedTargetToken?, text: String): InsertResult =
        serviceRef.get()?.insertText(token, text) ?: InsertResult.NOT_CONNECTED

    fun takeScreenshot(callback: (Result<Bitmap>) -> Unit) {
        serviceRef.get()?.takeUserScreenshot(callback)
            ?: callback(Result.failure(IllegalStateException("Accessibility is not connected")))
    }

    internal fun automationService(): ContextAccessibilityService? = serviceRef.get()
}

enum class InsertResult { INSERTED, PASTED, COPIED, TARGET_CHANGED, BLOCKED, NOT_CONNECTED, FAILED }
