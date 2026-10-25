package com.lielu.todo.widget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.lielu.todo.LieluApplication
import com.lielu.todo.MainActivity
import com.lielu.todo.reminder.ReminderReceiver

/**
 * Cible des clics sur les lignes du widget. Sans interface : coche la tâche ou ouvre l'app sur elle, puis se
 * ferme. Une activité plutôt qu'un récepteur, car Android interdit de lancer une activité depuis un récepteur
 * déclenché en arrière-plan.
 */
class WidgetActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0)
        val container = (application as LieluApplication).container
        if (taskId != 0L) {
            if (intent.getBooleanExtra(EXTRA_COMPLETE, false)) {
                container.todoRepository.setDone(taskId, true)
                container.todoRepository.saveNow()
            } else {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_COMPLETE = "complete"
    }
}
