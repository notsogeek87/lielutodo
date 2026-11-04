package com.lielu.todo.data.notes

import com.lielu.todo.data.joplin.DeltaItem
import com.lielu.todo.data.joplin.DeltaPage
import com.lielu.todo.data.joplin.JoplinApi
import com.lielu.todo.data.joplin.JoplinApiException
import com.lielu.todo.data.joplin.JoplinParser
import com.lielu.todo.testutil.FakeNotesRepository
import com.lielu.todo.testutil.FakeSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesSyncTest {
    /** API Joplin en mémoire : simule le delta, les contenus d'items et les écritures. */
    private class FakeJoplinApi : JoplinApi {
        val contents = mutableMapOf<String, String>()
        var changes = listOf<DeltaItem>()
        var cursor = "c1"
        var failWithResyncOnce = false
        val puts = mutableMapOf<String, String>()
        val deletes = mutableListOf<String>()

        override suspend fun ping(): String = "JoplinServer"

        override suspend fun delta(cursor: String?): DeltaPage {
            if (failWithResyncOnce) {
                failWithResyncOnce = false
                throw JoplinApiException("cursor invalide", statusCode = 400, errorCode = "resyncRequired")
            }
            return DeltaPage(changes, hasMore = false, cursor = cursor ?: this@FakeJoplinApi.cursor)
        }

        override suspend fun rootChildren(page: Int): DeltaPage = DeltaPage(emptyList(), hasMore = false, cursor = null)

        override suspend fun debugRawDelta(): String = "{}"

        override suspend fun itemContent(name: String): String = contents.getValue(name)

        override suspend fun putContent(
            name: String,
            content: String,
        ): JSONObject {
            puts[name] = content
            contents[name] = content
            return JSONObject().put("jop_updated_time", 100L)
        }

        override suspend fun deleteItem(name: String) {
            deletes += name
            contents.remove(name)
        }
    }

    private fun noteContent(
        id: String,
        title: String,
        updated: Long,
    ): String = JoplinParser.serialize(id, "folder-1", title, "contenu", updated)

    private suspend fun configuredSettings() =
        FakeSettingsRepository().apply {
            setJoplinServerUrl("https://notes.lielu.eu")
            setJoplinEmail("moi@lielu.eu")
            setJoplinPassword("mot-de-passe-de-test")
            setJoplinSyncEnabled(true)
        }

    @Test
    fun noteRecueViaDeltaEstStockeeEtCursorEnregistre() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            val api = FakeJoplinApi()
            val id = "a".repeat(32)
            api.changes = listOf(DeltaItem("notes/$id.md", type = 1, updatedTime = 50L))
            api.contents["notes/$id.md"] = noteContent(id, "Recue", 50L)
            val sync = NotesSync(settings, notes, apiFactory = { api })

            val result = sync.syncNow()

            assertTrue(result is SyncResult.Success)
            assertEquals("Recue", notes.getById(id)?.title)
            assertEquals("c1", settings.joplinCursor.first())
        }

    @Test
    fun noteLocaleModifieeEstPousseeAuFormatSerieEtMarqueePropre() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            val api = FakeJoplinApi()
            val id = "b".repeat(32)
            notes.upsert(
                Note(id = id, title = "Locale", body = "corps", parentId = "folder-1", userUpdatedTime = 10L, dirty = true),
            )
            val sync = NotesSync(settings, notes, apiFactory = { api })

            val result = sync.syncNow()

            assertTrue(result is SyncResult.Success)
            val envoye = api.puts.getValue("$id.md")
            assertTrue(envoye.contains("type_: 1"))
            assertTrue(envoye.startsWith("Locale\n\ncorps\n\n"))
            assertEquals(false, notes.getById(id)?.dirty)
            assertEquals(100L, notes.getById(id)?.userUpdatedTime)
        }

    @Test
    fun suppressionDistanteSupprimeLaNoteLocale() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            val api = FakeJoplinApi()
            val id = "c".repeat(32)
            notes.upsert(Note(id = id, title = "t", body = "", userUpdatedTime = 10L))
            api.changes = listOf(DeltaItem("notes/$id.md", type = 3, updatedTime = 60L))
            val sync = NotesSync(settings, notes, apiFactory = { api })

            sync.syncNow()

            assertNull(notes.getById(id))
        }

    @Test
    fun suppressionLocaleEstPousseeCoteServeur() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            val api = FakeJoplinApi()
            val id = "d".repeat(32)
            notes.upsert(Note(id = id, title = "t", body = "", userUpdatedTime = 10L, dirty = true, deleted = true))
            val sync = NotesSync(settings, notes, apiFactory = { api })

            sync.syncNow()

            assertEquals(listOf("$id.md"), api.deletes)
            assertNull(notes.getById(id))
        }

    @Test
    fun cursorInvalideDeclencheUneResyncComplete() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            val api = FakeJoplinApi()
            api.failWithResyncOnce = true
            val id = "e".repeat(32)
            api.changes = listOf(DeltaItem("notes/$id.md", type = 1, updatedTime = 50L))
            api.contents["notes/$id.md"] = noteContent(id, "Apres resync", 50L)
            val sync = NotesSync(settings, notes, apiFactory = { api })

            val result = sync.syncNow()

            assertTrue(result is SyncResult.Success)
            assertNotNull(notes.getById(id))
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
            assertTrue(api.puts.isEmpty())
            assertTrue(settings.syncStatus.first().lastError != null)
        }

    @Test
    fun erreurReseauEstSignaleeDansLeStatut() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            val failing =
                object : JoplinApi {
                    override suspend fun ping(): String = throw JoplinApiException("réseau coupé")

                    override suspend fun delta(cursor: String?): DeltaPage = error("inattendu")

                    override suspend fun rootChildren(page: Int): DeltaPage = error("inattendu")

                    override suspend fun debugRawDelta(): String = error("inattendu")

                    override suspend fun itemContent(name: String): String = error("inattendu")

                    override suspend fun putContent(
                        name: String,
                        content: String,
                    ): JSONObject = error("inattendu")

                    override suspend fun deleteItem(name: String) = error("inattendu")
                }
            val sync = NotesSync(settings, notes, apiFactory = { failing })

            val result = sync.syncNow()

            assertTrue(result is SyncResult.Failure)
            val lastError = settings.syncStatus.first().lastError
            assertTrue(lastError!!.contains("réseau coupé"))
        }
}
