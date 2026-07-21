package com.contextbubble.app.cloud

import android.content.Context
import com.contextbubble.app.BuildConfig
import com.contextbubble.app.data.SettingsRepository
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class BackendCapabilities(
    val apiCompatibilityVersion: Int,
    val minimumAndroidVersion: Int,
    val supabaseUrl: String? = null,
    val supabasePublishableKey: String? = null,
    val googleWebClientId: String? = null,
    val mcpConnectUrl: String? = null,
    val policyVersion: Int = 0,
    val features: Map<String, Boolean> = emptyMap(),
    val limits: Map<String, Long> = emptyMap(),
)

class CloudConfigurationRepository(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val settings: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var cached: CachedCapabilities? = null

    suspend fun baseUrl(): String {
        val custom = settings.settings.first().customBackendUrl
        return validateBaseUrl(custom ?: BuildConfig.BACKEND_BASE_URL)
    }

    suspend fun capabilities(forceRefresh: Boolean = false): BackendCapabilities = withContext(Dispatchers.IO) {
        val baseUrl = baseUrl()
        val current = cached
        if (!forceRefresh && current != null && current.baseUrl == baseUrl && current.expiresAtEpochMs > System.currentTimeMillis()) {
            return@withContext current.value
        }
        val request = Request.Builder().url("$baseUrl/v1/config").get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Backend configuration unavailable (${response.code})")
            val value = json.decodeFromString<BackendCapabilities>(body)
            if (value.apiCompatibilityVersion !in SUPPORTED_API_VERSIONS) {
                throw IOException("Backend API ${value.apiCompatibilityVersion} is incompatible with this app")
            }
            cached = CachedCapabilities(baseUrl, value, System.currentTimeMillis() + CACHE_MS)
            value
        }
    }

    fun invalidate() {
        cached = null
    }

    private fun validateBaseUrl(raw: String): String {
        val normalized = raw.trim().trimEnd('/')
        val isLocalDebug = BuildConfig.DEBUG && (
            normalized.startsWith("http://127.0.0.1") ||
                normalized.startsWith("http://localhost") ||
                normalized.startsWith("http://10.0.2.2")
            )
        if (!normalized.startsWith("https://") && !isLocalDebug) {
            throw IOException("Custom backend must use HTTPS")
        }
        return normalized
    }

    private data class CachedCapabilities(
        val baseUrl: String,
        val value: BackendCapabilities,
        val expiresAtEpochMs: Long,
    )

    private companion object {
        val SUPPORTED_API_VERSIONS = 1..2
        const val CACHE_MS = 5 * 60 * 1_000L
    }
}
