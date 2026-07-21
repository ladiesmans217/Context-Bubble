package com.contextbubble.app.cloud

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class CloudStorageUsage(val usedBytes: Long, val itemCount: Int, val limitBytes: Long)

@Serializable
data class CloudDashboard(
    val storage: CloudStorageUsage,
    val memories: Int,
    val activeMcpGrants: Int,
    val activeIntegrations: Int,
)

@Serializable
data class CloudDeletePreview(val confirmationToken: String, val expiresAt: String, val preview: String)

class CloudAccountRepository(
    private val configuration: CloudConfigurationRepository,
    private val accounts: AccountRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun dashboard(): CloudDashboard = get("/v1/cloud/dashboard") { json.decodeFromString(it) }

    suspend fun exportJson(): String = get("/v1/account/export") { it }

    suspend fun prepareDelete(): CloudDeletePreview = write("/v1/account/delete/prepare", "{}") { json.decodeFromString(it) }

    suspend fun commitDelete(preview: CloudDeletePreview) {
        write(
            "/v1/account/delete/commit",
            json.encodeToString(DeleteCommit(preview.confirmationToken)),
        ) { Unit }
        accounts.signOut()
    }

    private suspend fun <T> get(path: String, decode: (String) -> T): T = request { token, baseUrl ->
        execute(Request.Builder().url("$baseUrl$path").header(USER_TOKEN, "Bearer $token").get().build(), decode)
    }

    private suspend fun <T> write(path: String, body: String, decode: (String) -> T): T = request { token, baseUrl ->
        execute(
            Request.Builder().url("$baseUrl$path").header(USER_TOKEN, "Bearer $token")
                .post(body.toRequestBody(JSON)).build(),
            decode,
        )
    }

    private suspend fun <T> request(block: (String, String) -> T): T = withContext(Dispatchers.IO) {
        val token = accounts.accessToken() ?: throw IOException("Sign in to manage cloud data")
        block(token, configuration.baseUrl())
    }

    private fun <T> execute(request: Request, decode: (String) -> T): T {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(error(body, response.code))
            return decode(body)
        }
    }

    private fun error(raw: String, status: Int): String = runCatching {
        json.decodeFromString<AccountErrorEnvelope>(raw).error?.message
    }.getOrNull() ?: "Cloud request failed ($status)"

    private companion object {
        const val USER_TOKEN = "X-Context-Bubble-User-Token"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable private data class DeleteCommit(
    val confirmationToken: String,
    val confirmationText: String = "DELETE CLOUD DATA",
)
@Serializable private data class AccountErrorEnvelope(val error: AccountError? = null)
@Serializable private data class AccountError(val message: String? = null)
