package com.lielu.todo.backup

import com.lielu.todo.data.notes.Note
import com.lielu.todo.testutil.FakeNotesRepository
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteBackupSectionTest {
    @Test
    fun exportPuisImportRestaureLesNotes() =
        runTest {
            val notes = FakeNotesRepository()
            notes.upsert(Note(id = "a", title = "Courses", body = "- lait", userUpdatedTime = 10L))
            notes.upsert(
                Note(id = "b", title = "Idées", body = "", parentId = "dossier-1", userUpdatedTime = 20L, dirty = true),
            )
            val section = NoteBackupSection(notes)

            val exported = section.export()
            notes.replaceAll(emptyList())
            section.restore(exported)

            val restored = notes.getAllActive()
            assertEquals(2, restored.size)
            assertEquals("Courses", notes.getById("a")?.title)
            assertEquals("dossier-1", notes.getById("b")?.parentId)
            assertEquals(true, notes.getById("b")?.dirty)
        }

    @Test
    fun restoreRemplaceLesDonneesExistantes() =
        runTest {
            val notes = FakeNotesRepository()
            notes.upsert(Note(id = "ancienne", title = "x", body = "", userUpdatedTime = 1L))
            val section = NoteBackupSection(notes)
            val sauvegarde =
                JSONObject()
                    .put(
                        "notes",
                        JSONArray()
                            .put(JSONObject().put("id", "nouvelle").put("title", "y").put("body", "z")),
                    )

            section.restore(sauvegarde)

            assertEquals(null, notes.getById("ancienne"))
            assertEquals("y", notes.getById("nouvelle")?.title)
        }

    @Test
    fun restoreTolereLesChampsAbsentsDesAnciennesSauvegardes() =
        runTest {
            val notes = FakeNotesRepository()
            val section = NoteBackupSection(notes)
            val sauvegarde =
                JSONObject()
                    .put("notes", JSONArray().put(JSONObject().put("id", "a")))

            section.restore(sauvegarde)

            val note = notes.getById("a")
            assertEquals("", note?.title)
            assertEquals(0L, note?.userUpdatedTime)
            assertEquals(false, note?.dirty)
        }

    @Test
    fun uneSuppressionEnAttenteEtLeContenuServeurSurviventALaRestauration() =
        runTest {
            val notes = FakeNotesRepository()
            notes.upsert(
                Note(
                    id = "a",
                    title = "t",
                    body = "",
                    userUpdatedTime = 1L,
                    serverRaw = "brut",
                    serverUpdatedTime = 5L,
                ),
            )
            notes.upsert(Note(id = "b", title = "s", body = "", userUpdatedTime = 2L, deleted = true, dirty = true))
            val section = NoteBackupSection(notes)

            val exported = section.export()
            notes.replaceAll(emptyList())
            section.restore(exported)

            assertEquals("brut", notes.getById("a")?.serverRaw)
            assertEquals(5L, notes.getById("a")?.serverUpdatedTime)
            assertEquals(true, notes.getById("b")?.deleted)
            assertEquals(1, notes.getAllActive().size)
        }
}
