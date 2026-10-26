package com.lielu.todo.todo

import com.lielu.todo.data.model.taskFromJson
import com.lielu.todo.data.todo.productivity
import com.lielu.todo.data.todo.recentActivity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ProductivityTest {
    private val today = LocalDate.of(2026, 10, 26)
    private val zone = ZoneOffset.UTC

    private fun task(
        id: Int,
        created: String,
        doneAt: String? = null,
    ) = taskFromJson(
        JSONObject()
            .put("id", id)
            .put("title", "T$id")
            .put("project_id", 1)
            .put("done", doneAt != null)
            .put("created", created)
            .put("done_at", doneAt ?: "0001-01-01T00:00:00Z"),
    )

    @Test
    fun countsCompletionsPerDayAndTheStreak() {
        val tasks =
            listOf(
                task(1, "2026-10-20T08:00:00Z", "2026-10-26T09:00:00Z"),
                task(2, "2026-10-20T08:00:00Z", "2026-10-26T10:00:00Z"),
                task(3, "2026-10-20T08:00:00Z", "2026-10-25T10:00:00Z"),
                task(4, "2026-10-20T08:00:00Z", "2026-10-24T10:00:00Z"),
                task(5, "2026-10-20T08:00:00Z", "2026-10-22T10:00:00Z"),
                task(6, "2026-10-20T08:00:00Z"),
            )
        val stats = productivity(tasks, today, zone)
        assertEquals(2, stats.today)
        assertEquals(5, stats.week)
        assertEquals(3, stats.streak)
        assertEquals(7, stats.lastDays.size)
        assertEquals(today, stats.lastDays.last().first)
    }

    @Test
    fun yesterdaysStreakSurvivesUntilTheEndOfToday() {
        val tasks =
            listOf(
                task(1, "2026-10-20T08:00:00Z", "2026-10-25T10:00:00Z"),
                task(2, "2026-10-20T08:00:00Z", "2026-10-24T10:00:00Z"),
            )
        assertEquals(2, productivity(tasks, today, zone).streak)
        assertEquals(0, productivity(emptyList(), today, zone).streak)
    }

    @Test
    fun activityListsCreationsAndCompletionsNewestFirst() {
        val tasks = listOf(task(1, "2026-10-20T08:00:00Z", "2026-10-26T09:00:00Z"), task(2, "2026-10-25T08:00:00Z"))
        val events = recentActivity(tasks)
        assertEquals(listOf(1L to true, 2L to false, 1L to false), events.map { it.taskId to it.completed })
    }
}
