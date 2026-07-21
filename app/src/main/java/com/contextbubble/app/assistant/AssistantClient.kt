package com.contextbubble.app.assistant

import com.contextbubble.app.BuildConfig
import com.contextbubble.app.domain.AssistRequest
import com.contextbubble.app.domain.AssistResponse
import com.contextbubble.app.data.CryptoBox
import com.contextbubble.app.cloud.AccountRepository
import com.contextbubble.app.cloud.CloudConfigurationRepository
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Base64
import android.content.Context

interface AssistantClient {
    suspend fun assist(request: AssistRequest): Result<AssistResponse>
    suspend fun assistStreaming(request: AssistRequest, onDelta: (String) -> Unit): Result<AssistResponse>
    suspend fun transcribePcm(pcm: ByteArray, sampleRate: Int = 24_000): Result<String>
    suspend fun generateImage(prompt: String): Result<GeneratedImage>
    suspend fun exchangeRealtimeSdp(offerSdp: String): Result<String>
}

data class GeneratedImage(val bytes: ByteArray, val mimeType: String)

class BackendAssistantClient(
    context: Context,
    private val configuration: CloudConfigurationRepository,
    private val accounts: AccountRepository,
    private val crypto: CryptoBox,
) : AssistantClient {
    private val integrityAttestor = PlayIntegrityAttestor(context)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val identityPreferences = context.getSharedPreferences("installation_identity", Context.MODE_PRIVATE)
    private val installationId: String = identityPreferences.run {
        getString("id", null) ?: UUID.randomUUID().toString().also { edit().putString("id", it).apply() }
    }

    override suspend fun assist(request: AssistRequest): Result<AssistResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = configuration.baseUrl()
            val body = json.encodeToString(request).toRequestBody(JSON)
            val builder = Request.Builder()
                .url("$baseUrl/v1/assist")
                .header("X-Installation-Id", installationId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .post(body)
            accounts.accessToken()?.let { builder.header(USER_TOKEN_HEADER, "Bearer $it") }
            executeAuthorized(builder.build(), baseUrl).use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw backendError("Assistant", response.code, text)
                json.decodeFromString<AssistResponse>(text)
            }
        }
    }

    override suspend fun assistStreaming(request: AssistRequest, onDelta: (String) -> Unit): Result<AssistResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = configuration.baseUrl()
            val builder = Request.Builder()
                .url("$baseUrl/v1/assist")
                .header("X-Installation-Id", installationId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Accept", "text/event-stream")
                .post(json.encodeToString(request).toRequestBody(JSON))
            accounts.accessToken()?.let { builder.header(USER_TOKEN_HEADER, "Bearer $it") }
            executeAuthorized(builder.build(), baseUrl).use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    throw backendError("Assistant", response.code, text)
                }
                val source = response.body?.source() ?: throw IOException("Assistant returned no stream")
                var eventName = "message"
                val data = StringBuilder()
                var completed: AssistResponse? = null
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) {
                        if (data.isNotEmpty()) {
                            val payload = data.toString()
                            when (eventName) {
                                "delta" -> {
                                    val event = json.decodeFromString<AssistDeltaEvent>(payload)
                                    if (event.delta.isNotEmpty()) onDelta(event.delta)
                                }
                                "completed" -> completed = json.decodeFromString<AssistCompletedEvent>(payload).response
                                "error" -> throw IOException(json.decodeFromString<AssistStreamError>(payload).message)
                            }
                        }
                        eventName = "message"
                        data.clear()
                    } else if (line.startsWith("event:")) {
                        eventName = line.substringAfter(':').trim()
                    } else if (line.startsWith("data:")) {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.substringAfter(':').trimStart())
                    }
                }
                completed ?: throw IOException("Assistant stream ended before completion")
            }
        }
    }

    override suspend fun transcribePcm(pcm: ByteArray, sampleRate: Int): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = configuration.baseUrl()
            val request = Request.Builder()
                .url("$baseUrl/v1/transcriptions")
                .header("X-Installation-Id", installationId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("X-Audio-Sample-Rate", sampleRate.toString())
                .post(pcm.toRequestBody(PCM))
                .build()
            executeAuthorized(request, baseUrl).use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw backendError("Transcription", response.code, text)
                json.decodeFromString<TranscriptionResponse>(text).text
            }
        }
    }

    override suspend fun generateImage(prompt: String): Result<GeneratedImage> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = configuration.baseUrl()
            val body = json.encodeToString(ImageRequest(prompt)).toRequestBody(JSON)
            val request = Request.Builder()
                .url("$baseUrl/v1/images")
                .header("X-Installation-Id", installationId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .post(body)
                .build()
            executeAuthorized(request, baseUrl).use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw backendError("Image generation", response.code, text)
                val parsed = json.decodeFromString<ImageResponse>(text)
                GeneratedImage(Base64.decode(parsed.imageBase64, Base64.DEFAULT), parsed.mimeType)
            }
        }
    }

    override suspend fun exchangeRealtimeSdp(offerSdp: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = configuration.baseUrl()
            val builder = Request.Builder()
                .url("$baseUrl/v1/realtime/call")
                .header("X-Installation-Id", installationId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .post(offerSdp.toRequestBody(SDP))
            accounts.accessToken()?.let { builder.header(USER_TOKEN_HEADER, "Bearer $it") }
            executeAuthorized(builder.build(), baseUrl).use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw backendError("Realtime voice", response.code, text)
                if (text.isBlank()) throw IOException("Realtime voice returned no SDP answer")
                text
            }
        }
    }

    private suspend fun executeAuthorized(request: Request, baseUrl: String): Response {
        val first = client.newCall(
            request.newBuilder().header("Authorization", "Bearer ${installationToken(baseUrl)}").build(),
        ).execute()
        if (first.code != 401) return first
        first.close()
        clearInstallationToken()
        return client.newCall(
            request.newBuilder().header("Authorization", "Bearer ${installationToken(baseUrl)}").build(),
        ).execute()
    }

    private suspend fun installationToken(baseUrl: String): String {
        if (identityPreferences.getString(TOKEN_BACKEND_KEY, null) != baseUrl) clearInstallationToken()
        val expiresAt = identityPreferences.getLong(TOKEN_EXPIRY_KEY, 0L)
        val encrypted = identityPreferences.getString(TOKEN_KEY, null)
        if (encrypted != null && expiresAt > System.currentTimeMillis() + TOKEN_REFRESH_GUARD_MS) {
            runCatching { crypto.decryptString(encrypted) }.getOrNull()?.let { return it }
        }

        val request = Request.Builder()
            .url("$baseUrl/v1/installations/register")
            .post(json.encodeToString(RegistrationRequest(
                installationId = installationId,
                attestation = integrityAttestor.attestInstallation(installationId),
                inviteCredential = BuildConfig.LAB_INVITE_CREDENTIAL.takeIf(String::isNotBlank),
            )).toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(
                if (response.code == 401) "Device registration requires Play Integrity configuration" else "Device registration unavailable (${response.code})",
            )
            val registration = json.decodeFromString<RegistrationResponse>(responseBody)
            identityPreferences.edit()
                .putString(TOKEN_KEY, crypto.encryptString(registration.token))
                .putLong(TOKEN_EXPIRY_KEY, System.currentTimeMillis() + registration.expiresInSeconds * 1_000L)
                .putString(TOKEN_BACKEND_KEY, baseUrl)
                .apply()
            return registration.token
        }
    }

    private fun clearInstallationToken() {
        identityPreferences.edit().remove(TOKEN_KEY).remove(TOKEN_EXPIRY_KEY).remove(TOKEN_BACKEND_KEY).apply()
    }

    private fun backendError(operation: String, status: Int, body: String): IOException {
        val detail = runCatching { json.decodeFromString<BackendErrorEnvelope>(body).error?.message }
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.take(300)
        return IOException(detail ?: "$operation unavailable ($status)")
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val PCM = "audio/pcm".toMediaType()
        val SDP = "application/sdp".toMediaType()
        const val TOKEN_KEY = "encrypted_installation_token"
        const val TOKEN_EXPIRY_KEY = "installation_token_expiry"
        const val TOKEN_BACKEND_KEY = "installation_token_backend"
        const val USER_TOKEN_HEADER = "X-Context-Bubble-User-Token"
        const val TOKEN_REFRESH_GUARD_MS = 60_000L
    }
}

@Serializable
private data class RegistrationRequest(
    val installationId: String,
    val attestation: String? = null,
    val inviteCredential: String? = null,
)

@Serializable
private data class RegistrationResponse(val token: String, val expiresInSeconds: Long)

@Serializable
private data class TranscriptionResponse(val text: String)

@Serializable
private data class ImageRequest(val prompt: String)

@Serializable
private data class ImageResponse(val imageBase64: String, val mimeType: String)

@Serializable
private data class AssistDeltaEvent(val type: String, val delta: String)

@Serializable
private data class AssistCompletedEvent(val type: String, val response: AssistResponse)

@Serializable
private data class AssistStreamError(val code: String? = null, val message: String)

@Serializable
private data class BackendErrorEnvelope(val error: BackendErrorPayload? = null)

@Serializable
private data class BackendErrorPayload(val code: String? = null, val message: String? = null, val requestId: String? = null)
