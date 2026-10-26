package com.lielu.todo.todo

import com.lielu.todo.data.model.AppNotification
import com.lielu.todo.reminder.newUnread
import org.junit.Assert.assertEquals
import org.junit.Test

class CollaborationTest {
    private fun n(
        id: Long,
        read: Boolean,
    ) = AppNotification(id, "n$id", read, null, 0)

    @Test
    fun onlyUnreadNotificationsNewerThanTheLastOneAreReported() {
        val list = listOf(n(5, false), n(4, true), n(3, false), n(7, false))
        assertEquals(listOf(5L, 7L), newUnread(list, lastNotifiedId = 3).map { it.id })
        assertEquals(emptyList<Long>(), newUnread(list, lastNotifiedId = 7).map { it.id })
    }
}
