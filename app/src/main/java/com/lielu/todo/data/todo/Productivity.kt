package com.lielu.todo.data.todo

import com.lielu.todo.data.model.Task
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Statistiques de productivité calculées sur les tâches du cache (donc sans réseau). */
data class ProductivityStats(
    /** Tâches terminées chaque jour, du plus ancien au plus récent ; le dernier élément est aujourd'hui. */
    val lastDays: List<Pair<LocalDate, Int>>,
    val today: Int,
    val week: Int,
    /** Jours consécutifs, jusqu'à aujourd'hui (ou hier si rien n'est encore terminé aujourd'hui), avec au moins une tâche terminée. */
    val streak: Int,
)

fun productivity(
    tasks: List<Task>,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
    days: Int = 7,
): ProductivityStats {
    val perDay =
        tasks
            .filter { it.done }
            .mapNotNull { it.doneAt?.atZone(zone)?.toLocalDate() }
            .groupingBy { it }
            .eachCount()
    val lastDays = (days - 1 downTo 0).map { today.minusDays(it.toLong()).let { d -> d to (perDay[d] ?: 0) } }
    var streak = 0
    // Pas encore de tâche terminée aujourd'hui : la série d'hier n'est pas perdue avant la fin de la journée.
    var day = if ((perDay[today] ?: 0) > 0) today else today.minusDays(1)
    while ((perDay[day] ?: 0) > 0) {
        streak++
        day = day.minusDays(1)
    }
    return ProductivityStats(lastDays, perDay[today] ?: 0, lastDays.sumOf { it.second }, streak)
}

/** Un événement du journal d'activité, déduit des dates des tâches (Vikunja n'a pas de journal global). */
data class ActivityEvent(
    val at: Instant,
    val taskId: Long,
    val title: String,
    val completed: Boolean,
)

/** Créations et complétions récentes, les plus récentes d'abord. */
fun recentActivity(
    tasks: List<Task>,
    limit: Int = 50,
): List<ActivityEvent> =
    tasks
        .flatMap { t ->
            listOfNotNull(
                t.createdAt?.let { ActivityEvent(it, t.id, t.title, completed = false) },
                if (t.done) t.doneAt?.let { ActivityEvent(it, t.id, t.title, completed = true) } else null,
            )
        }.sortedByDescending { it.at }
        .take(limit)
