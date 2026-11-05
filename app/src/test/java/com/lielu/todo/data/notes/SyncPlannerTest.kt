package com.lielu.todo.data.notes

import com.lielu.todo.data.joplin.RemoteNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {
    private fun local(
        id: String,
        updated: Long = 0,
        dirty: Boolean = false,
        deleted: Boolean = false,
    ) = Note(id = id, title = "t$id", body = "b$id", userUpdatedTime = updated, dirty = dirty, deleted = deleted)

    private fun remote(
        id: String,
        updated: Long = 0,
    ) = RemoteNote(
        id = id,
        title = "t$id",
        body = "b$id",
        parentId = "",
        userUpdatedTime = updated,
    )

    @Test
    fun noteRecueInconnueLocalementEstAppliquee() {
        val plan =
            SyncPlanner.plan(
                local = emptyList(),
                pulled = listOf(remote("a", updated = 5)),
                remoteDeleted = emptySet(),
            )

        assertEquals(listOf(remote("a", updated = 5)), plan.toApplyRemote)
        assertTrue(plan.toPush.isEmpty())
    }

    @Test
    fun noteLocaleModifieeEstPousseeMemeSiReçue() {
        val plan =
            SyncPlanner.plan(
                local = listOf(local("a", updated = 10, dirty = true)),
                pulled = listOf(remote("a", updated = 20)),
                remoteDeleted = emptySet(),
            )

        assertEquals(1, plan.toPush.size)
        assertTrue(plan.toApplyRemote.isEmpty())
    }

    @Test
    fun noteRecuePlusRecenteEstAppliquee() {
        val plan =
            SyncPlanner.plan(
                local = listOf(local("a", updated = 10)),
                pulled = listOf(remote("a", updated = 20)),
                remoteDeleted = emptySet(),
            )

        assertEquals(listOf(remote("a", updated = 20)), plan.toApplyRemote)
    }

    @Test
    fun noteRecuePasPlusRecenteEstIgnoree() {
        val plan =
            SyncPlanner.plan(
                local = listOf(local("a", updated = 20)),
                pulled = listOf(remote("a", updated = 10)),
                remoteDeleted = emptySet(),
            )

        assertTrue(plan.toApplyRemote.isEmpty())
        assertTrue(plan.toPush.isEmpty())
    }

    @Test
    fun noteSupprimeeLocalementEstSupprimeeCoteServeur() {
        val plan =
            SyncPlanner.plan(
                local = listOf(local("a", deleted = true, dirty = true)),
                pulled = emptyList(),
                remoteDeleted = emptySet(),
            )

        assertEquals(listOf("a"), plan.toDeleteRemote.map { it.id })
        assertTrue(plan.toPush.isEmpty())
    }

    @Test
    fun noteSupprimeeCoteServeurEstSupprimeeLocalement() {
        val plan =
            SyncPlanner.plan(
                local = listOf(local("a")),
                pulled = emptyList(),
                remoteDeleted = setOf("a"),
            )

        assertEquals(listOf("a"), plan.toDeleteLocal)
    }

    @Test
    fun noteLocaleModifieeGagneSurLaSuppressionDistante() {
        val plan =
            SyncPlanner.plan(
                local = listOf(local("a", updated = 10, dirty = true)),
                pulled = emptyList(),
                remoteDeleted = setOf("a"),
            )

        assertEquals(1, plan.toPush.size)
        assertTrue(plan.toDeleteLocal.isEmpty())
    }

    @Test
    fun versionDistanteChangeeDepuisLaBasePendantUneModificationLocaleEstUnConflit() {
        val base = local("a", updated = 30, dirty = true).copy(body = "ici", serverUpdatedTime = 10)
        val plan =
            SyncPlanner.plan(
                local = listOf(base),
                pulled = listOf(remote("a", updated = 20).copy(body = "ailleurs")),
                remoteDeleted = emptySet(),
            )

        assertEquals(listOf("a"), plan.toPush.map { it.id })
        assertEquals(listOf("ailleurs"), plan.conflicts.map { it.body })
        assertTrue(plan.toApplyRemote.isEmpty())
    }

    @Test
    fun versionDistanteInchangeeDepuisLaBaseNEstPasUnConflit() {
        val base = local("a", updated = 30, dirty = true).copy(body = "ici", serverUpdatedTime = 20)
        val plan =
            SyncPlanner.plan(
                local = listOf(base),
                pulled = listOf(remote("a", updated = 20).copy(body = "avant")),
                remoteDeleted = emptySet(),
            )

        assertTrue(plan.conflicts.isEmpty())
        assertEquals(1, plan.toPush.size)
    }

    @Test
    fun unBrouillonVideNEstPasEnvoye() {
        val plan =
            SyncPlanner.plan(
                local =
                    listOf(
                        Note(id = "a", title = "", body = " ", dirty = true),
                        Note(id = "b", title = "x", body = "", dirty = true),
                    ),
                pulled = emptyList(),
                remoteDeleted = emptySet(),
            )

        assertEquals(listOf("b"), plan.toPush.map { it.id })
    }
}
