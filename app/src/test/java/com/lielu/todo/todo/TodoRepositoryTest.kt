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
    private var nextId = 100L

    override suspend fun call(
        method: String,
        path: String,
        query: Map<String, String>,
        body: JSONObject?,
    ): ApiResponse {
        calls += "$method $path"
        if (!online) throw IOException("offline")
        val ok = { text: String -> ApiResponse(200, text, mapOf("x-pagination-total-pages" to "1")) }
        return when {
            method == "GET" && path == "/projects" ->
                ok("""[{"id":1,"title":"Boîte de réception"},{"id":2,"title":"Perso"}]""")
            method == "GET" && path == "/labels" -> ok("""[{"id":10,"title":"urgent"}]""")
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
}
