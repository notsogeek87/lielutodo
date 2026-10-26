package com.lielu.todo.widget

import com.lielu.todo.data.model.Project
import com.lielu.todo.data.model.Task
import com.lielu.todo.ui.common.localDate
import java.time.LocalDate

/** Quelles tâches (selon leur échéance) le widget liste affiche. */
enum class WidgetPeriod(
    val label: String,
    val title: String,
) {
    OVERDUE_TODAY("Aujourd'hui et en retard", "Aujourd'hui"),
    TODAY("Aujourd'hui seulement", "Aujourd'hui"),
    WEEK("7 prochains jours (et en retard)", "7 jours"),
    DATED("Toutes celles qui ont une date", "Avec date"),
    ALL("Toutes, sans limite de date", "Toutes"),
}

/** Choix de l'utilisateur pour un widget liste. [projectId] 0 = tous les projets, [PERSONAL_PROJECT] = tâches personnelles. */
data class WidgetConfig(
    val period: WidgetPeriod = WidgetPeriod.OVERDUE_TODAY,
    val projectId: Long = 0,
    val includeSubProjects: Boolean = true,
    /** Taille du texte en % de la taille normale. */
    val fontPercent: Int = DEFAULT_FONT_PERCENT,
    /** Opacité du fond en % (0 = fond invisible). */
    val opacityPercent: Int = DEFAULT_OPACITY_PERCENT,
)

/** Valeur de [WidgetConfig.projectId] : boîte de réception + tâches affectées à l'utilisateur dans les autres projets. */
const val PERSONAL_PROJECT = -2L

const val DEFAULT_FONT_PERCENT = 100
const val DEFAULT_OPACITY_PERCENT = 100

/** En dessous, le texte devient illisible sur un fond d'écran chargé. */
const val MIN_OPACITY_PERCENT = 60

/** Tâches du widget : filtrées selon [config], triées comme dans l'app : date prévue (échéance, sinon rappel ; sans date en dernier), priorité, position. */
fun widgetTasks(
    tasks: List<Task>,
    today: LocalDate,
    config: WidgetConfig = WidgetConfig(),
    projects: List<Project> = emptyList(),
    inboxId: Long = 0,
    userId: Long = 0,
): List<Task> {
    val projectIds = projectScope(config, projects)
    val inScope = { t: Task ->
        when {
            config.projectId == PERSONAL_PROJECT -> t.projectId == inboxId || t.assignees.any { it.id == userId }
            else -> projectIds == null || t.projectId in projectIds
        }
    }
    return tasks
        .filter { !it.done && inScope(it) && matchesPeriod(it, today, config) }
        .sortedWith(
            compareBy<Task, java.time.Instant?>(nullsLast()) { it.plannedAt }
                .thenByDescending { it.priority }
                .thenBy { it.position }
                .thenBy { it.id },
        )
}

private fun matchesPeriod(
    task: Task,
    today: LocalDate,
    config: WidgetConfig,
): Boolean {
    val due = task.plannedAt?.localDate()
    return when (config.period) {
        WidgetPeriod.OVERDUE_TODAY -> due != null && !due.isAfter(today)
        WidgetPeriod.TODAY -> due == today
        WidgetPeriod.WEEK -> due != null && !due.isAfter(today.plusDays(WEEK_DAYS))
        WidgetPeriod.DATED -> due != null
        WidgetPeriod.ALL -> true
    }
}

/** Ids de projets visés (le projet choisi et ses sous-projets), `null` = tous. */
private fun projectScope(
    config: WidgetConfig,
    projects: List<Project>,
): Set<Long>? {
    if (config.projectId <= 0L) return null
    val ids = mutableSetOf(config.projectId)
    if (config.includeSubProjects) {
        var grew = true
        while (grew) {
            grew = projects.filter { it.parentId in ids }.any { ids.add(it.id) }
        }
    }
    return ids
}

private const val WEEK_DAYS = 6L
