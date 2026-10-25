package com.lielu.todo.widget

import com.lielu.todo.data.model.Task
import com.lielu.todo.ui.common.localDate
import java.time.LocalDate

/** Tâches du widget « Aujourd'hui » : en retard d'abord, puis du jour, par heure et priorité. */
fun widgetTasks(
    tasks: List<Task>,
    today: LocalDate,
): List<Task> =
    tasks
        .filter { !it.done && it.dueDate != null && !it.dueDate.localDate().isAfter(today) }
        .sortedWith(compareBy<Task> { it.dueDate }.thenByDescending { it.priority })
