package com.lielu.todo.data.notes

import com.lielu.todo.data.joplin.RemoteNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {
    private fun local(id: String, updated: Long = 0, dirty: Boolean = false, deleted: Boolean = false) =
        Note(id = id, title = "t$id", body = "b$id", userUpdatedTime = updated, dirty = dirty, deleted = deleted)

    private fun remote(id: String, updated: Long = 0) =
        RemoteNote(id = id, title = "t$id", body = "b$id", parentId = "", userUpdatedTime = updated)

    @Test
    fun noteDistanteInconnueLocalementEstTelechargee() {
        val plan = SyncPlanner.plan(local = emptyList(), remote = listOf(remote("a", updated = 5)))

        assertEquals(listOf(remote("a", updated = 5)), plan.toApplyRemote)
        assertTrue(plan.toPush.isEmpty())
    }

    @Test
    fun noteLocaleModifieeEstPousseeMemeSiLeServeurEnAUne() {
        val plan =
            SyncPlanner.plan(
                local = listOf(local("a", updated = 10, dirty = true)),
                remote = listOf(remote("a", updated = 20)),
            )

        assertEquals(listOf(local("a", updated = 10, dirty = true)), plan.toPush)
        assertTrue(plan.toApplyRemote.isEmpty())
    }

    @Test
    fun noteDistantePlusRecenteEstTelechargee() {
        val plan =
            SyncPlanner.plan(
                local = listOf(local("a", updated = 10)),
                remote = listOf(remote("a", updated = 20)),
            )

        assertEquals(listOf(remote("a", updated = 20)), plan.toApplyRemote)
    }

    @Test
    fun noteDistantePasPlusRecenteEstConserveeTelleQuelle() {
        val plan =
            SyncPlanner.plan(
                local = listOf(local("a", updated = 20)),
                remote = listOf(remote("a", updated = 10)),
            )

        assertTrue(plan.toApplyRemote.isEmpty())
        assertTrue(plan.toPush.isEmpty())
    }

    @Test
    fun noteSupprimeeLocalementEstSupprimeeCoteServeur() {
        val plan =
            SyncPlanner.plan(
                local = listOf(local("a", deleted = true, dirty = true)),
                remote = listOf(remote("a")),
            )

        assertEquals(listOf("a"), plan.toDeleteRemote)
        assertTrue(plan.toPush.isEmpty())
    }

    @Test
    fun noteAbsenteDuServeurEstSupprimeeLocalement() {
        val plan =
            SyncPlanner.plan(
                local = listOf(local("a")),
                remote = emptyList(),
            )

        assertEquals(listOf("a"), plan.toDeleteLocal)
    }
}
