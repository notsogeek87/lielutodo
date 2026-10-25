package com.lielu.todo.reminder

import com.lielu.todo.data.model.Task
import com.lielu.todo.data.todo.QuickAdd
import java.time.Instant
import java.time.ZoneId

/** Une notification à programmer : à [at], rappeler la tâche [taskId] (titre [title]). */
data class PlannedReminder(
    val taskId: Long,
    val title: String,
    val at: Instant,
)

/**
 * Les prochaines notifications, de la plus proche à la plus lointaine. Android limite le nombre d'alarmes par
 * application : on n'en programme que [max], les suivantes le seront quand celles-ci auront sonné (la liste est
 * recalculée à chaque changement, au démarrage et à chaque synchro).
 */
fun planReminders(
    tasks: List<Task>,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    max: Int = 60,
): List<PlannedReminder> =
    tasks
        .filter { !it.done }
        .flatMap { task ->
            val dueHasTime =
                task.dueDate
                    ?.atZone(zone)
                    ?.toLocalTime()
                    ?.let { it != QuickAdd.DATE_ONLY_TIME } ?: false
            task.notificationTimes(dueHasTime).map { PlannedReminder(task.id, task.title, it) }
        }.filter { it.at.isAfter(now) }
        .distinct()
        .sortedBy { it.at }
        .take(max)
