package com.contextbubble.app.cloud

import java.io.IOException
import java.net.URLEncoder
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
data class McpGrant(
    val clientId: String,
    val accessLevel: String,
    val revoked: Boolean,
    val lastAccessAt: String? = null,
    val createdAt: String,
)

@Serializable
data class McpChange(
    val memory_id: String,
    val operation: String,
    val actor_client_id: String? = null,
    val created_at: String,
)

class McpRepository(
    private val configuration: CloudConfigurationRepository,
    private val accounts: AccountRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun listGrants(): List<McpGrant> = request { token, baseUrl ->
        val request = Request.Builder().url("$baseUrl/v1/mcp/grants")
            .header(USER_TOKEN, "Bearer $token").get().build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(error(raw, response.code))
            json.decodeFromString<McpGrantList>(raw).grants
        }
    }

    suspend fun recentChanges(): List<McpChange> = request { token, baseUrl ->
        val request = Request.Builder().url("$baseUrl/v1/mcp/changes")
            .header(USER_TOKEN, "Bearer $token").get().build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(error(raw, response.code))
            json.decodeFromString<McpChanges>(raw).changes
        }
    }

    suspend fun setAccess(clientId: String, readWrite: Boolean) = request { token, baseUrl ->
        val body = json.encodeToString(McpAccess(if (readWrite) "READ_WRITE" else "READ_ONLY")).toRequestBody(JSON)
        executeWrite(token, "$baseUrl/v1/mcp/grants/${encoded(clientId)}", "PATCH", body)
    }

    suspend fun revoke(clientId: String) = request { token, baseUrl ->
        executeWrite(token, "$baseUrl/v1/mcp/grants/${encoded(clientId)}", "DELETE", ByteArray(0).toRequestBody(null))
    }

    private suspend fun <T> request(block: (String, String) -> T): T = withContext(Dispatchers.IO) {
        val token = accounts.accessToken() ?: throw IOException("Sign in to manage MCP connections")
        block(token, configuration.baseUrl())
    }

    private fun executeWrite(token: String, url: String, method: String, body: okhttp3.RequestBody) {
        val request = Request.Builder().url(url).header(USER_TOKEN, "Bearer $token").method(method, body).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(error(raw, response.code))
        }
    }

    private fun error(raw: String, status: Int): String = runCatching {
        json.decodeFromString<McpErrorEnvelope>(raw).error?.message
    }.getOrNull() ?: "MCP request failed ($status)"

    private fun encoded(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        const val USER_TOKEN = "X-Context-Bubble-User-Token"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable private data class McpGrantList(val grants: List<McpGrant>)
@Serializable private data class McpChanges(val changes: List<McpChange>)
@Serializable private data class McpAccess(val accessLevel: String)
@Serializable private data class McpErrorEnvelope(val error: McpError? = null)
@Serializable private data class McpError(val message: String? = null)
