package com.contextbubble.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

class SharedFileCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val path = inputData.getString(KEY_PATH) ?: return Result.failure()
        val sharedRoot = File(applicationContext.cacheDir, "shared").canonicalFile
        val target = File(path).canonicalFile
        if (target.parentFile != sharedRoot) return Result.failure()
        return if (!target.exists() || target.delete()) Result.success() else Result.retry()
    }

    companion object { const val KEY_PATH = "path" }
}
