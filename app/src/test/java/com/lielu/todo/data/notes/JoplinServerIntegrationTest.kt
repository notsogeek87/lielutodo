package com.lielu.todo.data.notes

import com.lielu.todo.data.joplin.HttpJoplinApi
import com.lielu.todo.data.joplin.JoplinParser
import com.lielu.todo.testutil.FakeSettingsRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.Socket
import java.net.URI

/**
 * Synchro et partage contre un VRAI Joplin Server (ignoré sans serveur). Pour le lancer :
 * ```
 * docker run -d --name joplin --network host -e APP_BASE_URL=http://localhost:22300 -e APP_PORT=22300 \
 *   -e MAX_TIME_DRIFT=0 joplin/server:latest
 * JOPLIN_IT_URL=http://localhost:22300 JOPLIN_IT_USER1=admin@localhost JOPLIN_IT_PASS1=admin \
 *   JOPLIN_IT_USER2=bob@localhost JOPLIN_IT_PASS2=… ./gradlew testProductionDebugUnitTest \
 *   --tests "*JoplinServerIntegrationTest"
 * ```
 * (le 2e compte se crée dans l'admin du serveur).
 */
class JoplinServerIntegrationTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val url = System.getenv("JOPLIN_IT_URL").orEmpty()

    private inner class Device(
        email: String,
        password: String,
        name: String,
    ) {
        val settings = FakeSettingsRepository()
        val notes = FileNotesRepository(File(dir.root, "$name.json"))
        val api = HttpJoplinApi(url, email, password)
        val sync = NotesSync(settings, notes, apiFactory = { api })

        init {
            runBlocking {
                settings.setJoplinServerUrl(url)
                settings.setJoplinEmail(email)
                settings.setJoplinPassword(password)
                settings.setJoplinSyncEnabled(true)
            }
        }

        suspend fun syncOk(): SyncResult.Success {
            val result = sync.syncNow()
            assertTrue("synchro en échec : $result", result is SyncResult.Success)
            return result as SyncResult.Success
        }
    }

    /**
     * Le client Android sait faire PATCH (OkHttp intégré), pas HttpURLConnection sur la JVM des tests : l'acceptation
     * passe ici par une requête HTTP/1.1 écrite à la main (serveur local en http).
     */
    private fun acceptOnJvm(
        email: String,
        password: String,
        shareUserId: String,
    ) {
        val uri = URI(url)

        fun raw(
            method: String,
            path: String,
            body: String,
            auth: String?,
        ): String =
            Socket(uri.host, uri.port).use { socket ->
                val bytes = body.toByteArray()
                val head =
                    "$method $path HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\nContent-Type: application/json\r\n" +
                        (if (auth != null) "X-API-AUTH: $auth\r\n" else "") +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(head.toByteArray())
                    write(bytes)
                    flush()
                }
                socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
        val login =
            raw("POST", "/api/sessions", JSONObject().put("email", email).put("password", password).toString(), null)
        val token = JSONObject(login.substringAfter("\r\n\r\n")).getString("id")
        val response = raw("PATCH", "/api/share_users/$shareUserId", """{"status":1}""", token)
        assertTrue(response, response.startsWith("HTTP/1.1 200"))
    }

    @Test
    fun synchroEtPartageDeBoutEnBout() =
        runBlocking {
            assumeTrue("JOPLIN_IT_URL absent : test d'intégration ignoré", url.isNotBlank())
            val user1 = System.getenv("JOPLIN_IT_USER1")
            val pass1 = System.getenv("JOPLIN_IT_PASS1")
            val user2 = System.getenv("JOPLIN_IT_USER2")
            val pass2 = System.getenv("JOPLIN_IT_PASS2")
            val stamp = System.currentTimeMillis().toString()

            // 1. Téléphone : nouveau carnet + note, envoyés.
            val phone = Device(user1, pass1, "phone")
            phone.syncOk()
            val folder = phone.notes.createFolder("IT carnet $stamp")
            val note = phone.notes.create()
            phone.notes.save(note.id, "IT note $stamp", "- [ ] pain\n- [x] lait")
            phone.notes.setParent(note.id, folder.id)
            phone.syncOk()
            assertEquals(false, phone.notes.getById(note.id)!!.dirty)

            // 2. Un 2e appareil du même compte la reçoit telle quelle.
            val laptop = Device(user1, pass1, "laptop")
            laptop.syncOk()
            assertEquals("- [ ] pain\n- [x] lait", laptop.notes.getById(note.id)?.body)
            assertEquals(folder.id, laptop.notes.getById(note.id)?.parentId)
            assertTrue(laptop.notes.getFolders().any { it.id == folder.id && it.title == "IT carnet $stamp" })

            // 3. Modifiée sur le 2e appareil, reçue sur le téléphone sans conflit.
            laptop.notes.save(note.id, "IT note $stamp", "modifiée sur le portable")
            laptop.syncOk()
            val received = phone.syncOk()
            assertEquals(0, received.conflicts)
            assertEquals("modifiée sur le portable", phone.notes.getById(note.id)?.body)

            // 4. Une to-do Joplin modifiée ici reste une to-do (propriétés gardées).
            val todoId = "7" + stamp.padStart(31, '0').takeLast(31)
            val todoRaw =
                JoplinParser
                    .serialize(todoId, folder.id, "IT to-do", "faire", System.currentTimeMillis())
                    .replace("is_todo: 0", "is_todo: 1")
                    .replace("todo_due: 0", "todo_due: 1893456000000")
            laptop.api.putContent("$todoId.md", todoRaw)
            phone.syncOk()
            phone.notes.save(todoId, "IT to-do", "faire vite")
            phone.syncOk()
            val pushedTodo = laptop.api.itemContent("$todoId.md")
            assertTrue(pushedTodo.contains("faire vite"))
            assertTrue(pushedTodo.contains("is_todo: 1"))
            assertTrue(pushedTodo.contains("todo_due: 1893456000000"))

            // 5. Conflit : modifiée des deux côtés, les deux versions survivent.
            Thread.sleep(5)
            laptop.syncOk()
            laptop.notes.save(note.id, "IT note $stamp", "version portable")
            phone.notes.save(note.id, "IT note $stamp", "version téléphone")
            laptop.syncOk()
            val conflict = phone.syncOk()
            assertEquals(1, conflict.conflicts)
            assertTrue(laptop.api.itemContent("${note.id}.md").contains("version téléphone"))
            assertTrue(
                phone.notes.getAllActive().any { it.title.contains("(conflit") && it.body == "version portable" },
            )

            // 6. Partage du carnet avec un autre compte.
            assumeTrue("JOPLIN_IT_USER2 absent : partage non testé", !user2.isNullOrBlank())
            val share = phone.sync.shareFolder(folder.id, user2)
            assertTrue("partage : ${share.exceptionOrNull()}", share.isSuccess)
            val shareId =
                phone.notes
                    .getFolders()
                    .first { it.id == folder.id }
                    .shareId
            assertNotEquals("", shareId)
            assertTrue(laptop.api.itemContent("${note.id}.md").contains("share_id: $shareId"))

            val bob = Device(user2, pass2, "bob")
            val invitation =
                bob.sync
                    .pendingInvitations()
                    .getOrThrow()
                    .firstOrNull { it.folderId == folder.id }
            assertNotNull("invitation reçue", invitation)
            acceptOnJvm(user2, pass2, invitation!!.shareUserId)
            bob.syncOk()
            assertTrue(bob.notes.getFolders().any { it.id == folder.id && it.shareId == shareId })
            assertEquals("version téléphone", bob.notes.getById(note.id)?.body)

            val recipients = phone.sync.shareRecipients(folder.id).getOrThrow()
            assertTrue(recipients.any { it.email == user2 && it.status.name == "ACCEPTED" })

            // 7. Bob modifie la note partagée et en crée une : le propriétaire voit les deux.
            bob.notes.save(note.id, "IT note $stamp", "écrit par Bob")
            val bobNote = bob.notes.create()
            bob.notes.save(bobNote.id, "Note de Bob $stamp", "salut")
            bob.notes.setParent(bobNote.id, folder.id)
            bob.syncOk()
            assertTrue(bob.api.itemContent("${bobNote.id}.md").contains("share_id: $shareId"))
            var seen = false
            repeat(20) {
                if (!seen) {
                    phone.syncOk()
                    seen = phone.notes.getById(bobNote.id) != null
                    if (!seen) Thread.sleep(1_000)
                }
            }
            assertTrue("la note créée par Bob arrive chez le propriétaire", seen)
            assertEquals("écrit par Bob", phone.notes.getById(note.id)?.body)

            // 8. Suppression = corbeille Joplin.
            phone.notes.delete(todoId)
            phone.syncOk()
            assertTrue(!laptop.api.itemContent("$todoId.md").contains("deleted_time: 0\n"))

            // 9. Arrêt du partage.
            val stop = phone.sync.stopSharing(folder.id)
            assertTrue("arrêt : ${stop.exceptionOrNull()}", stop.isSuccess)
            assertEquals(
                "",
                phone.notes
                    .getFolders()
                    .first { it.id == folder.id }
                    .shareId,
            )
            assertTrue(laptop.api.itemContent("${note.id}.md").contains("share_id: \n"))
        }
}
