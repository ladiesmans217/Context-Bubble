package com.contextbubble.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.contextbubble.app.appContainer
import java.io.File

class RetentionCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val database = applicationContext.appContainer.database
        val now = System.currentTimeMillis()
        database.memoryDao().deleteExpired(now)
        database.captureDao().expired(now).forEach { capture ->
            val file = File(capture.encryptedPath)
            if (!file.exists() || file.delete()) database.captureDao().delete(capture)
        }
        return Result.success()
    }
}

