package com.lielu.todo.todo

import com.lielu.todo.data.api.ApiException
import com.lielu.todo.data.api.ApiResponse
import com.lielu.todo.data.api.HttpApi
import com.lielu.todo.data.model.Label
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
    private var nextId = 100L

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
                            JSONObject().put("id", 2).put("title", "Perso"),
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
            method == "DELETE" && path.startsWith("/tasks/") -> {
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
            else -> throw ApiException(404, "route inconnue $method $path")
        }
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
    ) = TodoRepository(server, cache, CoroutineScope(StandardTestDispatcher(testScheduler)))

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
}
