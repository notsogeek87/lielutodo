package com.lielu.todo.data.notes

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileNotesRepositoryTest {
    @get:Rule
    val dir = TemporaryFolder()

    private fun cache() = File(dir.root, "notes-cache.json")

    @Test
    fun lesNotesSurviventAUnRedemarrage() =
        runTest {
            val repo = FileNotesRepository(cache())
            val note = repo.create()
            repo.save(note.id, "Titre", "Corps")

            val relu = FileNotesRepository(cache())

            assertEquals("Corps", relu.getById(note.id)?.body)
            assertEquals(true, relu.getById(note.id)?.dirty)
        }

    @Test
    fun unCacheIllisibleEstRelueDepuisLaCopieDeSecours() =
        runTest {
            val repo = FileNotesRepository(cache())
            val note = repo.create()
            repo.save(note.id, "Titre", "v1")
            repo.save(note.id, "Titre", "v2")
            cache().writeText("{ tronqué")

            val relu = FileNotesRepository(cache())

            assertEquals("v1", relu.getById(note.id)?.body)
        }

    @Test
    fun unCacheIllisibleSansCopieNEstPasEcrase() =
        runTest {
            cache().writeText("{ tronqué")

            val repo = FileNotesRepository(cache())
            repo.create()

            assertTrue(File(dir.root, "notes-cache.json.corrupt").readText().startsWith("{ tronqué"))
        }

    @Test
    fun updateNeRemplaceQueLaNoteVisee() =
        runTest {
            val repo = FileNotesRepository(cache())
            val a = repo.create()
            val b = repo.create()

            repo.update(a.id) { it?.copy(title = "A") }
            repo.update(b.id) { null }

            assertEquals("A", repo.getById(a.id)?.title)
            assertEquals(null, repo.getById(b.id))
        }
}
