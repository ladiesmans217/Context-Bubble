package com.contextbubble.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.contextbubble.app.MIGRATION_1_2
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudMemoryMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun v1ToV2PreservesMemoryAndAddsSafeSyncDefaults() {
        helper.createDatabase(TEST_DATABASE, 1).apply {
            execSQL(
                """INSERT INTO memories(
                    id,type,encryptedSummary,encryptedValue,sourcePackage,scope,sensitivity,
                    createdAtEpochMs,expiresAtEpochMs,pinned,syncState
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
                arrayOf<Any?>("memory-1", "fact", "enc-summary", "enc-value", "com.example", "LOCAL_ONLY", "normal", 123L, null, 0, "SYNCED"),
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 2, true, MIGRATION_1_2).use { database ->
            database.query(
                "SELECT id, encryptedValue, baseVersion, syncSequence, deleted, keyVersion, pendingIdempotencyKey FROM memories WHERE id = ?",
                arrayOf("memory-1"),
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("memory-1", cursor.getString(0))
                assertEquals("enc-value", cursor.getString(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals(0, cursor.getInt(3))
                assertEquals(0, cursor.getInt(4))
                assertEquals(1, cursor.getInt(5))
                assertEquals(true, cursor.isNull(6))
            }
        }
    }

    private companion object { const val TEST_DATABASE = "cloud-memory-migration-test" }
}
