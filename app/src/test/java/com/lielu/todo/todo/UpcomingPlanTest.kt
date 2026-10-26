package com.lielu.todo.todo

import com.lielu.todo.data.model.Task
import com.lielu.todo.data.model.taskFromJson
import com.lielu.todo.data.settings.UpcomingSettings
import com.lielu.todo.data.settings.UpcomingSort
import com.lielu.todo.ui.home.AgendaEntry
import com.lielu.todo.ui.home.Occurrence
import com.lielu.todo.ui.home.agendaEntries
import com.lielu.todo.ui.home.monthWeeks
import com.lielu.todo.ui.home.placeDay
import com.lielu.todo.ui.home.upcomingOccurrences
import com.lielu.todo.ui.home.weekDays
import com.lielu.todo.ui.home.weekStart
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class UpcomingPlanTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 26)

    private fun task(
        id: Int,
        due: String,
        done: Boolean = false,
        repeatAfter: Long = 0,
        repeatMode: Int = 0,
    ): Task =
        taskFromJson(
            JSONObject()
                .put("id", id)
                .put("title", "T$id")
                .put("project_id", 1)
                .put("done", done)
                .put("due_date", due)
                .put("repeat_after", repeatAfter)
                .put("repeat_mode", repeatMode),
        )

    @Test
    fun weekStartsOnSunday() {
        assertEquals(LocalDate.of(2026, 10, 25), weekStart(today))
        assertEquals(LocalDate.of(2026, 10, 25), weekStart(LocalDate.of(2026, 10, 25)))
        assertEquals(LocalDate.of(2026, 10, 25), weekDays(today).first())
        assertEquals(LocalDate.of(2026, 10, 31), weekDays(today).last())
    }

    @Test
    fun monthWeeksCoverTheWholeMonth() {
        val weeks = monthWeeks(YearMonth.of(2026, 10))
        assertTrue(weeks.all { it.size == 7 })
        assertEquals(LocalDate.of(2026, 9, 27), weeks.first().first())
        assertTrue(weeks.last().contains(LocalDate.of(2026, 10, 31)))
    }

    @Test
    fun agendaListsEveryDayEvenWhenEmpty() {
        val tasks = listOf(task(1, "2026-10-27T09:00:00Z"))
        val occurrences = upcomingOccurrences(tasks, UpcomingSettings(), today, today.plusDays(2), zone)
        val entries = agendaEntries(occurrences, today, 3, UpcomingSort.SMART, zone)
        assertEquals(
            listOf(
                AgendaEntry.Header(today),
                AgendaEntry.Empty(today),
                AgendaEntry.Header(today.plusDays(1)),
                AgendaEntry.Item(today.plusDays(1), occurrences.single()),
                AgendaEntry.Header(today.plusDays(2)),
                AgendaEntry.Empty(today.plusDays(2)),
            ),
            entries,
        )
    }

    @Test
    fun doneTasksOnlyAppearWhenEnabled() {
        val tasks = listOf(task(1, "2026-10-27T09:00:00Z", done = true))
        assertTrue(upcomingOccurrences(tasks, UpcomingSettings(), today, today.plusDays(5), zone).isEmpty())
        val shown = upcomingOccurrences(tasks, UpcomingSettings(showDone = true), today, today.plusDays(5), zone)
        assertEquals(1, shown.size)
    }

    @Test
    fun futureOccurrencesRepeatInsideTheWindow() {
        val tasks = listOf(task(1, "2026-10-26T09:00:00Z", repeatAfter = 86_400L * 2))
        val off = upcomingOccurrences(tasks, UpcomingSettings(), today, today.plusDays(6), zone)
        assertEquals(1, off.size)
        val on = upcomingOccurrences(tasks, UpcomingSettings(futureOccurrences = true), today, today.plusDays(6), zone)
        assertEquals(
            listOf(today, today.plusDays(2), today.plusDays(4), today.plusDays(6)),
            on.map { it.day(zone) },
        )
        assertEquals(listOf(false, true, true, true), on.map { it.future })
    }

    @Test
    fun monthlyRepeatKeepsTheDayOfMonth() {
        val tasks = listOf(task(1, "2026-10-26T09:00:00Z", repeatMode = Task.REPEAT_MONTHLY))
        val on =
            upcomingOccurrences(
                tasks,
                UpcomingSettings(futureOccurrences = true),
                today,
                LocalDate.of(2027, 1, 31),
                zone,
            )
        assertEquals(
            listOf(
                LocalDate.of(2026, 10, 26),
                LocalDate.of(2026, 11, 26),
                LocalDate.of(2026, 12, 26),
                LocalDate.of(2027, 1, 26),
            ),
            on.map { it.day(zone) },
        )
    }

    @Test
    fun overlappingTasksShareLanes() {
        val occurrences =
            listOf(
                Occurrence(task(1, "2026-10-26T09:00:00Z"), Instant.parse("2026-10-26T09:00:00Z")),
                Occurrence(task(2, "2026-10-26T09:15:00Z"), Instant.parse("2026-10-26T09:15:00Z")),
                Occurrence(task(3, "2026-10-26T11:00:00Z"), Instant.parse("2026-10-26T11:00:00Z")),
            )
        val placed = placeDay(occurrences, zone)
        assertEquals(listOf(0, 1, 0), placed.map { it.lane })
        assertEquals(listOf(2, 2, 1), placed.map { it.lanes })
    }
}
