package com.lielu.todo.ui.home

import com.lielu.todo.data.model.Task
import com.lielu.todo.data.settings.UpcomingSettings
import com.lielu.todo.data.settings.UpcomingSort
import com.lielu.todo.data.todo.QuickAdd
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Nombre de jours listés par la vue Agenda à partir d'aujourd'hui. */
internal const val AGENDA_DAYS = 365

/** Durée affichée d'une tâche dans la grille horaire (le serveur ne porte qu'une échéance). */
internal const val BLOCK_MINUTES = 30

private const val SECONDS_PER_DAY = 86_400L
private const val MAX_FUTURE = 400
private const val MINUTES_PER_HOUR = 60
private const val DAYS_PER_WEEK = 7

/** Une tâche à une date donnée ; [future] = occurrence calculée d'une tâche répétée (pas encore créée). */
internal data class Occurrence(
    val task: Task,
    val at: Instant,
    val future: Boolean = false,
) {
    val key: String get() = if (future) "occ-${task.id}-${at.epochSecond}" else "task-${task.id}"

    fun day(zone: ZoneId): LocalDate = at.atZone(zone).toLocalDate()

    /** `null` pour une « date seule » (midi, voir [QuickAdd.DATE_ONLY_TIME]). */
    fun minuteOfDay(zone: ZoneId): Int? {
        val time = at.atZone(zone).toLocalTime()
        return if (time == QuickAdd.DATE_ONLY_TIME) null else time.hour * MINUTES_PER_HOUR + time.minute
    }
}

/** Les occurrences à afficher entre [from] et [to] (inclus), selon les réglages. */
internal fun upcomingOccurrences(
    tasks: List<Task>,
    settings: UpcomingSettings,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId,
): List<Occurrence> {
    val result = mutableListOf<Occurrence>()
    for (task in tasks) {
        val planned = task.plannedAt ?: continue
        if (task.done && !settings.showDone) continue
        val day = planned.atZone(zone).toLocalDate()
        if (!day.isBefore(from) && !day.isAfter(to)) result += Occurrence(task, planned)
        if (settings.futureOccurrences && !task.done && task.isRecurring) {
            var n = 1L
            while (n <= MAX_FUTURE) {
                val next = nextOccurrence(task, planned, n, zone) ?: break
                val nextDay = next.atZone(zone).toLocalDate()
                if (nextDay.isAfter(to)) break
                if (!nextDay.isBefore(from)) result += Occurrence(task, next, future = true)
                n++
            }
        }
    }
    return result
}

/** La [n]-ième répétition après [start] ; `null` si la tâche se répète plus d'une fois par jour (non listée). */
internal fun nextOccurrence(
    task: Task,
    start: Instant,
    n: Long,
    zone: ZoneId,
): Instant? {
    val zoned = start.atZone(zone)
    return when {
        task.repeatMode == Task.REPEAT_MONTHLY -> zoned.plusMonths(n).toInstant()
        task.repeatAfter >= SECONDS_PER_DAY -> zoned.plusDays(task.repeatAfter / SECONDS_PER_DAY * n).toInstant()
        else -> null
    }
}

internal fun List<Occurrence>.sortedFor(sort: UpcomingSort): List<Occurrence> =
    when (sort) {
        // Heure d'abord (les tâches sans heure ensuite), puis priorité.
        UpcomingSort.SMART ->
            sortedWith(
                compareBy<Occurrence> { it.at.atZone(ZoneId.systemDefault()).toLocalTime() == QuickAdd.DATE_ONLY_TIME }
                    .thenBy { it.at }
                    .thenByDescending { it.task.priority }
                    .thenBy { it.task.position },
            )
        UpcomingSort.DATE -> sortedWith(compareBy<Occurrence> { it.at }.thenBy { it.task.title.lowercase() })
        UpcomingSort.PRIORITY ->
            sortedWith(compareByDescending<Occurrence> { it.task.priority }.thenBy { it.at })
        UpcomingSort.NAME -> sortedWith(compareBy<Occurrence> { it.task.title.lowercase() }.thenBy { it.at })
    }

internal sealed interface AgendaEntry {
    val day: LocalDate

    data class Header(
        override val day: LocalDate,
    ) : AgendaEntry

    data class Item(
        override val day: LocalDate,
        val occurrence: Occurrence,
    ) : AgendaEntry

    /** Jour sans tâche : un peu d'air sous son en-tête, comme Todoist. */
    data class Empty(
        override val day: LocalDate,
    ) : AgendaEntry
}

/** La liste à plat de la vue Agenda : un en-tête par jour de [today] sur [days] jours, puis ses tâches. */
internal fun agendaEntries(
    occurrences: List<Occurrence>,
    today: LocalDate,
    days: Int,
    sort: UpcomingSort,
    zone: ZoneId,
): List<AgendaEntry> {
    val byDay = occurrences.groupBy { it.day(zone) }
    val entries = mutableListOf<AgendaEntry>()
    for (offset in 0 until days) {
        val day = today.plusDays(offset.toLong())
        entries += AgendaEntry.Header(day)
        val items = byDay[day].orEmpty().sortedFor(sort)
        if (items.isEmpty()) {
            entries +=
                AgendaEntry.Empty(
                    day,
                )
        } else {
            items.forEach { entries += AgendaEntry.Item(day, it) }
        }
    }
    return entries
}

/** Premier jour de la semaine de [day] : le dimanche, comme la bande de dates de Todoist (D L M M J V S). */
internal fun weekStart(day: LocalDate): LocalDate = day.minusDays((day.dayOfWeek.value % DAYS_PER_WEEK).toLong())

internal fun weekDays(day: LocalDate): List<LocalDate> =
    weekStart(day).let { start ->
        (0 until DAYS_PER_WEEK).map {
            start.plusDays(it.toLong())
        }
    }

/** Les semaines complètes (dimanche → samedi) qui couvrent [month]. */
internal fun monthWeeks(month: YearMonth): List<List<LocalDate>> {
    val first = weekStart(month.atDay(1))
    val last = month.atEndOfMonth()
    val weeks = mutableListOf<List<LocalDate>>()
    var start = first
    while (!start.isAfter(last)) {
        weeks += weekDays(start)
        start = start.plusDays(DAYS_PER_WEEK.toLong())
    }
    return weeks
}

/** Une tâche à heure fixe placée dans la grille : [lane] sur [lanes] colonnes quand elles se chevauchent. */
internal data class Placed(
    val occurrence: Occurrence,
    val startMinute: Int,
    val lane: Int,
    val lanes: Int,
)

/** Répartit les tâches d'un jour sur des colonnes pour que celles qui se chevauchent ne se masquent pas. */
internal fun placeDay(
    timed: List<Occurrence>,
    zone: ZoneId,
): List<Placed> {
    class Slot(
        val occurrence: Occurrence,
        val start: Int,
        val lane: Int,
    )
    val result = mutableListOf<Placed>()
    val cluster = mutableListOf<Slot>()
    val laneEnds = mutableListOf<Int>()
    var clusterEnd = 0

    fun flush() {
        cluster.forEach { result += Placed(it.occurrence, it.start, it.lane, laneEnds.size) }
        cluster.clear()
        laneEnds.clear()
    }
    for (occurrence in timed.sortedBy { it.minuteOfDay(zone) ?: 0 }) {
        val start = occurrence.minuteOfDay(zone) ?: continue
        if (cluster.isNotEmpty() && start >= clusterEnd) flush()
        var lane = laneEnds.indexOfFirst { it <= start }
        if (lane == -1) {
            laneEnds += 0
            lane = laneEnds.lastIndex
        }
        laneEnds[lane] = start + BLOCK_MINUTES
        cluster += Slot(occurrence, start, lane)
        clusterEnd = maxOf(clusterEnd, start + BLOCK_MINUTES)
    }
    flush()
    return result
}
