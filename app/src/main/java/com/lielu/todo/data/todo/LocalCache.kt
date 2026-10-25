package com.lielu.todo.data.todo

import com.lielu.todo.data.model.Comment
import com.lielu.todo.data.model.Label
import com.lielu.todo.data.model.Project
import com.lielu.todo.data.model.SavedFilter
import com.lielu.todo.data.model.User
import com.lielu.todo.data.model.commentFromJson
import com.lielu.todo.data.model.labelFromJson
import com.lielu.todo.data.model.objects
import com.lielu.todo.data.model.projectFromJson
import com.lielu.todo.data.model.taskFromJson
import com.lielu.todo.data.model.toJson
import com.lielu.todo.data.model.userFromJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Copie locale lisible hors ligne : tout ce qu'il faut pour afficher les écrans sans réseau. */
interface LocalCache {
    fun load(): TodoState?

    fun save(state: TodoState)
}

/**
 * Cache disque en un seul fichier JSON, écrit de façon atomique (fichier temporaire puis renommage) : un arrêt en
 * plein milieu laisse l'ancien fichier intact. Le dernier fichier valide est aussi gardé en `.bak` : s'il est
 * illisible (disque, mise à jour de format), on repart de cette copie plutôt que de perdre les écritures en
 * attente.
 */
class FileLocalCache(
    private val file: File,
) : LocalCache {
    private val backup = File(file.parentFile, file.name + ".bak")

    override fun load(): TodoState? = read(file) ?: read(backup)

    private fun read(source: File): TodoState? {
        if (!source.exists()) return null
        return runCatching { parse(JSONObject(source.readText())) }.getOrNull()
    }

    override fun save(state: TodoState) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(serialize(state).toString())
        // Ne promouvoir en .bak que ce qui est lisible : un fichier déjà corrompu ne doit pas écraser la copie saine.
        if (file.exists() && read(file) != null) file.copyTo(backup, overwrite = true)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun serialize(state: TodoState): JSONObject =
        JSONObject()
            .put("projects", JSONArray(state.projects.map(Project::toJson)))
            .put("labels", JSONArray(state.labels.map(Label::toJson)))
            .put("tasks", JSONArray(state.tasks.map { it.toJson() }))
            .put("filters", JSONArray(state.filters.map(SavedFilter::toJson)))
            .put(
                "filter_results",
                JSONObject().also { o ->
                    state.filterResults.forEach { (id, tasks) ->
                        o.put(id.toString(), JSONArray(tasks.map { it.toJson() }))
                    }
                },
            ).put(
                "comments",
                JSONObject().also { o ->
                    state.comments.forEach { (id, list) -> o.put(id.toString(), JSONArray(list.map(Comment::toJson))) }
                },
            ).put(
                "project_users",
                JSONObject().also { o ->
                    state.projectUsers.forEach { (id, list) -> o.put(id.toString(), JSONArray(list.map(User::toJson))) }
                },
            ).put("default_project_id", state.defaultProjectId)
            .put("last_sync", state.lastSync)
            .put("ops", JSONArray(state.ops.map(::opToJson)))

    private fun parse(o: JSONObject): TodoState =
        TodoState(
            projects = o.getJSONArray("projects").objects().map(::projectFromJson),
            labels = o.getJSONArray("labels").objects().map(::labelFromJson),
            tasks = o.getJSONArray("tasks").objects().map(::taskFromJson),
            filters =
                o
                    .optJSONArray("filters")
                    ?.objects()
                    ?.map(::filterFromJson)
                    .orEmpty(),
            filterResults = mapOfArrays(o.optJSONObject("filter_results")) { taskFromJson(it) },
            comments = mapOfArrays(o.optJSONObject("comments")) { commentFromJson(it) },
            projectUsers = mapOfArrays(o.optJSONObject("project_users")) { userFromJson(it) },
            defaultProjectId = o.optLong("default_project_id"),
            ops = o.getJSONArray("ops").objects().map(::opFromJson),
            lastSync = o.optLong("last_sync"),
            loaded = true,
        )

    private fun <T> mapOfArrays(
        o: JSONObject?,
        parse: (JSONObject) -> T,
    ): Map<Long, List<T>> =
        o
            ?.keys()
            ?.asSequence()
            ?.associate {
                it.toLong() to o.getJSONArray(it).objects().map(parse)
            }.orEmpty()

    private fun filterFromJson(o: JSONObject) =
        SavedFilter(o.getLong("id"), o.optString("title"), o.optString("query"), o.optBoolean("include_nulls"))

    private fun opToJson(op: PendingOp) =
        JSONObject()
            .put("kind", op.kind.name)
            .put("task_id", op.taskId)
            .put("other_id", op.otherId)
            .put("text", op.text)
            .put("flag", op.flag)

    private fun opFromJson(o: JSONObject) =
        PendingOp(
            kind = PendingOp.Kind.valueOf(o.getString("kind")),
            taskId = o.getLong("task_id"),
            otherId = o.optLong("other_id"),
            text = o.optString("text"),
            // Ancien format : « labels » jouait le rôle de [PendingOp.flag] pour les UPDATE.
            flag = o.optBoolean("flag", o.optBoolean("labels")),
        )
}
