package com.lielu.todo.todo

import com.lielu.todo.data.api.ApiException
import com.lielu.todo.data.api.ApiResponse
import com.lielu.todo.data.api.HttpApi
import com.lielu.todo.data.model.Label
import com.lielu.todo.data.todo.AttachmentFiles
import com.lielu.todo.data.todo.LocalCache
import com.lielu.todo.data.todo.TodoRepository
import com.lielu.todo.data.todo.TodoState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Serveur Vikunja minimal en mémoire : juste les routes que l'app utilise. */
private class FakeServer : HttpApi {
    var online = true
    val tasks = mutableMapOf<Long, JSONObject>()
    val calls = mutableListOf<String>()
    var labelCalls = 0
    var serverError = false
    val created = mutableListOf<String>()
    private val extraProjects = mutableListOf<JSONObject>()
    private val extraLabels = mutableListOf<JSONObject>()
    val comments = mutableMapOf<Long, MutableList<String>>()
    val uploads = mutableListOf<String>()
    val labels = mutableMapOf<Long, JSONObject>()
    val unreadNotifications = mutableSetOf(1L, 2L)
    private var nextId = 100L

    override suspend fun upload(
        path: String,
        fileName: String,
        mime: String,
        bytes: ByteArray,
    ): ApiResponse {
        calls += "UPLOAD $path"
        if (!online) throw IOException("offline")
        uploads += "$fileName:$mime:${bytes.size}"
        return ApiResponse(200, "{}", emptyMap())
    }

    override suspend fun download(path: String): ByteArray {
        calls += "DOWNLOAD $path"
        if (!online) throw IOException("offline")
        return "contenu".toByteArray()
    }

    override suspend fun call(
        method: String,
        path: String,
        query: Map<String, String>,
        body: JSONObject?,
    ): ApiResponse {
        calls += "$method $path"
        if (!online) throw IOException("offline")
        if (serverError) throw ApiException(502, "Bad Gateway")
        val ok = { text: String -> ApiResponse(200, text, mapOf("x-pagination-total-pages" to "1")) }
        return when {
            method == "GET" && path == "/projects" ->
                ok(
                    JSONArray(
                        listOf(
                            JSONObject().put("id", 1).put("title", "Boîte de réception"),
                            JSONObject()
                                .put("id", 2)
                                .put("title", "Perso")
                                .put(
                                    "views",
                                    JSONArray(
                                        listOf(
                                            JSONObject().put("id", 20).put("view_kind", "list"),
                                            JSONObject().put("id", 21).put("view_kind", "kanban"),
                                        ),
                                    ),
                                ),
                        ) + extraProjects,
                    ).toString(),
                )
            method == "GET" && path == "/labels" ->
                ok(JSONArray(listOf(JSONObject().put("id", 10).put("title", "urgent")) + extraLabels).toString())
            method == "GET" && path == "/user" -> ok("""{"settings":{"default_project_id":1}}""")
            method == "GET" && path == "/tasks" ->
                ok(
                    JSONArray(tasks.values.filter { !it.getBoolean("done") }).toString(),
                )
            method == "PUT" && path.matches(Regex("/projects/\\d+/tasks")) -> {
                val task = JSONObject(body.toString()).put("id", nextId++).put("done", false)
                tasks[task.getLong("id")] = task
                ok(task.toString())
            }
            method == "POST" && path.matches(Regex("/tasks/\\d+")) -> {
                val id = path.substringAfterLast('/').toLong()
                if (!tasks.containsKey(id)) throw ApiException(404, "not found")
                tasks[id] = JSONObject(body.toString())
                ok(body.toString())
            }
            method == "POST" && path.endsWith("/labels/bulk") -> {
                labelCalls++
                ok("{}")
            }
            method == "DELETE" && path.matches(Regex("/tasks/\\d+")) -> {
                tasks.remove(path.substringAfterLast('/').toLong())
                ok("{}")
            }
            method == "PUT" && path == "/projects" -> {
                created += "project:" + body!!.getString("title")
                val project = JSONObject().put("id", 50).put("title", body.getString("title"))
                extraProjects += project
                ok(project.toString())
            }
            method == "PUT" && path == "/labels" -> {
                created += "label:" + body!!.getString("title")
                val label = JSONObject().put("id", 60).put("title", body.getString("title"))
                extraLabels += label
                ok(label.toString())
            }
            method == "PUT" && path.endsWith("/comments") -> {
                comments.getOrPut(path.split('/')[2].toLong()) { mutableListOf() } += body!!.getString("comment")
                ok("{}")
            }
            method == "GET" && path.endsWith("/comments") ->
                ok(
                    JSONArray(
                        comments[path.split('/')[2].toLong()].orEmpty().mapIndexed { i, c ->
                            JSONObject()
                                .put(
                                    "id",
                                    i + 1,
                                ).put("comment", c)
                                .put("author", JSONObject().put("name", "Moi"))
                        },
                    ).toString(),
                )
            method == "PUT" && path.endsWith("/relations") -> {
                created += "relation:" + path.split('/')[2] + ">" + body!!.getLong("other_task_id")
                ok("{}")
            }
            method == "PUT" && path.endsWith("/assignees") -> {
                created += "assign:" + path.split('/')[2] + ">" + body!!.getLong("user_id")
                ok("{}")
            }
            method == "POST" && path.matches(Regex("/tasks/\\d+/comments/\\d+")) -> {
                created += "comment-edit:" + path.split('/')[4] + ":" + body!!.getString("comment")
                ok("{}")
            }
            method == "DELETE" && path.matches(Regex("/tasks/\\d+/comments/\\d+")) -> {
                created += "comment-delete:" + path.split('/')[4]
                ok("{}")
            }
            method == "GET" && path.matches(Regex("/labels/\\d+")) ->
                ok(
                    labels
                        .getOrPut(path.substringAfterLast('/').toLong()) {
                            JSONObject().put("id", 10).put("title", "urgent").put("description", "garde-moi")
                        }.toString(),
                )
            method == "POST" && path.matches(Regex("/labels/\\d+")) -> {
                labels[path.substringAfterLast('/').toLong()] = JSONObject(body.toString())
                ok("{}")
            }
            method == "DELETE" && path.matches(Regex("/labels/\\d+")) -> {
                created += "label-delete:" + path.substringAfterLast('/')
                ok("{}")
            }
            method == "GET" && path == "/notifications" ->
                ok(
                    JSONArray(
                        listOf(1L, 2L, 3L).map { id ->
                            JSONObject()
                                .put("id", id)
                                .put("name", "task.assigned")
                                .put("read", id !in unreadNotifications)
                                .put(
                                    "notification",
                                    JSONObject()
                                        .put(
                                            "doer",
                                            JSONObject().put("username", "marie"),
                                        ).put("task", JSONObject().put("id", 5).put("title", "Rapport")),
                                )
                        },
                    ).toString(),
                )
            method == "POST" && path.startsWith("/notifications/") -> {
                unreadNotifications -= path.substringAfterLast('/').toLong()
                ok("{}")
            }
            method == "POST" && path == "/notifications" -> {
                unreadNotifications.clear()
                ok("{}")
            }
            method == "GET" && path.matches(Regex("/tasks/\\d+/attachments")) ->
                ok(
                    JSONArray(
                        listOf(
                            JSONObject()
                                .put(
                                    "id",
                                    900,
                                ).put(
                                    "file",
                                    JSONObject()
                                        .put(
                                            "name",
                                            "serveur.pdf",
                                        ).put("size", 12)
                                        .put("mime", "application/pdf"),
                                ),
                        ),
                    ).toString(),
                )
            method == "GET" && path == "/projects/2/views/20/tasks" -> ok(JSONArray(tasks.values.toList()).toString())
            method == "GET" && path == "/projects/2/views/21/tasks" ->
                ok(
                    JSONArray(
                        listOf(
                            JSONObject()
                                .put(
                                    "id",
                                    1,
                                ).put("title", "À faire")
                                .put("tasks", JSONArray(tasks.values.toList())),
                            JSONObject().put("id", 2).put("title", "Fait").put("tasks", JSONArray()),
                        ),
                    ).toString(),
                )
            method == "POST" && path.matches(Regex("/projects/\\d+/views/\\d+/buckets/\\d+/tasks")) -> {
                created += "bucket:" + path.split('/')[6] + ">" + body!!.getLong("task_id")
                ok("{}")
            }
            else -> throw ApiException(404, "route inconnue $method $path")
        }
    }
}

private class MemoryFiles : AttachmentFiles {
    val pending = mutableMapOf<String, ByteArray>()
    private val downloaded = mutableMapOf<String, java.io.File>()

    override fun stagePending(
        name: String,
        bytes: ByteArray,
    ): String =
        "pending-${pending.size}-$name".also {
            pending[it] =
                bytes
        }

    override fun readPending(path: String): ByteArray = pending[path] ?: throw IOException("manquant")

    override fun deletePending(path: String) {
        pending.remove(path)
    }

    override fun cached(
        attachmentId: Long,
        name: String,
    ) = downloaded["$attachmentId-$name"]

    override fun cache(
        attachmentId: Long,
        name: String,
        bytes: ByteArray,
    ): java.io.File =
        java.io.File.createTempFile("att", name).also {
            it.writeBytes(bytes)
            downloaded["$attachmentId-$name"] = it
        }
}

private class MemoryCache : LocalCache {
    var saved: TodoState? = null

    override fun load() = saved

    override fun save(state: TodoState) {
        saved = state
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TodoRepositoryTest {
    private fun TestScope.repo(
        server: FakeServer,
        cache: LocalCache = MemoryCache(),
        files: AttachmentFiles = MemoryFiles(),
    ) = TodoRepository(server, cache, files, CoroutineScope(StandardTestDispatcher(testScheduler)))

    @Test
    fun refreshLoadsProjectsLabelsAndTasks() =
        runTest {
            val server = FakeServer()
            server.tasks[5] = JSONObject("""{"id":5,"title":"Existante","project_id":2,"done":false,"priority":3}""")
            val repo = repo(server)
            repo.refresh()
            val s = repo.state.value
            assertEquals(listOf("Boîte de réception", "Perso"), s.projects.map { it.title })
            assertEquals(1L, s.inboxId)
            assertEquals(listOf("Existante"), s.tasks.map { it.title })
            assertTrue(s.loaded)
        }

    @Test
    fun addedTaskIsVisibleImmediatelyAndGetsItsServerId() =
        runTest {
            val server = FakeServer()
            val repo = repo(server)
            repo.refresh()
            val tempId = repo.addTask("Acheter du pain", 2, labels = listOf(Label(10, "urgent", "")))
            assertTrue(tempId < 0)
            assertEquals(1, repo.state.value.tasks.size)
            advanceUntilIdle()
            val s = repo.state.value
            assertEquals(0, s.pending)
            assertEquals(1, s.tasks.size)
            assertTrue(s.tasks.single().id > 0)
            assertEquals(1, server.labelCalls)
            assertEquals(
                "Acheter du pain",
                server.tasks.values
                    .single()
                    .getString("title"),
            )
        }

    @Test
    fun offlineEditsAreQueuedAndSentLater() =
        runTest {
            val server = FakeServer()
            val repo = repo(server)
            repo.refresh()
            server.online = false
            repo.addTask("Hors ligne", 2)
            advanceUntilIdle()
            assertEquals(1, repo.state.value.pending)
            assertEquals(
                "Hors ligne",
                repo.state.value.tasks
                    .single()
                    .title,
            )

            // Un rafraîchissement hors ligne ne doit rien écraser.
            repo.refresh()
            assertEquals(
                "Hors ligne",
                repo.state.value.tasks
                    .single()
                    .title,
            )

            server.online = true
            repo.refresh()
            assertEquals(0, repo.state.value.pending)
            assertEquals(
                listOf("Hors ligne"),
                repo.state.value.tasks
                    .map { it.title },
            )
            assertEquals(1, server.tasks.size)
        }

    @Test
    fun completingKeepsFieldsTheAppDoesNotManage() =
        runTest {
            val server = FakeServer()
            server.tasks[5] =
                JSONObject(
                    """{"id":5,"title":"Répétée","project_id":2,"done":false,"repeat_after":86400,"percent_done":0.5}""",
                )
            val repo = repo(server)
            repo.refresh()
            repo.setDone(5, true)
            advanceUntilIdle()
            val sent = server.tasks.getValue(5)
            assertTrue(sent.getBoolean("done"))
            assertEquals(86400, sent.getInt("repeat_after"))
            assertEquals(0.5, sent.getDouble("percent_done"), 0.0)
        }

    @Test
    fun deletingAnUnsentTaskNeverReachesTheServer() =
        runTest {
            val server = FakeServer()
            val repo = repo(server)
            repo.refresh()
            server.online = false
            val id = repo.addTask("Éphémère", 2)
            repo.deleteTask(id)
            advanceUntilIdle()
            assertEquals(0, repo.state.value.pending)
            assertTrue(
                repo.state.value.tasks
                    .isEmpty(),
            )
            server.online = true
            repo.refresh()
            assertFalse(server.calls.any { it.startsWith("DELETE") })
            assertTrue(server.tasks.isEmpty())
        }

    @Test
    fun stateSurvivesRestartThroughTheCache() =
        runTest {
            val server = FakeServer()
            val cache = MemoryCache()
            val first = repo(server, cache)
            first.refresh()
            server.online = false
            first.addTask("À garder", 2)
            advanceUntilIdle()
            cache.save(first.state.value)
            val second = repo(server, cache)
            assertEquals(
                "À garder",
                second.state.value.tasks
                    .single()
                    .title,
            )
            assertEquals(1, second.state.value.pending)
        }

    @Test
    fun temporaryIdsDoNotCollideAfterRestart() =
        runTest {
            val server = FakeServer()
            val cache = MemoryCache()
            val first = repo(server, cache)
            first.refresh()
            server.online = false
            val a = first.addTask("A", 2)
            advanceUntilIdle()
            cache.save(first.state.value)
            val second = repo(server, cache)
            val b = second.addTask("B", 2)
            assertTrue(a != b)
            assertEquals(
                2,
                second.state.value.tasks
                    .map { it.id }
                    .toSet()
                    .size,
            )
        }

    @Test
    fun everythingStaysReadableAndEditableWhenTheServerIsDown() =
        runTest {
            val server = FakeServer()
            server.tasks[5] = JSONObject("""{"id":5,"title":"Déjà là","project_id":2,"done":false}""")
            val cache = MemoryCache()
            val first = repo(server, cache)
            first.refresh()
            cache.save(first.state.value)

            server.serverError = true
            val second = repo(server, cache)
            second.refresh()
            val s = second.state.value
            assertTrue(s.offline)
            assertEquals(listOf("Déjà là"), s.tasks.map { it.title })
            assertEquals(listOf("Boîte de réception", "Perso"), s.projects.map { it.title })
            assertTrue(s.lastSync > 0)

            second.setDone(5, true)
            assertTrue(
                second.state.value.tasks
                    .single()
                    .done,
            )
            assertEquals(1, second.state.value.pending)
        }

    @Test
    fun projectLabelCommentAndSubtaskCreatedOfflineAreSentInOrder() =
        runTest {
            val server = FakeServer()
            val repo = repo(server)
            repo.refresh()
            server.online = false
            val projectId = repo.createProject("Voyage")
            val label = repo.createLabel("valise")
            val parent = repo.addTask("Préparer", projectId, labels = listOf(label))
            repo.addTask("Passeport", projectId, parentId = parent)
            repo.addComment(parent, "penser aux billets")
            advanceUntilIdle()
            assertTrue(repo.state.value.pending >= 5)
            assertTrue(
                repo.state.value.projects
                    .any { it.title == "Voyage" },
            )

            server.online = true
            repo.refresh()
            val s = repo.state.value
            assertEquals(0, s.pending)
            assertTrue(s.projects.any { it.id == 50L })
            assertTrue(s.tasks.all { it.projectId == 50L })
            assertTrue(s.tasks.flatMap { it.labels }.all { it.id == 60L })
            assertEquals(1, server.created.count { it.startsWith("relation:") })
            assertEquals(listOf("penser aux billets"), server.comments.values.single())
        }

    @Test
    fun assigningThenUnassigningBeforeSendingCancelsOut() =
        runTest {
            val server = FakeServer()
            server.tasks[5] = JSONObject("""{"id":5,"title":"T","project_id":2,"done":false}""")
            val repo = repo(server)
            repo.refresh()
            val user =
                com.lielu.todo.data.model
                    .User(7, "marie", "Marie")
            server.online = false
            repo.assign(5, user, true)
            repo.assign(5, user, false)
            assertEquals(0, repo.state.value.pending)
            assertTrue(
                repo.state.value.tasks
                    .single()
                    .assignees
                    .isEmpty(),
            )
        }

    @Test
    fun corruptedCacheFallsBackToTheBackupCopy() {
        val dir = createTempDir()
        val file = java.io.File(dir, "cache.json")
        val cache =
            com.lielu.todo.data.todo
                .FileLocalCache(file)
        val state =
            TodoState(
                tasks =
                    listOf(
                        com.lielu.todo.data.model
                            .taskFromJson(JSONObject("""{"id":1,"title":"Gardée","project_id":2}""")),
                    ),
                loaded = true,
            )
        cache.save(state)
        cache.save(state)
        file.writeText("{ pas du json")
        assertEquals(
            "Gardée",
            cache
                .load()!!
                .tasks
                .single()
                .title,
        )
        dir.deleteRecursively()
    }

    @Test
    fun backupSectionExportsAndRestoresTheLocalState() =
        runTest {
            val server = FakeServer()
            server.tasks[5] = JSONObject("""{"id":5,"title":"À sauver","project_id":2,"done":false}""")
            val repo = repo(server)
            repo.refresh()
            val section =
                com.lielu.todo.backup
                    .TodoBackupSection(repo)
            val exported = section.export()

            server.online = false
            repo.deleteTask(5)
            assertTrue(
                repo.state.value.tasks
                    .isEmpty(),
            )
            section.restore(exported)
            assertEquals(
                listOf("À sauver"),
                repo.state.value.tasks
                    .map { it.title },
            )
        }

    @Test
    fun attachmentAddedOfflineIsStagedThenUploaded() =
        runTest {
            val server = FakeServer()
            server.tasks[5] = JSONObject("""{"id":5,"title":"T","project_id":2,"done":false}""")
            val files = MemoryFiles()
            val repo = repo(server, files = files)
            repo.refresh()
            server.online = false
            repo.addAttachment(5, "photo.jpg", "image/jpeg", ByteArray(1000))
            advanceUntilIdle()
            assertEquals(1, repo.state.value.pending)
            assertEquals(
                listOf("photo.jpg"),
                repo.state.value.attachments[5]
                    .orEmpty()
                    .map { it.name },
            )
            assertEquals(1, files.pending.size)

            server.online = true
            repo.refresh()
            assertEquals(listOf("photo.jpg:image/jpeg:1000"), server.uploads)
            assertEquals(0, repo.state.value.pending)
            assertTrue(files.pending.isEmpty())
            // La liste du serveur remplace le marqueur local.
            assertEquals(
                listOf("serveur.pdf"),
                repo.state.value.attachments[5]
                    .orEmpty()
                    .map { it.name },
            )
        }

    @Test
    fun removingAnUnsentAttachmentNeverUploadsAndDownloadedFilesStayAvailableOffline() =
        runTest {
            val server = FakeServer()
            server.tasks[5] = JSONObject("""{"id":5,"title":"T","project_id":2,"done":false}""")
            val files = MemoryFiles()
            val repo = repo(server, files = files)
            repo.refresh()
            server.online = false
            repo.addAttachment(5, "a.txt", "text/plain", ByteArray(3))
            val local =
                repo.state.value.attachments
                    .getValue(5)
                    .single()
            repo.deleteAttachment(5, local)
            assertEquals(0, repo.state.value.pending)
            assertTrue(files.pending.isEmpty())

            server.online = true
            repo.refreshAttachments(5)
            val remote =
                repo.state.value.attachments
                    .getValue(5)
                    .single()
            val first = repo.openAttachment(5, remote)
            server.online = false
            assertEquals(first, repo.openAttachment(5, remote))
            assertEquals(1, server.calls.count { it.startsWith("DOWNLOAD") })
        }

    @Test
    fun commentsCanBeEditedAndDeletedOffline() =
        runTest {
            val server = FakeServer()
            server.tasks[5] = JSONObject("""{"id":5,"title":"T","project_id":2,"done":false}""")
            val repo = repo(server)
            repo.refresh()
            server.comments[5] = mutableListOf("du serveur")
            repo.refreshComments(5)
            val remote =
                repo.state.value.comments
                    .getValue(5)
                    .single()
            server.online = false
            repo.editComment(5, remote, "corrigé")
            repo.addComment(5, "brouillon")
            val draft =
                repo.state.value.comments
                    .getValue(5)
                    .last()
            repo.editComment(5, draft, "brouillon corrigé")
            assertEquals(
                listOf("corrigé", "brouillon corrigé"),
                repo.state.value.comments
                    .getValue(5)
                    .map { it.text },
            )

            server.online = true
            repo.refresh()
            assertEquals(0, repo.state.value.pending)
            assertTrue("comment-edit:${remote.id}:corrigé" in server.created)
            assertEquals(listOf("du serveur", "brouillon corrigé"), server.comments.getValue(5))

            repo.deleteComment(5, remote)
            repo.refresh()
            assertTrue("comment-delete:${remote.id}" in server.created)
        }

    @Test
    fun labelRenameKeepsServerDescriptionAndDeleteRemovesItFromTasks() =
        runTest {
            val server = FakeServer()
            server.tasks[5] =
                JSONObject("""{"id":5,"title":"T","project_id":2,"done":false,"labels":[{"id":10,"title":"urgent"}]}""")
            val repo = repo(server)
            repo.refresh()
            server.online = false
            repo.updateLabel(10, "important", "ff0000")
            assertEquals(
                "important",
                repo.state.value.tasks
                    .single()
                    .labels
                    .single()
                    .title,
            )
            server.online = true
            repo.refresh()
            val sent = server.labels.getValue(10)
            assertEquals("important", sent.getString("title"))
            assertEquals("ff0000", sent.getString("hex_color"))
            assertEquals("garde-moi", sent.getString("description"))

            repo.deleteLabel(10)
            assertTrue(
                repo.state.value.tasks
                    .single()
                    .labels
                    .isEmpty(),
            )
            repo.refresh()
            assertTrue("label-delete:10" in server.created)
        }

    @Test
    fun kanbanMoveAndNotificationReadAreQueuedAndSent() =
        runTest {
            val server = FakeServer()
            server.tasks[5] = JSONObject("""{"id":5,"title":"T","project_id":2,"done":false}""")
            val repo = repo(server)
            repo.refresh()
            repo.refreshNotifications()
            assertEquals(2, repo.state.value.unreadNotifications)
            assertEquals(
                "marie vous a assigné Rapport",
                repo.state.value.notifications
                    .first()
                    .text,
            )
            assertEquals(
                5L,
                repo.state.value.notifications
                    .first()
                    .taskId,
            )

            repo.refreshKanban(2)
            assertEquals(
                listOf("À faire", "Fait"),
                repo.state.value.kanban
                    .getValue(2)
                    .map { it.title },
            )
            assertEquals(
                listOf(5L),
                repo.state.value.kanban
                    .getValue(2)
                    .first()
                    .taskIds,
            )

            server.online = false
            repo.moveToBucket(2, 5, 2)
            assertEquals(
                listOf(5L),
                repo.state.value.kanban
                    .getValue(2)
                    .last()
                    .taskIds,
            )
            assertTrue(
                repo.state.value.kanban
                    .getValue(2)
                    .first()
                    .taskIds
                    .isEmpty(),
            )
            repo.markNotificationRead(1)
            assertEquals(1, repo.state.value.unreadNotifications)
            repo.markAllNotificationsRead()
            assertEquals(0, repo.state.value.unreadNotifications)
            server.online = true
            repo.refresh()
            assertEquals(0, repo.state.value.pending)
            assertTrue(server.unreadNotifications.isEmpty())
            assertTrue("bucket:2>5" in server.created)
        }
}
