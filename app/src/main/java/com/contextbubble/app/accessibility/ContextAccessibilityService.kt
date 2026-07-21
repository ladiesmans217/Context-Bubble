package com.contextbubble.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.contextbubble.app.appContainer
import com.contextbubble.app.domain.FocusedTargetToken
import com.contextbubble.app.domain.ScreenContext
import com.contextbubble.app.overlay.OverlayInteractionState
import java.util.ArrayDeque
import java.util.concurrent.Executor

class ContextAccessibilityService : AccessibilityService() {
    private var lastHandledAt = 0L
    private var foregroundPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // TYPE_APPLICATION_OVERLAY and IME windows also emit window-change events.
        // Using event.packageName directly makes our own bubble look like the
        // foreground app: the privacy policy then hides it, which emits another
        // event and creates a visible show/hide loop. The active accessibility
        // root remains the authoritative application window underneath those
        // transient windows.
        val activeRootPackage = rootInActiveWindow?.packageName?.toString()
        val packageName = resolveForegroundPackage(
            activeRootPackage = activeRootPackage,
            eventPackage = event.packageName?.toString(),
            previousPackage = foregroundPackage,
            ownPackage = packageName,
            ownOverlayHasFocus = OverlayInteractionState.shouldKeepUnderlyingPackage(),
        )
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            !activeRootPackage.isNullOrBlank()
        ) {
            foregroundPackage = packageName
        }

        val now = SystemClock.uptimeMillis()
        val mustHandle = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (!mustHandle && now - lastHandledAt < EVENT_COALESCE_MS) return
        lastHandledAt = now

        val blocked = appContainer.packagePolicy.isBlocked(packageName)
        if (blocked) {
            AccessibilityBridge.update(
                AccessibilityState(
                    foregroundPackage = packageName,
                    blocked = true,
                ),
            )
            return
        }

        val focus = event.source?.takeIf { it.isFocused } ?: findEditableFocus()
        AccessibilityBridge.update(
            AccessibilityState(
                foregroundPackage = packageName,
                blocked = false,
                focusedEditable = focus?.isEditable == true,
                focusedSensitive = focus?.isSensitiveField() == true,
            ),
        )
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AccessibilityBridge.detach(this)
        super.onDestroy()
    }

    fun captureTarget(): FocusedTargetToken? {
        val packageName = foregroundPackage ?: return null
        if (appContainer.packagePolicy.isBlocked(packageName)) return null
        val node = findEditableFocus() ?: return null
        if (!node.isEditable || node.isSensitiveField()) return null
        val text = editableValue(node.text?.toString(), node.isShowingHintText)
        return FocusedTargetToken(
            packageName = packageName,
            windowId = node.windowId,
            viewId = node.viewIdResourceName,
            className = node.className?.toString(),
            selectionStart = node.textSelectionStart.coerceAtLeast(0),
            selectionEnd = node.textSelectionEnd.coerceAtLeast(0),
            textFingerprint = text.take(FINGERPRINT_LENGTH).hashCode(),
        )
    }

    fun captureScreenContext(): ScreenContext? {
        val packageName = foregroundPackage ?: return null
        if (appContainer.packagePolicy.isBlocked(packageName)) return null
        val root = rootInActiveWindow ?: return null
        val focus = findEditableFocus()
        if (focus?.isSensitiveField() == true) return null

        val text = collectBoundedText(root)
        return ScreenContext(
            packageName = packageName,
            windowId = root.windowId,
            focusedText = focus?.let { editableValue(it.text?.toString(), it.isShowingHintText) }
                ?.take(MAX_FOCUSED_CHARS),
            surroundingText = text,
            editable = focus?.isEditable == true,
            sensitive = false,
            capturedAtEpochMs = System.currentTimeMillis(),
        )
    }

    fun insertText(token: FocusedTargetToken?, text: String): InsertResult {
        if (text.isBlank()) return InsertResult.FAILED
        val packageName = foregroundPackage
        if (appContainer.packagePolicy.isBlocked(packageName)) return InsertResult.BLOCKED
        val focus = findEditableFocus() ?: return copyToClipboard(text)
        if (focus.isSensitiveField()) return InsertResult.BLOCKED
        if (token != null && !matches(token, focus, packageName)) return InsertResult.TARGET_CHANGED

        // Some editors expose their hint (for example "Message") through
        // node.text while the field is empty. It is UI chrome, not user text.
        val existing = editableValue(focus.text?.toString(), focus.isShowingHintText)
        val start = if (focus.isShowingHintText) 0 else focus.textSelectionStart.takeIf { it >= 0 } ?: existing.length
        val end = if (focus.isShowingHintText) 0 else focus.textSelectionEnd.takeIf { it >= 0 } ?: start
        val lower = minOf(start, end).coerceIn(0, existing.length)
        val upper = maxOf(start, end).coerceIn(lower, existing.length)
        val combined = existing.replaceRange(lower, upper, text)
        val inserted = focus.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined) },
        )
        if (inserted) {
            focus.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, lower + text.length)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, lower + text.length)
                },
            )
            return InsertResult.INSERTED
        }

        putSensitiveClipboard(text)
        if (focus.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return InsertResult.PASTED
        return InsertResult.COPIED
    }

    fun takeUserScreenshot(callback: (Result<Bitmap>) -> Unit) {
        val packageName = foregroundPackage
        if (appContainer.packagePolicy.isBlocked(packageName)) {
            callback(Result.failure(SecurityException("Screenshots are disabled in this app")))
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, Executor(Runnable::run), object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                screenshot.hardwareBuffer.close()
                if (bitmap == null) callback(Result.failure(IllegalStateException("Screenshot conversion failed")))
                else callback(Result.success(bitmap))
            }

            override fun onFailure(errorCode: Int) {
                callback(Result.failure(SecurityException("Screenshot unavailable ($errorCode)")))
            }
        })
    }

    private fun matches(token: FocusedTargetToken, node: AccessibilityNodeInfo, packageName: String?): Boolean {
        return matchesFocusedTarget(
            token,
            FocusedNodeDescriptor(
                packageName = packageName,
                windowId = node.windowId,
                viewId = node.viewIdResourceName,
                className = node.className?.toString(),
                textFingerprint = node.text?.toString().orEmpty().take(FINGERPRINT_LENGTH).hashCode(),
            ),
        )
    }

    private fun findEditableFocus(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable || it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT } }
    }

    private fun collectBoundedText(root: AccessibilityNodeInfo): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val output = StringBuilder()
        queue += root
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES && output.length < MAX_CONTEXT_CHARS) {
            val node = queue.removeFirst()
            if (!node.isSensitiveField()) {
                val piece = node.text?.toString()?.trim().orEmpty()
                if (piece.isNotBlank() && !output.contains(piece)) {
                    if (output.isNotEmpty()) output.append('\n')
                    output.append(piece.take(MAX_CONTEXT_CHARS - output.length))
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
            }
        }
        return output.toString()
    }

    private fun AccessibilityNodeInfo.isSensitiveField(): Boolean {
        if (isPassword) return true
        return looksSensitive(listOf(text, hintText, contentDescription, viewIdResourceName))
    }

    private fun sensitiveClip(text: String): ClipData {
        return ClipData.newPlainText("Context Bubble transcription", text).also { clip ->
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
    }

    private fun copyToClipboard(text: String): InsertResult {
        putSensitiveClipboard(text)
        return InsertResult.COPIED
    }

    private fun putSensitiveClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(sensitiveClip(text))
        val fingerprint = text.hashCode()
        Handler(Looper.getMainLooper()).postDelayed({
            val current = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            // Never erase clipboard content the user copied after Context Bubble.
            if (current?.hashCode() == fingerprint && current == text) clipboard.clearPrimaryClip()
        }, SENSITIVE_CLIPBOARD_TTL_MS)
    }

    private companion object {
        const val EVENT_COALESCE_MS = 100L
        const val MAX_NODES = 60
        const val MAX_CONTEXT_CHARS = 4_000
        const val MAX_FOCUSED_CHARS = 1_000
        const val FINGERPRINT_LENGTH = 128
        const val SENSITIVE_CLIPBOARD_TTL_MS = 2 * 60_000L
    }
}

internal fun resolveForegroundPackage(
    activeRootPackage: String?,
    eventPackage: String?,
    previousPackage: String?,
    ownPackage: String,
    ownOverlayHasFocus: Boolean = false,
): String? = when {
    ownOverlayHasFocus && activeRootPackage == ownPackage && !previousPackage.isNullOrBlank() -> previousPackage
    !activeRootPackage.isNullOrBlank() -> activeRootPackage
    eventPackage.isNullOrBlank() -> previousPackage
    // If the active root is briefly unavailable, never let an event from the
    // overlay itself replace a known underlying foreground package.
    eventPackage == ownPackage && !previousPackage.isNullOrBlank() -> previousPackage
    else -> eventPackage
}

internal fun editableValue(rawText: String?, isShowingHintText: Boolean): String =
    if (isShowingHintText) "" else rawText.orEmpty()
