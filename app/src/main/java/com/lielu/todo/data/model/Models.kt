package com.lielu.todo.data.model

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/** Vikunja renvoie cette date pour « pas de date ». */
private const val ZERO_DATE = "0001-01-01T00:00:00Z"

data class Project(
    val id: Long,
    val title: String,
    val parentId: Long,
    val hexColor: String,
    val isFavorite: Boolean,
    val isArchived: Boolean,
    val position: Double,
)

data class Label(
    val id: Long,
    val title: String,
    val hexColor: String,
)

data class Comment(
    val id: Long,
    val text: String,
    val author: String,
    val created: Instant?,
)

/**
 * Une tâche Vikunja. [raw] garde le JSON du serveur : Vikunja remplace la tâche entière à chaque mise à jour,
 * renvoyer seulement nos champs effacerait ceux qu'on ne gère pas (répétition, rappels, pourcentage…).
 */
data class Task(
    val id: Long,
    val projectId: Long,
    val title: String,
    val description: String,
    val done: Boolean,
    val dueDate: Instant?,
    val priority: Int,
    val labels: List<Label>,
    val isFavorite: Boolean,
    val position: Double,
    val commentCount: Int,
    val raw: String,
) {
    /** JSON complet (champs du serveur + nos modifications), relu tel quel par [taskFromJson]. */
    fun toJson(): JSONObject {
        val o = JSONObject(raw)
        o.put("id", id)
        o.put("project_id", projectId)
        o.put("title", title)
        o.put("description", description)
        o.put("done", done)
        o.put("due_date", dueDate?.toString() ?: ZERO_DATE)
        o.put("priority", priority)
        o.put("is_favorite", isFavorite)
        o.put("position", position)
        o.put("labels", JSONArray(labels.map { it.toJson() }))
        return o
    }

    /** Corps d'une requête d'écriture : sans les champs en lecture seule ou gérés par d'autres routes. */
    fun toRequestJson(): JSONObject {
        val o = toJson()
        READ_ONLY_KEYS.forEach { o.remove(it) }
        return o
    }

    private companion object {
        val READ_ONLY_KEYS =
            listOf(
                "labels",
                "assignees",
                "created_by",
                "attachments",
                "related_tasks",
                "reactions",
                "comments",
                "subscription",
                "buckets",
                "created",
                "updated",
                "comment_count",
                "is_unread",
            )
    }
}

fun Label.toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("hex_color", hexColor)

fun Project.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("title", title)
        .put("parent_project_id", parentId)
        .put("hex_color", hexColor)
        .put("is_favorite", isFavorite)
        .put("is_archived", isArchived)
        .put("position", position)

fun parseDate(value: String?): Instant? {
    if (value.isNullOrBlank() || value.startsWith("0001")) return null
    return runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
}

fun labelFromJson(o: JSONObject) = Label(o.getLong("id"), o.optString("title"), o.optString("hex_color"))

fun projectFromJson(o: JSONObject) =
    Project(
        id = o.getLong("id"),
        title = o.optString("title"),
        parentId = o.optLong("parent_project_id", 0),
        hexColor = o.optString("hex_color"),
        isFavorite = o.optBoolean("is_favorite"),
        isArchived = o.optBoolean("is_archived"),
        position = o.optDouble("position", 0.0),
    )

fun taskFromJson(o: JSONObject): Task {
    val labels = o.optJSONArray("labels")
    return Task(
        id = o.getLong("id"),
        projectId = o.optLong("project_id"),
        title = o.optString("title"),
        description = o.optString("description"),
        done = o.optBoolean("done"),
        dueDate = parseDate(o.optString("due_date")),
        priority = o.optInt("priority"),
        labels = (0 until (labels?.length() ?: 0)).map { labelFromJson(labels!!.getJSONObject(it)) },
        isFavorite = o.optBoolean("is_favorite"),
        position = o.optDouble("position", 0.0),
        commentCount = o.optInt("comment_count"),
        raw = o.toString(),
    )
}

fun commentFromJson(o: JSONObject): Comment {
    val author = o.optJSONObject("author")
    return Comment(
        id = o.getLong("id"),
        text = o.optString("comment"),
        author = author?.optString("name").orEmpty().ifBlank { author?.optString("username").orEmpty() },
        created = parseDate(o.optString("created")),
    )
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
