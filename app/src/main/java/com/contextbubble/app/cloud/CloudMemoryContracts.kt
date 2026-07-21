package com.contextbubble.app.cloud

import kotlinx.serialization.Serializable

@Serializable
data class CloudMemoryPayload(
    val id: String,
    val type: String,
    val summary: String,
    val value: String,
    val sourcePackage: String? = null,
    val sensitivity: String = "normal",
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long? = null,
    val pinned: Boolean,
)

@Serializable
data class MemoryMutation(
    val operation: String,
    val id: String,
    val baseVersion: Int,
    val payload: CloudMemoryPayload? = null,
    val idempotencyKey: String,
)

@Serializable
data class MemorySyncRequest(
    val cursor: Long,
    val mutations: List<MemoryMutation>,
)

@Serializable
data class CloudMemoryRecord(
    val id: String,
    val type: String,
    val summary: String,
    val value: String,
    val sourcePackage: String? = null,
    val sensitivity: String = "normal",
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long? = null,
    val pinned: Boolean,
    val version: Int,
    val syncSequence: Long,
    val deleted: Boolean,
    val remoteUpdatedAtEpochMs: Long,
    val embeddingModel: String? = null,
)

@Serializable
data class MemoryConflict(
    val id: String,
    val localBaseVersion: Int,
    val cloud: CloudMemoryRecord,
)

@Serializable
data class MemorySyncResponse(
    val requestId: String,
    val nextCursor: Long,
    val changes: List<CloudMemoryRecord>,
    val conflicts: List<MemoryConflict>,
)
