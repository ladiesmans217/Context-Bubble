package com.contextbubble.app

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.NetworkType
import com.contextbubble.app.assistant.BackendAssistantClient
import com.contextbubble.app.cloud.AccountRepository
import com.contextbubble.app.cloud.CloudConfigurationRepository
import com.contextbubble.app.cloud.CloudMemorySyncClient
import com.contextbubble.app.cloud.CloudMemorySyncScheduler
import com.contextbubble.app.cloud.GoogleSignInCoordinator
import com.contextbubble.app.cloud.McpRepository
import com.contextbubble.app.cloud.CalendarAuthorizationCoordinator
import com.contextbubble.app.cloud.CalendarRepository
import com.contextbubble.app.cloud.CloudAccountRepository
import com.contextbubble.app.data.AppDatabase
import com.contextbubble.app.data.CryptoBox
import com.contextbubble.app.data.LocalVaultRepository
import com.contextbubble.app.data.SettingsRepository
import com.contextbubble.app.data.RetentionCleanupWorker
import com.contextbubble.app.policy.PackagePolicyRepositoryImpl
import com.contextbubble.app.policy.PackagePolicyRefreshWorker
import com.contextbubble.app.reminders.AndroidReminderScheduler
import com.contextbubble.app.speech.RealtimeVoiceClient
import java.util.concurrent.TimeUnit

class ContextBubbleApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "retention-cleanup",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<RetentionCleanupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).setRequiresBatteryNotLow(true).build())
                .build(),
        )
        CloudMemorySyncScheduler.schedulePeriodic(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "signed-package-policy-refresh",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<PackagePolicyRefreshWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build(),
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()
}

class AppContainer(app: Application) {
    val settings = SettingsRepository(app)
    val cryptoBox = CryptoBox()
    val cloudConfiguration = CloudConfigurationRepository(app, settings)
    val accounts = AccountRepository(app, cryptoBox, cloudConfiguration)
    val googleSignIn = GoogleSignInCoordinator(cloudConfiguration, accounts)
    val mcp = McpRepository(cloudConfiguration, accounts)
    val cloudAccount = CloudAccountRepository(cloudConfiguration, accounts)
    val calendar = CalendarRepository(cloudConfiguration, accounts)
    val calendarAuthorization = CalendarAuthorizationCoordinator(cloudConfiguration, calendar)
    val database: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "context-bubble.db")
        .addMigrations(MIGRATION_1_2)
        .build()
    val vault = LocalVaultRepository(database, cryptoBox, app.filesDir) {
        CloudMemorySyncScheduler.enqueue(app)
    }
    val cloudMemorySync = CloudMemorySyncClient(cloudConfiguration, accounts, settings, vault)
    val packagePolicy = PackagePolicyRepositoryImpl(app, settings, app.packageName, cloudConfiguration)
    val assistant = BackendAssistantClient(app, cloudConfiguration, accounts, cryptoBox)
    val realtimeVoice = RealtimeVoiceClient(app, assistant)
    val reminders = AndroidReminderScheduler(app, database.reminderDao())
    val actionPolicy = com.contextbubble.app.actions.DefaultActionPolicyEngine()
    val automation = com.contextbubble.app.accessibility.FlavorAutomationActuator()
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE memories ADD COLUMN baseVersion INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE memories ADD COLUMN syncSequence INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE memories ADD COLUMN conflictState TEXT")
        database.execSQL("ALTER TABLE memories ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE memories ADD COLUMN remoteUpdatedAtEpochMs INTEGER")
        database.execSQL("ALTER TABLE memories ADD COLUMN keyVersion INTEGER NOT NULL DEFAULT 1")
        database.execSQL("ALTER TABLE memories ADD COLUMN pendingIdempotencyKey TEXT")
    }
}



val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as ContextBubbleApp).container
