package com.contextbubble.app.cloud

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class CalendarEventInput(
    val title: String,
    val description: String? = null,
    val startDateTime: String,
    val endDateTime: String,
    val timeZone: String,
    val recurrence: List<String>? = null,
)

class CalendarRepository(
    private val configuration: CloudConfigurationRepository,
    private val accounts: AccountRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun connected(): Boolean = authorizedRequest("/v1/calendar/status") { raw ->
        json.decodeFromString<CalendarStatus>(raw).connected
    }

    suspend fun exchangeCode(code: String) {
        val body = json.encodeToString(CalendarCode(code)).toRequestBody(JSON)
        authorizedWrite("/v1/calendar/oauth/exchange", "POST", body)
    }

    suspend fun create(title: String, description: String, startEpochMs: Long, endEpochMs: Long): String {
        val zone = ZoneId.systemDefault()
        val event = CalendarEventInput(
            title = title,
            description = description.takeIf(String::isNotBlank),
            startDateTime = Instant.ofEpochMilli(startEpochMs).atZone(zone).toOffsetDateTime().toString(),
            endDateTime = Instant.ofEpochMilli(endEpochMs).atZone(zone).toOffsetDateTime().toString(),
            timeZone = zone.id,
        )
        val preview = authorizedWrite(
            "/v1/calendar/events/prepare",
            "POST",
            json.encodeToString(CalendarPrepare(event = event)).toRequestBody(JSON),
        ) { raw -> json.decodeFromString<CalendarPrepared>(raw) }
        val payload = json.encodeToString(CalendarConfirmedEvent(preview.confirmationToken, event)).toRequestBody(JSON)
        return authorizedWrite("/v1/calendar/events", "POST", payload, UUID.randomUUID().toString()) { raw ->
            json.decodeFromString<CalendarEventResult>(raw).id
        }
    }

    suspend fun revoke() {
        authorizedWrite("/v1/calendar/connection", "DELETE", ByteArray(0).toRequestBody(null))
    }

    private suspend fun <T> authorizedRequest(path: String, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        val token = accounts.accessToken() ?: throw IOException("Sign in before connecting Calendar")
        val request = Request.Builder().url("${configuration.baseUrl()}$path")
            .header(USER_TOKEN, "Bearer $token").get().build()
        client.newCall(request).execute().use { response -> parseResponse(response, parse) }
    }

    private suspend fun authorizedWrite(path: String, method: String, body: okhttp3.RequestBody) {
        authorizedWrite(path, method, body, null) { Unit }
    }

    private suspend fun <T> authorizedWrite(
        path: String,
        method: String,
        body: okhttp3.RequestBody,
        idempotencyKey: String? = null,
        parse: (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        val token = accounts.accessToken() ?: throw IOException("Sign in before connecting Calendar")
        val builder = Request.Builder().url("${configuration.baseUrl()}$path")
            .header(USER_TOKEN, "Bearer $token")
            .method(method, body)
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        client.newCall(builder.build()).execute().use { response -> parseResponse(response, parse) }
    }

    private fun <T> parseResponse(response: okhttp3.Response, parse: (String) -> T): T {
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val message = runCatching { json.decodeFromString<CalendarErrorEnvelope>(raw).error?.message }.getOrNull()
            throw IOException(message ?: "Calendar request failed (${response.code})")
        }
        return parse(raw)
    }

    private companion object {
        const val USER_TOKEN = "X-Context-Bubble-User-Token"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

class CalendarAuthorizationCoordinator(
    private val configuration: CloudConfigurationRepository,
    private val calendar: CalendarRepository,
) {
    suspend fun connect(activity: Activity, launchResolution: suspend (PendingIntent) -> Intent?): Boolean {
        val clientId = configuration.capabilities(forceRefresh = true).googleWebClientId
            ?.takeIf(String::isNotBlank)
            ?: throw IOException("Google Calendar client ID is not configured")
        val client = Identity.getAuthorizationClient(activity)
        val request = AuthorizationRequest.builder()
            .requestOfflineAccess(clientId)
            .setRequestedScopes(listOf(Scope(CALENDAR_SCOPE)))
            .build()
        var result = client.authorize(request).await()
        if (result.hasResolution()) {
            val intent = launchResolution(result.pendingIntent ?: throw IOException("Calendar authorization could not be opened"))
                ?: return false
            result = client.getAuthorizationResultFromIntent(intent)
        }
        val code = result.serverAuthCode ?: throw IOException("Google did not return a Calendar authorization code")
        calendar.exchangeCode(code)
        return true
    }

    private companion object {
        const val CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar.events"
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

@Serializable private data class CalendarStatus(val connected: Boolean)
@Serializable private data class CalendarCode(val code: String)
@Serializable private data class CalendarPrepare(val operation: String = "CREATE", val event: CalendarEventInput)
@Serializable private data class CalendarPrepared(val confirmationToken: String, val expiresAt: String)
@Serializable private data class CalendarConfirmedEvent(val confirmationToken: String, val event: CalendarEventInput)
@Serializable private data class CalendarEventResult(val id: String)
@Serializable private data class CalendarErrorEnvelope(val error: CalendarErrorPayload? = null)
@Serializable private data class CalendarErrorPayload(val message: String? = null)
