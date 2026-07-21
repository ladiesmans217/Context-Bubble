package com.contextbubble.app.data

import com.contextbubble.app.domain.MemoryScope
import com.contextbubble.app.domain.RetentionPolicy
import com.contextbubble.app.cloud.CloudMemoryRecord
import com.contextbubble.app.cloud.MemoryConflict
import com.contextbubble.app.cloud.MemoryMutation
import com.contextbubble.app.cloud.MemorySyncResponse
import com.contextbubble.app.cloud.CloudMemoryPayload
import java.util.UUID
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class VaultMemory(
    val id: String,
    val type: String,
    val summary: String,
    val value: String,
    val scope: MemoryScope,
    val sourcePackage: String?,
    val createdAtEpochMs: Long,
    val pinned: Boolean,
    val syncState: String,
    val baseVersion: Int,
    val hasConflict: Boolean,
)

data class QuickFillItem(
    val id: String,
    val label: String,
    val kind: String,
    val value: String,
    val allowAi: Boolean,
)

data class LocalCapture(
    val id: String,
    val kind: String,
    val sourcePackage: String?,
    val createdAtEpochMs: Long,
    val state: String,
    val sizeBytes: Long,
)

class LocalVaultRepository(
    private val database: AppDatabase,
    private val crypto: CryptoBox,
    private val rootDir: File,
    private val onSharedMutation: () -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    val memories: Flow<List<VaultMemory>> = database.memoryDao().observeAll().map { rows ->
        rows.mapNotNull { row ->
            runCatching {
                VaultMemory(
                    id = row.id,
                    type = row.type,
                    summary = crypto.decryptString(row.encryptedSummary),
                    value = crypto.decryptString(row.encryptedValue),
                    scope = MemoryScope.valueOf(row.scope),
                    sourcePackage = row.sourcePackage,
                    createdAtEpochMs = row.createdAtEpochMs,
                    pinned = row.pinned,
                    syncState = row.syncState,
                    baseVersion = row.baseVersion,
                    hasConflict = row.conflictState != null,
                )
            }.getOrNull()
        }
    }

    val quickFill: Flow<List<QuickFillItem>> = database.quickFillDao().observeAll().map { rows ->
        rows.mapNotNull { row ->
            runCatching {
                QuickFillItem(row.id, row.label, row.kind, crypto.decryptString(row.encryptedValue), row.allowAi)
            }.getOrNull()
        }
    }

    val captures: Flow<List<LocalCapture>> = database.captureDao().observeAll().map { rows ->
        rows.map { LocalCapture(it.id, it.kind, it.sourcePackage, it.createdAtEpochMs, it.state, it.sizeBytes) }
    }

    val captureStorageBytes: Flow<Long> = database.captureDao().observeTotalBytes()

    suspend fun saveMemory(
        type: String,
        summary: String,
        value: String,
        scope: MemoryScope,
        sourcePackage: String?,
        retention: RetentionPolicy,
        pinned: Boolean = false,
    ): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        database.memoryDao().upsert(
            MemoryEntity(
                id = id,
                type = type,
                encryptedSummary = crypto.encryptString(summary),
                encryptedValue = crypto.encryptString(value),
                sourcePackage = sourcePackage,
                scope = scope.name,
                sensitivity = "normal",
                createdAtEpochMs = now,
                expiresAtEpochMs = retention.days?.let { now + TimeUnit.DAYS.toMillis(it.toLong()) },
                pinned = pinned,
                syncState = if (scope == MemoryScope.SHARED_AI) "PENDING" else "LOCAL",
                pendingIdempotencyKey = if (scope == MemoryScope.SHARED_AI) UUID.randomUUID().toString() else null,
            ),
        )
        if (scope == MemoryScope.SHARED_AI) onSharedMutation()
        return id
    }

    suspend fun deleteMemory(item: VaultMemory) {
        val row = database.memoryDao().find(item.id) ?: return
        if (row.scope == MemoryScope.SHARED_AI.name && row.baseVersion > 0) {
            database.memoryDao().markDeleted(row.id, "DELETE_PENDING", UUID.randomUUID().toString())
            onSharedMutation()
        } else {
            database.memoryDao().delete(row)
        }
    }

    suspend fun pendingSharedMutations(limit: Int = 50): List<MemoryMutation> =
        database.memoryDao().pendingShared(limit).mapNotNull { row ->
            val key = row.pendingIdempotencyKey ?: return@mapNotNull null
            if (row.syncState == "DELETE_PENDING") {
                return@mapNotNull MemoryMutation(
                    operation = "DELETE",
                    id = row.id,
                    baseVersion = row.baseVersion,
                    payload = null,
                    idempotencyKey = key,
                )
            }
            runCatching {
                MemoryMutation(
                    operation = "UPSERT",
                    id = row.id,
                    baseVersion = row.baseVersion,
                    payload = CloudMemoryPayload(
                        id = row.id,
                        type = row.type,
                        summary = crypto.decryptString(row.encryptedSummary),
                        value = crypto.decryptString(row.encryptedValue),
                        sourcePackage = row.sourcePackage,
                        sensitivity = row.sensitivity,
                        createdAtEpochMs = row.createdAtEpochMs,
                        expiresAtEpochMs = row.expiresAtEpochMs,
                        pinned = row.pinned,
                    ),
                    idempotencyKey = key,
                )
            }.getOrNull()
        }

    suspend fun applyCloudSync(response: MemorySyncResponse) {
        val conflictIds = response.conflicts.mapTo(mutableSetOf()) { it.id }
        response.conflicts.forEach { conflict ->
            database.memoryDao().markConflict(conflict.id, crypto.encryptString(json.encodeToString(conflict)))
        }
        response.changes.filterNot { it.id in conflictIds }.forEach { record ->
            applyCloudRecord(record)
        }
    }

    suspend fun resolveConflictKeepCloud(id: String) {
        val row = database.memoryDao().find(id) ?: return
        val conflict = row.conflictState?.let { encrypted ->
            runCatching { json.decodeFromString<MemoryConflict>(crypto.decryptString(encrypted)) }.getOrNull()
        } ?: return
        applyCloudRecord(conflict.cloud)
    }

    suspend fun loadConflict(id: String): MemoryConflict? {
        val row = database.memoryDao().find(id) ?: return null
        return row.conflictState?.let { encrypted ->
            runCatching { json.decodeFromString<MemoryConflict>(crypto.decryptString(encrypted)) }.getOrNull()
        }
    }

    suspend fun resolveConflictKeepPhone(id: String) {
        val row = database.memoryDao().find(id) ?: return
        val conflict = row.conflictState?.let { encrypted ->
            runCatching { json.decodeFromString<MemoryConflict>(crypto.decryptString(encrypted)) }.getOrNull()
        } ?: return
        database.memoryDao().upsert(
            row.copy(
                baseVersion = conflict.cloud.version,
                syncSequence = conflict.cloud.syncSequence,
                syncState = "PENDING",
                conflictState = null,
                deleted = false,
                pendingIdempotencyKey = UUID.randomUUID().toString(),
            ),
        )
        onSharedMutation()
    }

    suspend fun resolveConflictKeepBoth(id: String) {
        val row = database.memoryDao().find(id) ?: return
        val conflict = row.conflictState?.let { encrypted ->
            runCatching { json.decodeFromString<MemoryConflict>(crypto.decryptString(encrypted)) }.getOrNull()
        } ?: return
        val duplicate = row.copy(
            id = UUID.randomUUID().toString(),
            baseVersion = 0,
            syncSequence = 0,
            syncState = "PENDING",
            conflictState = null,
            deleted = false,
            remoteUpdatedAtEpochMs = null,
            pendingIdempotencyKey = UUID.randomUUID().toString(),
        )
        database.memoryDao().upsert(duplicate)
        applyCloudRecord(conflict.cloud)
        onSharedMutation()
    }

    private suspend fun applyCloudRecord(record: CloudMemoryRecord) {
        val existing = database.memoryDao().find(record.id)
        if (record.deleted) {
            if (existing != null) database.memoryDao().markDeleted(record.id, "SYNCED", null)
            return
        }
        database.memoryDao().upsert(
            MemoryEntity(
                id = record.id,
                type = record.type,
                encryptedSummary = crypto.encryptString(record.summary),
                encryptedValue = crypto.encryptString(record.value),
                sourcePackage = record.sourcePackage,
                scope = MemoryScope.SHARED_AI.name,
                sensitivity = record.sensitivity,
                createdAtEpochMs = record.createdAtEpochMs,
                expiresAtEpochMs = record.expiresAtEpochMs,
                pinned = record.pinned,
                syncState = "SYNCED",
                baseVersion = record.version,
                syncSequence = record.syncSequence,
                conflictState = null,
                deleted = false,
                remoteUpdatedAtEpochMs = record.remoteUpdatedAtEpochMs,
                keyVersion = existing?.keyVersion ?: 1,
                pendingIdempotencyKey = null,
            ),
        )
    }

    suspend fun saveQuickFill(label: String, kind: String, value: String, allowAi: Boolean) {
        database.quickFillDao().upsert(
            QuickFillEntity(
                id = UUID.randomUUID().toString(),
                label = label.trim(),
                kind = kind,
                encryptedValue = crypto.encryptString(value),
                allowAi = allowAi,
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteQuickFill(item: QuickFillItem) {
        database.quickFillDao().delete(
            QuickFillEntity(item.id, item.label, item.kind, crypto.encryptString(item.value), item.allowAi, 0),
        )
    }

    suspend fun savePendingCapture(
        id: String,
        kind: String,
        encryptedPath: String,
        sourcePackage: String?,
        retention: RetentionPolicy,
        sizeBytes: Long,
        state: String,
    ) {
        val now = System.currentTimeMillis()
        database.captureDao().upsert(
            CaptureEntity(
                id = id,
                kind = kind,
                encryptedPath = encryptedPath,
                sourcePackage = sourcePackage,
                createdAtEpochMs = now,
                expiresAtEpochMs = retention.days?.let { now + TimeUnit.DAYS.toMillis(it.toLong()) },
                pinned = false,
                state = state,
                sizeBytes = sizeBytes,
            ),
        )
    }

    suspend fun saveGeneratedImage(bytes: ByteArray, sourcePackage: String?, retention: RetentionPolicy): String {
        val id = UUID.randomUUID().toString()
        val file = File(rootDir, "captures/$id.png.enc")
        crypto.openEncryptedOutput(file).use { it.write(bytes) }
        savePendingCapture(
            id = id,
            kind = "GENERATED_IMAGE_PNG",
            encryptedPath = file.absolutePath,
            sourcePackage = sourcePackage,
            retention = retention,
            sizeBytes = file.length(),
            state = "LOCAL",
        )
        return id
    }

    suspend fun savePendingAudio(bytes: ByteArray, sourcePackage: String?, retention: RetentionPolicy): String {
        val id = UUID.randomUUID().toString()
        val file = File(rootDir, "captures/$id.pcm.enc")
        crypto.openEncryptedOutput(file).use { it.write(bytes) }
        savePendingCapture(
            id = id,
            kind = "AUDIO_PCM_24K",
            encryptedPath = file.absolutePath,
            sourcePackage = sourcePackage,
            retention = retention,
            sizeBytes = file.length(),
            state = "PENDING_TRANSCRIPTION",
        )
        return id
    }

    suspend fun loadCaptureBytes(id: String): ByteArray? {
        val row = database.captureDao().find(id) ?: return null
        val file = File(row.encryptedPath)
        if (!file.exists()) return null
        return crypto.decryptFile(file)
    }

    suspend fun approvedAssistantContext(limit: Int = 12): List<String> = memories.first()
        .asSequence()
        .filter { it.scope == MemoryScope.SHARED_AI }
        .take(limit)
        .map { "${it.summary}: ${it.value}".take(500) }
        .toList()
}
