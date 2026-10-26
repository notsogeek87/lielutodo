package com.lielu.todo.reminder

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lielu.todo.MainActivity
import com.lielu.todo.R
import com.lielu.todo.data.model.AppNotification

/** Notifications non lues à signaler : celles dont l'id dépasse le dernier déjà signalé. */
fun newUnread(
    notifications: List<AppNotification>,
    lastNotifiedId: Long,
): List<AppNotification> = notifications.filter { !it.read && it.id > lastNotifiedId }.sortedBy { it.id }

/**
 * Affiche en notification système les nouvelles notifications Vikunja (assignation, commentaire, mention…)
 * découvertes à la synchro. Le premier passage ne fait que mémoriser l'état : sans cela, installer l'app sur un
 * compte ancien afficherait d'un coup toutes les notifications non lues depuis des mois.
 */
class CollaborationNotifier(
    context: Context,
) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("collaboration", Context.MODE_PRIVATE)

    fun onNotifications(notifications: List<AppNotification>) {
        val newest = notifications.maxOfOrNull { it.id } ?: return
        if (!prefs.contains(KEY_LAST)) {
            prefs.edit().putLong(KEY_LAST, newest).apply()
            return
        }
        val fresh = newUnread(notifications, prefs.getLong(KEY_LAST, 0))
        if (fresh.isEmpty()) return
        prefs.edit().putLong(KEY_LAST, maxOf(newest, prefs.getLong(KEY_LAST, 0))).apply()
        fresh.forEach(::show)
    }

    @SuppressLint("MissingPermission") // Vérifiée par canNotify() avant tout appel.
    private fun show(n: AppNotification) {
        if (!canNotify()) return
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("Collaboration")
                .setDescription("Assignations, commentaires et mentions")
                .build(),
        )
        val open =
            PendingIntent.getActivity(
                context,
                n.id.hashCode(),
                Intent(context, MainActivity::class.java)
                    .putExtra(ReminderReceiver.EXTRA_TASK_ID, n.taskId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        manager.notify(
            NOTIFICATION_BASE + n.id.hashCode(),
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Vikunja")
                .setContentText(n.text)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val KEY_LAST = "last_notified_id"
        const val CHANNEL_ID = "collaboration"
        const val NOTIFICATION_BASE = 20_000
    }
}
