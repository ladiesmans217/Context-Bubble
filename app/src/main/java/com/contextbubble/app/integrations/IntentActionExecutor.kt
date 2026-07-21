package com.contextbubble.app.integrations

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.contextbubble.app.actions.ActionPolicyEngine
import com.contextbubble.app.actions.ProposedAction
import com.contextbubble.app.domain.ExecutionReceipt
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

sealed interface SafeIntentAction {
    data class WhatsAppDraft(val phoneE164: String?, val message: String) : SafeIntentAction
    data class YouTubeSearch(val query: String) : SafeIntentAction
    data class SpotifySearch(val query: String) : SafeIntentAction
    data class WebSearch(val query: String) : SafeIntentAction
    data class EmailDraft(val recipient: String?, val subject: String, val body: String) : SafeIntentAction
}

class IntentActionExecutor(private val policy: ActionPolicyEngine) {
    fun preview(action: SafeIntentAction): String = when (action) {
        is SafeIntentAction.WhatsAppDraft -> "Open WhatsApp with this unsent message:\n${action.message}"
        is SafeIntentAction.YouTubeSearch -> "Open YouTube search for “${action.query}”"
        is SafeIntentAction.SpotifySearch -> "Open Spotify search for “${action.query}”"
        is SafeIntentAction.WebSearch -> "Search the web for “${action.query}”"
        is SafeIntentAction.EmailDraft -> "Open an unsent email draft to ${action.recipient ?: "a recipient"}: ${action.subject}"
    }

    fun execute(context: Context, action: SafeIntentAction, confirmed: Boolean): ExecutionReceipt {
        val proposed = ProposedAction(
            type = when (action) {
                is SafeIntentAction.WhatsAppDraft, is SafeIntentAction.EmailDraft -> "external_draft"
                else -> "open"
            },
            externalWrite = action is SafeIntentAction.WhatsAppDraft || action is SafeIntentAction.EmailDraft,
        )
        val decision = policy.evaluate(proposed)
        require(decision.allowed) { decision.reason }
        require(!decision.confirmationRequired || confirmed) { "Confirmation is required" }
        context.startActivity(toIntent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ExecutionReceipt(UUID.randomUUID().toString(), "OPENED", preview(action), System.currentTimeMillis())
    }

    private fun toIntent(action: SafeIntentAction): Intent = when (action) {
        is SafeIntentAction.WhatsAppDraft -> {
            val target = action.phoneE164?.filter(Char::isDigit).orEmpty()
            val path = if (target.isBlank()) "https://wa.me/?text=${encode(action.message)}" else "https://wa.me/$target?text=${encode(action.message)}"
            Intent(Intent.ACTION_VIEW, Uri.parse(path))
        }
        is SafeIntentAction.YouTubeSearch -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${encode(action.query)}"))
        is SafeIntentAction.SpotifySearch -> Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${encode(action.query)}"))
        is SafeIntentAction.WebSearch -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${encode(action.query)}"))
        is SafeIntentAction.EmailDraft -> Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:${action.recipient.orEmpty()}")
            putExtra(Intent.EXTRA_SUBJECT, action.subject)
            putExtra(Intent.EXTRA_TEXT, action.body)
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}

