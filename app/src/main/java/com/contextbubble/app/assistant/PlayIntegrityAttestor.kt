package com.contextbubble.app.assistant

import android.content.Context
import android.util.Base64
import com.contextbubble.app.BuildConfig
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine

class PlayIntegrityAttestor(context: Context) {
    private val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
    private val mutex = Mutex()
    @Volatile private var provider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

    suspend fun attestInstallation(installationId: String): String? {
        if (BuildConfig.LAB_AUTOMATION || BuildConfig.PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER <= 0L) return null
        val requestHash = requestHash(installationId)
        val prepared = provider ?: mutex.withLock {
            provider ?: manager.prepareIntegrityToken(
                StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(BuildConfig.PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER)
                    .build(),
            ).await().also { provider = it }
        }
        return try {
            prepared.request(
                StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                    .setRequestHash(requestHash)
                    .build(),
            ).await().token()
        } catch (first: Exception) {
            provider = null
            val refreshed = mutex.withLock {
                manager.prepareIntegrityToken(
                    StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                        .setCloudProjectNumber(BuildConfig.PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER)
                        .build(),
                ).await().also { provider = it }
            }
            refreshed.request(
                StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                    .setRequestHash(requestHash)
                    .build(),
            ).await().token()
        }
    }

    private fun requestHash(installationId: String): String = Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest("register:$installationId".toByteArray(Charsets.UTF_8)),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
