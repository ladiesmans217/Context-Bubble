package com.contextbubble.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey val id: String,
    val type: String,
    val encryptedSummary: String,
    val encryptedValue: String,
    val sourcePackage: String?,
    val scope: String,
    val sensitivity: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
    val pinned: Boolean,
    val syncState: String,
    val baseVersion: Int = 0,
    val syncSequence: Long = 0,
    val conflictState: String? = null,
    val deleted: Boolean = false,
    val remoteUpdatedAtEpochMs: Long? = null,
    val keyVersion: Int = 1,
    val pendingIdempotencyKey: String? = null,
)

@Entity(tableName = "quick_fill")
data class QuickFillEntity(
    @PrimaryKey val id: String,
    val label: String,
    val kind: String,
    val encryptedValue: String,
    val allowAi: Boolean,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "captures")
data class CaptureEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val encryptedPath: String,
    val sourcePackage: String?,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
    val pinned: Boolean,
    val state: String,
    val sizeBytes: Long,
)

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey val id: String,
    val title: String,
    val note: String,
    val triggerAtEpochMs: Long,
    val exact: Boolean,
    val completed: Boolean = false,
    val createdAtEpochMs: Long,
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories WHERE deleted = 0 ORDER BY pinned DESC, createdAtEpochMs DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    suspend fun find(id: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE scope = 'SHARED_AI' AND syncState IN ('PENDING', 'DELETE_PENDING') ORDER BY createdAtEpochMs LIMIT :limit")
    suspend fun pendingShared(limit: Int = 50): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MemoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MemoryEntity>)

    @Query("UPDATE memories SET syncState = 'SYNCED', baseVersion = :version, syncSequence = :sequence, conflictState = NULL, remoteUpdatedAtEpochMs = :remoteUpdatedAt, pendingIdempotencyKey = NULL WHERE id = :id")
    suspend fun markSynced(id: String, version: Int, sequence: Long, remoteUpdatedAt: Long)

    @Query("UPDATE memories SET syncState = 'CONFLICT', conflictState = :conflict WHERE id = :id")
    suspend fun markConflict(id: String, conflict: String)

    @Query("UPDATE memories SET deleted = 1, syncState = :syncState, pendingIdempotencyKey = :idempotencyKey WHERE id = :id")
    suspend fun markDeleted(id: String, syncState: String, idempotencyKey: String?)

    @Delete suspend fun delete(item: MemoryEntity)

    @Query("DELETE FROM memories WHERE expiresAtEpochMs IS NOT NULL AND expiresAtEpochMs < :now AND pinned = 0")
    suspend fun deleteExpired(now: Long): Int
}

@Dao
interface QuickFillDao {
    @Query("SELECT * FROM quick_fill ORDER BY kind, label")
    fun observeAll(): Flow<List<QuickFillEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: QuickFillEntity)

    @Delete suspend fun delete(item: QuickFillEntity)
}

@Dao
interface CaptureDao {
    @Query("SELECT * FROM captures ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<CaptureEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: CaptureEntity)

    @Update suspend fun update(item: CaptureEntity)

    @Query("SELECT * FROM captures WHERE state = 'PENDING_TRANSCRIPTION' ORDER BY createdAtEpochMs LIMIT :limit")
    suspend fun pendingTranscriptions(limit: Int = 3): List<CaptureEntity>

    @Query("SELECT * FROM captures WHERE id = :id LIMIT 1")
    suspend fun find(id: String): CaptureEntity?

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM captures")
    fun observeTotalBytes(): Flow<Long>

    @Query("SELECT * FROM captures WHERE expiresAtEpochMs IS NOT NULL AND expiresAtEpochMs < :now AND pinned = 0")
    suspend fun expired(now: Long): List<CaptureEntity>

    @Delete suspend fun delete(item: CaptureEntity)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE completed = 0 ORDER BY triggerAtEpochMs")
    fun observeUpcoming(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE id = :id LIMIT 1")
    suspend fun find(id: String): ReminderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ReminderEntity)

    @Update suspend fun update(item: ReminderEntity)

    @Delete suspend fun delete(item: ReminderEntity)
}

@Database(
    entities = [MemoryEntity::class, QuickFillEntity::class, CaptureEntity::class, ReminderEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun quickFillDao(): QuickFillDao
    abstract fun captureDao(): CaptureDao
    abstract fun reminderDao(): ReminderDao
}
