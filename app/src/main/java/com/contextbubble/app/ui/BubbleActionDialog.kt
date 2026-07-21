package com.contextbubble.app.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.graphics.BitmapFactory
import android.content.ClipData
import android.content.ClipboardManager
import androidx.core.content.FileProvider
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.contextbubble.app.appContainer
import com.contextbubble.app.domain.AssistMode
import com.contextbubble.app.domain.AssistRequest
import com.contextbubble.app.domain.AssistantAction
import com.contextbubble.app.domain.MemoryScope
import com.contextbubble.app.domain.RetentionPolicy
import com.contextbubble.app.data.SharedFileCleanupWorker
import com.contextbubble.app.overlay.PendingBubbleAction
import com.contextbubble.app.speech.PendingTranscript
import com.contextbubble.app.ui.theme.Slate
import java.text.DateFormat
import java.util.Date
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

@Composable
fun BubbleActionDialog(action: PendingBubbleAction, onDismiss: () -> Unit) {
    when (action.action) {
        AssistantAction.REMINDER -> ReminderComposer(action, onDismiss)
        AssistantAction.ASK, AssistantAction.REMEMBER, AssistantAction.GENERATE_IMAGE -> AssistantComposer(action, onDismiss)
        else -> onDismiss()
    }
}

@Composable
private fun AssistantComposer(action: PendingBubbleAction, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transcript = remember { PendingTranscript.value.also { PendingTranscript.value = null } }
    val defaultPrompt = transcript ?: when (action.action) {
        AssistantAction.ASK -> "What should I know about this screen?"
        AssistantAction.REMEMBER -> "Extract the important details worth remembering."
        AssistantAction.GENERATE_IMAGE -> "Create an image inspired by this context."
        else -> ""
    }
    var prompt by remember { mutableStateOf(defaultPrompt) }
    var loading by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var generatedBytes by remember { mutableStateOf<ByteArray?>(null) }

    val mode = when (action.action) {
        AssistantAction.REMEMBER -> AssistMode.REMEMBER
        AssistantAction.GENERATE_IMAGE -> AssistMode.IMAGE
        else -> AssistMode.ASK
    }
    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(when (action.action) {
            AssistantAction.REMEMBER -> "Review a memory"
            AssistantAction.GENERATE_IMAGE -> "Generate from context"
            else -> "Ask Context Bubble"
        }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                action.context?.let {
                    Text("Context from ${it.packageName}", color = Slate)
                    it.surroundingText?.take(260)?.let { preview -> Text(preview, color = Slate) }
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Your request") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (loading) CircularProgressIndicator()
                answer?.let { Text(it) }
                generatedBytes?.let { bytes ->
                    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    if (bitmap == null) {
                        Text("The generated image could not be decoded. Regenerate it or try again.", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                    } else {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Generated image",
                            modifier = Modifier.fillMaxWidth().height(240.dp),
                            contentScale = ContentScale.Crop,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { shareImage(context, bytes) }) { Text("Share") }
                            OutlinedButton(onClick = { copyImage(context, bytes) }) { Text("Copy") }
                        }
                    }
                }
                error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                if (saved) Text("Saved locally. Nothing was added without your approval.")
            }
        },
        confirmButton = {
            when {
                answer != null && action.action == AssistantAction.REMEMBER && !saved -> Button(onClick = {
                    scope.launch {
                        context.appContainer.vault.saveMemory(
                            type = "screen_fact",
                            summary = answer!!.take(120),
                            value = answer!!,
                            scope = MemoryScope.LOCAL_ONLY,
                            sourcePackage = action.context?.packageName,
                            retention = RetentionPolicy.UNTIL_DELETE,
                            pinned = true,
                        )
                        saved = true
                    }
                }) { Text("Approve and save") }
                answer != null || generatedBytes != null -> Button(onClick = onDismiss) { Text("Done") }
                else -> Button(enabled = prompt.isNotBlank() && !loading, onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        if (action.action == AssistantAction.GENERATE_IMAGE) {
                            val contextualPrompt = buildString {
                                append(prompt)
                                action.context?.surroundingText?.take(800)?.let { append("\nContext: ").append(it) }
                            }
                            context.appContainer.assistant.generateImage(contextualPrompt)
                                .onSuccess { image ->
                                    generatedBytes = image.bytes
                                    val retention = context.appContainer.settings.settings.first().retention
                                    context.appContainer.vault.saveGeneratedImage(image.bytes, action.context?.packageName, retention)
                                    saved = true
                                }
                                .onFailure { error = it.message ?: "Image generation is unavailable" }
                        } else {
                            context.appContainer.assistant.assist(
                                AssistRequest(prompt = prompt, screen = action.context, mode = mode),
                            ).onSuccess { response -> answer = response.text }
                                .onFailure { error = it.message ?: "The assistant is unavailable" }
                        }
                        loading = false
                    }
                }) { Text(if (action.action == AssistantAction.GENERATE_IMAGE) "Generate" else "Ask") }
            }
        },
        dismissButton = { OutlinedButton(enabled = !loading, onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun shareImage(context: Context, bytes: ByteArray) {
    val file = sharedImageFile(context, bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share generated image",
        ),
    )
}

private fun copyImage(context: Context, bytes: ByteArray) {
    val file = sharedImageFile(context, bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(context.contentResolver, "Generated image", uri))
}

private fun sharedImageFile(context: Context, bytes: ByteArray): File {
    val directory = File(context.cacheDir, "shared").apply { mkdirs() }
    val expiry = System.currentTimeMillis() - 10 * 60_000L
    directory.listFiles()?.filter { it.lastModified() < expiry }?.forEach(File::delete)
    val file = File(directory, "generated-${System.currentTimeMillis()}.png").apply { writeBytes(bytes) }
    WorkManager.getInstance(context).enqueue(
        OneTimeWorkRequestBuilder<SharedFileCleanupWorker>()
            .setInitialDelay(10, TimeUnit.MINUTES)
            .setInputData(workDataOf(SharedFileCleanupWorker.KEY_PATH to file.absolutePath))
            .build(),
    )
    return file
}

@Composable
private fun ReminderComposer(action: PendingBubbleAction, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("Follow up") }
    var note by remember { mutableStateOf(action.context?.surroundingText?.take(280).orEmpty()) }
    var delayMinutes by remember { mutableStateOf(60) }
    var exact by remember { mutableStateOf(true) }
    var createdMessage by remember { mutableStateOf<String?>(null) }
    val baseTime = remember { System.currentTimeMillis() }
    val triggerAt = baseTime + delayMinutes * 60_000L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("Private note") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Text("When")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(10 to "10 min", 60 to "1 hour", 1440 to "Tomorrow").forEach { (minutes, label) ->
                        FilterChip(selected = delayMinutes == minutes, onClick = { delayMinutes = minutes }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = exact, onClick = { exact = true }, label = { Text("Exact") })
                    FilterChip(selected = !exact, onClick = { exact = false }, label = { Text("Approximate") })
                }
                Text("Preview: ${DateFormat.getDateTimeInstance().format(Date(triggerAt))}", color = Slate)
                createdMessage?.let { Text(it) }
            }
        },
        confirmButton = {
            if (createdMessage == null) Button(enabled = title.isNotBlank(), onClick = {
                if (exact && !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                        )
                    }
                }
                scope.launch {
                    val item = context.appContainer.reminders.schedule(title, note, triggerAt, exact)
                    createdMessage = if (item.exact) "Exact reminder created" else "Reminder created; Android may deliver it approximately"
                }
            }) { Text("Create reminder") }
            else Button(onClick = onDismiss) { Text("Done") }
        },
        dismissButton = { if (createdMessage == null) OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
