package com.contextbubble.app.cloud

import android.app.Activity
import android.util.Base64
import com.contextbubble.app.BuildConfig
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

class GoogleSignInCoordinator(
    private val configuration: CloudConfigurationRepository,
    private val accounts: AccountRepository,
) {
    suspend fun signIn(activity: Activity): AccountState {
        if (BuildConfig.DEBUG) return accounts.signInForLocalDebug()
        val webClientId = configuration.capabilities(forceRefresh = true).googleWebClientId
            ?.takeIf(String::isNotBlank)
            ?: throw IOException("Google sign-in client ID is not configured")
        val nonce = generateNonce()
        val hashedNonce = MessageDigest.getInstance("SHA-256")
            .digest(nonce.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        val credentialManager = CredentialManager.create(activity)
        val result = runCatching {
            credentialManager.getCredential(
                context = activity,
                request = request(webClientId, hashedNonce),
            )
        }.getOrElse { error ->
            if (BuildConfig.DEBUG) return accounts.signInForLocalDebug()
            throw error
        }
        val credential = result.credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw IOException("Google returned an unsupported credential")
        }
        val google = GoogleIdTokenCredential.createFrom(credential.data)
        return runCatching { accounts.exchangeGoogleIdToken(google.idToken, nonce) }
            .getOrElse { error ->
                if (BuildConfig.DEBUG) accounts.signInForLocalDebug() else throw error
            }
    }

    private fun request(webClientId: String, hashedNonce: String): GetCredentialRequest {
        val google = GetSignInWithGoogleOption.Builder(webClientId)
            .setNonce(hashedNonce)
            .build()
        return GetCredentialRequest.Builder().addCredentialOption(google).build()
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
