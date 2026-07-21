package com.contextbubble.app.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class MemoryScope { EPHEMERAL, LOCAL_ONLY, SHARED_AI }

enum class RetentionPolicy(val days: Int?) {
    ONE_DAY(1),
    SEVEN_DAYS(7),
    THIRTY_DAYS(30),
    SIXTY_DAYS(60),
    NINETY_DAYS(90),
    UNTIL_DELETE(null),
}

enum class RiskLevel { READ_ONLY, LOW, CONSEQUENTIAL, BLOCKED }

enum class AssistantAction {
    ASK, REMEMBER, GENERATE_IMAGE, QUICK_FILL, REMINDER, RECENT, PAUSE,
}

@Serializable
data class ScreenContext(
    val packageName: String,
    val windowId: Int,
    val focusedText: String? = null,
    val surroundingText: String? = null,
    val editable: Boolean = false,
    val sensitive: Boolean = false,
    val capturedAtEpochMs: Long,
    /** User-initiated, short-lived visual context. Never persisted by this type. */
    val screenshotBase64: String? = null,
)

data class FocusedTargetToken(
    val packageName: String,
    val windowId: Int,
    val viewId: String?,
    val className: String?,
    val selectionStart: Int,
    val selectionEnd: Int,
    val textFingerprint: Int,
)

@Serializable
data class AssistRequest(
    val prompt: String,
    val screen: ScreenContext? = null,
    val relevantMemories: List<String> = emptyList(),
    val mode: AssistMode = AssistMode.ASK,
)

@Serializable
enum class AssistMode { ASK, REMEMBER, REMINDER, IMAGE }

@Serializable
data class AssistResponse(
    val requestId: String,
    val text: String,
    val memoryCandidates: List<MemoryCandidate> = emptyList(),
    val actionPlan: ActionPlan? = null,
)

@Serializable
data class MemoryCandidate(
    val id: String,
    val type: String,
    val summary: String,
    val value: String,
    val sensitivity: String = "normal",
)

@Serializable
data class ActionPlan(
    val id: String,
    val summary: String,
    val risk: RiskLevel,
    val steps: List<ActionStep>,
)

@Serializable
data class ActionStep(
    val type: String,
    val label: String,
    val payload: Map<String, String> = emptyMap(),
)

@Serializable
data class ExecutionReceipt(
    val idempotencyKey: String,
    val status: String,
    val detail: String,
    val completedAtEpochMs: Long,
)
