package com.contextbubble.app.overlay

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Switch
import com.contextbubble.app.domain.MemoryScope

private const val INK = 0xFF111827.toInt()
private const val SLATE = 0xFF5F6B7A.toInt()
private const val COBALT = 0xFF5C64F4.toInt()
private const val MIST = 0xFFF1F4F8.toInt()
private const val MINT = 0xFF1B8A67.toInt()

internal typealias OverlaySaveAction = (onComplete: (Boolean) -> Unit) -> Unit

internal class StreamingAssistantReplyOverlayView(
    context: Context,
    label: String,
    private val onDismiss: () -> Unit,
    private val onSaveText: (text: String, onComplete: (Boolean) -> Unit) -> Unit,
    private val onRetry: () -> Unit,
) : LinearLayout(context) {
    private val renderedText = StringBuilder()
    private val pendingText = StringBuilder()
    private val answerView = TextView(context)
    private val statusView = TextView(context)
    private val copyButton: TextView
    private val saveButton: TextView
    private val retryButton: TextView
    private var framePending = false
    private var saving = false

    init {
        overlayCard()
        addView(eyebrow(label))
        statusView.apply {
            text = "Thinking…"
            textSize = 12f
            setTextColor(SLATE)
            setPadding(0, dp(8), 0, 0)
        }
        addView(statusView)
        answerView.apply {
            text = ""
            textSize = 15f
            setTextColor(INK)
            setLineSpacing(0f, 1.14f)
            setPadding(0, dp(7), 0, dp(9))
            contentDescription = "Streaming assistant answer"
        }
        addView(ScrollView(context).apply { addView(answerView) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        copyButton = action("Copy") {
            val text = currentText()
            if (text.isNotBlank()) {
                OverlayShareActions.copyText(context, text)
                android.widget.Toast.makeText(context, "Answer copied", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        saveButton = action("Save") {
            val text = currentText()
            if (text.isBlank() || saving || saveButton.text == "Saved") return@action
            saving = true
            saveButton.text = "Saving…"
            saveButton.isEnabled = false
            onSaveText(text) { success ->
                saveButton.post {
                    saving = false
                    saveButton.text = if (success) "Saved" else "Save"
                    saveButton.isEnabled = !success
                    saveButton.alpha = if (success) 0.62f else 1f
                }
            }
        }
        retryButton = action("Retry", onRetry)
        addView(actionRow(copyButton, saveButton, retryButton, action("Close", onDismiss)))
        refreshActions()
    }

    fun appendDelta(delta: String) {
        if (delta.isEmpty()) return
        pendingText.append(delta)
        if (framePending) return
        framePending = true
        Choreographer.getInstance().postFrameCallback {
            framePending = false
            flushPending()
        }
    }

    fun complete(text: String) {
        pendingText.clear()
        renderedText.clear()
        renderedText.append(text.trim())
        answerView.text = renderedText.toString()
        statusView.text = "Complete"
        retryButton.visibility = View.GONE
        refreshActions()
    }

    fun fail(message: String) {
        flushPending()
        statusView.text = if (renderedText.isEmpty()) message else "Connection ended — partial answer kept"
        retryButton.visibility = View.VISIBLE
        refreshActions()
    }

    private fun flushPending() {
        if (pendingText.isEmpty()) return
        renderedText.append(pendingText)
        pendingText.clear()
        answerView.text = renderedText.toString()
        statusView.text = "Answering…"
        refreshActions()
    }

    private fun currentText(): String = buildString {
        append(renderedText)
        append(pendingText)
    }.trim()

    private fun refreshActions() {
        val enabled = currentText().isNotBlank()
        copyButton.isEnabled = enabled
        copyButton.alpha = if (enabled) 1f else 0.45f
        saveButton.isEnabled = enabled && !saving && saveButton.text != "Saved"
        saveButton.alpha = if (saveButton.isEnabled) 1f else 0.45f
    }
}

internal class AssistantReplyOverlayView(
    context: Context,
    label: String,
    text: String,
    onDismiss: () -> Unit,
    onSave: OverlaySaveAction? = null,
) : LinearLayout(context) {
    init {
        val screenBrief = if (label == "SCREEN BRIEF") parseScreenBrief(text) else null
        val copyText = screenBrief?.asPlainText() ?: text.trim()
        overlayCard()
        addView(eyebrow(label))
        addView(ScrollView(context).apply {
            isFillViewport = false
            addView(
                screenBrief?.let { screenBriefContent(it, copyText) }
                    ?: regularAnswerContent(copyText),
            )
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val actions = mutableListOf<TextView>()
        onSave?.let { actions += saveAction(it) }
        actions += action("Copy") {
                OverlayShareActions.copyText(context, copyText)
                android.widget.Toast.makeText(context, "Answer copied", android.widget.Toast.LENGTH_SHORT).show()
            }
        actions += action("Done", onDismiss)
        addView(actionRow(*actions.toTypedArray()))
    }

    private fun regularAnswerContent(copyText: String) = TextView(context).apply {
        text = copyText
        textSize = 15f
        setTextColor(INK)
        setLineSpacing(0f, 1.14f)
        setPadding(0, dp(9), 0, dp(9))
        setTextIsSelectable(false)
        contentDescription = "Assistant answer. Tap to copy."
        copyOnTap(copyText)
    }

    private fun screenBriefContent(brief: ScreenBrief, copyText: String) = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, dp(9), 0, dp(9))
        contentDescription = "Screen brief. Tap to copy."
        addView(TextView(context).apply {
            text = brief.overview
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(INK)
            setLineSpacing(0f, 1.08f)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = rounded(0xFFF0F1FF.toInt(), 15, 0x225C64F4)
            copyOnTap(copyText)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (brief.details.isNotEmpty()) {
            addView(View(context).apply { setBackgroundColor(0xFFE5E9EF.toInt()) }, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(12)
                bottomMargin = dp(5)
            })
        }
        brief.details.forEach { detail ->
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.TOP
                addView(TextView(context).apply {
                    text = "•"
                    textSize = 17f
                    setTextColor(COBALT)
                    gravity = Gravity.TOP
                }, LayoutParams(dp(18), LayoutParams.WRAP_CONTENT))
                addView(TextView(context).apply {
                    text = detail
                    textSize = 14f
                    setTextColor(INK)
                    setLineSpacing(0f, 1.12f)
                    copyOnTap(copyText)
                }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
        }
        copyOnTap(copyText)
    }

    private fun View.copyOnTap(copyText: String) {
        setOnClickListener {
            OverlayShareActions.copyText(context, copyText)
            android.widget.Toast.makeText(context, "Answer copied", android.widget.Toast.LENGTH_SHORT).show()
        }
        isClickable = true
    }
}

internal data class ScreenBrief(val overview: String, val details: List<String>) {
    fun asPlainText(): String = buildString {
        append(overview)
        details.forEach { append("\n\n• ").append(it) }
    }
}

internal fun parseScreenBrief(raw: String): ScreenBrief {
    val lines = raw
        .replace("\r\n", "\n")
        .lineSequence()
        .map { line ->
            val trimmed = line.trim()
            if (trimmed.matches(Regex("^#{1,6}\\s+.*"))) "" else trimmed.replace("**", "").trim()
        }
        .filter { it.isNotBlank() }
        .toList()
    if (lines.isEmpty()) return ScreenBrief("No useful screen details were returned.", emptyList())

    val bulletPrefix = Regex("^(?:[•●▪◦*+-]|\\d+[.)])\\s+")
    val normalized = lines.map { it.replace(bulletPrefix, "").trim() }.filter { it.isNotBlank() }
    if (normalized.isEmpty()) return ScreenBrief("No useful screen details were returned.", emptyList())
    if (normalized.size > 1) return ScreenBrief(normalized.first(), normalized.drop(1).take(5))

    val sentences = normalized.first()
        .split(Regex("(?<=[.!?])\\s+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
    return ScreenBrief(sentences.first(), sentences.drop(1).take(5))
}

internal class AssistantImageOverlayView(
    context: Context,
    label: String,
    bytes: ByteArray,
    onDismiss: () -> Unit,
    onSave: OverlaySaveAction? = null,
) : LinearLayout(context) {
    init {
        overlayCard()
        addView(eyebrow(label))
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap == null) {
            addView(bodyText("This image could not be decoded."), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            addView(ImageView(context).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = label
                background = rounded(MIST, 16)
                clipToOutline = true
            }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10); bottomMargin = dp(10) })
        }
        val actions = mutableListOf<TextView>()
        onSave?.let { actions += saveAction(it) }
        actions += action("Copy") {
                OverlayShareActions.copyImage(context, bytes)
                android.widget.Toast.makeText(context, "Image copied", android.widget.Toast.LENGTH_SHORT).show()
            }
        actions += action("Share") { OverlayShareActions.shareImage(context, bytes) }
        actions += action("Done", onDismiss)
        addView(actionRow(*actions.toTypedArray()))
    }
}

internal class RememberInputOverlayView(
    context: Context,
    screenPreview: String,
    onReview: (String) -> Unit,
    onCancel: () -> Unit,
) : LinearLayout(context) {
    val input = EditText(context)

    init {
        overlayCard()
        addView(eyebrow("REMEMBER"))
        addView(bodyText(screenPreview.ifBlank { "Use the visible screen as context." }).apply { maxLines = 4 })
        input.apply {
            hint = "Add context, or say what to include or exclude"
            textSize = 14f
            setTextColor(INK)
            setHintTextColor(SLATE)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(MIST, 14, 0xFFDCE2EA.toInt())
            minLines = 2
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        addView(input, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10); bottomMargin = dp(10) })
        addView(actionRow(action("Cancel", onCancel), action("Review memory") { onReview(input.text.toString()) }))
    }
}

internal class MemoryReviewOverlayView(
    context: Context,
    suggestedValue: String,
    defaultScope: MemoryScope,
    onSave: (String, MemoryScope) -> Unit,
    onCancel: () -> Unit,
) : LinearLayout(context) {
    val valueEditor = EditText(context)
    private var selectedScope = defaultScope
    private val localButton: TextView
    private val sharedButton: TextView

    init {
        overlayCard()
        addView(eyebrow("REVIEW MEMORY"))
        valueEditor.apply {
            setText(suggestedValue)
            setSelection(text.length)
            textSize = 14f
            setTextColor(INK)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(MIST, 14, 0xFFDCE2EA.toInt())
            minLines = 4
            maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        addView(valueEditor, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(9); bottomMargin = dp(9) })
        addView(TextView(context).apply {
            text = "Choose where this approved memory is saved. Local only never leaves this device. Shared Cloud can be retrieved by Context Bubble and your connected MCP client."
            textSize = 11f
            setTextColor(SLATE)
        })
        localButton = action("Local only") {
            selectedScope = MemoryScope.LOCAL_ONLY
            refreshScopeButtons()
        }
        sharedButton = action("Shared Cloud") {
            selectedScope = MemoryScope.SHARED_AI
            refreshScopeButtons()
        }
        addView(actionRow(localButton, sharedButton))
        addView(actionRow(action("Cancel", onCancel), action("Save") {
            onSave(valueEditor.text.toString(), selectedScope)
        }))
        refreshScopeButtons()
    }

    private fun refreshScopeButtons() {
        localButton.background = rounded(if (selectedScope == MemoryScope.LOCAL_ONLY) 0xFFE7E9FF.toInt() else MIST, 14)
        sharedButton.background = rounded(if (selectedScope == MemoryScope.SHARED_AI) 0xFFE7E9FF.toInt() else MIST, 14)
        localButton.setTextColor(if (selectedScope == MemoryScope.LOCAL_ONLY) COBALT else INK)
        sharedButton.setTextColor(if (selectedScope == MemoryScope.SHARED_AI) COBALT else INK)
    }
}

internal class ReminderOverlayView(
    context: Context,
    screenPreview: String,
    calendarAvailable: Boolean,
    onCreate: (title: String, note: String, delayMinutes: Int, syncCalendar: Boolean) -> Unit,
    onCancel: () -> Unit,
) : LinearLayout(context) {
    val titleInput = EditText(context)
    private val noteInput = EditText(context)
    private var delayMinutes = 60
    private val calendarSwitch = Switch(context)
    private val timeButtons = mutableListOf<Pair<Int, TextView>>()

    init {
        overlayCard()
        addView(eyebrow("REMINDER"))
        titleInput.apply {
            hint = "What should I remind you about?"
            setText("Follow up")
            setSelection(text.length)
            isSingleLine = true
            textSize = 14f
            setTextColor(INK)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(MIST, 14, 0xFFDCE2EA.toInt())
        }
        addView(titleInput, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        noteInput.apply {
            hint = "Private note"
            setText(screenPreview.take(500))
            textSize = 13f
            setTextColor(INK)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(MIST, 14, 0xFFDCE2EA.toInt())
            minLines = 2
            maxLines = 3
        }
        addView(noteInput, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        val times = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(10 to "10 min", 60 to "1 hour", 1440 to "Tomorrow").forEach { (minutes, label) ->
            val button = action(label) {
                delayMinutes = minutes
                refreshTimes()
            }
            timeButtons += minutes to button
            times.addView(button, LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        }
        addView(times, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        refreshTimes()
        if (calendarAvailable) {
            addView(calendarSwitch.apply {
                text = "Also add to Google Calendar"
                textSize = 13f
                setTextColor(INK)
                isChecked = false
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(actionRow(action("Cancel", onCancel), action("Create") {
            onCreate(titleInput.text.toString(), noteInput.text.toString(), delayMinutes, calendarAvailable && calendarSwitch.isChecked)
        }))
    }

    private fun refreshTimes() {
        timeButtons.forEach { (minutes, view) ->
            view.background = rounded(if (minutes == delayMinutes) 0xFFE7E9FF.toInt() else MIST, 14)
            view.setTextColor(if (minutes == delayMinutes) COBALT else INK)
        }
    }
}

private fun LinearLayout.overlayCard() {
    orientation = LinearLayout.VERTICAL
    elevation = dp(18).toFloat()
    setPadding(dp(16), dp(14), dp(16), dp(12))
    background = rounded(Color.WHITE, 22, 0x225C64F4)
}

private fun View.eyebrow(text: String) = TextView(context).apply {
    this.text = text
    textSize = 11f
    letterSpacing = 0.1f
    setTextColor(COBALT)
}

private fun View.bodyText(text: String) = TextView(context).apply {
    this.text = text
    textSize = 14f
    setTextColor(INK)
    setPadding(0, dp(7), 0, 0)
}

private fun View.action(label: String, onClick: () -> Unit) = TextView(context).apply {
    text = label
    textSize = 13f
    gravity = Gravity.CENTER
    setTextColor(if (label in setOf("Done", "Save", "Review memory", "Assistant", "Create")) Color.WHITE else COBALT)
    background = rounded(if (label in setOf("Done", "Save", "Review memory", "Assistant", "Create")) COBALT else MIST, 14)
    setOnClickListener { onClick() }
    isClickable = true
    isFocusable = true
    setPadding(dp(10), 0, dp(10), 0)
}

private fun View.saveAction(onSave: OverlaySaveAction): TextView {
    var saving = false
    lateinit var button: TextView
    button = action("Save") {
        if (saving || button.text == "Saved") return@action
        saving = true
        button.text = "Saving…"
        button.isEnabled = false
        button.alpha = 0.68f
        onSave { success ->
            button.post {
                saving = false
                button.text = if (success) "Saved" else "Save"
                button.isEnabled = !success
                button.alpha = if (success) 0.62f else 1f
            }
        }
    }
    return button
}

private fun View.actionRow(vararg actions: TextView) = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.END
    actions.forEach { addView(it, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(6) }) }
}

private fun View.rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
    cornerRadius = dp(radiusDp).toFloat()
    setColor(color)
    strokeColor?.let { setStroke(dp(1), it) }
}

private fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
