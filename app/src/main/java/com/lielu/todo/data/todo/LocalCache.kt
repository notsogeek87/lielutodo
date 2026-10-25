package com.lielu.todo.data.todo

import com.lielu.todo.data.model.Label
import com.lielu.todo.data.model.Project
import com.lielu.todo.data.model.Task
import com.lielu.todo.data.model.labelFromJson
import com.lielu.todo.data.model.objects
import com.lielu.todo.data.model.projectFromJson
import com.lielu.todo.data.model.taskFromJson
import com.lielu.todo.data.model.toJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Copie locale lisible hors ligne : tout ce qu'il faut pour afficher les écrans sans réseau. */
interface LocalCache {
    fun load(): TodoState?

    fun save(state: TodoState)
}

class FileLocalCache(
    private val file: File,
) : LocalCache {
    override fun load(): TodoState? {
        if (!file.exists()) return null
        return runCatching {
            val o = JSONObject(file.readText())
            TodoState(
                projects = o.getJSONArray("projects").objects().map(::projectFromJson),
                labels = o.getJSONArray("labels").objects().map(::labelFromJson),
                tasks = o.getJSONArray("tasks").objects().map(::taskFromJson),
                defaultProjectId = o.optLong("default_project_id"),
                ops = o.getJSONArray("ops").objects().map(::opFromJson),
                loaded = true,
            )
        }.getOrNull()
    }

    override fun save(state: TodoState) {
        val o =
            JSONObject()
                .put("projects", JSONArray(state.projects.map(Project::toJson)))
                .put("labels", JSONArray(state.labels.map(Label::toJson)))
                .put("tasks", JSONArray(state.tasks.map { it.toJson() }))
                .put("default_project_id", state.defaultProjectId)
                .put("ops", JSONArray(state.ops.map(::opToJson)))
        // Écriture atomique : un arrêt en plein milieu ne doit jamais laisser un cache tronqué.
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun opToJson(op: PendingOp) =
        JSONObject().put("kind", op.kind.name).put("task_id", op.taskId).put("labels", op.labelsChanged)

    private fun opFromJson(o: JSONObject) =
        PendingOp(PendingOp.Kind.valueOf(o.getString("kind")), o.getLong("task_id"), o.optBoolean("labels"))
}

fun Task.isTemporary() = id < 0
