package com.lielu.todo.reminder

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lielu.todo.LieluApplication
import com.lielu.todo.MainActivity
import com.lielu.todo.R
import kotlinx.coroutines.launch

/** Affiche la notification d'un rappel et traite ses boutons « Terminer » et « Reporter ». */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val container = (context.applicationContext as LieluApplication).container
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0)
        when (intent.action) {
            ACTION_DAILY -> {
                showDaily(context, container.todoRepository.state.value.tasks)
                container.reminderScheduler.scheduleDaily()
            }
            ACTION_REMIND ->
                show(
                    context,
                    container.todoRepository.task(taskId),
                    taskId,
                    intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                )
            ACTION_COMPLETE -> {
                NotificationManagerCompat.from(context).cancel(notificationId(taskId))
                val pending = goAsync()
                container.applicationScope.launch {
                    try {
                        container.todoRepository.setDone(taskId, true)
                        container.todoRepository.saveNow()
                        container.todoRepository.syncPending()
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_SNOOZE -> {
                NotificationManagerCompat.from(context).cancel(notificationId(taskId))
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                container.reminderScheduler.snooze(taskId, title, System.currentTimeMillis() + SNOOZE_MS)
            }
        }
    }

    @SuppressLint("MissingPermission") // Vérifiée juste avant : voir canNotify.
    private fun showDaily(
        context: Context,
        tasks: List<com.lielu.todo.data.model.Task>,
    ) {
        val today = java.time.LocalDate.now()
        val due =
            tasks.count { t ->
                !t.done &&
                    t.dueDate != null &&
                    !t.dueDate
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                        .isAfter(today)
            }
        if (due == 0 || !canNotify(context)) return
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Rappels")
                .build(),
        )
        val open =
            PendingIntent.getActivity(
                context,
                DAILY_NOTIFICATION_ID,
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Aujourd'hui")
                .setContentText(if (due == 1) "1 tâche à faire" else "$due tâches à faire")
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
        manager.notify(DAILY_NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission") // Vérifiée juste avant : voir canNotify.
    private fun show(
        context: Context,
        task: com.lielu.todo.data.model.Task?,
        taskId: Long,
        fallbackTitle: String,
    ) {
        // Terminée ou supprimée entre-temps (sur un autre appareil, par exemple) : plus rien à rappeler.
        if (task?.done == true) return
        if (!canNotify(context)) return
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Rappels")
                .setDescription("Rappels de tâches")
                .build(),
        )
        val title = task?.title ?: fallbackTitle
        val open =
            PendingIntent.getActivity(
                context,
                notificationId(taskId),
                Intent(context, MainActivity::class.java)
                    .putExtra(EXTRA_TASK_ID, taskId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText("Rappel")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(open)
                .addAction(0, "Terminer", action(context, ACTION_COMPLETE, taskId, title))
                .addAction(0, "Reporter 1 h", action(context, ACTION_SNOOZE, taskId, title))
                .build()
        manager.notify(notificationId(taskId), notification)
    }

    private fun action(
        context: Context,
        action: String,
        taskId: Long,
        title: String,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            notificationId(taskId) * 31 + action.hashCode(),
            Intent(context, ReminderReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_TITLE, title),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun notificationId(taskId: Long): Int = taskId.hashCode()

    companion object {
        const val ACTION_REMIND = "com.lielu.todo.REMIND"
        const val ACTION_DAILY = "com.lielu.todo.DAILY"
        private const val DAILY_NOTIFICATION_ID = 7_009
        const val ACTION_COMPLETE = "com.lielu.todo.COMPLETE"
        const val ACTION_SNOOZE = "com.lielu.todo.SNOOZE"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TITLE = "title"
        private const val CHANNEL_ID = "reminders"
        private const val SNOOZE_MS = 60 * 60 * 1000L
    }
}

/**
 * Reçoit le démarrage du téléphone et la mise à jour de l'app : il suffit que l'app démarre pour que
 * [com.lielu.todo.AppContainer.start] reprogramme les alarmes (Android les efface au redémarrage).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        (context.applicationContext as LieluApplication).container
    }
}
