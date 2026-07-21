package com.contextbubble.app.speech

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.contextbubble.app.R
import com.contextbubble.app.appContainer
import com.contextbubble.app.domain.MemoryScope
import com.contextbubble.app.domain.RetentionPolicy
import com.contextbubble.app.overlay.BubbleService
import java.io.File
import java.util.concurrent.TimeUnit

class TranscriptionRetryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        val pending = container.database.captureDao().pendingTranscriptions()
        if (pending.isEmpty()) return Result.success()
        var retryNeeded = false
        pending.forEach { capture ->
            val file = File(capture.encryptedPath)
            if (!file.exists()) {
                container.database.captureDao().delete(capture)
                return@forEach
            }
            val pcm = runCatching { container.cryptoBox.decryptFile(file) }.getOrElse {
                container.database.captureDao().update(capture.copy(state = "CORRUPT"))
                return@forEach
            }
            container.assistant.transcribePcm(pcm).onSuccess { transcript ->
                container.vault.saveMemory(
                    type = "transcript",
                    summary = transcript.take(120),
                    value = transcript,
                    scope = MemoryScope.LOCAL_ONLY,
                    sourcePackage = capture.sourcePackage,
                    retention = RetentionPolicy.UNTIL_DELETE,
                )
                container.database.captureDao().update(capture.copy(state = "TRANSCRIBED"))
                notifyReady(transcript)
            }.onFailure { retryNeeded = true }
        }
        return if (retryNeeded) Result.retry() else Result.success()
    }

    private fun notifyReady(transcript: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                BubbleService.CHANNEL_REMINDERS,
                applicationContext.getString(R.string.notification_channel_reminders),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        manager.notify(
            transcript.hashCode(),
            NotificationCompat.Builder(applicationContext, BubbleService.CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("Your transcription is ready")
                .setContentText(transcript.take(100))
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "transcription-retry",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<TranscriptionRetryWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
