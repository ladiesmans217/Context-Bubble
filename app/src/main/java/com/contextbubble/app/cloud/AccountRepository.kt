package com.contextbubble.app.cloud

import android.content.Context
import com.contextbubble.app.BuildConfig
import com.contextbubble.app.data.CryptoBox
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface AccountState {
    data object SignedOut : AccountState
    data class SignedIn(val userId: String, val email: String?) : AccountState
    data class Unavailable(val message: String) : AccountState
}

class AccountRepository(
    private val context: Context,
    private val crypto: CryptoBox,
    private val configuration: CloudConfigurationRepository,
) {
    private val preferences = context.getSharedPreferences("cloud_account", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val refreshMutex = Mutex()
    private val mutableState = MutableStateFlow(loadPersistedState())
    val state: StateFlow<AccountState> = mutableState.asStateFlow()

    suspend fun signInForLocalDebug(): AccountState = withContext(Dispatchers.IO) {
        val baseUrl = configuration.baseUrl()
        val capabilities = configuration.capabilities(forceRefresh = true)
        val supabaseUrl = capabilities.supabaseUrl ?: throw IOException("Supabase URL is not configured")
        val localDebug = BuildConfig.DEBUG && (
            baseUrl.startsWith("http://127.0.0.1") ||
                baseUrl.startsWith("http://localhost") ||
                baseUrl.startsWith("http://10.0.2.2")
            )
        if (!localDebug) throw IOException("Local debug sign-in is unavailable")
        val installationPreferences = context.getSharedPreferences("installation_identity", Context.MODE_PRIVATE)
        val installationId = installationPreferences.getString("id", null)
            ?: UUID.randomUUID().toString().also { installationPreferences.edit().putString("id", it).apply() }
        val request = Request.Builder()
            .url("$baseUrl/v1/debug/session")
            .post(json.encodeToString(DebugSessionRequest(installationId)).toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(authError(raw, response.code))
            val session = json.decodeFromString<SupabaseSession>(raw)
            persist(session, supabaseUrl)
            val next = AccountState.SignedIn(session.user.id, "Local demo account")
            mutableState.value = next
            next
        }
    }

    suspend fun exchangeGoogleIdToken(idToken: String, rawNonce: String): AccountState = withContext(Dispatchers.IO) {
        val capabilities = configuration.capabilities(forceRefresh = true)
        val supabaseUrl = capabilities.supabaseUrl ?: throw IOException("Supabase URL is not configured")
        val publishableKey = capabilities.supabasePublishableKey ?: throw IOException("Supabase publishable key is not configured")
        val body = json.encodeToString(IdTokenRequest(idToken = idToken, nonce = rawNonce)).toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url("${supabaseUrl.trimEnd('/')}/auth/v1/token?grant_type=id_token")
            .header("apikey", publishableKey)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(authError(raw, response.code))
            val session = json.decodeFromString<SupabaseSession>(raw)
            persist(session, supabaseUrl)
            val next = AccountState.SignedIn(session.user.id, session.user.email)
            mutableState.value = next
            next
        }
    }

    suspend fun accessToken(): String? = refreshMutex.withLock {
        val encrypted = preferences.getString(ACCESS_TOKEN, null) ?: return@withLock null
        val storedUrl = preferences.getString(SUPABASE_URL, null) ?: return@withLock null
        val capabilities = runCatching { configuration.capabilities() }.getOrNull() ?: return@withLock null
        if (capabilities.supabaseUrl?.trimEnd('/') != storedUrl.trimEnd('/')) return@withLock null
        val expiresAt = preferences.getLong(EXPIRES_AT, 0)
        if (expiresAt > System.currentTimeMillis() + REFRESH_GUARD_MS) {
            return@withLock runCatching { crypto.decryptString(encrypted) }.getOrNull()
        }
        refresh(capabilities, storedUrl)
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        val token = runCatching { preferences.getString(ACCESS_TOKEN, null)?.let(crypto::decryptString) }.getOrNull()
        val capabilities = runCatching { configuration.capabilities() }.getOrNull()
        if (token != null && capabilities?.supabaseUrl != null && capabilities.supabasePublishableKey != null) {
            val request = Request.Builder()
                .url("${capabilities.supabaseUrl.trimEnd('/')}/auth/v1/logout")
                .header("apikey", capabilities.supabasePublishableKey)
                .header("Authorization", "Bearer $token")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            runCatching { client.newCall(request).execute().close() }
        }
        clearSession()
    }

    private fun refresh(capabilities: BackendCapabilities, supabaseUrl: String): String? {
        val refreshToken = runCatching {
            preferences.getString(REFRESH_TOKEN, null)?.let(crypto::decryptString)
        }.getOrNull() ?: return null
        val publishableKey = capabilities.supabasePublishableKey ?: return null
        val request = Request.Builder()
            .url("${supabaseUrl.trimEnd('/')}/auth/v1/token?grant_type=refresh_token")
            .header("apikey", publishableKey)
            .post(json.encodeToString(RefreshRequest(refreshToken)).toRequestBody(JSON_MEDIA))
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException(authError(raw, response.code))
                val session = json.decodeFromString<SupabaseSession>(raw)
                persist(session, supabaseUrl)
                mutableState.value = AccountState.SignedIn(session.user.id, session.user.email)
                session.accessToken
            }
        }.getOrElse {
            clearSession()
            null
        }
    }

    private fun persist(session: SupabaseSession, supabaseUrl: String) {
        preferences.edit()
            .putString(ACCESS_TOKEN, crypto.encryptString(session.accessToken))
            .putString(REFRESH_TOKEN, crypto.encryptString(session.refreshToken))
            .putLong(EXPIRES_AT, System.currentTimeMillis() + session.expiresIn * 1_000L)
            .putString(USER_ID, crypto.encryptString(session.user.id))
            .putString(USER_EMAIL, session.user.email?.let(crypto::encryptString))
            .putString(SUPABASE_URL, supabaseUrl.trimEnd('/'))
            .apply()
    }

    private fun clearSession() {
        preferences.edit().clear().apply()
        mutableState.value = AccountState.SignedOut
    }

    private fun loadPersistedState(): AccountState {
        val encryptedId = preferences.getString(USER_ID, null) ?: return AccountState.SignedOut
        return runCatching {
            AccountState.SignedIn(
                crypto.decryptString(encryptedId),
                preferences.getString(USER_EMAIL, null)?.let(crypto::decryptString),
            )
        }.getOrElse { AccountState.SignedOut }
    }

    private fun authError(raw: String, status: Int): String {
        val parsed = runCatching { json.decodeFromString<AuthErrorPayload>(raw) }.getOrNull()
        return parsed?.message ?: parsed?.description ?: "Cloud sign-in failed ($status)"
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val ACCESS_TOKEN = "access_token"
        const val REFRESH_TOKEN = "refresh_token"
        const val EXPIRES_AT = "expires_at"
        const val USER_ID = "user_id"
        const val USER_EMAIL = "user_email"
        const val SUPABASE_URL = "supabase_url"
        const val REFRESH_GUARD_MS = 2 * 60 * 1_000L
    }
}

@Serializable
private data class IdTokenRequest(
    val provider: String = "google",
    @SerialName("id_token") val idToken: String,
    val nonce: String,
)

@Serializable
private data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
private data class DebugSessionRequest(val installationId: String)

@Serializable
private data class SupabaseSession(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    val user: SupabaseUser,
)

@Serializable
private data class SupabaseUser(val id: String, val email: String? = null)

@Serializable
private data class AuthErrorPayload(
    val message: String? = null,
    @SerialName("error_description") val description: String? = null,
)
