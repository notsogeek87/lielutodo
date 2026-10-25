package com.lielu.todo.ui.common

import com.lielu.todo.data.todo.QuickAdd
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)
private val weekday = DateTimeFormatter.ofPattern("EEEE", Locale.FRENCH)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)
private val longDay = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

fun Instant.localDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate = atZone(zone).toLocalDate()

/** L'heure d'une échéance, ou `null` si c'est une « date seule » (midi, voir [QuickAdd.dueInstant]). */
fun Instant.timeLabel(zone: ZoneId = ZoneId.systemDefault()): String? {
    val time = atZone(zone).toLocalTime()
    return if (time == QuickAdd.DATE_ONLY_TIME) null else time.format(timeFormat)
}

fun dayLabel(
    date: LocalDate,
    today: LocalDate,
): String {
    val days = date.toEpochDay() - today.toEpochDay()
    return when {
        days == 0L -> "Aujourd'hui"
        days == 1L -> "Demain"
        days == -1L -> "Hier"
        days in 2..6 -> date.format(weekday).replaceFirstChar { it.uppercase() }
        date.year == today.year -> date.format(dayMonth)
        else -> date.format(dayMonth) + " " + date.year
    }
}

/** « Demain 09:30 », « Lundi », « 3 déc. » : le libellé d'une échéance sous le titre d'une tâche. */
fun dueLabel(
    due: Instant,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val base = dayLabel(due.localDate(zone), today)
    val time = due.timeLabel(zone)
    return if (time == null) base else "$base $time"
}

/** En-tête de groupe de l'écran « À venir » : « Lundi 26 octobre ». */
fun dayHeader(date: LocalDate): String = date.format(longDay).replaceFirstChar { it.uppercase() }
