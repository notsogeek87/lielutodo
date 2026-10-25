package com.lielu.todo.data.todo

import com.lielu.todo.data.api.ApiException
import com.lielu.todo.data.api.HttpApi
import com.lielu.todo.data.model.Comment
import com.lielu.todo.data.model.Label
import com.lielu.todo.data.model.Project
import com.lielu.todo.data.model.Task
import com.lielu.todo.data.model.commentFromJson
import com.lielu.todo.data.model.labelFromJson
import com.lielu.todo.data.model.objects
import com.lielu.todo.data.model.projectFromJson
import com.lielu.todo.data.model.taskFromJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

/**
 * Source de vérité de l'app, « local d'abord » comme Todoist : chaque action modifie l'état local tout de suite
 * (l'écran réagit sans attendre le réseau), met une écriture en file, puis l'envoie dès que possible. Au
 * rafraîchissement, le serveur reprend la main — sauf s'il reste des écritures non envoyées, pour ne jamais les
 * écraser.
 */
@OptIn(FlowPreview::class)
class TodoRepository(
    private val api: HttpApi,
    private val cache: LocalCache,
    private val scope: CoroutineScope,
    private val onAuthExpired: () -> Unit = {},
) {
    private val _state = MutableStateFlow(cache.load() ?: TodoState())
    val state: StateFlow<TodoState> = _state

    private val syncLock = Mutex()
    private var nextTempId = -1L

    init {
        scope.launch(Dispatchers.IO) {
            _state.drop(1).debounce(SAVE_DELAY_MS).collect { runCatching { cache.save(it) } }
        }
    }

    // ---- Lecture ----

    fun task(id: Long): Task? = _state.value.tasks.firstOrNull { it.id == id }

    // ---- Écritures locales (envoyées ensuite) ----

    fun addTask(
        title: String,
        projectId: Long,
        due: Instant? = null,
        priority: Int = 0,
        labels: List<Label> = emptyList(),
        description: String = "",
    ): Long {
        val id = nextTempId--
        val raw = JSONObject().put("id", id).put("project_id", projectId).toString()
        val task =
            Task(
                id = id,
                projectId = projectId,
                title = title,
                description = description,
                done = false,
                dueDate = due,
                priority = priority,
                labels = labels,
                isFavorite = false,
                position = 0.0,
                commentCount = 0,
                raw = raw,
            )
        _state.update { it.copy(tasks = it.tasks + task, ops = it.ops + PendingOp(PendingOp.Kind.CREATE, id)) }
        flushSoon()
        return id
    }

    /** Remplace la tâche locale du même id ; [labelsChanged] = les étiquettes ont changé. */
    fun updateTask(
        task: Task,
        labelsChanged: Boolean = false,
    ) {
        _state.update { s ->
            val tasks = s.tasks.map { if (it.id == task.id) task else it }
            s.copy(tasks = tasks, ops = enqueueUpdate(s.ops, task.id, labelsChanged))
        }
        flushSoon()
    }

    fun setDone(
        id: Long,
        done: Boolean,
    ) {
        val task = task(id) ?: return
        updateTask(task.copy(done = done))
    }

    fun deleteTask(id: Long) {
        _state.update { s ->
            val tasks = s.tasks.filterNot { it.id == id }
            val ops =
                if (id < 0) {
                    // Jamais envoyée : on oublie simplement toutes ses écritures.
                    s.ops.filterNot { it.taskId == id }
                } else {
                    s.ops.filterNot { it.taskId == id } + PendingOp(PendingOp.Kind.DELETE, id)
                }
            s.copy(tasks = tasks, ops = ops)
        }
        flushSoon()
    }

    private fun enqueueUpdate(
        ops: List<PendingOp>,
        id: Long,
        labelsChanged: Boolean,
    ): List<PendingOp> {
        // Une tâche pas encore créée porte déjà tout dans son CREATE ; un UPDATE existant est fusionné.
        if (ops.any { it.kind == PendingOp.Kind.CREATE && it.taskId == id }) return ops
        val existing = ops.firstOrNull { it.kind == PendingOp.Kind.UPDATE && it.taskId == id }
        if (existing == null) return ops + PendingOp(PendingOp.Kind.UPDATE, id, labelsChanged)
        return ops.map { if (it === existing) it.copy(labelsChanged = it.labelsChanged || labelsChanged) else it }
    }

    // ---- Réseau ----

    private fun flushSoon() {
        scope.launch { syncLock.withLock { runCatching { flush() }.onFailure(::reportError) } }
    }

    /** Synchronisation complète : envoie les écritures en attente puis relit le serveur. */
    suspend fun refresh() {
        syncLock.withLock {
            _state.update { it.copy(syncing = true) }
            try {
                val clean = flush()
                val projects = fetchPaged("/projects", mapOf("is_archived" to "false")).map(::projectFromJson)
                val labels = fetchPaged("/labels").map(::labelFromJson)
                val defaultProject =
                    JSONObject(api.call("GET", "/user").body)
                        .optJSONObject("settings")
                        ?.optLong("default_project_id") ?: 0L
                val tasks =
                    if (clean) {
                        fetchPaged("/tasks", mapOf("filter" to "done = false", "sort_by" to "id")).map(::taskFromJson)
                    } else {
                        null
                    }
                _state.update {
                    it.copy(
                        projects = projects,
                        labels = labels,
                        tasks = tasks ?: it.tasks,
                        defaultProjectId = defaultProject,
                        error = null,
                        loaded = true,
                    )
                }
            } catch (e: IOException) {
                reportError(e)
                _state.update { it.copy(loaded = true) }
            } finally {
                _state.update { it.copy(syncing = false) }
            }
        }
    }

    private suspend fun fetchPaged(
        path: String,
        query: Map<String, String> = emptyMap(),
    ): List<JSONObject> {
        val all = mutableListOf<JSONObject>()
        var page = 1
        while (true) {
            val response =
                api.call(
                    "GET",
                    path,
                    query + mapOf("page" to page.toString(), "per_page" to PAGE_SIZE.toString()),
                )
            all += JSONArray(response.body).objects()
            val totalPages = response.headers["x-pagination-total-pages"]?.toIntOrNull() ?: 1
            if (page >= totalPages) return all
            page++
        }
    }

    /** Envoie la file dans l'ordre. `true` si elle est vide à la fin ; s'arrête au premier problème réseau. */
    private suspend fun flush(): Boolean {
        while (true) {
            val op = _state.value.ops.firstOrNull() ?: return true
            try {
                apply(op)
            } catch (e: ApiException) {
                if (e.code == HTTP_UNAUTHORIZED) throw e
                // Refus définitif (tâche supprimée côté serveur, droits…) : réessayer ne servirait à rien.
                reportError(e)
            } catch (e: IOException) {
                reportError(e)
                return false
            }
            _state.update { s -> s.copy(ops = s.ops.drop(1)) }
        }
    }

    private suspend fun apply(op: PendingOp) {
        when (op.kind) {
            PendingOp.Kind.CREATE -> {
                val local = task(op.taskId) ?: return
                val body = local.toRequestJson().also { it.remove("id") }
                val created =
                    taskFromJson(JSONObject(api.call("PUT", "/projects/${local.projectId}/tasks", body = body).body))
                if (local.labels.isNotEmpty()) setLabels(created.id, local.labels)
                _state.update { s ->
                    s.copy(
                        tasks =
                            s.tasks.map {
                                if (it.id ==
                                    local.id
                                ) {
                                    created.copy(labels = it.labels, done = it.done)
                                } else {
                                    it
                                }
                            },
                        ops =
                            s.ops.map { if (it.taskId == local.id) it.copy(taskId = created.id) else it },
                    )
                }
            }
            PendingOp.Kind.UPDATE -> {
                val local = task(op.taskId) ?: return
                val saved =
                    taskFromJson(JSONObject(api.call("POST", "/tasks/${local.id}", body = local.toRequestJson()).body))
                if (op.labelsChanged) setLabels(local.id, local.labels)
                // Une tâche répétée revient « non terminée » avec une nouvelle échéance : on prend la réponse.
                _state.update { s ->
                    s.copy(
                        tasks =
                            s.tasks.map {
                                if (it.id == local.id &&
                                    it == local
                                ) {
                                    saved.copy(labels = it.labels)
                                } else {
                                    it
                                }
                            },
                    )
                }
            }
            PendingOp.Kind.DELETE -> {
                try {
                    api.call("DELETE", "/tasks/${op.taskId}")
                } catch (e: ApiException) {
                    if (e.code != HTTP_NOT_FOUND) throw e
                }
            }
        }
    }

    private suspend fun setLabels(
        taskId: Long,
        labels: List<Label>,
    ) {
        val body = JSONObject().put("labels", JSONArray(labels.map { JSONObject().put("id", it.id) }))
        api.call("POST", "/tasks/$taskId/labels/bulk", body = body)
    }

    private fun reportError(e: Throwable) {
        if (e is ApiException && e.code == HTTP_UNAUTHORIZED) {
            onAuthExpired()
            return
        }
        _state.update { it.copy(error = if (e is IOException && e !is ApiException) "Hors ligne" else e.message) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // ---- Opérations en ligne uniquement (rares) ----

    suspend fun createProject(title: String): Project {
        val p = projectFromJson(JSONObject(api.call("PUT", "/projects", body = JSONObject().put("title", title)).body))
        _state.update { it.copy(projects = it.projects + p) }
        return p
    }

    suspend fun createLabel(title: String): Label {
        val l = labelFromJson(JSONObject(api.call("PUT", "/labels", body = JSONObject().put("title", title)).body))
        _state.update { it.copy(labels = it.labels + l) }
        return l
    }

    suspend fun comments(taskId: Long): List<Comment> =
        JSONArray(api.call("GET", "/tasks/$taskId/comments").body).objects().map(::commentFromJson)

    suspend fun addComment(
        taskId: Long,
        text: String,
    ) {
        api.call("PUT", "/tasks/$taskId/comments", body = JSONObject().put("comment", text))
        _state.update { s ->
            s.copy(tasks = s.tasks.map { if (it.id == taskId) it.copy(commentCount = it.commentCount + 1) else it })
        }
    }

    /** Déconnexion : le cache d'un compte ne doit jamais se retrouver chez le suivant. */
    fun reset() {
        _state.value = TodoState()
        runCatching { cache.save(TodoState()) }
    }

    private companion object {
        const val SAVE_DELAY_MS = 300L
        const val PAGE_SIZE = 50
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
    }
}
