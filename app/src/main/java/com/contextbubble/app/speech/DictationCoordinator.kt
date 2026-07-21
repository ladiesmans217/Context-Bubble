package com.contextbubble.app.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.contextbubble.app.accessibility.AccessibilityBridge
import com.contextbubble.app.accessibility.InsertResult
import com.contextbubble.app.appContainer
import com.contextbubble.app.domain.FocusedTargetToken
import com.contextbubble.app.overlay.BubbleView
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DictationUiState(
    val visualState: BubbleView.VisualState,
    val notification: String,
)

data class DictationResult(val transcript: String? = null, val insertion: InsertResult? = null, val message: String? = null)

class DictationCoordinator(
    context: Context,
    private val target: FocusedTargetToken?,
    private val insertTranscript: Boolean = true,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var timeoutJob: Job? = null
    private var encryptedFile: File? = null
    private var completion: ((DictationResult) -> Unit)? = null
    private var stateListener: ((DictationUiState) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun start(onState: (DictationUiState) -> Unit, onComplete: (DictationResult) -> Unit) {
        if (recording.getAndSet(true)) return
        completion = onComplete
        stateListener = onState
        onState(DictationUiState(BubbleView.VisualState.LISTENING, "Listening — release to finish"))

        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufferSize = max(minimum, SAMPLE_RATE / 5 * 2)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bufferSize * 2,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            recording.set(false)
            onComplete(DictationResult(message = "The microphone could not start"))
            return
        }

        val id = UUID.randomUUID().toString()
        val file = File(appContext.filesDir, "captures/$id.pcm.enc")
        encryptedFile = file
        audioRecord = recorder
        recorder.startRecording()
        captureJob = scope.launch(Dispatchers.IO) {
            runCatching {
                appContext.appContainer.cryptoBox.openEncryptedOutput(file).use { output ->
                    val buffer = ByteArray(bufferSize)
                    while (recording.get()) {
                        val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (count > 0) output.write(buffer, 0, count)
                    }
                }
            }.onFailure {
                withContext(Dispatchers.Main) { finishWithError("Recording stopped unexpectedly") }
            }
        }
        timeoutJob = scope.launch {
            delay(MAX_DURATION_MS)
            if (recording.get()) finish()
        }
    }

    fun finish() {
        if (!recording.getAndSet(false)) return
        timeoutJob?.cancel()
        runCatching { audioRecord?.stop() }
        stateListener?.invoke(DictationUiState(BubbleView.VisualState.WORKING, "Transcribing…"))
        scope.launch {
            captureJob?.join()
            audioRecord?.release()
            audioRecord = null
            val file = encryptedFile
            if (file == null || !file.exists() || file.length() == 0L) {
                finishWithError("No speech was captured")
                return@launch
            }
            val pcm = withContext(Dispatchers.IO) { appContext.appContainer.cryptoBox.decryptFile(file) }
            val result = appContext.appContainer.assistant.transcribePcm(pcm, SAMPLE_RATE)
            result.onSuccess { transcript ->
                val insertion = if (insertTranscript && target != null) {
                    AccessibilityBridge.insertText(target, transcript)
                } else if (insertTranscript) {
                    InsertResult.COPIED
                } else null
                withContext(Dispatchers.IO) { file.delete() }
                completion?.invoke(
                    DictationResult(
                        transcript = transcript,
                        insertion = insertion,
                        message = when (insertion) {
                            InsertResult.INSERTED, InsertResult.PASTED -> "Inserted"
                            InsertResult.COPIED -> if (target == null) "Opening your answer" else "Copied — the field rejected insertion"
                            InsertResult.TARGET_CHANGED -> "The field changed, so the text was not inserted"
                            InsertResult.BLOCKED -> "Blocked on this protected screen"
                            InsertResult.FAILED, InsertResult.NOT_CONNECTED -> "Transcription is ready to copy"
                            null -> null
                        },
                    ),
                )
            }.onFailure {
                val settings = appContext.appContainer.settings.settings.first()
                appContext.appContainer.vault.savePendingCapture(
                    id = file.nameWithoutExtension,
                    kind = "AUDIO_PCM_24K",
                    encryptedPath = file.absolutePath,
                    sourcePackage = target?.packageName,
                    retention = settings.retention,
                    sizeBytes = file.length(),
                    state = "PENDING_TRANSCRIPTION",
                )
                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    "transcription-retry",
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    OneTimeWorkRequestBuilder<TranscriptionRetryWorker>()
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                        .build(),
                )
                completion?.invoke(DictationResult(message = "Offline — recording saved for retry"))
            }
            scope.cancel()
        }
    }

    fun cancel() {
        if (recording.getAndSet(false)) runCatching { audioRecord?.stop() }
        timeoutJob?.cancel()
        scope.launch(Dispatchers.IO) {
            captureJob?.join()
            audioRecord?.release()
            encryptedFile?.delete()
        }
        completion?.invoke(DictationResult(message = "Dictation cancelled"))
    }

    private fun finishWithError(message: String) {
        recording.set(false)
        runCatching { audioRecord?.stop() }
        audioRecord?.release()
        encryptedFile?.delete()
        completion?.invoke(DictationResult(message = message))
        scope.cancel()
    }

    private companion object {
        const val SAMPLE_RATE = 24_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val MAX_DURATION_MS = 5 * 60 * 1_000L
    }
}

object PendingTranscript {
    @Volatile var value: String? = null
}
