package com.contextbubble.app.autofill

import android.app.assist.AssistStructure
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.contextbubble.app.appContainer
import com.contextbubble.app.data.QuickFillItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ContextBubbleAutofillService : AutofillService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
        val packageName = structure.activityComponent?.packageName
        if (appContainer.packagePolicy.isBlocked(packageName)) return callback.onSuccess(null)
        scope.launch {
            runCatching {
                val fields = collectFields(structure)
                if (fields.isEmpty()) return@runCatching null
                val items = appContainer.vault.quickFill.first()
                    .filter { classifyItem(it) != null }
                val response = FillResponse.Builder()
                var datasets = 0
                items.forEach { item ->
                    val kind = classifyItem(item) ?: return@forEach
                    val targets = fields.filter { it.kind == kind }
                    if (targets.isEmpty()) return@forEach
                    val presentation = presentation(item.label)
                    val dataset = Dataset.Builder(presentation)
                    targets.forEach { target -> dataset.setValue(target.node.autofillId!!, AutofillValue.forText(item.value), presentation) }
                    response.addDataset(dataset.build())
                    datasets++
                }
                if (datasets == 0) null else response.build()
            }.onSuccess(callback::onSuccess).onFailure { callback.onFailure("Quick Fill is temporarily unavailable") }
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // Context Bubble never learns values from arbitrary forms. Users add Quick Fill records explicitly in the vault.
        callback.onSuccess()
    }

    private fun collectFields(structure: AssistStructure): List<DetectedField> {
        val fields = mutableListOf<DetectedField>()
        repeat(structure.windowNodeCount) { index -> traverse(structure.getWindowNodeAt(index).rootViewNode, fields) }
        return fields
    }

    private fun traverse(node: AssistStructure.ViewNode, output: MutableList<DetectedField>) {
        if (node.autofillId != null && node.autofillType == View.AUTOFILL_TYPE_TEXT && !isSensitive(node)) {
            classifyNode(node)?.let { output += DetectedField(node, it) }
        }
        repeat(node.childCount) { traverse(node.getChildAt(it), output) }
    }

    private fun isSensitive(node: AssistStructure.ViewNode): Boolean {
        val hints = node.autofillHints.orEmpty().map(String::lowercase)
        if (hints.any { hint -> FORBIDDEN_HINT_WORDS.any(hint::contains) }) return true
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        return variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
    }

    private fun classifyNode(node: AssistStructure.ViewNode): FieldKind? {
        val evidence = buildString {
            node.autofillHints.orEmpty().forEach { append(it).append(' ') }
            append(node.idEntry.orEmpty()).append(' ')
            append(node.hint.orEmpty()).append(' ')
            append(node.text ?: "")
        }.lowercase()
        return when {
            listOf("email", "emailaddress", "e-mail").any(evidence::contains) ||
                (node.inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> FieldKind.EMAIL
            listOf("phone", "tel", "mobile").any(evidence::contains) ||
                (node.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_PHONE -> FieldKind.PHONE
            listOf("address", "street", "postaladdress").any(evidence::contains) -> FieldKind.ADDRESS
            listOf("name", "personname", "full_name", "fullname").any(evidence::contains) -> FieldKind.NAME
            else -> null
        }
    }

    private fun classifyItem(item: QuickFillItem): FieldKind? {
        val value = "${item.kind} ${item.label}".lowercase()
        return when {
            "email" in value -> FieldKind.EMAIL
            listOf("phone", "mobile", "tel").any(value::contains) -> FieldKind.PHONE
            "address" in value -> FieldKind.ADDRESS
            "name" in value -> FieldKind.NAME
            else -> null
        }
    }

    private fun presentation(label: String) = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
        setTextViewText(android.R.id.text1, label)
    }

    private data class DetectedField(val node: AssistStructure.ViewNode, val kind: FieldKind)
    private enum class FieldKind { NAME, EMAIL, PHONE, ADDRESS }

    private companion object {
        val FORBIDDEN_HINT_WORDS = setOf("password", "passcode", "pin", "otp", "onetime", "creditcard", "cardnumber", "cvv", "cvc")
    }
}
