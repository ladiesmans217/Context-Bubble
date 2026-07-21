package com.contextbubble.app.overlay

import android.os.SystemClock

/** Lets Accessibility keep reporting the underlying app while an editor overlay owns IME focus. */
object OverlayInteractionState {
    @Volatile var focusableEditorVisible: Boolean = false

    @Volatile private var keepUnderlyingUntilUptimeMs: Long = 0L

    fun editorOpened() {
        focusableEditorVisible = true
        keepUnderlyingUntilUptimeMs = Long.MAX_VALUE
    }

    fun editorClosed() {
        focusableEditorVisible = false
        // Window/accessibility events can trail removeView and IME dismissal.
        keepUnderlyingUntilUptimeMs = SystemClock.uptimeMillis() + EDITOR_CLOSE_GRACE_MS
    }

    fun shouldKeepUnderlyingPackage(): Boolean =
        focusableEditorVisible || SystemClock.uptimeMillis() < keepUnderlyingUntilUptimeMs

    private const val EDITOR_CLOSE_GRACE_MS = 1_500L
}
