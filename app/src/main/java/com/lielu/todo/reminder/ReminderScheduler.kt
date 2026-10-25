package com.lielu.todo.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Objects

/** Programme les notifications de rappel avec AlarmManager : elles sonnent même app fermée ou serveur coupé. */
class ReminderScheduler(
    context: Context,
) {
    private val context = context.applicationContext
    private val alarms = this.context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val prefs = this.context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    /** Remplace les alarmes précédentes par [planned]. */
    fun apply(planned: List<PlannedReminder>) {
        previousCodes().forEach { cancel(it) }
        val codes = mutableSetOf<String>()
        planned.forEach { reminder ->
            val code = requestCode(reminder.taskId, reminder.at.epochSecond)
            schedule(code, reminder.at.toEpochMilli(), reminder.taskId, reminder.title)
            codes += code.toString()
        }
        prefs.edit().putStringSet(KEY_CODES, codes).apply()
    }

    /** Une alarme isolée (« Reporter ») : hors de la liste recalculée, elle n'est jamais annulée par [apply]. */
    fun snooze(
        taskId: Long,
        title: String,
        atMillis: Long,
    ) = schedule(requestCode(taskId, SNOOZE_SALT), atMillis, taskId, title)

    private fun schedule(
        code: Int,
        atMillis: Long,
        taskId: Long,
        title: String,
    ) {
        val intent =
            Intent(context, ReminderReceiver::class.java)
                .setAction(ReminderReceiver.ACTION_REMIND)
                .putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
                .putExtra(ReminderReceiver.EXTRA_TITLE, title)
        val pending =
            PendingIntent.getBroadcast(
                context,
                code,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        // Exacte si le système l'autorise (permission accordée d'office sur Android 13+ pour ce type d'app),
        // sinon approximative : une minute ou deux de retard valent mieux qu'un rappel perdu.
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }
    }

    private fun cancel(code: String) {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_REMIND)
        val pending =
            PendingIntent.getBroadcast(
                context,
                code.toInt(),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: return
        alarms.cancel(pending)
        pending.cancel()
    }

    private fun previousCodes(): Set<String> = prefs.getStringSet(KEY_CODES, emptySet()).orEmpty()

    private fun requestCode(
        taskId: Long,
        salt: Long,
    ): Int = Objects.hash(taskId, salt)

    private companion object {
        const val KEY_CODES = "codes"
        const val SNOOZE_SALT = -1L
    }
}
