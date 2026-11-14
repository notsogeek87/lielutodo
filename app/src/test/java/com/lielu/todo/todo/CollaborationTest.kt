package com.lielu.todo.todo

import com.lielu.todo.data.model.AppNotification
import com.lielu.todo.data.model.appNotificationFromStoredJson
import com.lielu.todo.data.model.toJson
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

    @Test
    fun remindersAreNotRelayedBecauseTheAppAlreadyRings() {
        val list =
            listOf(
                AppNotification(1, "rappel", false, null, 9, kind = "task.reminder"),
                AppNotification(2, "échéance", false, null, 9, kind = "task.deadline.reached"),
                AppNotification(3, "commentaire", false, null, 9, kind = "task.comment"),
            )
        assertEquals(listOf(2L, 3L), newUnread(list, lastNotifiedId = 0).map { it.id })
    }

    @Test
    fun kindSurvivesTheCacheAndOldCachesStillRead() {
        val n = AppNotification(4, "x", false, null, 9, kind = "task.reminder")
        assertEquals(n, appNotificationFromStoredJson(n.toJson()))
        val old = n.toJson().apply { remove("kind") }
        assertEquals("", appNotificationFromStoredJson(old).kind)
    }
}
