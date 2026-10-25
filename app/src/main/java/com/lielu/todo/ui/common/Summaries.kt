package com.lielu.todo.ui.common

import com.lielu.todo.data.model.Reminder
import com.lielu.todo.data.model.Task
import com.lielu.todo.data.todo.TodoState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val DAY = 86_400L
private const val WEEK = 7 * DAY
private const val MINUTE = 60L
private const val HOUR = 3_600L

/** État de synchro sous le titre : « Hors ligne · dernière synchro il y a 5 min · 2 en attente », ou `null`. */
fun syncSummary(
    state: TodoState,
    nowMillis: Long,
): String? {
    val parts = mutableListOf<String>()
    if (state.offline) {
        parts += "Hors ligne"
        if (state.lastSync > 0) parts += "synchro ${ago(nowMillis - state.lastSync)}"
    }
    if (state.pending > 0) parts += "${state.pending} en attente"
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

fun ago(millis: Long): String {
    val minutes = millis / 60_000
    return when {
        minutes < 1 -> "à l'instant"
        minutes < 60 -> "il y a $minutes min"
        minutes < 24 * 60 -> "il y a ${minutes / 60} h"
        else -> "il y a ${minutes / (24 * 60)} j"
    }
}

fun repeatLabel(task: Task): String? =
    when {
        task.repeatMode == Task.REPEAT_MONTHLY -> "Tous les mois"
        task.repeatAfter <= 0 -> null
        else -> {
            val base =
                when {
                    task.repeatAfter % WEEK == 0L -> every(task.repeatAfter / WEEK, "semaine", "semaines", "Toutes les")
                    task.repeatAfter % DAY == 0L -> every(task.repeatAfter / DAY, "jour", "jours", "Tous les")
                    task.repeatAfter % HOUR == 0L -> "Toutes les ${task.repeatAfter / HOUR} h"
                    else -> "Toutes les ${task.repeatAfter / MINUTE} min"
                }
            if (task.repeatMode == Task.REPEAT_FROM_COMPLETION) "$base, après complétion" else base
        }
    }

private fun every(
    n: Long,
    one: String,
    many: String,
    plural: String,
): String =
    when (n) {
        1L -> if (one == "jour") "Tous les jours" else "Toutes les semaines"
        else -> "$plural $n $many"
    }

fun reminderLabel(
    reminder: Reminder,
    due: Instant?,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (reminder.relativeTo == Reminder.RELATIVE_TO_DUE) {
        val before = -reminder.relativePeriod
        return when {
            before == 0L -> "À l'échéance"
            before > 0 && before % DAY == 0L -> "${before / DAY} j avant"
            before > 0 && before % HOUR == 0L -> "${before / HOUR} h avant"
            before > 0 -> "${before / MINUTE} min avant"
            else -> "${-before / MINUTE} min après"
        }
    }
    val at = reminder.effective(due) ?: return "Rappel"
    return dueLabel(at, today, zone).let {
        if (at.timeLabel(zone) ==
            null
        ) {
            "$it ${at.atZone(zone).toLocalTime()}"
        } else {
            it
        }
    }
}
