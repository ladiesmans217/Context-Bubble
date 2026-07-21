package com.contextbubble.app.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.contextbubble.app.R
import com.contextbubble.app.appContainer
import com.contextbubble.app.data.ReminderDao
import com.contextbubble.app.data.ReminderEntity
import com.contextbubble.app.overlay.BubbleService
import com.contextbubble.app.ui.MainActivity
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

interface ReminderScheduler {
    suspend fun schedule(title: String, note: String, triggerAtEpochMs: Long, exactRequested: Boolean): ReminderEntity
    suspend fun cancel(reminder: ReminderEntity)
    suspend fun complete(id: String)
}

class AndroidReminderScheduler(
    private val context: Context,
    private val dao: ReminderDao,
) : ReminderScheduler {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override suspend fun schedule(title: String, note: String, triggerAtEpochMs: Long, exactRequested: Boolean): ReminderEntity {
        val item = ReminderEntity(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            note = note.trim(),
            triggerAtEpochMs = triggerAtEpochMs,
            exact = exactRequested && alarmManager.canScheduleExactAlarms(),
            createdAtEpochMs = System.currentTimeMillis(),
        )
        dao.upsert(item)
        val operation = pendingIntent(item.id)
        if (item.exact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.triggerAtEpochMs, operation)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.triggerAtEpochMs, operation)
        }
        return item
    }

    override suspend fun cancel(reminder: ReminderEntity) {
        alarmManager.cancel(pendingIntent(reminder.id))
        dao.delete(reminder)
    }

    override suspend fun complete(id: String) {
        dao.find(id)?.let { dao.update(it.copy(completed = true)) }
    }

    private fun pendingIntent(id: String) = PendingIntent.getBroadcast(
        context,
        id.hashCode(),
        Intent(context, ReminderReceiver::class.java).putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val reminder = context.appContainer.database.reminderDao().find(id) ?: return@launch
                val manager = context.getSystemService(NotificationManager::class.java)
                when (intent.action) {
                    ACTION_COMPLETE -> {
                        context.appContainer.reminders.complete(id)
                        manager.cancel(id.hashCode())
                        return@launch
                    }
                    ACTION_SNOOZE -> {
                        context.appContainer.reminders.cancel(reminder)
                        context.appContainer.reminders.schedule(
                            reminder.title,
                            reminder.note,
                            System.currentTimeMillis() + 10 * 60_000L,
                            reminder.exact,
                        )
                        manager.cancel(id.hashCode())
                        return@launch
                    }
                }
                manager.createNotificationChannel(
                    NotificationChannel(BubbleService.CHANNEL_REMINDERS, context.getString(R.string.notification_channel_reminders), NotificationManager.IMPORTANCE_HIGH),
                )
                val open = PendingIntent.getActivity(
                    context,
                    id.hashCode(),
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val complete = actionIntent(context, id, ACTION_COMPLETE, 1)
                val snooze = actionIntent(context, id, ACTION_SNOOZE, 2)
                manager.notify(
                    id.hashCode(),
                    NotificationCompat.Builder(context, BubbleService.CHANNEL_REMINDERS)
                        .setSmallIcon(R.drawable.ic_app)
                        .setContentTitle(reminder.title)
                        .setContentText(reminder.note.ifBlank { "Context Bubble reminder" })
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .addAction(0, "Complete", complete)
                        .addAction(0, "Snooze 10 min", snooze)
                        .build(),
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun actionIntent(context: Context, id: String, action: String, salt: Int) = PendingIntent.getBroadcast(
        context,
        id.hashCode() * 31 + salt,
        Intent(context, ReminderReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val EXTRA_ID = "reminder_id"
        private const val ACTION_COMPLETE = "com.contextbubble.reminder.COMPLETE"
        private const val ACTION_SNOOZE = "com.contextbubble.reminder.SNOOZE"
    }
}
