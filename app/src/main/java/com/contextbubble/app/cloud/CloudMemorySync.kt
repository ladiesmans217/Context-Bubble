package com.contextbubble.app.cloud

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.contextbubble.app.appContainer
import com.contextbubble.app.data.LocalVaultRepository
import com.contextbubble.app.data.SettingsRepository
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class CloudMemorySyncClient(
    private val configuration: CloudConfigurationRepository,
    private val accounts: AccountRepository,
    private val settings: SettingsRepository,
    private val vault: LocalVaultRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun sync(): CloudSyncOutcome = withContext(Dispatchers.IO) {
        val token = accounts.accessToken() ?: return@withContext CloudSyncOutcome.SIGNED_OUT
        val capabilities = configuration.capabilities()
        if (capabilities.features["cloudMemory"] != true) return@withContext CloudSyncOutcome.NOT_CONFIGURED
        val currentSettings = settings.settings.first()
        val mutations = vault.pendingSharedMutations(50)
        val payload = MemorySyncRequest(currentSettings.cloudSyncCursor, mutations)
        val request = Request.Builder()
            .url("${configuration.baseUrl()}/v1/memories/sync")
            .header("X-Context-Bubble-User-Token", "Bearer $token")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 429 || response.code >= 500) throw RetryableCloudException("Cloud sync unavailable (${response.code})")
                throw IOException(backendError(body, response.code))
            }
            val sync = json.decodeFromString<MemorySyncResponse>(body)
            vault.applyCloudSync(sync)
            settings.updateCloudSync(sync.nextCursor)
            if (sync.conflicts.isEmpty()) CloudSyncOutcome.SYNCED else CloudSyncOutcome.CONFLICT
        }
    }

    suspend fun saveGeneratedImage(bytes: ByteArray, mimeType: String): Boolean = withContext(Dispatchers.IO) {
        val token = accounts.accessToken() ?: return@withContext false
        val capabilities = configuration.capabilities()
        if (capabilities.features["cloudMemory"] != true) return@withContext false
        val request = Request.Builder()
            .url("${configuration.baseUrl()}/v1/cloud/blobs/generated-image")
            .header("X-Context-Bubble-User-Token", "Bearer $token")
            .header("X-Content-Mime-Type", mimeType)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .post(bytes.toRequestBody(OCTET_STREAM))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(backendError(body, response.code))
            true
        }
    }

    private fun backendError(body: String, status: Int): String {
        val envelope = runCatching { json.decodeFromString<CloudErrorEnvelope>(body) }.getOrNull()
        return envelope?.error?.message ?: "Cloud sync failed ($status)"
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}

enum class CloudSyncOutcome { SYNCED, CONFLICT, SIGNED_OUT, NOT_CONFIGURED }

class CloudMemorySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        applicationContext.appContainer.cloudMemorySync.sync()
        Result.success()
    } catch (_: RetryableCloudException) {
        Result.retry()
    } catch (_: IOException) {
        Result.failure()
    }
}

object CloudMemorySyncScheduler {
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<CloudMemorySyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "cloud-memory-sync",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<CloudMemorySyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "cloud-memory-sync-periodic",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

private class RetryableCloudException(message: String) : IOException(message)

@kotlinx.serialization.Serializable
private data class CloudErrorEnvelope(val error: CloudError? = null)

@kotlinx.serialization.Serializable
private data class CloudError(val message: String? = null)
