package com.lielu.todo.todo

import com.lielu.todo.calendar.CalendarAction
import com.lielu.todo.calendar.SyncedEvent
import com.lielu.todo.calendar.planCalendarEvents
import com.lielu.todo.calendar.reconcileEvents
import com.lielu.todo.data.model.taskFromJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class CalendarPlanTest {
    private val zone = ZoneOffset.UTC

    private fun task(
        id: Int,
        due: String?,
        done: Boolean = false,
        title: String = "T$id",
    ) = taskFromJson(
        JSONObject()
            .put("id", id)
            .put("title", title)
            .put("project_id", 1)
            .put("done", done)
            .put("due_date", due ?: "0001-01-01T00:00:00Z"),
    )

    @Test
    fun onlyOpenDatedTasksBecomeEvents() {
        val plan =
            planCalendarEvents(
                listOf(task(1, "2026-10-25T09:30:00Z"), task(2, null), task(3, "2026-10-25T09:30:00Z", done = true)),
                zone,
            )
        assertEquals(listOf(1L), plan.map { it.taskId })
    }

    @Test
    fun dateOnlyTaskIsAnAllDayEventAndTimedTaskIsNot() {
        val plan = planCalendarEvents(listOf(task(1, "2026-10-25T12:00:00Z"), task(2, "2026-10-25T09:30:00Z")), zone)
        assertTrue(plan[0].allDay)
        assertEquals(24 * 60 * 60 * 1000L, plan[0].endMillis - plan[0].startMillis)
        assertFalse(plan[1].allDay)
        assertEquals(30 * 60 * 1000L, plan[1].endMillis - plan[1].startMillis)
    }

    @Test
    fun newTaskIsInsertedAndUnchangedTaskIsLeftAlone() {
        val planned = planCalendarEvents(listOf(task(1, "2026-10-25T09:30:00Z")), zone)
        assertTrue(reconcileEvents(planned, emptyMap()).single() is CalendarAction.Insert)
        val synced = mapOf(1L to SyncedEvent(42, planned.single().fingerprint))
        assertTrue(reconcileEvents(planned, synced).isEmpty())
    }

    @Test
    fun changedDateUpdatesTheSameEvent() {
        val before = planCalendarEvents(listOf(task(1, "2026-10-25T09:30:00Z")), zone).single()
        val after = planCalendarEvents(listOf(task(1, "2026-10-27T09:30:00Z")), zone)
        val action = reconcileEvents(after, mapOf(1L to SyncedEvent(42, before.fingerprint))).single()
        assertEquals(42L, (action as CalendarAction.Update).eventId)
    }

    @Test
    fun renamedTaskUpdatesItsEvent() {
        val before = planCalendarEvents(listOf(task(1, "2026-10-25T09:30:00Z")), zone).single()
        val after = planCalendarEvents(listOf(task(1, "2026-10-25T09:30:00Z", title = "Autre")), zone)
        assertTrue(
            reconcileEvents(after, mapOf(1L to SyncedEvent(42, before.fingerprint))).single() is CalendarAction.Update,
        )
    }

    @Test
    fun removedDateDoneOrDeletedTaskDeletesItsEvent() {
        val event = planCalendarEvents(listOf(task(1, "2026-10-25T09:30:00Z")), zone).single()
        val synced = mapOf(1L to SyncedEvent(42, event.fingerprint))
        val noDate = planCalendarEvents(listOf(task(1, null)), zone)
        val done = planCalendarEvents(listOf(task(1, "2026-10-25T09:30:00Z", done = true)), zone)
        val gone = planCalendarEvents(emptyList(), zone)
        listOf(noDate, done, gone).forEach { plan ->
            assertEquals(CalendarAction.Delete(1, 42), reconcileEvents(plan, synced).single())
        }
    }
}
