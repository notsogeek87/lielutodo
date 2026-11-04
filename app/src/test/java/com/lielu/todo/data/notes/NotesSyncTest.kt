package com.lielu.todo.data.notes

import com.lielu.todo.data.joplin.JoplinApi
import com.lielu.todo.data.joplin.JoplinApiException
import com.lielu.todo.data.joplin.RemoteNote
import com.lielu.todo.testutil.FakeNotesRepository
import com.lielu.todo.testutil.FakeSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesSyncTest {
    /** API Joplin en mémoire : simule le serveur, y compris le 404 d'une note jamais créée. */
    private class FakeJoplinApi : JoplinApi {
        val notes = mutableMapOf<String, RemoteNote>()

        override suspend fun listNotes(): List<RemoteNote> = notes.values.toList()

        override suspend fun createNote(
            title: String,
            body: String,
            parentId: String,
        ): RemoteNote {
            val note = RemoteNote("srv-${notes.size + 1}", title, body, parentId, userUpdatedTime = 100L)
            notes[note.id] = note
            return note
        }

        override suspend fun updateNote(
            id: String,
            title: String,
            body: String,
            parentId: String,
        ): RemoteNote {
            if (id !in notes) throw JoplinApiException("Note introuvable", statusCode = 404)
            val note = RemoteNote(id, title, body, parentId, userUpdatedTime = 100L)
            notes[id] = note
            return note
        }

        override suspend fun deleteNote(id: String) {
            if (id !in notes) throw JoplinApiException("Note introuvable", statusCode = 404)
            notes.remove(id)
        }
    }

    private fun configuredSettings() =
        FakeSettingsRepository().apply {
            setJoplinServerUrl("https://notes.lielu.eu")
            setJoplinApiToken("token-de-test")
            setJoplinSyncEnabled(true)
        }

    @Test
    fun noteLocaleModifieeEstPousseeEtMarqueePropre() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            val api = FakeJoplinApi()
            api.notes["a"] = RemoteNote("a", "ancien", "", "", userUpdatedTime = 1L)
            notes.upsert(Note(id = "a", title = "nouveau", body = "contenu", userUpdatedTime = 50L, dirty = true))
            val sync = NotesSync(settings, notes, apiFactory = { api })

            val result = sync.syncNow()

            assertTrue(result is SyncResult.Success)
            assertEquals("nouveau", api.notes.getValue("a").title)
            val local = notes.getById("a")
            assertEquals(false, local?.dirty)
            assertEquals(100L, local?.userUpdatedTime)
        }

    @Test
    fun noteJamaisPousseeEstCreeeCoteServeurAvecSonNouvelId() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            val api = FakeJoplinApi()
            notes.upsert(Note(id = "local-1", title = "t", body = "b", userUpdatedTime = 50L, dirty = true))
            val sync = NotesSync(settings, notes, apiFactory = { api })

            val result = sync.syncNow()

            assertTrue(result is SyncResult.Success)
            assertEquals(1, api.notes.size)
            assertNull(notes.getById("local-1"))
            assertEquals(1, notes.getAllActive().size)
            assertEquals("srv-1", notes.getAllActive().first().id)
        }

    @Test
    fun noteDistanteNouvelleEstTelechargee() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            val api = FakeJoplinApi()
            api.notes["a"] = RemoteNote("a", "distante", "b", "", userUpdatedTime = 10L)
            val sync = NotesSync(settings, notes, apiFactory = { api })

            sync.syncNow()

            assertEquals("distante", notes.getById("a")?.title)
        }

    @Test
    fun noteSupprimeeLocalementEstSupprimeeDesDeuxCotes() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            val api = FakeJoplinApi()
            api.notes["a"] = RemoteNote("a", "t", "", "", userUpdatedTime = 1L)
            notes.upsert(Note(id = "a", title = "t", body = "", userUpdatedTime = 50L, dirty = true, deleted = true))
            val sync = NotesSync(settings, notes, apiFactory = { api })

            sync.syncNow()

            assertTrue(api.notes.isEmpty())
            assertNull(notes.getById("a"))
        }

    @Test
    fun nonConfigreeRetourneUneEchecSansRienToucher() =
        runTest {
            val settings = FakeSettingsRepository()
            val notes = FakeNotesRepository()
            val api = FakeJoplinApi()
            val sync = NotesSync(settings, notes, apiFactory = { api })

            val result = sync.syncNow()

            assertTrue(result is SyncResult.Failure)
            assertTrue(api.notes.isEmpty())
            assertTrue(settings.syncStatus.first().lastError != null)
        }

    @Test
    fun erreurReseauEstSignaleeDansLeStatut() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            val failing =
                object : JoplinApi {
                    override suspend fun listNotes(): List<RemoteNote> = throw JoplinApiException("réseau coupé")

                    override suspend fun createNote(
                        title: String,
                        body: String,
                        parentId: String,
                    ): RemoteNote = error("inattendu")

                    override suspend fun updateNote(
                        id: String,
                        title: String,
                        body: String,
                        parentId: String,
                    ): RemoteNote = error("inattendu")

                    override suspend fun deleteNote(id: String) = error("inattendu")
                }
            val sync = NotesSync(settings, notes, apiFactory = { failing })

            val result = sync.syncNow()

            assertTrue(result is SyncResult.Failure)
            val lastError = settings.syncStatus.first().lastError
            assertTrue(lastError!!.contains("réseau coupé"))
        }
}
