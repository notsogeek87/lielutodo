package com.lielu.todo.calendar

import com.lielu.todo.data.model.Task
import com.lielu.todo.data.todo.QuickAdd
import java.time.ZoneId
import java.time.ZoneOffset

/** Un événement d'agenda à tenir à jour pour la tâche [taskId]. */
data class PlannedEvent(
    val taskId: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    /** Tâche sans heure (stockée à midi, voir [QuickAdd.DATE_ONLY_TIME]) : événement « toute la journée ». */
    val allDay: Boolean,
) {
    /** Change dès qu'un champ visible dans l'agenda change : c'est ce qui déclenche la mise à jour. */
    val fingerprint: String get() = "$title|$startMillis|$endMillis|$allDay"
}

/** Événement déjà créé dans l'agenda pour une tâche, avec l'empreinte de ce qui y a été écrit. */
data class SyncedEvent(
    val eventId: Long,
    val fingerprint: String,
)

sealed interface CalendarAction {
    data class Insert(
        val event: PlannedEvent,
    ) : CalendarAction

    data class Update(
        val eventId: Long,
        val event: PlannedEvent,
    ) : CalendarAction

    data class Delete(
        val taskId: Long,
        val eventId: Long,
    ) : CalendarAction
}

/** Un événement par tâche non terminée qui a une échéance. */
fun planCalendarEvents(
    tasks: List<Task>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<PlannedEvent> =
    tasks.filter { !it.done && it.dueDate != null }.map { task ->
        val due = task.dueDate!!
        val local = due.atZone(zone)
        if (local.toLocalTime() == QuickAdd.DATE_ONLY_TIME) {
            // Un événement « toute la journée » se note en UTC, de minuit à minuit.
            val start =
                local
                    .toLocalDate()
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            PlannedEvent(task.id, task.title, start, start + DAY_MS, allDay = true)
        } else {
            PlannedEvent(task.id, task.title, due.toEpochMilli(), due.toEpochMilli() + EVENT_MS, allDay = false)
        }
    }

/**
 * Ce qu'il faut faire pour que l'agenda reflète [planned], sachant ce qu'on y a déjà écrit ([synced], par tâche).
 * Fonction pure : c'est la même logique que la date change ici, sur le web ou après une synchro.
 */
fun reconcileEvents(
    planned: List<PlannedEvent>,
    synced: Map<Long, SyncedEvent>,
): List<CalendarAction> {
    val wanted = planned.associateBy { it.taskId }
    val deletes = synced.filterKeys { it !in wanted }.map { (taskId, s) -> CalendarAction.Delete(taskId, s.eventId) }
    val writes =
        planned.mapNotNull { event ->
            val existing = synced[event.taskId]
            when {
                existing == null -> CalendarAction.Insert(event)
                existing.fingerprint != event.fingerprint -> CalendarAction.Update(existing.eventId, event)
                else -> null
            }
        }
    return deletes + writes
}

private const val DAY_MS = 24 * 60 * 60 * 1000L
private const val EVENT_MS = 30 * 60 * 1000L
