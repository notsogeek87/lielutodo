package com.lielu.todo.data.todo

import com.lielu.todo.data.api.ApiException
import com.lielu.todo.data.api.HttpApi
import com.lielu.todo.data.model.Attachment
import com.lielu.todo.data.model.Comment
import com.lielu.todo.data.model.Label
import com.lielu.todo.data.model.Project
import com.lielu.todo.data.model.ProjectAccess
import com.lielu.todo.data.model.SavedFilter
import com.lielu.todo.data.model.Task
import com.lielu.todo.data.model.User
import com.lielu.todo.data.model.attachmentFromJson
import com.lielu.todo.data.model.commentFromJson
import com.lielu.todo.data.model.labelFromJson
import com.lielu.todo.data.model.lastUnreadableDate
import com.lielu.todo.data.model.notificationFromJson
import com.lielu.todo.data.model.objects
import com.lielu.todo.data.model.projectFromJson
import com.lielu.todo.data.model.resetUnreadableDate
import com.lielu.todo.data.model.taskFromJson
import com.lielu.todo.data.model.textToHtml
import com.lielu.todo.data.model.userFromJson
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
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Source de vérité de l'app, « local d'abord » comme Todoist : chaque action modifie l'état local tout de suite
 * (l'écran réagit sans attendre le réseau), met une écriture en file, puis l'envoie dès que possible. Le serveur
 * étant un serveur personnel qui peut être coupé, TOUT reste lisible et modifiable sans lui : tâches, projets,
 * étiquettes, commentaires déjà vus, résultats des filtres, et même la création de projets et d'étiquettes.
 * Au rafraîchissement, le serveur reprend la main — sauf s'il reste des écritures non envoyées, pour ne jamais
 * les écraser.
 */
@OptIn(FlowPreview::class)
class TodoRepository(
    private val api: HttpApi,
    private val cache: LocalCache,
    private val files: AttachmentFiles,
    private val scope: CoroutineScope,
    private val onAuthExpired: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    /** Appelé quand une tâche vient d'être terminée (son de complétion). */
    private val onCompleted: () -> Unit = {},
) {
    private val _state = MutableStateFlow(cache.load() ?: TodoState())
    val state: StateFlow<TodoState> = _state

    private val syncLock = Mutex()
    private var positionsSyncedAt = 0L

    // Les ids temporaires (objets créés hors ligne) sont négatifs. On repart sous le plus bas déjà utilisé : après
    // un redémarrage, un compteur remis à -1 réattribuerait l'id d'une tâche encore en attente d'envoi.
    private var nextTempId = (lowestTempId(_state.value) - 1).coerceAtMost(-1)

    init {
        scope.launch(Dispatchers.IO) {
            _state.drop(1).debounce(SAVE_DELAY_MS).collect { saveNow() }
        }
    }

    private fun lowestTempId(s: TodoState): Long =
        (
            s.tasks.map { it.id } + s.projects.map { it.id } + s.labels.map { it.id } +
                (s.ops + s.failed.map { it.op }).flatMap { listOf(it.taskId, it.otherId) }
        ).minOrNull()?.coerceAtMost(0) ?: 0

    /** Écrit le cache tout de suite (arrêt de l'app, rappel reçu) au lieu d'attendre la temporisation. */
    fun saveNow() {
        runCatching { cache.save(_state.value) }
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
        parentId: Long = 0,
        repeatAfter: Long = 0,
        repeatMode: Int = 0,
    ): Long {
        val id = nextTempId--
        val raw = JSONObject().put("id", id).put("project_id", projectId).toString()
        _state.update { s ->
            val last = s.tasks.filter { it.projectId == projectId }.maxOfOrNull { it.position } ?: 0.0
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
                    position = last + POSITION_STEP,
                    commentCount = 0,
                    raw = raw,
                    repeatAfter = repeatAfter,
                    repeatMode = repeatMode,
                    parentId = parentId,
                )
            var ops = enqueue(s.ops, PendingOp(PendingOp.Kind.CREATE, id))
            if (parentId != 0L) ops = enqueue(ops, PendingOp(PendingOp.Kind.RELATE, id, otherId = parentId))
            s.copy(tasks = s.tasks + task, ops = ops)
        }
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
            s.copy(tasks = tasks, ops = enqueue(s.ops, PendingOp(PendingOp.Kind.UPDATE, task.id, flag = labelsChanged)))
        }
        flushSoon()
    }

    fun setDone(
        id: Long,
        done: Boolean,
    ) {
        val task = task(id) ?: return
        updateTask(task.copy(done = done, doneAt = if (done) Instant.ofEpochMilli(clock()) else null))
        if (done && !task.done) onCompleted()
    }

    fun deleteTask(id: Long) {
        (_state.value.ops + _state.value.failed.map { it.op })
            .filter { it.kind == PendingOp.Kind.ATTACH && it.taskId == id }
            .forEach { runCatching { files.deletePending(it.text) } }
        _state.update { s ->
            val tasks = s.tasks.filterNot { it.id == id }
            val related = { op: PendingOp ->
                op.kind in TASK_KINDS &&
                    (op.taskId == id || (op.kind == PendingOp.Kind.RELATE && op.otherId == id))
            }
            // Une écriture refusée sur une tâche supprimée n'a plus d'objet.
            val failed = s.failed.filterNot { related(it.op) }
            val ops =
                if (id < 0) {
                    // Jamais envoyée : on oublie simplement toutes ses écritures.
                    s.ops.filterNot(related)
                } else {
                    s.ops.filterNot(related) + PendingOp(PendingOp.Kind.DELETE, id)
                }
            s.copy(tasks = tasks, ops = ops, failed = failed)
        }
        flushSoon()
    }

    /** Range une tâche à une nouvelle [position] (ordre manuel de la liste de son projet). */
    fun reorder(
        id: Long,
        position: Double,
    ) {
        val task = task(id) ?: return
        _state.update { s ->
            s.copy(
                tasks = s.tasks.map { if (it.id == id) it.copy(position = position) else it },
                ops = enqueue(s.ops, PendingOp(PendingOp.Kind.POSITION, task.id)),
            )
        }
        flushSoon()
    }

    fun assign(
        taskId: Long,
        user: User,
        add: Boolean,
    ) {
        _state.update { s ->
            val tasks =
                s.tasks.map { t ->
                    if (t.id != taskId) {
                        t
                    } else if (add) {
                        t.copy(assignees = t.assignees.filter { it.id != user.id } + user)
                    } else {
                        t.copy(assignees = t.assignees.filter { it.id != user.id })
                    }
                }
            s.copy(tasks = tasks, ops = enqueue(s.ops, PendingOp(PendingOp.Kind.ASSIGN, taskId, user.id, flag = add)))
        }
        flushSoon()
    }

    /** Crée un projet, même hors ligne (id temporaire, remplacé à l'envoi). Renvoie l'id local. */
    fun createProject(
        title: String,
        hexColor: String = "",
    ): Long {
        val id = nextTempId--
        _state.update { s ->
            val position = (s.projects.maxOfOrNull { it.position } ?: 0.0) + POSITION_STEP
            val project = Project(id, title, 0, hexColor, false, false, position)
            s.copy(
                projects = s.projects + project,
                ops = enqueue(s.ops, PendingOp(PendingOp.Kind.CREATE_PROJECT, id, text = title, extra = hexColor)),
            )
        }
        flushSoon()
        return id
    }

    /** Crée une étiquette, même hors ligne. Renvoie l'étiquette locale. */
    fun createLabel(
        title: String,
        hexColor: String = "",
    ): Label {
        val label = Label(nextTempId--, title, hexColor)
        _state.update { s ->
            s.copy(
                labels = s.labels + label,
                ops = enqueue(s.ops, PendingOp(PendingOp.Kind.CREATE_LABEL, label.id, text = title, extra = hexColor)),
            )
        }
        flushSoon()
        return label
    }

    fun addComment(
        taskId: Long,
        text: String,
    ) {
        _state.update { s ->
            val local = Comment(nextTempId--, text, "Moi", Instant.ofEpochMilli(clock()))
            s.copy(
                comments = s.comments + (taskId to (s.comments[taskId].orEmpty() + local)),
                tasks = s.tasks.map { if (it.id == taskId) it.copy(commentCount = it.commentCount + 1) else it },
                ops = enqueue(s.ops, PendingOp(PendingOp.Kind.COMMENT, taskId, text = text)),
            )
        }
        flushSoon()
    }

    private fun enqueue(
        ops: List<PendingOp>,
        op: PendingOp,
    ): List<PendingOp> {
        val sameTask = { o: PendingOp -> o.kind == op.kind && o.taskId == op.taskId }
        return when (op.kind) {
            PendingOp.Kind.UPDATE -> {
                // Une tâche pas encore créée porte déjà tout dans son CREATE ; un UPDATE existant est fusionné.
                if (ops.any { it.kind == PendingOp.Kind.CREATE && it.taskId == op.taskId }) return ops
                val existing = ops.firstOrNull(sameTask) ?: return ops + op
                ops.map { if (it === existing) it.copy(flag = it.flag || op.flag) else it }
            }
            PendingOp.Kind.POSITION -> if (ops.any(sameTask)) ops else ops + op
            PendingOp.Kind.MOVE_BUCKET -> ops.filterNot(sameTask) + op
            PendingOp.Kind.LABEL_UPDATE -> ops.filterNot(sameTask) + op
            PendingOp.Kind.COMMENT_EDIT -> ops.filterNot { sameTask(it) && it.otherId == op.otherId } + op
            PendingOp.Kind.MARK_READ -> if (ops.any(sameTask)) ops else ops + op
            PendingOp.Kind.MARK_ALL_READ -> if (ops.any { it.kind == op.kind }) ops else ops + op
            PendingOp.Kind.ASSIGN -> {
                val opposite = ops.firstOrNull { sameTask(it) && it.otherId == op.otherId && it.flag != op.flag }
                // Ajouter puis retirer le même utilisateur avant envoi ne laisse rien à envoyer.
                if (opposite != null) ops.filterNot { it === opposite } else ops + op
            }
            else -> ops + op
        }
    }

    // ---- Réseau ----

    private fun flushSoon() {
        scope.launch { syncPending() }
    }

    /** Envoie les écritures en attente sans relire le serveur. `true` si la file est vide ensuite. */
    suspend fun syncPending(): Boolean =
        syncLock.withLock {
            try {
                flush()
            } catch (e: IOException) {
                reportError(e)
                false
            }
        }

    /** Synchronisation complète : envoie les écritures en attente puis relit le serveur. */
    suspend fun refresh() {
        syncLock.withLock {
            _state.update { it.copy(syncing = true) }
            try {
                val clean = flush()
                pull(clean)
                _state.update { it.copy(loaded = true, offline = false, lastSync = clock()) }
            } catch (e: IOException) {
                reportError(e)
                _state.update { it.copy(loaded = true) }
            } finally {
                _state.update { it.copy(syncing = false) }
            }
        }
    }

    private suspend fun pull(clean: Boolean) {
        resetUnreadableDate()
        val projectJson = fetchPaged("/projects", mapOf("is_archived" to "false"))
        val projects = projectJson.filter { it.getLong("id") > 0 }.map(::projectFromJson)
        val labels = fetchPaged("/labels").map(::labelFromJson)
        val me = JSONObject(api.call("GET", "/user").body)
        val defaultProject = me.optJSONObject("settings")?.optLong("default_project_id") ?: 0L
        val userId = me.optLong("id")

        // Les filtres enregistrés apparaissent dans /projects avec un id négatif : -(id du filtre) - 1.
        val filters =
            fetchFilters(projectJson.map { it.getLong("id") to it.optString("title") }.filter { it.first < -1 })

        var tasks: List<Task>? = null
        if (clean) {
            val open = fetchPaged("/tasks", mapOf("filter" to "done = false", "sort_by" to "id")).map(::taskFromJson)
            // L'historique récent reste consultable ; s'il échoue (filtre non supporté), on garde l'ancien.
            val recentDone =
                runCatching {
                    fetchPaged("/tasks", mapOf("filter" to "done = true && done_at > now-30d", "sort_by" to "id"))
                        .map(::taskFromJson)
                }.getOrElse { _state.value.tasks.filter { it.done && it.id > 0 } }
            val openIds = open.map { it.id }.toSet()
            tasks = withPositions(open + recentDone.filter { it.id !in openIds }, projects)
        }
        val results = if (clean) fetchFilterResults(filters) else null
        tasks?.let(::diagnoseDates)
        pullExtras(clean)
        _state.update {
            it.copy(
                projects = mergeProjects(projects, it),
                labels = mergeLabels(labels, it),
                tasks = tasks?.let { server -> mergeTasks(server, it) } ?: it.tasks,
                filters = filters,
                filterResults = results ?: it.filterResults,
                defaultProjectId = defaultProject,
                userId = userId,
            )
        }
    }

    /**
     * La liste du serveur, sauf ce que des écritures non envoyées (en file ou refusées) contredisent : on garde la
     * version locale des tâches qu'elles touchent (dont celles créées ici, encore sans id serveur) et on écarte
     * celles supprimées ici. Vaut aussi pour une écriture faite PENDANT la lecture du serveur.
     */
    private fun mergeTasks(
        server: List<Task>,
        s: TodoState,
    ): List<Task> {
        val writes = (s.ops + s.failed.map { it.op }).filter { it.kind in TASK_FIELD_KINDS }
        val deleted = writes.filter { it.kind == PendingOp.Kind.DELETE }.map { it.taskId }.toSet()
        val touched = writes.map { it.taskId }.toSet()
        val local = s.tasks.filter { it.id in touched }
        val localIds = local.map { it.id }.toSet()
        return server.filter { it.id !in deleted && it.id !in localIds } + local
    }

    /** Projets créés ici et pas encore acceptés par le serveur : gardés, sinon leurs tâches n'auraient plus de projet. */
    private fun mergeProjects(
        server: List<Project>,
        s: TodoState,
    ): List<Project> {
        val unsent = unsentIds(s, PendingOp.Kind.CREATE_PROJECT)
        return server + s.projects.filter { it.id < 0 && it.id in unsent }
    }

    /** Comme [mergeProjects], et une étiquette renommée ou supprimée ici garde sa version locale. */
    private fun mergeLabels(
        server: List<Label>,
        s: TodoState,
    ): List<Label> {
        val created = unsentIds(s, PendingOp.Kind.CREATE_LABEL)
        val renamed = unsentIds(s, PendingOp.Kind.LABEL_UPDATE)
        val deleted = unsentIds(s, PendingOp.Kind.LABEL_DELETE)
        val local = s.labels.filter { (it.id < 0 && it.id in created) || it.id in renamed }
        val localIds = local.map { it.id }.toSet()
        return server.filter { it.id !in deleted && it.id !in localIds } + local
    }

    private fun unsentIds(
        s: TodoState,
        kind: PendingOp.Kind,
    ): Set<Long> = (s.ops + s.failed.map { it.op }).filter { it.kind == kind }.map { it.taskId }.toSet()

    /** Des tâches sans date alors que le serveur en envoie : on le dit, avec la valeur reçue, pour pouvoir corriger. */
    private fun diagnoseDates(tasks: List<Task>) {
        val bad = lastUnreadableDate
        val missingKey = tasks.isNotEmpty() && tasks.none { it.raw.contains("\"due_date\"") }
        val message =
            when {
                bad != null -> "Date d'échéance illisible reçue du serveur : « $bad »"
                missingKey -> "Le serveur n'a envoyé aucune date d'échéance"
                else -> return
            }
        _state.update { it.copy(error = message) }
    }

    private suspend fun fetchFilters(pseudoProjects: List<Pair<Long, String>>): List<SavedFilter> =
        pseudoProjects.mapNotNull { (pseudoId, title) ->
            val id = -pseudoId - 1
            try {
                val o = JSONObject(api.call("GET", "/filters/$id").body)
                val f = o.optJSONObject("filters")
                SavedFilter(
                    id,
                    o.optString("title", title),
                    f?.optString("filter").orEmpty(),
                    f?.optBoolean("filter_include_nulls") ?: false,
                )
            } catch (e: ApiException) {
                _state.value.filters.firstOrNull { it.id == id }
            }
        }

    private suspend fun fetchFilterResults(filters: List<SavedFilter>): Map<Long, List<Task>> =
        filters.associate { f ->
            f.id to
                try {
                    fetchPaged(
                        "/tasks",
                        mapOf("filter" to f.query, "filter_include_nulls" to f.includeNulls.toString()),
                    ).map(::taskFromJson)
                } catch (e: ApiException) {
                    _state.value.filterResults[f.id].orEmpty()
                }
        }

    /**
     * `/tasks` ne donne pas l'ordre manuel : celui-ci vit dans la vue « liste » de chaque projet. On le relit au
     * plus toutes les [POSITIONS_EVERY_MS], et une erreur sur un projet n'empêche pas les autres.
     */
    private suspend fun withPositions(
        tasks: List<Task>,
        projects: List<Project>,
    ): List<Task> {
        val now = clock()
        if (now - positionsSyncedAt < POSITIONS_EVERY_MS) {
            val known = _state.value.tasks.associate { it.id to it.position }
            return tasks.map { t -> known[t.id]?.let { t.copy(position = it) } ?: t }
        }
        val positions = mutableMapOf<Long, Double>()
        var complete = true
        projects.filter { it.listViewId > 0 }.forEach { p ->
            try {
                fetchPaged("/projects/${p.id}/views/${p.listViewId}/tasks", mapOf("filter" to "done = false"))
                    .forEach { positions[it.getLong("id")] = it.optDouble("position", 0.0) }
            } catch (e: ApiException) {
                complete = false
            }
        }
        if (complete) positionsSyncedAt = now
        return tasks.map { t -> positions[t.id]?.let { t.copy(position = it) } ?: t }
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

    /**
     * Envoie la file dans l'ordre. `true` si elle est vide à la fin ; s'arrête au premier problème réseau.
     *
     * Une écriture que le serveur refuse ne doit ni disparaître (c'est une saisie de l'utilisateur) ni bloquer
     * les suivantes : elle part dans [TodoState.failed]. Refus définitif (4xx) : tout de suite. Erreur 500 : le
     * serveur peut être en panne (on attend) ou planter sur CETTE écriture seulement ; on ne la met de côté que
     * s'il répond au reste, après [MAX_SERVER_ERRORS] essais.
     */
    private suspend fun flush(): Boolean {
        while (true) {
            val op = _state.value.ops.firstOrNull() ?: return true
            try {
                apply(op)
            } catch (e: ApiException) {
                when {
                    e.code == HTTP_UNAUTHORIZED -> throw e
                    // Serveur coupé (502/503/504 derrière le proxy), surchargé ou lent : on garde pour plus tard.
                    e.code in TRANSIENT_CODES -> {
                        reportError(e)
                        return false
                    }
                    e.code >= HTTP_SERVER_ERROR -> {
                        if (!serverAnswers()) {
                            reportError(e)
                            return false
                        }
                        if (op.attempts + 1 < MAX_SERVER_ERRORS) {
                            _state.update { s ->
                                s.copy(
                                    ops =
                                        s.ops.mapIndexed { i, o ->
                                            if (i ==
                                                0
                                            ) {
                                                o.copy(attempts = o.attempts + 1)
                                            } else {
                                                o
                                            }
                                        },
                                    error = "Le serveur refuse une modification (${e.message}), nouvel essai plus tard",
                                )
                            }
                            return false
                        }
                        setAside(e)
                    }
                    else -> setAside(e)
                }
                continue
            } catch (e: IOException) {
                reportError(e)
                return false
            }
            _state.update { s -> s.copy(ops = s.ops.drop(1)) }
        }
    }

    /** Le serveur répond-il à une lecture simple ? Distingue une panne générale d'un refus de l'écriture en tête. */
    private suspend fun serverAnswers(): Boolean =
        try {
            api.call("GET", "/user")
            true
        } catch (e: IOException) {
            false
        }

    /** Sort l'écriture en tête de file (dans sa version à jour : ids remappés) et la garde avec la raison du refus. */
    private fun setAside(e: ApiException) {
        _state.update { s ->
            val head = s.ops.firstOrNull() ?: return@update s
            val failed = FailedOp(head.copy(attempts = 0), e.code, e.message.orEmpty(), clock())
            s.copy(
                ops = s.ops.drop(1),
                failed = s.failed + failed,
                error = "Modification refusée par le serveur (${e.message}) : à revoir dans Parcourir",
            )
        }
    }

    // ---- Écritures refusées : renvoyer ou abandonner ----

    /** Remet une écriture refusée en file (à la fin), par exemple après correction côté serveur. */
    fun retryFailed(failed: FailedOp) {
        _state.update { s ->
            if (failed !in s.failed) return@update s
            s.copy(failed = s.failed - failed, ops = enqueue(s.ops, failed.op))
        }
        flushSoon()
    }

    fun retryAllFailed() {
        _state.update { s -> s.copy(failed = emptyList(), ops = s.failed.fold(s.ops) { ops, f -> enqueue(ops, f.op) }) }
        flushSoon()
    }

    /**
     * Renonce à une écriture refusée. Ce qu'elle avait créé localement disparaît (une tâche dont la création est
     * abandonnée, un commentaire, un fichier en attente) ; les tâches d'un projet abandonné passent dans la boîte
     * de réception plutôt que d'être perdues. Le reste reprend la version du serveur au prochain rafraîchissement.
     */
    fun abandonFailed(failed: FailedOp) {
        if (failed !in _state.value.failed) return
        _state.update { it.copy(failed = it.failed - failed) }
        val op = failed.op
        when (op.kind) {
            PendingOp.Kind.CREATE -> if (op.taskId < 0) deleteTask(op.taskId)
            PendingOp.Kind.CREATE_LABEL -> if (op.taskId < 0) deleteLabel(op.taskId)
            PendingOp.Kind.CREATE_PROJECT ->
                _state.update { s ->
                    val inbox = s.inboxId.takeIf { it != op.taskId } ?: s.projects.firstOrNull { it.id > 0 }?.id ?: 0
                    s.copy(
                        projects = s.projects.filter { it.id != op.taskId },
                        tasks = s.tasks.map { if (it.projectId == op.taskId) it.copy(projectId = inbox) else it },
                    )
                }
            PendingOp.Kind.COMMENT ->
                _state.update { s ->
                    val list = s.comments[op.taskId].orEmpty()
                    val local = list.firstOrNull { it.id < 0 && it.text == op.text } ?: return@update s
                    s.copy(
                        comments = s.comments + (op.taskId to list.filter { it !== local }),
                        tasks =
                            s.tasks.map {
                                if (it.id ==
                                    op.taskId
                                ) {
                                    it.copy(commentCount = (it.commentCount - 1).coerceAtLeast(0))
                                } else {
                                    it
                                }
                            },
                    )
                }
            PendingOp.Kind.ATTACH -> {
                runCatching { files.deletePending(op.text) }
                dropPlaceholder(op)
            }
            else -> Unit
        }
        scope.launch { refresh() }
    }

    private suspend fun apply(op: PendingOp) {
        when (op.kind) {
            // Id déjà positif : la tâche a été créée, seule la suite (étiquettes) a échoué. La recréer la doublerait.
            PendingOp.Kind.CREATE -> if (op.taskId > 0) applyUpdate(op.copy(flag = true)) else applyCreate(op)
            PendingOp.Kind.UPDATE -> applyUpdate(op)
            PendingOp.Kind.DELETE -> {
                try {
                    api.call("DELETE", "/tasks/${op.taskId}")
                } catch (e: ApiException) {
                    if (e.code != HTTP_NOT_FOUND) throw e
                }
            }
            PendingOp.Kind.CREATE_PROJECT -> {
                val created =
                    projectFromJson(
                        JSONObject(
                            api
                                .call(
                                    "PUT",
                                    "/projects",
                                    body =
                                        JSONObject().put("title", op.text).also {
                                            if (op.extra.isNotEmpty()) it.put("hex_color", op.extra.removePrefix("#"))
                                        },
                                ).body,
                        ),
                    )
                _state.update { s ->
                    s.copy(
                        projects = s.projects.map { if (it.id == op.taskId) created else it },
                        tasks = s.tasks.map { if (it.projectId == op.taskId) it.copy(projectId = created.id) else it },
                    )
                }
            }
            PendingOp.Kind.CREATE_LABEL -> {
                val created =
                    labelFromJson(
                        JSONObject(
                            api
                                .call(
                                    "PUT",
                                    "/labels",
                                    body =
                                        JSONObject()
                                            .put(
                                                "title",
                                                op.text,
                                            ).put("hex_color", op.extra.removePrefix("#")),
                                ).body,
                        ),
                    )
                _state.update { s ->
                    s.copy(
                        labels = s.labels.map { if (it.id == op.taskId) created else it },
                        tasks =
                            s.tasks.map { t ->
                                if (t.labels.any { it.id == op.taskId }) {
                                    t.copy(
                                        labels =
                                            t.labels.map {
                                                if (it.id ==
                                                    op.taskId
                                                ) {
                                                    created
                                                } else {
                                                    it
                                                }
                                            },
                                    )
                                } else {
                                    t
                                }
                            },
                    )
                }
            }
            PendingOp.Kind.COMMENT -> {
                api.call("PUT", "/tasks/${op.taskId}/comments", body = JSONObject().put("comment", textToHtml(op.text)))
                // Le commentaire local temporaire est remplacé par la vraie liste, au prochain affichage.
                _state.update { s ->
                    s.copy(comments = s.comments + (op.taskId to s.comments[op.taskId].orEmpty().filter { it.id > 0 }))
                }
            }
            PendingOp.Kind.RELATE -> {
                val body = JSONObject().put("other_task_id", op.otherId).put("relation_kind", "parenttask")
                api.call("PUT", "/tasks/${op.taskId}/relations", body = body)
            }
            PendingOp.Kind.ASSIGN ->
                if (op.flag) {
                    api.call("PUT", "/tasks/${op.taskId}/assignees", body = JSONObject().put("user_id", op.otherId))
                } else {
                    try {
                        api.call("DELETE", "/tasks/${op.taskId}/assignees/${op.otherId}")
                    } catch (e: ApiException) {
                        if (e.code != HTTP_NOT_FOUND) throw e
                    }
                }
            PendingOp.Kind.ATTACH -> applyAttach(op)
            PendingOp.Kind.DELETE_ATTACHMENT ->
                ignoringMissing { api.call("DELETE", "/tasks/${op.taskId}/attachments/${op.otherId}") }
            PendingOp.Kind.COMMENT_EDIT ->
                api.call(
                    "POST",
                    "/tasks/${op.taskId}/comments/${op.otherId}",
                    body = JSONObject().put("comment", textToHtml(op.text)),
                )
            PendingOp.Kind.COMMENT_DELETE ->
                ignoringMissing { api.call("DELETE", "/tasks/${op.taskId}/comments/${op.otherId}") }
            PendingOp.Kind.LABEL_UPDATE -> {
                // Relire la version du serveur : la mise à jour remplace tout, y compris la description.
                val json = JSONObject(api.call("GET", "/labels/${op.taskId}").body)
                json.put("title", op.text)
                if (op.extra.isNotEmpty()) json.put("hex_color", op.extra.removePrefix("#"))
                api.call("POST", "/labels/${op.taskId}", body = json)
            }
            PendingOp.Kind.LABEL_DELETE -> ignoringMissing { api.call("DELETE", "/labels/${op.taskId}") }
            // Ancien déplacement Kanban resté en file avant le retrait de la vue : envoyé une dernière fois.
            PendingOp.Kind.MOVE_BUCKET -> {
                val (project, view) = op.extra.split(':').let { it[0] to it[1] }
                val body =
                    JSONObject()
                        .put("task_id", op.taskId)
                        .put("bucket_id", op.otherId)
                        .put("project_view_id", view.toLong())
                api.call("POST", "/projects/$project/views/$view/buckets/${op.otherId}/tasks", body = body)
            }
            PendingOp.Kind.MARK_READ ->
                api.call("POST", "/notifications/${op.taskId}", body = JSONObject().put("read", true))
            PendingOp.Kind.MARK_ALL_READ -> api.call("POST", "/notifications", body = JSONObject())
            PendingOp.Kind.POSITION -> {
                val task = task(op.taskId) ?: return
                val view =
                    _state.value.projects
                        .firstOrNull { it.id == task.projectId }
                        ?.listViewId ?: 0
                if (view == 0L) return
                val body = JSONObject().put("position", task.position).put("project_view_id", view)
                api.call("POST", "/tasks/${task.id}/position", body = body)
            }
        }
    }

    private suspend fun ignoringMissing(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: ApiException) {
            if (e.code != HTTP_NOT_FOUND) throw e
        }
    }

    private suspend fun applyAttach(op: PendingOp) {
        val (name, mime) =
            op.extra
                .split(
                    '\n',
                    limit = 2,
                ).let { it[0] to it.getOrElse(1) { "application/octet-stream" } }
        val bytes =
            try {
                files.readPending(op.text)
            } catch (e: IOException) {
                // Fichier en attente disparu (stockage vidé) : rien à envoyer, on oublie cette écriture.
                dropPlaceholder(op)
                return
            }
        api.upload("/tasks/${op.taskId}/attachments", name, mime, bytes)
        runCatching { files.deletePending(op.text) }
        dropPlaceholder(op)
        // La vraie liste remplace l'ancienne ; si le serveur ne répond plus, la prochaine ouverture la relira.
        try {
            refreshAttachments(op.taskId)
        } catch (e: IOException) {
            // Sans importance : l'envoi lui-même a réussi.
        }
    }

    private fun dropPlaceholder(op: PendingOp) {
        _state.update { s ->
            s.copy(
                attachments =
                    s.attachments + (op.taskId to s.attachments[op.taskId].orEmpty().filter { it.id != op.otherId }),
            )
        }
    }

    /**
     * Notifications : utiles mais secondaires, une erreur ici ne doit jamais faire échouer la
     * synchronisation des tâches.
     */
    private suspend fun pullExtras(clean: Boolean) {
        if (clean) {
            runCatching {
                JSONArray(api.call("GET", "/notifications", mapOf("per_page" to "50")).body)
                    .objects()
                    .map(::notificationFromJson)
            }.getOrNull()?.let { list -> _state.update { it.copy(notifications = list) } }
        }
    }

    private suspend fun applyCreate(op: PendingOp) {
        val local = task(op.taskId) ?: return
        val body = local.toRequestJson().also { it.remove("id") }
        val created = taskFromJson(JSONObject(api.call("PUT", "/projects/${local.projectId}/tasks", body = body).body))
        // Remapper AVANT les étiquettes : si elles échouent, la tâche existe déjà sur le serveur sous son vrai id.
        _state.update { s ->
            s.copy(
                tasks =
                    s.tasks.map {
                        when {
                            it.id == local.id ->
                                it.copy(
                                    id = created.id,
                                    raw = created.raw,
                                    commentCount = created.commentCount,
                                )
                            it.parentId == local.id -> it.copy(parentId = created.id)
                            else -> it
                        }
                    },
                ops =
                    s.ops.map { o ->
                        o.copy(
                            taskId =
                                if (o.taskId ==
                                    local.id
                                ) {
                                    created.id
                                } else {
                                    o.taskId
                                },
                            otherId =
                                if (o.otherId ==
                                    local.id
                                ) {
                                    created.id
                                } else {
                                    o.otherId
                                },
                        )
                    },
                // Une écriture refusée peut viser cette tâche (commentaire posé avant sa création) : même remappage.
                failed =
                    s.failed.map { f ->
                        val o = f.op
                        f.copy(
                            op =
                                o.copy(
                                    taskId = if (o.taskId == local.id) created.id else o.taskId,
                                    otherId = if (o.otherId == local.id) created.id else o.otherId,
                                ),
                        )
                    },
                comments = s.comments.mapKeys { if (it.key == local.id) created.id else it.key },
                attachments = s.attachments.mapKeys { if (it.key == local.id) created.id else it.key },
            )
        }
        if (local.labels.isNotEmpty()) setLabels(created.id, local.labels)
    }

    private suspend fun applyUpdate(op: PendingOp) {
        val local = task(op.taskId) ?: return
        val saved = taskFromJson(JSONObject(api.call("POST", "/tasks/${local.id}", body = local.toRequestJson()).body))
        if (op.flag) setLabels(local.id, local.labels)
        restoreAssignees(local, saved)
        // Une tâche répétée revient « non terminée » avec une nouvelle échéance : on prend la réponse, sauf si
        // l'utilisateur a encore modifié la tâche pendant l'envoi.
        _state.update { s ->
            s.copy(
                tasks =
                    s.tasks.map {
                        if (it.id == local.id && it == local) {
                            saved.copy(
                                labels = it.labels,
                                assignees = it.assignees,
                                parentId = it.parentId,
                                position = it.position,
                            )
                        } else {
                            it
                        }
                    },
            )
        }
    }

    /**
     * Vikunja efface les affectations d'une tâche mise à jour sans champ `assignees` (on ne l'envoie pas, voir
     * `READ_ONLY_KEYS`) : on remet celles que la réponse ne contient plus, sauf celles qu'une écriture ASSIGN en
     * attente enverra de toute façon. Un échec (déjà affecté) est sans importance.
     */
    private suspend fun restoreAssignees(
        local: Task,
        saved: Task,
    ) {
        val kept = saved.assignees.map { it.id }.toSet()
        val queued = _state.value.ops.filter { it.kind == PendingOp.Kind.ASSIGN && it.taskId == local.id }
        local.assignees
            .filter { u -> u.id !in kept && queued.none { it.otherId == u.id } }
            .forEach { u ->
                try {
                    api.call("PUT", "/tasks/${local.id}/assignees", body = JSONObject().put("user_id", u.id))
                } catch (_: ApiException) {
                    // déjà affecté ou refusé : la prochaine synchronisation relira l'état du serveur
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
        val unreachable = (e is IOException && e !is ApiException) || (e is ApiException && e.code >= HTTP_SERVER_ERROR)
        _state.update {
            it.copy(
                error = if (unreachable) "Serveur injoignable" else e.message,
                offline = it.offline || unreachable,
            )
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // ---- Lectures en ligne qui alimentent le cache ----

    /** Relit les commentaires d'une tâche et les garde en cache ; [IOException] si le serveur ne répond pas. */
    suspend fun refreshComments(taskId: Long) {
        if (taskId < 0) return
        val fromServer = JSONArray(api.call("GET", "/tasks/$taskId/comments").body).objects().map(::commentFromJson)
        _state.update { s ->
            // Les commentaires écrits hors ligne et pas encore envoyés restent visibles à la suite.
            val unsent =
                (s.ops + s.failed.map { it.op })
                    .filter { it.kind == PendingOp.Kind.COMMENT && it.taskId == taskId }
                    .map { it.text }
            val local = s.comments[taskId].orEmpty().filter { it.id < 0 && it.text in unsent }
            s.copy(comments = s.comments + (taskId to fromServer + local))
        }
    }

    /** Utilisateurs ayant accès au projet (pour l'assignation) ; gardés en cache pour le hors ligne. */
    suspend fun refreshProjectUsers(projectId: Long) {
        if (projectId < 0) return
        val users = JSONArray(api.call("GET", "/projects/$projectId/projectusers").body).objects().map(::userFromJson)
        _state.update { it.copy(projectUsers = it.projectUsers + (projectId to users)) }
    }

    // ---- Opérations en ligne uniquement (rares) ----

    /** Utilisateurs et équipes ayant accès au projet, avec leurs droits (le serveur doit répondre). */
    suspend fun projectAccess(projectId: Long): List<ProjectAccess> {
        val users =
            JSONArray(api.call("GET", "/projects/$projectId/users").body).objects().map {
                ProjectAccess(userFromJson(it).display, it.optInt("permission"))
            }
        val teams =
            JSONArray(api.call("GET", "/projects/$projectId/teams").body).objects().map {
                ProjectAccess(it.optString("name"), it.optInt("permission"), isTeam = true)
            }
        return users + teams
    }

    suspend fun createFilter(
        title: String,
        query: String,
    ) {
        val body =
            JSONObject()
                .put("title", title)
                .put("filters", JSONObject().put("filter", query).put("filter_include_nulls", false))
        api.call("PUT", "/filters", body = body)
        refresh()
    }

    /**
     * Donne accès à un projet. [userOrEmail] : pseudo, ou adresse e-mail (résolue en pseudo via la recherche
     * d'utilisateurs du serveur). [permission] : 0 lecture, 1 lecture et écriture, 2 administrateur.
     * Renvoie le pseudo avec lequel le projet a été partagé.
     */
    suspend fun shareProject(
        projectId: Long,
        userOrEmail: String,
        permission: Int,
    ): String {
        try {
            val username = if ('@' in userOrEmail) resolveEmail(userOrEmail) else userOrEmail
            api.call(
                "PUT",
                "/projects/$projectId/users",
                body = JSONObject().put("username", username).put("permission", permission),
            )
            // Le partage a réussi : un échec de ce rafraîchissement annexe ne doit pas le faire passer pour raté.
            runCatching { refreshProjectUsers(projectId) }
            return username
        } catch (e: com.lielu.todo.data.api.ApiException) {
            throw IOException("${e.message} (HTTP ${e.code}, partage de « $userOrEmail » sur le projet $projectId)", e)
        } catch (e: org.json.JSONException) {
            throw IOException("réponse inattendue du serveur (${e.message})", e)
        } catch (e: RuntimeException) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw IOException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    private suspend fun resolveEmail(email: String): String {
        // Sans résultat, Vikunja renvoie `null` (ou rien) au lieu de `[]`.
        val response = api.call("GET", "/users", query = mapOf("s" to email))
        val body = response.body.trim()
        val found = if (body.startsWith("[")) JSONArray(body).objects() else emptyList()
        val match =
            found.firstOrNull { it.optString("email").equals(email, ignoreCase = true) }
                ?: found.singleOrNull()
                ?: throw IOException(
                    "aucun utilisateur trouvé pour $email : son compte n'est pas visible par e-mail, " +
                        "saisissez son pseudo Vikunja à la place.\n" +
                        "Détail : GET /users?s=… -> HTTP ${response.code}, ${found.size} résultat(s)" +
                        found.joinToString(prefix = " [", postfix = "]") { it.optString("username") } +
                        ", réponse brute : ${body.take(DIAGNOSTIC_BODY_MAX).ifEmpty { "(vide)" }}",
                )
        return match.optString("username").ifBlank { throw IOException("utilisateur sans pseudo pour $email") }
    }

    // ---- Pièces jointes ----

    /** Ajoute un fichier à une tâche, même hors ligne : copié sur le téléphone, envoyé dès que possible. */
    fun addAttachment(
        taskId: Long,
        name: String,
        mime: String,
        bytes: ByteArray,
    ) {
        val path = files.stagePending(name, bytes)
        val placeholder = Attachment(nextTempId--, name, bytes.size.toLong(), mime, Instant.ofEpochMilli(clock()))
        _state.update { s ->
            val op =
                PendingOp(PendingOp.Kind.ATTACH, taskId, otherId = placeholder.id, text = path, extra = "$name\n$mime")
            s.copy(
                attachments = s.attachments + (taskId to s.attachments[taskId].orEmpty() + placeholder),
                ops = enqueue(s.ops, op),
            )
        }
        flushSoon()
    }

    fun deleteAttachment(
        taskId: Long,
        attachment: Attachment,
    ) {
        val isUpload = { op: PendingOp -> op.kind == PendingOp.Kind.ATTACH && op.otherId == attachment.id }
        if (attachment.id < 0) {
            (_state.value.ops + _state.value.failed.map { it.op })
                .firstOrNull(isUpload)
                ?.let { runCatching { files.deletePending(it.text) } }
        }
        _state.update { s ->
            val remaining = s.attachments[taskId].orEmpty().filter { it.id != attachment.id }
            val ops =
                if (attachment.id < 0) {
                    // Jamais envoyé : on oublie simplement l'envoi.
                    s.ops.filterNot(isUpload)
                } else {
                    enqueue(s.ops, PendingOp(PendingOp.Kind.DELETE_ATTACHMENT, taskId, otherId = attachment.id))
                }
            s.copy(
                attachments = s.attachments + (taskId to remaining),
                ops = ops,
                failed = s.failed.filterNot { isUpload(it.op) },
            )
        }
        flushSoon()
    }

    /** Relit la liste des fichiers d'une tâche ; ceux pas encore envoyés restent affichés à la suite. */
    suspend fun refreshAttachments(taskId: Long) {
        if (taskId < 0) return
        val list = JSONArray(api.call("GET", "/tasks/$taskId/attachments").body).objects().map(::attachmentFromJson)
        _state.update { s ->
            val unsent = s.attachments[taskId].orEmpty().filter { it.id < 0 }
            s.copy(attachments = s.attachments + (taskId to list + unsent))
        }
    }

    /**
     * Le fichier à ouvrir : celui déjà téléchargé (donc lisible serveur coupé), sinon on le télécharge. Un fichier
     * pas encore envoyé s'ouvre depuis sa copie locale.
     */
    suspend fun openAttachment(
        taskId: Long,
        attachment: Attachment,
    ): File {
        files.cached(attachment.id, attachment.name)?.let { return it }
        val bytes =
            if (attachment.id < 0) {
                val staged =
                    (_state.value.ops + _state.value.failed.map { it.op })
                        .firstOrNull { it.kind == PendingOp.Kind.ATTACH && it.otherId == attachment.id }
                        ?: throw IOException("Fichier introuvable")
                files.readPending(staged.text)
            } else {
                api.download("/tasks/$taskId/attachments/${attachment.id}")
            }
        return files.cache(attachment.id, attachment.name, bytes)
    }

    // ---- Commentaires : modifier, supprimer ----

    fun editComment(
        taskId: Long,
        comment: Comment,
        text: String,
    ) {
        _state.update { s ->
            val local = s.comments[taskId].orEmpty().map { if (it.id == comment.id) it.copy(text = text) else it }
            // Pas encore envoyé : on change le texte de son envoi (en file, ou refusé par le serveur).
            var done = false
            val retext = { op: PendingOp ->
                if (!done &&
                    comment.id < 0 &&
                    op.kind == PendingOp.Kind.COMMENT &&
                    op.taskId == taskId &&
                    op.text == comment.text
                ) {
                    done = true
                    op.copy(text = text)
                } else {
                    op
                }
            }
            val ops =
                if (comment.id < 0) {
                    s.ops.map(retext)
                } else {
                    enqueue(s.ops, PendingOp(PendingOp.Kind.COMMENT_EDIT, taskId, otherId = comment.id, text = text))
                }
            val failed = s.failed.map { it.copy(op = retext(it.op)) }
            s.copy(comments = s.comments + (taskId to local), ops = ops, failed = failed)
        }
        flushSoon()
    }

    fun deleteComment(
        taskId: Long,
        comment: Comment,
    ) {
        _state.update { s ->
            val local = s.comments[taskId].orEmpty().filter { it.id != comment.id }
            // Pas encore envoyé : on oublie son envoi, qu'il soit en file ou refusé par le serveur.
            var removed = false
            val isItsSending = { op: PendingOp ->
                val match =
                    !removed &&
                        comment.id < 0 &&
                        op.kind == PendingOp.Kind.COMMENT &&
                        op.taskId == taskId &&
                        op.text == comment.text
                if (match) removed = true
                match
            }
            val ops =
                if (comment.id < 0) {
                    s.ops.filterNot(isItsSending)
                } else {
                    enqueue(s.ops, PendingOp(PendingOp.Kind.COMMENT_DELETE, taskId, otherId = comment.id))
                }
            val failed = s.failed.filterNot { isItsSending(it.op) }
            s.copy(
                failed = failed,
                comments = s.comments + (taskId to local),
                tasks =
                    s.tasks.map {
                        if (it.id ==
                            taskId
                        ) {
                            it.copy(commentCount = (it.commentCount - 1).coerceAtLeast(0))
                        } else {
                            it
                        }
                    },
                ops = ops,
            )
        }
        flushSoon()
    }

    // ---- Étiquettes : modifier, supprimer ----

    fun updateLabel(
        id: Long,
        title: String,
        hexColor: String,
    ) {
        _state.update { s ->
            val updated = { l: Label -> if (l.id == id) l.copy(title = title, hexColor = hexColor) else l }
            val retitle = { op: PendingOp ->
                if (id < 0 && op.kind == PendingOp.Kind.CREATE_LABEL && op.taskId == id) {
                    op.copy(text = title, extra = hexColor)
                } else {
                    op
                }
            }
            val ops =
                if (id < 0) {
                    // Étiquette pas encore créée : sa création porte déjà le bon titre et la bonne couleur.
                    s.ops.map(retitle)
                } else {
                    enqueue(s.ops, PendingOp(PendingOp.Kind.LABEL_UPDATE, id, text = title, extra = hexColor))
                }
            s.copy(
                failed = s.failed.map { it.copy(op = retitle(it.op)) },
                labels = s.labels.map(updated),
                tasks =
                    s.tasks.map { t ->
                        if (t.labels.any { it.id == id }) t.copy(labels = t.labels.map(updated)) else t
                    },
                ops = ops,
            )
        }
        flushSoon()
    }

    fun deleteLabel(id: Long) {
        _state.update { s ->
            // Ses créations et renommages, en file ou refusés, n'ont plus d'objet.
            val obsolete = { op: PendingOp ->
                op.taskId == id && (op.kind == PendingOp.Kind.CREATE_LABEL || op.kind == PendingOp.Kind.LABEL_UPDATE)
            }
            val ops =
                if (id < 0) {
                    s.ops.filterNot(obsolete)
                } else {
                    s.ops.filterNot(obsolete) + PendingOp(PendingOp.Kind.LABEL_DELETE, id)
                }
            s.copy(
                failed = s.failed.filterNot { obsolete(it.op) },
                labels = s.labels.filter { it.id != id },
                tasks =
                    s.tasks.map { t ->
                        if (t.labels.any { it.id == id }) {
                            t.copy(
                                labels =
                                    t.labels.filter {
                                        it.id !=
                                            id
                                    },
                            )
                        } else {
                            t
                        }
                    },
                ops = ops,
            )
        }
        flushSoon()
    }

    // ---- Notifications ----

    /** Relit les notifications ; [IOException] si le serveur ne répond pas (les anciennes restent affichées). */
    suspend fun refreshNotifications() {
        val list =
            JSONArray(
                api.call("GET", "/notifications", mapOf("per_page" to "50")).body,
            ).objects().map(::notificationFromJson)
        _state.update { it.copy(notifications = list) }
    }

    fun markNotificationRead(id: Long) {
        _state.update { s ->
            s.copy(
                notifications = s.notifications.map { if (it.id == id) it.copy(read = true) else it },
                ops = enqueue(s.ops, PendingOp(PendingOp.Kind.MARK_READ, id)),
            )
        }
        flushSoon()
    }

    fun markAllNotificationsRead() {
        _state.update { s ->
            s.copy(
                notifications = s.notifications.map { it.copy(read = true) },
                ops = enqueue(s.ops, PendingOp(PendingOp.Kind.MARK_ALL_READ, 0)),
            )
        }
        flushSoon()
    }

    // ---- Filtres et projets (en ligne : le serveur doit valider) ----

    suspend fun updateFilter(
        id: Long,
        title: String,
        query: String,
    ) {
        val json = JSONObject(api.call("GET", "/filters/$id").body)
        json.put("title", title)
        val filters = json.optJSONObject("filters") ?: JSONObject().also { json.put("filters", it) }
        filters.put("filter", query)
        api.call("POST", "/filters/$id", body = json)
        refresh()
    }

    suspend fun deleteFilter(id: Long) {
        api.call("DELETE", "/filters/$id")
        _state.update { s -> s.copy(filters = s.filters.filter { it.id != id }, filterResults = s.filterResults - id) }
        refresh()
    }

    /** Place le projet [id] sous [parentId] (0 = à la racine). */
    suspend fun moveProject(
        id: Long,
        parentId: Long,
    ) = updateProject(id) { it.put("parent_project_id", parentId) }

    /** Monte ou descend un projet parmi ses frères, en échangeant leurs positions. */
    suspend fun moveProjectOrder(
        id: Long,
        up: Boolean,
    ) {
        val me = _state.value.projects.firstOrNull { it.id == id } ?: return
        val siblings =
            _state.value.projects
                .filter { it.parentId == me.parentId }
                .sortedWith(compareBy({ it.position }, { it.id }))
        val index = siblings.indexOfFirst { it.id == id }
        val other = siblings.getOrNull(if (up) index - 1 else index + 1) ?: return
        // Positions confondues : on les écarte, sinon l'échange ne changerait rien.
        val (mine, theirs) =
            if (other.position == me.position) {
                if (up) (other.position - 1) to other.position else (other.position + 1) to other.position
            } else {
                other.position to me.position
            }
        updateProject(id) { it.put("position", mine) }
        updateProject(other.id) { it.put("position", theirs) }
    }

    /**
     * Reporte à [today] les tâches en retard, en gardant l'heure de chacune (comme « Reporter » dans Todoist).
     * Renvoie le nombre de tâches déplacées.
     */
    fun rescheduleOverdue(
        today: java.time.LocalDate,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Int {
        val overdue =
            _state.value.tasks.filter { t ->
                !t.done &&
                    t.dueDate != null &&
                    t.dueDate
                        .atZone(zone)
                        .toLocalDate()
                        .isBefore(today)
            }
        overdue.forEach { t ->
            val time = t.dueDate!!.atZone(zone).toLocalTime()
            updateTask(t.copy(dueDate = today.atTime(time).atZone(zone).toInstant()))
        }
        return overdue.size
    }

    /** Copie une tâche (même projet, mêmes réglages, non terminée). Renvoie l'id local de la copie. */
    fun duplicateTask(id: Long): Long? {
        val t = task(id) ?: return null
        return addTask(
            title = t.title,
            projectId = t.projectId,
            due = t.dueDate,
            priority = t.priority,
            labels = t.labels,
            description = t.description,
            parentId = t.parentId,
            repeatAfter = t.repeatAfter,
            repeatMode = t.repeatMode,
        )
    }

    // ---- Gestion d'un projet (en ligne : rare, et le serveur doit valider) ----

    /** Modifie un projet : relit sa version du serveur (pour ne rien effacer), applique [change], renvoie. */
    suspend fun updateProject(
        id: Long,
        change: (JSONObject) -> Unit,
    ) {
        val json = JSONObject(api.call("GET", "/projects/$id").body)
        change(json)
        api.call("POST", "/projects/$id", body = json)
        refresh()
    }

    /** Supprime un projet ET ses tâches côté serveur. */
    suspend fun deleteProject(id: Long) {
        api.call("DELETE", "/projects/$id")
        _state.update { s ->
            s.copy(
                projects = s.projects.filter { it.id != id },
                tasks =
                    s.tasks.filter {
                        it.projectId !=
                            id
                    },
            )
        }
        refresh()
    }

    /** Remplace tout l'état local (restauration d'une sauvegarde), puis tente de resynchroniser. */
    fun restore(restored: TodoState) {
        _state.value = restored.copy(syncing = false, error = null, loaded = true)
        saveNow()
        flushSoon()
    }

    /** Déconnexion : le cache d'un compte ne doit jamais se retrouver chez le suivant. */
    fun reset() {
        _state.value = TodoState()
        runCatching { cache.save(TodoState()) }
    }

    private companion object {
        const val SAVE_DELAY_MS = 150L
        const val PAGE_SIZE = 50
        const val DIAGNOSTIC_BODY_MAX = 300
        const val POSITION_STEP = 65536.0
        const val POSITIONS_EVERY_MS = 5 * 60 * 1000L
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val HTTP_SERVER_ERROR = 500
        const val MAX_SERVER_ERRORS = 3

        /** Panne passagère : réessayer plus tard sans compter d'échec. 408 délai, 429 trop de requêtes, 502-504 proxy. */
        val TRANSIENT_CODES = setOf(408, 429, 502, 503, 504)

        /** Écritures qui changent les champs de la tâche elle-même : sa version locale prime sur celle du serveur. */
        val TASK_FIELD_KINDS =
            setOf(
                PendingOp.Kind.CREATE,
                PendingOp.Kind.UPDATE,
                PendingOp.Kind.DELETE,
                PendingOp.Kind.POSITION,
                PendingOp.Kind.ASSIGN,
                PendingOp.Kind.RELATE,
                PendingOp.Kind.MOVE_BUCKET,
            )

        /** Écritures dont [PendingOp.taskId] est une tâche (pas une étiquette, un projet ou une notification). */
        val TASK_KINDS =
            setOf(
                PendingOp.Kind.CREATE,
                PendingOp.Kind.UPDATE,
                PendingOp.Kind.DELETE,
                PendingOp.Kind.POSITION,
                PendingOp.Kind.ASSIGN,
                PendingOp.Kind.RELATE,
                PendingOp.Kind.MOVE_BUCKET,
                PendingOp.Kind.COMMENT,
                PendingOp.Kind.ATTACH,
                PendingOp.Kind.DELETE_ATTACHMENT,
                PendingOp.Kind.COMMENT_EDIT,
                PendingOp.Kind.COMMENT_DELETE,
            )
    }
}
