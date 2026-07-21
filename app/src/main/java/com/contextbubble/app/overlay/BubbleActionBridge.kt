package com.contextbubble.app.overlay

import com.contextbubble.app.domain.AssistantAction
import com.contextbubble.app.domain.ScreenContext
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PendingBubbleAction(
    val id: String = UUID.randomUUID().toString(),
    val action: AssistantAction,
    val context: ScreenContext?,
)

object BubbleActionBridge {
    private val mutablePending = MutableStateFlow<PendingBubbleAction?>(null)
    val pending = mutablePending.asStateFlow()

    fun submit(action: AssistantAction, context: ScreenContext?) {
        mutablePending.value = PendingBubbleAction(action = action, context = context)
    }

    fun consume(id: String) {
        if (mutablePending.value?.id == id) mutablePending.value = null
    }
}

