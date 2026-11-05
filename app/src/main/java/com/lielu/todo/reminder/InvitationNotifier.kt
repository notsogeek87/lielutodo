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
import com.lielu.todo.data.joplin.ShareInvitation
import com.lielu.todo.data.settings.AppPrefs

/** Invitations à signaler : en attente et jamais signalées. Pure, testée. */
fun newInvitations(
    pending: List<ShareInvitation>,
    alreadyNotified: Set<String>,
): List<ShareInvitation> = pending.filter { it.shareUserId !in alreadyNotified }

/**
 * Notification système quand quelqu'un vous partage un carnet Joplin (découvert à la synchro de fond). Une seule
 * notification par invitation ; la toucher ouvre l'onglet Notes avec la liste des invitations à accepter ou refuser.
 */
class InvitationNotifier(
    context: Context,
    private val prefs: AppPrefs,
) {
    private val context = context.applicationContext

    fun onInvitations(pending: List<ShareInvitation>) {
        val fresh = newInvitations(pending, prefs.notifiedInvitations)
        // On ne garde que les invitations encore en attente : la liste ne grossit pas indéfiniment.
        prefs.notifiedInvitations = pending.map { it.shareUserId }.toSet()
        fresh.forEach(::show)
    }

    @SuppressLint("MissingPermission") // Vérifiée par canNotify() avant tout appel.
    private fun show(invitation: ShareInvitation) {
        if (!canNotify()) return
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("Carnets partagés")
                .setDescription("Invitations à rejoindre un carnet de notes Joplin")
                .build(),
        )
        val open =
            PendingIntent.getActivity(
                context,
                invitation.shareUserId.hashCode(),
                Intent(context, MainActivity::class.java)
                    .putExtra(EXTRA_OPEN_INVITATIONS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val who = invitation.ownerName.ifBlank { invitation.ownerEmail }.ifBlank { "Quelqu'un" }
        manager.notify(
            NOTIFICATION_BASE + invitation.shareUserId.hashCode(),
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Carnet partagé")
                .setContentText("$who vous partage un carnet de notes. Touchez pour accepter ou refuser.")
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        /** Extra de l'intent : ouvrir l'onglet Notes sur la liste des invitations. */
        const val EXTRA_OPEN_INVITATIONS = "open_note_invitations"
        private const val CHANNEL_ID = "note_invitations"
        private const val NOTIFICATION_BASE = 40_000
    }
}
