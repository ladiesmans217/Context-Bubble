package com.contextbubble.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.contextbubble.app.domain.RetentionPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("context_bubble_settings")

data class AppSettings(
    val onboardingComplete: Boolean = false,
    val notificationPromptShown: Boolean = false,
    val bubbleEnabled: Boolean = false,
    val startOnBoot: Boolean = false,
    val retention: RetentionPolicy = RetentionPolicy.THIRTY_DAYS,
    val excludedPackages: Set<String> = emptySet(),
    val longPressTranscriptionOnly: Boolean = false,
    val readTextAnswersAloud: Boolean = false,
    val customBackendUrl: String? = null,
    val cloudSyncCursor: Long = 0,
    val lastCloudSyncAtEpochMs: Long = 0,
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val onboardingComplete = booleanPreferencesKey("onboarding_complete")
        val notificationPromptShown = booleanPreferencesKey("notification_prompt_shown")
        val bubbleEnabled = booleanPreferencesKey("bubble_enabled")
        val startOnBoot = booleanPreferencesKey("start_on_boot")
        val retentionDays = intPreferencesKey("retention_days")
        val excludedPackages = stringSetPreferencesKey("excluded_packages")
        val longPressTranscriptionOnly = booleanPreferencesKey("long_press_transcription_only")
        val readTextAnswersAloud = booleanPreferencesKey("read_text_answers_aloud")
        val customBackendUrl = stringPreferencesKey("custom_backend_url")
        val cloudSyncCursor = longPreferencesKey("cloud_sync_cursor")
        val lastCloudSyncAtEpochMs = longPreferencesKey("last_cloud_sync_at_epoch_ms")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map(::toSettings)

    suspend fun setOnboardingComplete(complete: Boolean) = edit(Keys.onboardingComplete, complete)
    suspend fun markNotificationPromptShown() = edit(Keys.notificationPromptShown, true)
    suspend fun setBubbleEnabled(enabled: Boolean) = edit(Keys.bubbleEnabled, enabled)
    suspend fun setStartOnBoot(enabled: Boolean) = edit(Keys.startOnBoot, enabled)
    suspend fun setRetention(policy: RetentionPolicy) = edit(Keys.retentionDays, policy.days ?: -1)
    suspend fun setLongPressTranscriptionOnly(enabled: Boolean) = edit(Keys.longPressTranscriptionOnly, enabled)
    suspend fun setReadTextAnswersAloud(enabled: Boolean) = edit(Keys.readTextAnswersAloud, enabled)
    suspend fun setCustomBackendUrl(url: String?) {
        context.settingsDataStore.edit { preferences ->
            if (url.isNullOrBlank()) preferences.remove(Keys.customBackendUrl)
            else preferences[Keys.customBackendUrl] = url.trim().trimEnd('/')
        }
    }
    suspend fun updateCloudSync(cursor: Long, syncedAtEpochMs: Long = System.currentTimeMillis()) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.cloudSyncCursor] = cursor.coerceAtLeast(0)
            preferences[Keys.lastCloudSyncAtEpochMs] = syncedAtEpochMs
        }
    }

    suspend fun setPackageExcluded(packageName: String, excluded: Boolean) {
        context.settingsDataStore.edit { preferences ->
            val packages = preferences[Keys.excludedPackages].orEmpty().toMutableSet()
            if (excluded) packages += packageName else packages -= packageName
            preferences[Keys.excludedPackages] = packages
        }
    }

    private suspend fun <T> edit(key: Preferences.Key<T>, value: T) {
        context.settingsDataStore.edit { it[key] = value }
    }

    private fun toSettings(preferences: Preferences): AppSettings {
        val storedDays = preferences[Keys.retentionDays] ?: 30
        return AppSettings(
            onboardingComplete = preferences[Keys.onboardingComplete] ?: false,
            notificationPromptShown = preferences[Keys.notificationPromptShown] ?: false,
            bubbleEnabled = preferences[Keys.bubbleEnabled] ?: false,
            startOnBoot = preferences[Keys.startOnBoot] ?: false,
            retention = RetentionPolicy.entries.firstOrNull { (it.days ?: -1) == storedDays }
                ?: RetentionPolicy.THIRTY_DAYS,
            excludedPackages = preferences[Keys.excludedPackages].orEmpty(),
            longPressTranscriptionOnly = preferences[Keys.longPressTranscriptionOnly] ?: false,
            readTextAnswersAloud = preferences[Keys.readTextAnswersAloud] ?: false,
            customBackendUrl = preferences[Keys.customBackendUrl],
            cloudSyncCursor = preferences[Keys.cloudSyncCursor] ?: 0,
            lastCloudSyncAtEpochMs = preferences[Keys.lastCloudSyncAtEpochMs] ?: 0,
        )
    }
}
