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
        val deltaCursors = mutableListOf<String?>()
        val log = mutableListOf<String>()
        var shareCounter = 0

        override suspend fun ping(): String = "JoplinServer"

        override suspend fun delta(cursor: String?): DeltaPage {
            deltaCursors += cursor
            if (failWithResyncOnce) {
                failWithResyncOnce = false
                throw JoplinApiException("cursor invalide", statusCode = 400, errorCode = "resyncRequired")
            }
            return DeltaPage(changes, hasMore = false, cursor = cursor ?: this@FakeJoplinApi.cursor)
        }

        override suspend fun rootChildren(page: Int): DeltaPage = DeltaPage(emptyList(), hasMore = false, cursor = null)

        override suspend fun itemContent(name: String): String =
            contents[name] ?: throw JoplinApiException("introuvable", statusCode = 404)

        override suspend fun putContent(
            name: String,
            content: String,
        ): JSONObject {
            puts[name] = content
            log += "put $name"
            contents[name] = content
            return JSONObject().put("jop_updated_time", 100L)
        }

        override suspend fun createShare(folderId: String): String {
            log += "share $folderId"
            return "s${++shareCounter}"
        }

        override suspend fun addShareRecipient(
            shareId: String,
            email: String,
        ) {
            log += "invite $shareId $email"
        }

        override suspend fun deleteShare(shareId: String) {
            log += "unshare $shareId"
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
    fun deconnexionEffaceIdentifiantsCacheEtCursorMaisGardeLeServeur() =
        runTest {
            val settings = configuredSettings()
            settings.setJoplinCursor("c9")
            val notes = FakeNotesRepository()
            notes.upsert(Note(id = "d".repeat(32), title = "Pas envoyée", body = "", parentId = "f", dirty = true))
            notes.createFolder("Carnet local")
            val sync = NotesSync(settings, notes, apiFactory = { error("pas de réseau") })
            assertEquals(2, sync.unsentCount())

            sync.disconnect()

            val joplin = settings.joplin.first()
            assertEquals("https://notes.lielu.eu", joplin.serverUrl)
            assertEquals("", joplin.email)
            assertEquals("", joplin.password)
            assertTrue(!joplin.syncEnabled)
            assertNull(settings.joplinCursor.first())
            assertTrue(notes.getAllIncludingDeleted().isEmpty())
            assertTrue(notes.getFolders().isEmpty())
            assertEquals(0, sync.unsentCount())
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
                Note(
                    id = id,
                    title = "Locale",
                    body = "corps",
                    parentId = "folder-1",
                    userUpdatedTime = 10L,
                    dirty = true,
                ),
            )
            val sync = NotesSync(settings, notes, apiFactory = { api }, clock = { 100L })

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

    private fun todoContent(
        id: String,
        title: String,
        body: String,
        updated: Long,
    ): String =
        "$title\n\n$body\n\nid: $id\nparent_id: folder-1\nupdated_time: ${JoplinParser.formatTime(updated)}\n" +
            "author: Moi\nis_todo: 1\ntodo_due: 1700000000000\nencryption_applied: 0\ntype_: 1\n"

    private fun folderContent(id: String): String = "Carnet\n\nid: $id\nparent_id: \ntype_: 2\n"

    @Test
    fun uneModificationLocaleGardeLesProprietesJoplin() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            val api = FakeJoplinApi()
            val id = "1".repeat(32)
            api.changes = listOf(DeltaItem("$id.md", type = 1, updatedTime = 50L))
            api.contents["$id.md"] = todoContent(id, "A faire", "corps", 50_000L)
            val sync = NotesSync(settings, notes, apiFactory = { api }, clock = { 90_000L })
            sync.syncNow()
            notes.save(id, "A faire vite", "corps modifié")
            api.changes = emptyList()

            sync.syncNow()

            val envoye = api.puts.getValue("$id.md")
            assertTrue(envoye.startsWith("A faire vite\n\ncorps modifié\n\n"))
            assertTrue(envoye.contains("is_todo: 1"))
            assertTrue(envoye.contains("todo_due: 1700000000000"))
            assertTrue(envoye.contains("author: Moi"))
            assertEquals(false, notes.getById(id)?.dirty)
        }

    @Test
    fun uneModificationPendantLEnvoiResteAEnvoyer() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            val api = FakeJoplinApi()
            val id = "2".repeat(32)
            notes.upsert(
                Note(id = id, title = "v1", body = "", parentId = "folder-1", userUpdatedTime = 1L, dirty = true),
            )
            // L'utilisateur tape pendant que v1 part au serveur.
            notes.beforeUpdate = { notes.save(id, "v2", "") }
            val sync = NotesSync(settings, notes, apiFactory = { api })

            sync.syncNow()

            assertTrue(api.puts.getValue("$id.md").startsWith("v1"))
            assertEquals("v2", notes.getById(id)?.title)
            assertEquals(true, notes.getById(id)?.dirty)
        }

    @Test
    fun unConflitGardeLesDeuxVersions() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            val api = FakeJoplinApi()
            val id = "3".repeat(32)
            notes.upsert(
                Note(
                    id = id,
                    title = "Note",
                    body = "version téléphone",
                    parentId = "folder-1",
                    userUpdatedTime = 60_000L,
                    dirty = true,
                    serverRaw = todoContent(id, "Note", "origine", 50_000L),
                    serverUpdatedTime = 50_000L,
                ),
            )
            api.changes = listOf(DeltaItem("$id.md", type = 1, updatedTime = 80L))
            api.contents["$id.md"] = todoContent(id, "Note", "version ordinateur", 80_000L)
            val sync = NotesSync(settings, notes, apiFactory = { api })

            val result = sync.syncNow()

            assertEquals(1, (result as SyncResult.Success).conflicts)
            assertEquals("version téléphone", notes.getById(id)?.body)
            val copie = notes.getAllActive().single { it.id != id }
            assertTrue(copie.title.startsWith("Note (conflit"))
            assertEquals("version ordinateur", copie.body)
            assertEquals(2, api.puts.size)
        }

    @Test
    fun laSynchroResteIncrementaleQuandRienNAChange() =
        runTest {
            val settings = configuredSettings()
            settings.setJoplinCursor("c0")
            val notes = FakeNotesRepository()
            notes.upsertFolders(listOf(Folder("f".repeat(32), "Carnet")))
            notes.upsert(
                Note(id = "4".repeat(32), title = "t", body = "", userUpdatedTime = 1L, serverUpdatedTime = 1L),
            )
            val api = FakeJoplinApi()
            val sync = NotesSync(settings, notes, apiFactory = { api })

            sync.syncNow()

            assertEquals(listOf<String?>("c0"), api.deltaCursors)
        }

    @Test
    fun uneNoteChiffreeEstIgnoree() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            val api = FakeJoplinApi()
            val id = "5".repeat(32)
            api.changes = listOf(DeltaItem("$id.md", type = 1, updatedTime = 50L))
            api.contents["$id.md"] = "\n\nid: $id\nencryption_applied: 1\nencryption_cipher_text: xyz\ntype_: 1\n"
            val sync = NotesSync(settings, notes, apiFactory = { api })

            val result = sync.syncNow()

            assertEquals(1, (result as SyncResult.Success).encryptedSkipped)
            assertNull(notes.getById(id))
        }

    @Test
    fun supprimerUneNoteSynchroniseeLaMetALaCorbeilleJoplin() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            val api = FakeJoplinApi()
            val id = "6".repeat(32)
            val folder = "f".repeat(32)
            api.changes = listOf(DeltaItem("$id.md", type = 1, updatedTime = 50L), DeltaItem("$folder.md", 1, 50L))
            api.contents["$id.md"] = todoContent(id, "A jeter", "", 50_000L)
            api.contents["$folder.md"] = folderContent(folder)
            val sync = NotesSync(settings, notes, apiFactory = { api }, clock = { 90_000L })
            sync.syncNow()
            assertEquals(listOf(folder to "Carnet"), notes.getFolders().map { it.id to it.title })
            notes.delete(id)
            api.changes = emptyList()

            sync.syncNow()

            assertTrue(api.deletes.isEmpty())
            assertTrue(api.puts.getValue("$id.md").contains("deleted_time: 90000"))
            assertNull(notes.getById(id))
        }

    @Test
    fun unCarnetCreeIciEstEnvoyeAvantSesNotes() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            val api = FakeJoplinApi()
            val folder = notes.createFolder("Projets")
            val note = notes.create()
            notes.save(note.id, "Idée", "texte")
            notes.setParent(note.id, folder.id)
            val sync = NotesSync(settings, notes, apiFactory = { api })

            sync.syncNow()

            assertEquals(listOf("put ${folder.id}.md", "put ${note.id}.md"), api.log)
            assertTrue(api.puts.getValue("${folder.id}.md").contains("type_: 2"))
            assertEquals(false, notes.getFolders().single().dirty)
        }

    @Test
    fun supprimerUnCarnetLeMetALaCorbeilleAvecSesNotesEtSousCarnets() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            val api = FakeJoplinApi()
            val folder = notes.createFolder("Vieux")
            val note = notes.create()
            notes.save(note.id, "Dedans", "texte")
            notes.setParent(note.id, folder.id)
            val sync = NotesSync(settings, notes, apiFactory = { api }, clock = { 90_000L })
            sync.syncNow()
            val sub = Folder("c".repeat(32), "Sous", parentId = folder.id)
            notes.upsertFolders(listOf(sub))
            api.log.clear()

            notes.deleteFolder(folder.id)
            assertTrue(notes.folders.first().isEmpty())
            assertTrue(notes.notes.first().isEmpty())
            val result = sync.syncNow()

            assertTrue(result is SyncResult.Success)
            assertTrue(api.puts.getValue("${folder.id}.md").contains("deleted_time: 90000"))
            assertTrue(api.puts.getValue("${note.id}.md").contains("deleted_time: 90000"))
            // Sous-carnet sans contenu serveur connu (404) : retiré ici, rien à envoyer.
            assertTrue(notes.getFolders().isEmpty())
            assertNull(notes.getById(note.id))
        }

    @Test
    fun supprimerUnCarnetJamaisEnvoyeNeContactePasLeServeur() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            val api = FakeJoplinApi()
            val folder = notes.createFolder("Brouillon")
            notes.deleteFolder(folder.id)
            val sync = NotesSync(settings, notes, apiFactory = { api })

            val result = sync.syncNow()

            assertTrue(result is SyncResult.Success)
            assertTrue(api.log.isEmpty())
            assertTrue(notes.getFolders().isEmpty())
        }

    @Test
    fun partagerPoseLeShareIdSurLeCarnetEtSesNotesAvantDInviter() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            val api = FakeJoplinApi()
            val folder = notes.createFolder("Famille")
            val note = notes.create()
            notes.save(note.id, "Courses", "pain")
            notes.setParent(note.id, folder.id)
            val sync = NotesSync(settings, notes, apiFactory = { api })
            sync.syncNow()
            api.log.clear()

            val result = sync.shareFolder(folder.id, "bob@lielu.eu")

            assertTrue(result.isSuccess)
            assertEquals(
                listOf("share ${folder.id}", "put ${folder.id}.md", "put ${note.id}.md", "invite s1 bob@lielu.eu"),
                api.log,
            )
            assertTrue(api.puts.getValue("${note.id}.md").contains("share_id: s1"))
            assertEquals("s1", notes.getFolders().single().shareId)
        }

    @Test
    fun uneNouvelleNoteDUnCarnetPartageRecoitSonShareId() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            val api = FakeJoplinApi()
            notes.upsertFolders(listOf(Folder("a".repeat(32), "Partagé", shareId = "s9")))
            val note = notes.create()
            notes.save(note.id, "Nouvelle", "")
            notes.setParent(note.id, "a".repeat(32))
            val sync = NotesSync(settings, notes, apiFactory = { api })

            sync.syncNow()

            assertTrue(api.puts.getValue("${note.id}.md").contains("share_id: s9"))
        }

    @Test
    fun unSousCarnetNePeutPasEtrePartage() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            notes.upsertFolders(listOf(Folder("b".repeat(32), "Sous", parentId = "a".repeat(32))))
            val sync = NotesSync(settings, notes, apiFactory = { FakeJoplinApi() })

            val result = sync.shareFolder("b".repeat(32), "bob@lielu.eu")

            assertTrue(result.exceptionOrNull()!!.message!!.contains("racine"))
        }

    @Test
    fun laSessionEstReutiliseeDUneSynchroALAutre() =
        runTest {
            val settings = configuredSettings()
            val notes = FakeNotesRepository()
            var created = 0
            val sync =
                NotesSync(settings, notes, apiFactory = {
                    created++
                    FakeJoplinApi()
                })

            sync.syncNow()
            sync.syncNow()

            assertEquals(1, created)
        }
}
