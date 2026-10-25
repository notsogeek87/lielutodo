package com.lielu.todo.todo

import com.lielu.todo.data.model.Reminder
import com.lielu.todo.data.model.taskFromJson
import com.lielu.todo.reminder.planReminders
import com.lielu.todo.widget.widgetTasks
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ReminderPlanTest {
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-25T08:00:00Z")

    private fun task(
        id: Int,
        due: String?,
        done: Boolean = false,
        reminders: List<Reminder> = emptyList(),
    ) = taskFromJson(
        JSONObject()
            .put("id", id)
            .put("title", "T$id")
            .put("project_id", 1)
            .put("done", done)
            .put("due_date", due ?: "0001-01-01T00:00:00Z"),
    ).copy(reminders = reminders)

    @Test
    fun dueTimeNotifiesOnlyWhenTheTaskHasATimeOfDay() {
        val timed = task(1, "2026-10-25T09:30:00Z")
        val dateOnly = task(2, "2026-10-25T12:00:00Z")
        val plan = planReminders(listOf(timed, dateOnly), now, zone)
        assertEquals(listOf(1L), plan.map { it.taskId })
        assertEquals(Instant.parse("2026-10-25T09:30:00Z"), plan.single().at)
    }

    @Test
    fun explicitRemindersReplaceTheDueTimeAndFollowTheDueDate() {
        val t =
            task(
                1,
                "2026-10-25T18:00:00Z",
                reminders = listOf(Reminder.beforeDue(3_600), Reminder(Instant.parse("2026-10-26T07:00:00Z"))),
            )
        val plan = planReminders(listOf(t), now, zone)
        assertEquals(listOf("2026-10-25T17:00:00Z", "2026-10-26T07:00:00Z").map(Instant::parse), plan.map { it.at })
    }

    @Test
    fun doneTasksAndPastRemindersAreSkippedAndThePlanIsSortedAndCapped() {
        val tasks =
            listOf(
                task(1, "2026-10-25T07:00:00Z"),
                task(2, "2026-10-25T10:00:00Z", done = true),
                task(3, "2026-10-26T10:00:00Z"),
                task(4, "2026-10-25T09:00:00Z"),
            )
        val plan = planReminders(tasks, now, zone, max = 2)
        assertEquals(listOf(4L, 3L), plan.map { it.taskId })
        assertTrue(plan.zipWithNext().all { (a, b) -> !a.at.isAfter(b.at) })
    }

    @Test
    fun widgetShowsOverdueAndTodayOnly() {
        val today = LocalDate.of(2026, 10, 25)
        val tasks =
            listOf(
                task(1, "2026-10-24T12:00:00Z"),
                task(2, "2026-10-25T12:00:00Z"),
                task(3, "2026-10-26T12:00:00Z"),
                task(4, null),
                task(5, "2026-10-25T12:00:00Z", done = true),
            )
        assertEquals(listOf(1L, 2L), widgetTasks(tasks, today).map { it.id })
    }
}
