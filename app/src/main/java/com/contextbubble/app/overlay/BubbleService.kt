package com.contextbubble.app.overlay

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.os.IBinder
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import com.contextbubble.app.R
import com.contextbubble.app.accessibility.AccessibilityBridge
import com.contextbubble.app.appContainer
import com.contextbubble.app.domain.AssistantAction
import com.contextbubble.app.domain.AssistMode
import com.contextbubble.app.domain.AssistRequest
import com.contextbubble.app.domain.MemoryScope
import com.contextbubble.app.domain.RetentionPolicy
import com.contextbubble.app.domain.ScreenContext
import com.contextbubble.app.data.AppSettings
import com.contextbubble.app.cloud.AccountState
import com.contextbubble.app.speech.DictationCoordinator
import com.contextbubble.app.speech.RealtimeVoiceState
import com.contextbubble.app.speech.VoiceToolRequest
import com.contextbubble.app.speech.TranscriptionRetryWorker
import com.contextbubble.app.ui.MainActivity
import kotlin.math.max
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BubbleService : Service(), BubbleView.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: BubbleView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var menuView: View? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var pendingRawX = 0f
    private var pendingRawY = 0f
    private var framePosted = false
    private var hiddenByUser = false
    private var hiddenForPrivacy = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var dictation: DictationCoordinator? = null
    private var accessJob: Job? = null
    private var settingsJob: Job? = null
    private var realtimeJob: Job? = null
    private var realtimeToolJob: Job? = null
    private var currentSettings = AppSettings()
    private var voiceScreenContext: ScreenContext? = null
    private var overlayIsFocusable = false
    private var textToSpeech: TextToSpeech? = null
    private var activeHoldTranscriptionOnly = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        updateForegroundTypes(microphoneActive = false, text = READY_NOTIFICATION_TEXT)
        windowManager = getSystemService(WindowManager::class.java)
        bubbleView = BubbleView(this).also { it.listener = this }
        val initialBounds = bubbleBounds()
        bubbleParams = baseLayoutParams(dp(64), dp(64)).apply {
            x = initialBounds.right - dp(64)
            y = initialBounds.top + dp(180)
        }
        if (Settings.canDrawOverlays(this)) windowManager.addView(bubbleView, bubbleParams)
        accessJob = scope.launch {
            AccessibilityBridge.state.collectLatest { state ->
                hiddenForPrivacy = state.blocked
                if (state.blocked) {
                    dismissMenu()
                    dictation?.cancel()
                    textToSpeech?.stop()
                }
                updateVisibility()
                if (!state.blocked) clampBubble()
                bubbleView.state = when {
                    state.blocked -> BubbleView.VisualState.BLOCKED
                    !state.connected -> BubbleView.VisualState.WARNING
                    else -> BubbleView.VisualState.IDLE
                }
                updateNotification(
                    when {
                        state.blocked -> "Hidden on a protected screen"
                        !state.connected -> "Screen access needs repair"
                        else -> READY_NOTIFICATION_TEXT
                    },
                )
            }
        }
        settingsJob = scope.launch {
            appContainer.settings.settings.collectLatest { currentSettings = it }
        }
        realtimeJob = scope.launch {
            appContainer.realtimeVoice.state.collectLatest { state ->
                when (state) {
                    RealtimeVoiceState.CONNECTING -> {
                        bubbleView.state = BubbleView.VisualState.WORKING
                        updateNotification("Connecting live voice…")
                    }
                    RealtimeVoiceState.LISTENING -> {
                        bubbleView.state = BubbleView.VisualState.LISTENING
                        updateNotification("Listening — release to finish")
                    }
                    RealtimeVoiceState.THINKING -> {
                        bubbleView.state = BubbleView.VisualState.WORKING
                        updateNotification("Assistant is thinking…")
                    }
                    RealtimeVoiceState.SPEAKING -> {
                        bubbleView.state = BubbleView.VisualState.WORKING
                        updateNotification("Assistant is speaking — hold to interrupt")
                    }
                    RealtimeVoiceState.ERROR -> {
                        bubbleView.state = BubbleView.VisualState.WARNING
                        val message = appContainer.realtimeVoice.consumeLastError() ?: "Realtime voice is unavailable."
                        val buffered = appContainer.realtimeVoice.consumeFallbackPcm()
                        if (buffered != null) fallbackRealtimeTurn(buffered, message) else showReply("LIVE VOICE", message)
                        updateNotification("Live voice needs retry")
                    }
                    RealtimeVoiceState.IDLE -> {
                        if (!hiddenForPrivacy) bubbleView.state = BubbleView.VisualState.IDLE
                        updateNotification(READY_NOTIFICATION_TEXT)
                    }
                }
            }
        }
        realtimeToolJob = scope.launch {
            appContainer.realtimeVoice.toolRequests.collectLatest { request ->
                when (request) {
                    is VoiceToolRequest.CurrentScreen -> {
                        if (hiddenForPrivacy) appContainer.realtimeVoice.completeTool(request.callId, "Blocked on a protected screen.")
                        else if (!requestsScreenContext(request.spokenRequest)) {
                            appContainer.realtimeVoice.completeTool(request.callId, "The screen was not shared because the current spoken request did not explicitly ask for screen context.")
                        }
                        else captureVisualContext(AccessibilityBridge.captureScreenContext()) { screen ->
                            appContainer.realtimeVoice.completeTool(
                                request.callId,
                                if (screen?.screenshotBase64.isNullOrBlank()) "Screen image capture failed." else "The user explicitly shared the current screen image.",
                                screen?.screenshotBase64,
                            )
                        }
                    }
                    is VoiceToolRequest.MemorySuggestion -> {
                        if (hiddenForPrivacy) appContainer.realtimeVoice.completeTool(request.callId, "Blocked on a protected screen.")
                        else {
                            appContainer.realtimeVoice.completeTool(request.callId, "Memory preview displayed. Nothing is saved until the user approves it.")
                            showMemoryReview(request.value, "voice_suggestion", request.summary, AccessibilityBridge.state.value.foregroundPackage)
                        }
                    }
                    is VoiceToolRequest.ReminderPreview -> {
                        if (hiddenForPrivacy) appContainer.realtimeVoice.completeTool(request.callId, "Blocked on a protected screen.")
                        else {
                            appContainer.realtimeVoice.completeTool(request.callId, "Reminder preview displayed. Nothing is scheduled until the user confirms it.")
                            scope.launch {
                                val calendarConnected = if (appContainer.accounts.state.value is AccountState.SignedIn) {
                                    runCatching { appContainer.calendar.connected() }.getOrDefault(false)
                                } else false
                                renderReminderComposer(
                                    AccessibilityBridge.captureScreenContext()?.copy(surroundingText = "${request.title}\n${request.note}\nSuggested delay: ${request.delayMinutes} minutes"),
                                    calendarConnected,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_HIDE -> {
                hiddenByUser = !hiddenByUser
                dismissMenu()
                updateVisibility()
            }
            ACTION_REPAIR -> startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        accessJob?.cancel()
        settingsJob?.cancel()
        realtimeJob?.cancel()
        realtimeToolJob?.cancel()
        dictation?.cancel()
        appContainer.realtimeVoice.close()
        dismissMenu()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        if (::bubbleView.isInitialized && bubbleView.isAttachedToWindow) windowManager.removeView(bubbleView)
        scope.cancel()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        dismissMenu()
        clampBubble()
    }

    override fun onTouchPrepared() {
        dragOffsetX = pendingRawX - bubbleParams.x
        dragOffsetY = pendingRawY - bubbleParams.y
    }

    override fun onTapped() {
        if (hiddenForPrivacy) return
        if (appContainer.realtimeVoice.isActive()) {
            appContainer.realtimeVoice.close()
            dismissMenu()
            return
        }
        // The bubble is the persistent dismiss control for any attached reply.
        if (menuView != null) dismissMenu() else showMenu()
    }

    override fun onDragStarted() {
        dismissMenu()
    }

    override fun onDragged(rawX: Float, rawY: Float) {
        pendingRawX = rawX
        pendingRawY = rawY
        if (!framePosted) {
            framePosted = true
            Choreographer.getInstance().postFrameCallback {
                framePosted = false
                val bounds = bubbleBounds()
                bubbleParams.x = bounds.clampX((pendingRawX - dp(32)).toInt(), dp(64))
                bubbleParams.y = bounds.clampY((pendingRawY - dp(32)).toInt(), dp(64))
                if (bubbleView.isAttachedToWindow) windowManager.updateViewLayout(bubbleView, bubbleParams)
            }
        }
    }

    override fun onDragFinished(rawX: Float, rawY: Float) {
        val bounds = bubbleBounds()
        bubbleParams.x = bounds.snapX(bubbleParams.x, dp(64))
        bubbleParams.y = bounds.clampY(bubbleParams.y, dp(64))
        if (bubbleView.isAttachedToWindow) windowManager.updateViewLayout(bubbleView, bubbleParams)
    }

    override fun onHoldStarted() {
        if (hiddenForPrivacy) {
            bubbleView.state = BubbleView.VisualState.BLOCKED
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            bubbleView.state = BubbleView.VisualState.WARNING
            showReply("MICROPHONE", "Microphone access is required. Open Context Bubble once to grant it in Assistant health.")
            return
        }
        val transcriptionOnly = currentSettings.longPressTranscriptionOnly
        activeHoldTranscriptionOnly = transcriptionOnly
        val target = if (transcriptionOnly) AccessibilityBridge.captureTarget() else null
        // Realtime receives screen data only after it requests the explicit
        // current-screen tool in response to the user's spoken request.
        voiceScreenContext = null
        updateForegroundTypes(microphoneActive = true, text = "Listening — release to finish")
        if (!transcriptionOnly) {
            scope.launch {
                val memories = withContext(Dispatchers.IO) { appContainer.vault.approvedAssistantContext() }
                appContainer.realtimeVoice.beginTurn(null, memories)
            }
            return
        }
        dictation = DictationCoordinator(this, target, insertTranscript = transcriptionOnly).also { coordinator ->
            coordinator.start(
                onState = { state ->
                    bubbleView.state = state.visualState
                    updateNotification(state.notification)
                },
                onComplete = { result ->
                    if (transcriptionOnly) {
                        bubbleView.state = BubbleView.VisualState.IDLE
                        if (target == null && result.transcript != null) {
                            OverlayShareActions.copyText(this, result.transcript)
                            showReply("TRANSCRIPT", result.transcript)
                        } else {
                            result.message?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
                        }
                    } else if (result.transcript != null) {
                        askFromVoice(result.transcript)
                    } else {
                        bubbleView.state = BubbleView.VisualState.IDLE
                        result.message?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
                    }
                    updateForegroundTypes(microphoneActive = false, text = READY_NOTIFICATION_TEXT)
                },
            )
        }
    }

    override fun onHoldFinished(cancelled: Boolean) {
        if (activeHoldTranscriptionOnly) {
            if (cancelled) dictation?.cancel() else dictation?.finish()
        } else {
            if (cancelled) appContainer.realtimeVoice.cancelTurn() else appContainer.realtimeVoice.commitTurn()
            updateForegroundTypes(microphoneActive = false, text = READY_NOTIFICATION_TEXT)
        }
    }

    private fun showMenu() {
        val bounds = safeBounds()
        val menu = BubbleMenuView(this, ::handleAction)
        val width = minOf(dp(206), bounds.width)
        val height = minOf(dp(280), bounds.height)
        val rightDocked = bubbleParams.x > (bounds.left + bounds.right) / 2
        val params = baseLayoutParams(width, height).apply {
            x = if (rightDocked) bubbleParams.x - width - dp(8) else bubbleParams.x + dp(72)
            x = bounds.clampX(x, width)
            y = bounds.clampY(bubbleParams.y - height / 2 + dp(32), height)
        }
        menuView = menu
        menuParams = params
        windowManager.addView(menu, params)
    }

    private fun dismissMenu() {
        val wasFocusable = overlayIsFocusable
        if (wasFocusable) {
            menuView?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
        }
        menuView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        menuView = null
        menuParams = null
        overlayIsFocusable = false
        if (wasFocusable) OverlayInteractionState.editorClosed()
    }

    private fun handleAction(action: AssistantAction) {
        dismissMenu()
        if (action == AssistantAction.PAUSE) {
            stopSelf()
            scope.launch { applicationContext.appContainer.settings.setBubbleEnabled(false) }
            return
        }
        when (action) {
            AssistantAction.ASK -> askAboutScreen()
            AssistantAction.REMEMBER -> startRemember()
            AssistantAction.GENERATE_IMAGE -> generateImageFromScreen()
            AssistantAction.QUICK_FILL -> showQuickFill()
            AssistantAction.REMINDER -> showReminderComposer()
            AssistantAction.RECENT -> Unit
            AssistantAction.PAUSE -> Unit
        }
    }

    private fun showQuickFill() {
        val target = AccessibilityBridge.captureTarget()
        if (target == null) {
            Toast.makeText(this, "Focus a text field first", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val items = appContainer.vault.quickFill.first()
            val bounds = safeBounds()
            val width = minOf(dp(220), bounds.width)
            val height = minOf(bounds.height, minOf(dp(360), max(dp(88), dp(64) * items.size + dp(16))))
            val view = QuickFillOverlayView(this@BubbleService, items) { selected ->
                val result = AccessibilityBridge.insertText(target, selected.value)
                Toast.makeText(this@BubbleService, when (result) {
                    com.contextbubble.app.accessibility.InsertResult.INSERTED,
                    com.contextbubble.app.accessibility.InsertResult.PASTED -> "Inserted ${selected.label}"
                    com.contextbubble.app.accessibility.InsertResult.COPIED -> "Copied ${selected.label}"
                    com.contextbubble.app.accessibility.InsertResult.TARGET_CHANGED -> "The field changed — nothing was inserted"
                    com.contextbubble.app.accessibility.InsertResult.BLOCKED -> "Blocked on this protected screen"
                    else -> "Could not insert"
                }, Toast.LENGTH_SHORT).show()
                dismissMenu()
            }
            val rightDocked = bubbleParams.x > (bounds.left + bounds.right) / 2
            val params = baseLayoutParams(width, height).apply {
                x = if (rightDocked) bubbleParams.x - width - dp(8) else bubbleParams.x + dp(72)
                x = bounds.clampX(x, width)
                y = bounds.clampY(bubbleParams.y - height / 2 + dp(32), height)
            }
            menuView = view
            menuParams = params
            windowManager.addView(view, params)
        }
    }

    private fun askAboutScreen() {
        val screen = AccessibilityBridge.captureScreenContext()
        if (screen == null) {
            showReply("SCREEN", "Screen context is unavailable here. Check Screen access or try another app.")
            return
        }
        bubbleView.state = BubbleView.VisualState.WORKING
        captureVisualContext(screen) { visualScreen ->
            if (visualScreen?.screenshotBase64.isNullOrBlank()) {
                bubbleView.state = BubbleView.VisualState.WARNING
                showReply("SCREEN", "The screen image could not be captured. Check Screen access and try again.")
                return@captureVisualContext
            }
            performAsk(
                prompt = SCREEN_BRIEF_PROMPT,
                screen = visualScreen,
                speak = currentSettings.readTextAnswersAloud,
                replyLabel = "SCREEN BRIEF",
            )
        }
    }

    private fun askFromVoice(transcript: String) {
        bubbleView.state = BubbleView.VisualState.WORKING
        if (requestsScreenContext(transcript)) {
            captureVisualContext(AccessibilityBridge.captureScreenContext()) { visualScreen ->
                performAsk(transcript, visualScreen, speak = true)
            }
        } else {
            performAsk(transcript, null, speak = true)
        }
    }

    private fun fallbackRealtimeTurn(pcm: ByteArray, realtimeError: String) {
        bubbleView.state = BubbleView.VisualState.WORKING
        scope.launch {
            appContainer.assistant.transcribePcm(pcm, 24_000)
                .onSuccess { transcript -> askFromVoice(transcript) }
                .onFailure {
                    val retention = appContainer.settings.settings.first().retention
                    withContext(Dispatchers.IO) {
                        appContainer.vault.savePendingAudio(pcm, voiceScreenContext?.packageName, retention)
                    }
                    TranscriptionRetryWorker.enqueue(this@BubbleService)
                    bubbleView.state = BubbleView.VisualState.WARNING
                    showReply("OFFLINE QUEUED", "$realtimeError Your encrypted recording is safe on this phone and will retry when the connection returns.")
                }
        }
    }

    private fun performAsk(prompt: String, screen: ScreenContext?, speak: Boolean, replyLabel: String = "ANSWER") {
        val outputSourcePackage = screen?.packageName
        lateinit var streamingView: StreamingAssistantReplyOverlayView
        streamingView = StreamingAssistantReplyOverlayView(
            context = this,
            label = replyLabel,
            onDismiss = ::dismissMenu,
            onSaveText = { text, onComplete -> saveAssistantOutput(text, outputSourcePackage, onComplete) },
            onRetry = {
                dismissMenu()
                bubbleView.state = BubbleView.VisualState.WORKING
                performAsk(prompt, screen, speak, replyLabel)
            },
        )
        showAttached(
            streamingView,
            widthDp = if (replyLabel == "SCREEN BRIEF") 312 else 300,
            heightDp = if (replyLabel == "SCREEN BRIEF") 340 else 270,
        )
        scope.launch {
            val memories = withContext(Dispatchers.IO) { appContainer.vault.approvedAssistantContext() }
            appContainer.assistant.assistStreaming(
                AssistRequest(prompt = prompt, screen = screen, relevantMemories = memories, mode = AssistMode.ASK),
                onDelta = { delta -> streamingView.post { streamingView.appendDelta(delta) } },
            ).onSuccess { response ->
                bubbleView.state = BubbleView.VisualState.IDLE
                streamingView.complete(response.text)
                if (speak) speak(response.text)
            }.onFailure {
                bubbleView.state = BubbleView.VisualState.WARNING
                streamingView.fail(it.message ?: "The assistant is unavailable.")
            }
        }
    }

    private fun startRemember() {
        val screen = AccessibilityBridge.captureScreenContext()
        if (screen == null) {
            showReply("REMEMBER", "Screen context is unavailable here. Nothing was saved.")
            return
        }
        bubbleView.state = BubbleView.VisualState.WORKING
        captureVisualContext(screen) { visualScreen ->
            bubbleView.state = BubbleView.VisualState.IDLE
            val preview = visualScreen?.surroundingText.orEmpty().take(500)
            val view = RememberInputOverlayView(
                this,
                preview,
                onReview = { extra -> reviewMemory(visualScreen, extra) },
                onCancel = ::dismissMenu,
            )
            showAttached(view, widthDp = 310, heightDp = 330, focusable = true, focusTarget = view.input)
        }
    }

    private fun reviewMemory(screen: ScreenContext?, extra: String) {
        dismissMenu()
        bubbleView.state = BubbleView.VisualState.WORKING
        val prompt = buildString {
            append("Extract only the useful information the user should remember from this screen.")
            if (extra.isNotBlank()) append(" User instructions: ").append(extra.trim())
        }
        scope.launch {
            appContainer.assistant.assist(AssistRequest(prompt, screen, mode = AssistMode.REMEMBER))
                .onSuccess { response ->
                    bubbleView.state = BubbleView.VisualState.IDLE
                    val candidate = response.memoryCandidates.firstOrNull()
                    showMemoryReview(
                        value = candidate?.value?.takeIf { it.isNotBlank() } ?: response.text,
                        type = candidate?.type ?: "screen_fact",
                        summary = candidate?.summary,
                        sourcePackage = screen?.packageName,
                    )
                }
                .onFailure {
                    bubbleView.state = BubbleView.VisualState.WARNING
                    showReply("REMEMBER", it.message ?: "A memory suggestion could not be created.")
                }
        }
    }

    private fun showMemoryReview(value: String, type: String, summary: String?, sourcePackage: String?) {
        val view = MemoryReviewOverlayView(
            this,
            value,
            defaultScope = if (appContainer.accounts.state.value is AccountState.SignedIn) MemoryScope.SHARED_AI else MemoryScope.LOCAL_ONLY,
            onSave = { edited, memoryScope ->
                if (edited.isNotBlank()) {
                    dismissMenu()
                    scope.launch {
                        appContainer.vault.saveMemory(
                            type = type,
                            summary = summary?.takeIf { it.isNotBlank() } ?: edited.take(120),
                            value = edited,
                            scope = memoryScope,
                            sourcePackage = sourcePackage,
                            retention = RetentionPolicy.UNTIL_DELETE,
                            pinned = true,
                        )
                        showReply(
                            "SAVED",
                            if (memoryScope == MemoryScope.LOCAL_ONLY) "Saved on this phone only." else "Saved as approved assistant memory.",
                        )
                    }
                }
            },
            onCancel = ::dismissMenu,
        )
        showAttached(view, widthDp = 310, heightDp = 410, focusable = true, focusTarget = view.valueEditor)
    }

    private fun generateImageFromScreen() {
        val screen = AccessibilityBridge.captureScreenContext()
        if (screen == null) {
            showReply("IMAGE", "Screen context is unavailable here.")
            return
        }
        bubbleView.state = BubbleView.VisualState.WORKING
        val prompt = buildString {
            append("Create a useful, polished image inspired by the current screen.")
            screen.surroundingText?.take(1_200)?.let { append(" Screen context: ").append(it) }
        }
        scope.launch {
            appContainer.assistant.generateImage(prompt)
                .onSuccess { image ->
                    bubbleView.state = BubbleView.VisualState.IDLE
                    showImage(
                        label = "GENERATED IMAGE",
                        bytes = image.bytes,
                        onSave = { onComplete ->
                            saveGeneratedImageOutput(image.bytes, image.mimeType, screen.packageName, onComplete)
                        },
                    )
                }
                .onFailure {
                    bubbleView.state = BubbleView.VisualState.WARNING
                    showReply("IMAGE", it.message ?: "Image generation is unavailable.")
                }
        }
    }

    private fun showReminderComposer() {
        val screen = AccessibilityBridge.captureScreenContext()
        scope.launch {
            val calendarConnected = if (appContainer.accounts.state.value is AccountState.SignedIn) {
                runCatching { appContainer.calendar.connected() }.getOrDefault(false)
            } else false
            renderReminderComposer(screen, calendarConnected)
        }
    }

    private fun renderReminderComposer(screen: ScreenContext?, calendarConnected: Boolean) {
        val view = ReminderOverlayView(
            this,
            screen?.surroundingText.orEmpty(),
            calendarAvailable = calendarConnected,
            onCreate = { title, note, delayMinutes, syncCalendar ->
                if (title.isNotBlank()) {
                    dismissMenu()
                    scope.launch {
                        val alarmManager = getSystemService(AlarmManager::class.java)
                        val exact = alarmManager.canScheduleExactAlarms()
                        val triggerAt = System.currentTimeMillis() + delayMinutes * 60_000L
                        val item = appContainer.reminders.schedule(
                            title.trim(),
                            note.trim(),
                            triggerAt,
                            exact,
                        )
                        val calendarResult = if (syncCalendar) {
                            runCatching { appContainer.calendar.create(title.trim(), note.trim(), triggerAt, triggerAt + 30 * 60_000L) }
                        } else null
                        showReply(
                            "REMINDER",
                            when {
                                calendarResult?.isSuccess == true -> if (item.exact) "Exact local reminder and Google Calendar event created." else "Approximate local reminder and Google Calendar event created."
                                calendarResult?.isFailure == true -> "Local reminder created. Calendar failed: ${calendarResult.exceptionOrNull()?.message ?: "try again from Settings"}"
                                item.exact -> "Exact reminder created."
                                else -> "Reminder created; Android may deliver it approximately."
                            },
                        )
                    }
                }
            },
            onCancel = ::dismissMenu,
        )
        showAttached(view, widthDp = 310, heightDp = if (calendarConnected) 410 else 360, focusable = true, focusTarget = view.titleInput)
    }

    private fun captureVisualContext(base: ScreenContext?, onReady: (ScreenContext?) -> Unit) {
        if (base == null || hiddenForPrivacy) {
            onReady(base)
            return
        }
        bubbleView.visibility = View.GONE
        bubbleView.postDelayed({
            AccessibilityBridge.takeScreenshot { result ->
                scope.launch {
                    val encoded = result.getOrNull()?.let { bitmap ->
                        withContext(Dispatchers.Default) { encodeVisionScreenshot(bitmap) }
                    }
                    updateVisibility()
                    onReady(base.copy(screenshotBase64 = encoded))
                }
            }
        }, 80L)
    }

    private fun encodeVisionScreenshot(bitmap: Bitmap): String? = runCatching {
        val maxDimension = maxOf(bitmap.width, bitmap.height)
        val scale = minOf(1f, 720f / maxDimension.toFloat())
        val prepared = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        val bytes = ByteArrayOutputStream().use { output ->
            check(prepared.compress(Bitmap.CompressFormat.JPEG, 72, output))
            output.toByteArray()
        }
        if (prepared !== bitmap) prepared.recycle()
        bitmap.recycle()
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()

    private fun saveAssistantOutput(text: String, sourcePackage: String?, onComplete: (Boolean) -> Unit) {
        scope.launch {
            val destination = if (appContainer.accounts.state.value is AccountState.SignedIn) {
                MemoryScope.SHARED_AI
            } else {
                MemoryScope.LOCAL_ONLY
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    appContainer.vault.saveMemory(
                        type = "assistant_output",
                        summary = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: "Assistant output",
                        value = text,
                        scope = destination,
                        sourcePackage = sourcePackage,
                        retention = RetentionPolicy.UNTIL_DELETE,
                        pinned = true,
                    )
                }
            }.onSuccess {
                Toast.makeText(
                    this@BubbleService,
                    if (destination == MemoryScope.SHARED_AI) "Saved to Shared Cloud queue" else "Saved on this phone",
                    Toast.LENGTH_SHORT,
                ).show()
                onComplete(true)
            }.onFailure {
                Toast.makeText(this@BubbleService, "Could not save this answer", Toast.LENGTH_SHORT).show()
                onComplete(false)
            }
        }
    }

    private fun saveGeneratedImageOutput(bytes: ByteArray, mimeType: String, sourcePackage: String?, onComplete: (Boolean) -> Unit) {
        scope.launch {
            val cloudPreferred = appContainer.accounts.state.value is AccountState.SignedIn
            val savedToCloud = if (cloudPreferred) {
                runCatching { appContainer.cloudMemorySync.saveGeneratedImage(bytes, mimeType) }.getOrDefault(false)
            } else false
            runCatching {
                withContext(Dispatchers.IO) {
                    if (!savedToCloud) appContainer.vault.saveGeneratedImage(bytes, sourcePackage, RetentionPolicy.UNTIL_DELETE)
                }
            }.onSuccess {
                Toast.makeText(
                    this@BubbleService,
                    if (savedToCloud) "Image saved encrypted in Shared Cloud" else if (cloudPreferred) "Cloud unavailable — image saved on this phone" else "Image saved on this phone",
                    Toast.LENGTH_SHORT,
                ).show()
                onComplete(true)
            }.onFailure {
                Toast.makeText(this@BubbleService, "Could not save this image", Toast.LENGTH_SHORT).show()
                onComplete(false)
            }
        }
    }

    private fun showReply(label: String, text: String, onSave: OverlaySaveAction? = null) {
        val isScreenBrief = label == "SCREEN BRIEF"
        showAttached(
            AssistantReplyOverlayView(this, label, text, ::dismissMenu, onSave),
            widthDp = if (isScreenBrief) 312 else 300,
            heightDp = if (isScreenBrief) 340 else 270,
        )
    }

    private fun showImage(label: String, bytes: ByteArray, onSave: OverlaySaveAction? = null) {
        showAttached(
            AssistantImageOverlayView(this, label, bytes, ::dismissMenu, onSave),
            widthDp = 306,
            heightDp = 390,
        )
    }

    private fun showAttached(
        view: View,
        widthDp: Int,
        heightDp: Int,
        focusable: Boolean = false,
        focusTarget: View? = null,
    ) {
        dismissMenu()
        val bounds = safeBounds()
        val width = minOf(dp(widthDp), bounds.width)
        val height = minOf(dp(heightDp), bounds.height)
        val rightDocked = bubbleParams.x > (bounds.left + bounds.right) / 2
        val flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            x = if (rightDocked) bubbleParams.x - width - dp(8) else bubbleParams.x + dp(72)
            x = bounds.clampX(x, width)
            y = bounds.clampY(bubbleParams.y - height / 2 + dp(32), height)
        }
        overlayIsFocusable = focusable
        if (focusable) OverlayInteractionState.editorOpened()
        menuView = view
        menuParams = params
        windowManager.addView(view, params)
        if (focusable && focusTarget != null) {
            focusTarget.postDelayed({
                focusTarget.requestFocus()
                getSystemService(InputMethodManager::class.java).showSoftInput(focusTarget, InputMethodManager.SHOW_IMPLICIT)
            }, 120L)
        }
    }

    private fun speak(text: String) {
        val existing = textToSpeech
        if (existing != null) {
            existing.speak(text.take(4_000), TextToSpeech.QUEUE_FLUSH, null, "context-bubble-answer")
            return
        }
        textToSpeech = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.getDefault()
                textToSpeech?.speak(text.take(4_000), TextToSpeech.QUEUE_FLUSH, null, "context-bubble-answer")
            }
        }
    }

    private fun updateVisibility() {
        if (!::bubbleView.isInitialized) return
        bubbleView.visibility = if (hiddenByUser || hiddenForPrivacy) View.GONE else View.VISIBLE
    }

    private fun clampBubble() {
        if (!::bubbleParams.isInitialized) return
        val bounds = bubbleBounds()
        bubbleParams.x = bounds.clampX(bubbleParams.x, dp(64))
        bubbleParams.y = bounds.clampY(bubbleParams.y, dp(64))
        if (bubbleView.isAttachedToWindow) windowManager.updateViewLayout(bubbleView, bubbleParams)
    }

    private fun baseLayoutParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun bubbleBounds(): OverlayBounds = overlayBounds(edgeDocked = true)

    private fun safeBounds(): OverlayBounds = overlayBounds(edgeDocked = false)

    private fun overlayBounds(edgeDocked: Boolean): OverlayBounds {
        val metrics = windowManager.currentWindowMetrics
        val stable = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        val gestures = metrics.windowInsets.getInsets(WindowInsets.Type.systemGestures())
        val ime = metrics.windowInsets.getInsets(WindowInsets.Type.ime())
        val bounds = metrics.bounds
        val guard8 = dp(8)
        val displayBounds = OverlayBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
        val stableInsets = OverlayInsets(stable.left, stable.top, stable.right, stable.bottom)
        val gestureInsets = OverlayInsets(gestures.left, gestures.top, gestures.right, gestures.bottom)
        val imeInsets = OverlayInsets(ime.left, ime.top, ime.right, ime.bottom)
        return if (edgeDocked) calculateEdgeDockedBounds(
            display = displayBounds,
            stable = stableInsets,
            gestures = gestureInsets,
            ime = imeInsets,
            horizontalGuard = dp(10),
            verticalGuard = guard8,
        ) else calculateOverlayBounds(
            display = displayBounds,
            stable = stableInsets,
            gestures = gestureInsets,
            ime = imeInsets,
            horizontalGuard = dp(4),
            verticalGuard = guard8,
        )
    }

    private fun notification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hideIntent = servicePendingIntent(ACTION_HIDE, 2)
        val stopIntent = servicePendingIntent(ACTION_STOP, 3)
        val repairIntent = servicePendingIntent(ACTION_REPAIR, 4)
        return NotificationCompat.Builder(this, CHANNEL_BUBBLE)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(getString(R.string.notification_bubble_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, if (hiddenByUser) "Show" else "Hide", hideIntent)
            .addAction(0, "Repair", repairIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun updateForegroundTypes(microphoneActive: Boolean, text: String) {
        val specialUse = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val types = specialUse or if (microphoneActive) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), types)
    }

    private fun servicePendingIntent(action: String, code: Int) = PendingIntent.getService(
        this,
        code,
        Intent(this, BubbleService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createNotificationChannels() {
        getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_BUBBLE, getString(R.string.notification_channel_bubble), NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Controls the user-enabled floating assistant"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_REMINDERS, getString(R.string.notification_channel_reminders), NotificationManager.IMPORTANCE_HIGH),
            ),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SCREEN_BRIEF_PROMPT =
            "Brief the visible screen for me. Start with one short overview sentence, then give 3 to 5 short bullet points containing only the most important visible details. Use plain text with real line breaks and bullet characters. Do not use Markdown headings or tables, and do not mention screenshots, images, capture, or accessibility."
        private const val READY_NOTIFICATION_TEXT = "Ready — tap or hold the bubble"
        const val EXTRA_ACTION = "bubble_action"
        const val CHANNEL_REMINDERS = "reminders"
        private const val CHANNEL_BUBBLE = "bubble"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_STOP = "com.contextbubble.action.STOP"
        private const val ACTION_HIDE = "com.contextbubble.action.HIDE"
        private const val ACTION_REPAIR = "com.contextbubble.action.REPAIR"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BubbleService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BubbleService::class.java))
        }
    }
}

internal fun requestsScreenContext(transcript: String): Boolean {
    val normalized = transcript.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    return listOf(
        "screen", "this page", "current page", "current app", "what is this", "what s this",
        "on this page", "shown here", "ye kya", "yeh kya", "is screen", "iss screen",
        "is page", "iss page", "isme kya", "dikh raha",
    ).any(normalized::contains)
}
