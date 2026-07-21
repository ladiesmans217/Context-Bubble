package com.contextbubble.app.policy

import android.content.Context
import android.util.Base64
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.contextbubble.app.BuildConfig
import com.contextbubble.app.appContainer
import com.contextbubble.app.cloud.CloudConfigurationRepository
import com.contextbubble.app.data.SettingsRepository
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

interface PackagePolicyRepository {
    val userExcludedPackages: StateFlow<Set<String>>
    fun isHardBlocked(packageName: String?): Boolean
    fun isBlocked(packageName: String?): Boolean
    suspend fun setExcluded(packageName: String, excluded: Boolean)
    suspend fun refresh(): Boolean
}

class PackagePolicyRepositoryImpl(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val ownPackageName: String,
    private val configuration: CloudConfigurationRepository,
) : PackagePolicyRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val preferences = context.getSharedPreferences("signed_package_policy", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()
    @Volatile private var remoteHardBlocks: Set<String> = loadLastKnownGood()
    @Volatile private var remoteCertificateFingerprints: Map<String, Set<String>> = loadCertificateFingerprints()
    private val packageManager = context.packageManager
    private val certificateBlockCache = ConcurrentHashMap<String, Boolean>()

    override val userExcludedPackages = settingsRepository.settings
        .map { it.excludedPackages }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    override fun isHardBlocked(packageName: String?): Boolean {
        return isHardBlockedPackage(packageName, ownPackageName) || packageName in remoteHardBlocks || hasBlockedCertificate(packageName)
    }

    override fun isBlocked(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return true
        return isHardBlocked(packageName) || packageName in userExcludedPackages.value
    }

    override suspend fun setExcluded(packageName: String, excluded: Boolean) {
        if (!isHardBlocked(packageName)) settingsRepository.setPackageExcluded(packageName, excluded)
    }

    override suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val publicKeyBase64 = BuildConfig.POLICY_SIGNING_PUBLIC_KEY_DER_BASE64
        if (publicKeyBase64.isBlank()) return@withContext false
        val request = Request.Builder().url("${configuration.baseUrl()}/v1/policies/package-blocklist").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext false
            val envelope = json.decodeFromString<SignedPolicyEnvelope>(response.body?.string().orEmpty())
            val signatureText = envelope.signature ?: return@withContext false
            val payloadBytes = Base64.decode(envelope.signedPayload, Base64.DEFAULT)
            val signatureBytes = Base64.decode(signatureText, Base64.DEFAULT)
            val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                X509EncodedKeySpec(Base64.decode(publicKeyBase64, Base64.DEFAULT)),
            )
            val verified = Signature.getInstance("Ed25519").run {
                initVerify(publicKey)
                update(payloadBytes)
                verify(signatureBytes)
            }
            if (!verified) return@withContext false
            val policy = json.decodeFromString<SignedPolicyPayload>(payloadBytes.toString(Charsets.UTF_8))
            val now = System.currentTimeMillis()
            val issuedAt = runCatching { Instant.parse(policy.issuedAt).toEpochMilli() }.getOrNull() ?: return@withContext false
            val expiresAt = runCatching { Instant.parse(policy.expiresAt).toEpochMilli() }.getOrNull() ?: return@withContext false
            val storedVersion = preferences.getInt(KEY_VERSION, 0)
            if (policy.version < storedVersion || issuedAt > now + 5 * 60_000L || expiresAt <= now || expiresAt > now + 14L * 24 * 60 * 60_000L) {
                return@withContext false
            }
            val packages = policy.packages
                .filter { it.matches(PACKAGE_PATTERN) }
                .toSet()
            if (packages.isEmpty()) return@withContext false
            val certificateFingerprints = policy.certificateFingerprints.mapValues { (_, values) ->
                values.map { it.replace(":", "").uppercase() }.filter { it.matches(FINGERPRINT_PATTERN) }.toSet()
            }.filterValues(Set<String>::isNotEmpty)
            preferences.edit()
                .putInt(KEY_VERSION, policy.version)
                .putLong(KEY_EXPIRY, expiresAt)
                .putString(KEY_PACKAGES, json.encodeToString(packages))
                .putString(KEY_CERTIFICATES, json.encodeToString(certificateFingerprints))
                .apply()
            remoteHardBlocks = packages
            remoteCertificateFingerprints = certificateFingerprints
            certificateBlockCache.clear()
            true
        }
    }

    private fun loadLastKnownGood(): Set<String> {
        if (preferences.getLong(KEY_EXPIRY, 0L) <= System.currentTimeMillis()) return emptySet()
        val stored = preferences.getString(KEY_PACKAGES, null) ?: return emptySet()
        return runCatching { json.decodeFromString<Set<String>>(stored) }.getOrDefault(emptySet())
    }

    private fun loadCertificateFingerprints(): Map<String, Set<String>> {
        if (preferences.getLong(KEY_EXPIRY, 0L) <= System.currentTimeMillis()) return emptyMap()
        val stored = preferences.getString(KEY_CERTIFICATES, null) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, Set<String>>>(stored) }.getOrDefault(emptyMap())
    }

    private fun hasBlockedCertificate(packageName: String?): Boolean {
        if (packageName.isNullOrBlank() || packageName !in remoteCertificateFingerprints) return false
        return certificateBlockCache.getOrPut(packageName) {
            runCatching {
                val signingInfo = packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())).signingInfo
                    ?: return@runCatching false
                val signatures = if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
                val expected = remoteCertificateFingerprints[packageName].orEmpty()
                signatures.any { signature ->
                    val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                        .joinToString("") { byte -> "%02X".format(byte) }
                    digest in expected
                }
            }.getOrDefault(false)
        }
    }

    private companion object {
        const val KEY_VERSION = "version"
        const val KEY_EXPIRY = "expires_at"
        const val KEY_PACKAGES = "packages"
        const val KEY_CERTIFICATES = "certificate_fingerprints"
        val PACKAGE_PATTERN = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
        val FINGERPRINT_PATTERN = Regex("^[0-9A-F]{64}$")
    }

}

class PackagePolicyRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { applicationContext.appContainer.packagePolicy.refresh() }
        return Result.success()
    }
}

@Serializable
private data class SignedPolicyEnvelope(val signedPayload: String, val signature: String? = null)

@Serializable
private data class SignedPolicyPayload(
    val version: Int,
    val issuedAt: String,
    val expiresAt: String,
    val packages: List<String>,
    val certificateFingerprints: Map<String, List<String>> = emptyMap(),
)

internal fun isHardBlockedPackage(packageName: String?, ownPackageName: String): Boolean {
    if (packageName.isNullOrBlank()) return true
    return packageName == ownPackageName || packageName in HARD_BLOCKED_PACKAGES ||
        HARD_BLOCKED_PREFIXES.any(packageName::startsWith)
}

private val HARD_BLOCKED_PREFIXES = setOf(
    "com.android.permissioncontroller",
    "com.google.android.permissioncontroller",
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
    "com.android.systemui",
    "com.samsung.android.biometrics",
)

private val HARD_BLOCKED_PACKAGES = setOf(
    "com.google.android.apps.nbu.paisa.user",
    "com.phonepe.app",
    "net.one97.paytm",
    "in.org.npci.upiapp",
    "com.sbi.SBIFreedomPlus",
    "com.axis.mobile",
    "com.csam.icici.bank.imobile",
    "com.kotak811mobilebankingapp.instantsavingsup",
    "com.unionbank.ecommerce.mobile.android",
    "com.yesbank",
    "com.enstage.wibmo.hdfc",
    "com.idfcfirstbank.optimus",
    "com.dreamplug.androidapp",
    "com.agilebits.onepassword",
    "com.x8bit.bitwarden",
    "com.google.android.apps.authenticator2",
    "com.azure.authenticator",
    "com.authy.authy",
)
