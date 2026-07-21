package com.contextbubble.app.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.contextbubble.app.data.SharedFileCleanupWorker
import java.io.File
import java.util.concurrent.TimeUnit

internal object OverlayShareActions {
    fun copyText(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Context Bubble answer", text))
    }

    fun copyImage(context: Context, bytes: ByteArray) {
        val uri = sharedImageUri(context, bytes)
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newUri(context.contentResolver, "Context Bubble image", uri))
    }

    fun shareImage(context: Context, bytes: ByteArray) {
        val uri = sharedImageUri(context, bytes)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = if (bytes.isJpeg()) "image/jpeg" else "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share image",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun sharedImageUri(context: Context, bytes: ByteArray): android.net.Uri {
        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        val extension = if (bytes.isJpeg()) "jpg" else "png"
        val file = File(directory, "context-${System.currentTimeMillis()}.$extension").apply { writeBytes(bytes) }
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<SharedFileCleanupWorker>()
                .setInitialDelay(10, TimeUnit.MINUTES)
                .setInputData(workDataOf(SharedFileCleanupWorker.KEY_PATH to file.absolutePath))
                .build(),
        )
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    private fun ByteArray.isJpeg(): Boolean =
        size >= 2 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()
}
