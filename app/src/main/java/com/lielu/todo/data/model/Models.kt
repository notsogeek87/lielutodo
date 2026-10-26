package com.lielu.todo.data.model

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

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
    /** Vue « liste » du projet : c'est elle qui porte l'ordre manuel des tâches ; 0 si inconnue. */
    val listViewId: Long = 0,
)

data class Label(
    val id: Long,
    val title: String,
    val hexColor: String,
)

data class User(
    val id: Long,
    val username: String,
    val name: String,
) {
    val display: String get() = name.ifBlank { username }
}

/** Accès d'un utilisateur ou d'une équipe à un projet. [permission] : 0 lecture, 1 lecture et écriture, 2 admin. */
data class ProjectAccess(
    val name: String,
    val permission: Int,
    val isTeam: Boolean = false,
) {
    val rightsLabel: String
        get() =
            when (permission) {
                0 -> "Lecture seule"
                1 -> "Lecture et écriture"
                else -> "Administrateur"
            }
}

/** Fichier joint à une tâche. Un [id] négatif = ajouté hors ligne, pas encore envoyé. */
data class Attachment(
    val id: Long,
    val name: String,
    val size: Long,
    val mime: String,
    val created: Instant?,
)

/** Notification Vikunja (assignation, commentaire, rappel…), résumée en une phrase lisible. */
data class AppNotification(
    val id: Long,
    val text: String,
    val read: Boolean,
    val created: Instant?,
    /** Tâche concernée, 0 si la notification n'en vise pas. */
    val taskId: Long,
)

data class Comment(
    val id: Long,
    val text: String,
    val author: String,
    val created: Instant?,
)

/** Filtre enregistré Vikunja : [query] est sa requête (ex. `due_date < now && done = false`). */
data class SavedFilter(
    val id: Long,
    val title: String,
    val query: String,
    val includeNulls: Boolean,
)

/**
 * Rappel d'une tâche. Relatif (« 1 h avant l'échéance ») s'il a un [relativeTo], sinon absolu à [at]. Un rappel
 * relatif suit l'échéance quand elle change, comme côté serveur.
 */
data class Reminder(
    val at: Instant?,
    val relativePeriod: Long = 0,
    val relativeTo: String = "",
) {
    fun effective(due: Instant?): Instant? =
        if (relativeTo == RELATIVE_TO_DUE && due != null) due.plusSeconds(relativePeriod) else at

    fun toJson(): JSONObject =
        if (relativeTo.isNotBlank()) {
            JSONObject()
                .put(
                    "reminder",
                    ZERO_DATE,
                ).put("relative_period", relativePeriod)
                .put("relative_to", relativeTo)
        } else {
            JSONObject().put("reminder", at?.toString() ?: ZERO_DATE).put("relative_period", 0).put("relative_to", "")
        }

    companion object {
        const val RELATIVE_TO_DUE = "due_date"

        fun beforeDue(seconds: Long) = Reminder(null, -seconds, RELATIVE_TO_DUE)
    }
}

/**
 * Une tâche Vikunja. [raw] garde le JSON du serveur : Vikunja remplace la tâche entière à chaque mise à jour,
 * renvoyer seulement nos champs effacerait ceux qu'on ne gère pas (pourcentage, dates de début/fin…).
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
    val reminders: List<Reminder> = emptyList(),
    /** Répétition en secondes (0 = aucune) ; [repeatMode] 1 = tous les mois, 2 = depuis la date de complétion. */
    val repeatAfter: Long = 0,
    val repeatMode: Int = 0,
    /** Tâche parente (0 = aucune) : les sous-tâches sont des tâches dont [parentId] pointe vers la parente. */
    val parentId: Long = 0,
    val assignees: List<User> = emptyList(),
    /** Date de complétion et de création (pour la productivité et l'activité) ; `null` si inconnue. */
    val doneAt: Instant? = null,
    val createdAt: Instant? = null,
) {
    val isRecurring: Boolean get() = repeatAfter > 0 || repeatMode == REPEAT_MONTHLY

    /** Date de planification : l'échéance, sinon le premier rappel (une tâche avec rappel seul reste planifiée). */
    val plannedAt: Instant? get() = dueDate ?: reminders.mapNotNull { it.effective(null) }.minOrNull()

    /** Instants où la notifier : ses rappels, ou à l'échéance si elle a une heure et aucun rappel explicite. */
    fun notificationTimes(dueHasTime: Boolean): List<Instant> {
        val explicit = reminders.mapNotNull { it.effective(dueDate) }
        if (explicit.isNotEmpty()) return explicit
        return if (dueHasTime && dueDate != null) listOf(dueDate) else emptyList()
    }

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
        o.put("done_at", doneAt?.toString() ?: ZERO_DATE)
        o.put("repeat_after", repeatAfter)
        o.put("repeat_mode", repeatMode)
        o.put("labels", JSONArray(labels.map { it.toJson() }))
        o.put("reminders", JSONArray(reminders.map { it.toJson() }))
        o.put("assignees", JSONArray(assignees.map { it.toJson() }))
        val related = o.optJSONObject("related_tasks") ?: JSONObject()
        if (parentId !=
            0L
        ) {
            related.put("parenttask", JSONArray().put(JSONObject().put("id", parentId)))
        } else {
            related.remove("parenttask")
        }
        o.put("related_tasks", related)
        return o
    }

    /** Corps d'une requête d'écriture : sans les champs en lecture seule ou gérés par d'autres routes. */
    fun toRequestJson(): JSONObject {
        val o = toJson()
        READ_ONLY_KEYS.forEach { o.remove(it) }
        return o
    }

    companion object {
        const val REPEAT_MONTHLY = 1
        const val REPEAT_FROM_COMPLETION = 2

        private val READ_ONLY_KEYS =
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

fun User.toJson(): JSONObject = JSONObject().put("id", id).put("username", username).put("name", name)

fun Comment.toJson(): JSONObject =
    JSONObject().put("id", id).put("comment", text).put("author", JSONObject().put("name", author)).put(
        "created",
        created?.toString() ?: ZERO_DATE,
    )

fun Project.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("title", title)
        .put("parent_project_id", parentId)
        .put("hex_color", hexColor)
        .put("is_favorite", isFavorite)
        .put("is_archived", isArchived)
        .put("position", position)
        .put("list_view_id", listViewId)

fun SavedFilter.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("title", title)
        .put("query", query)
        .put("include_nulls", includeNulls)

/**
 * Dernière date reçue du serveur qu'aucun format connu n'a pu lire (`null` si tout va bien). Lue par le dépôt pour
 * le signaler à l'utilisateur au lieu d'afficher des tâches sans date sans explication.
 */
@Volatile
var lastUnreadableDate: String? = null
    private set

private val OFFSET_WITHOUT_COLON: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSSSSSSSS][.SSSSSS][.SSS]xx")

fun resetUnreadableDate() {
    lastUnreadableDate = null
}

/** Lit une date du serveur : RFC3339 d'abord, puis les variantes rencontrées ailleurs. `null` = pas de date. */
fun parseDate(value: String?): Instant? {
    if (value.isNullOrBlank() || value == "null" || value.startsWith("0001")) return null
    val zone = ZoneId.systemDefault()
    val parsed =
        runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value, OFFSET_WITHOUT_COLON).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(value).atZone(zone).toInstant() }.getOrNull()
            // Date seule : on prend midi, l'heure qui veut dire « pas d'heure » dans l'app.
            ?: runCatching {
                LocalDate
                    .parse(value)
                    .atTime(LocalTime.NOON)
                    .atZone(zone)
                    .toInstant()
            }.getOrNull()
            ?: value.toLongOrNull()?.let {
                if (it >
                    EPOCH_MILLIS_THRESHOLD
                ) {
                    Instant.ofEpochMilli(it)
                } else {
                    Instant.ofEpochSecond(it)
                }
            }
    if (parsed == null) lastUnreadableDate = value
    return parsed
}

private const val EPOCH_MILLIS_THRESHOLD = 100_000_000_000L

fun labelFromJson(o: JSONObject) = Label(o.getLong("id"), o.optString("title"), o.optString("hex_color"))

fun userFromJson(o: JSONObject) = User(o.getLong("id"), o.optString("username"), o.optString("name"))

fun projectFromJson(o: JSONObject): Project {
    val views = o.optJSONArray("views")
    val viewObjects = (0 until (views?.length() ?: 0)).map { views!!.getJSONObject(it) }
    val listView = viewObjects.firstOrNull { it.optString("view_kind") == "list" }
    return Project(
        id = o.getLong("id"),
        title = o.optString("title"),
        parentId = o.optLong("parent_project_id", 0),
        hexColor = o.optString("hex_color"),
        isFavorite = o.optBoolean("is_favorite"),
        isArchived = o.optBoolean("is_archived"),
        position = o.optDouble("position", 0.0),
        listViewId = listView?.optLong("id") ?: o.optLong("list_view_id", 0),
    )
}

fun taskFromJson(o: JSONObject): Task {
    val labels = o.optJSONArray("labels")
    val reminders = o.optJSONArray("reminders")
    val assignees = o.optJSONArray("assignees")
    val parents = o.optJSONObject("related_tasks")?.optJSONArray("parenttask")
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
        reminders =
            (0 until (reminders?.length() ?: 0)).map {
                val r = reminders!!.getJSONObject(it)
                Reminder(parseDate(r.optString("reminder")), r.optLong("relative_period"), r.optString("relative_to"))
            },
        repeatAfter = o.optLong("repeat_after"),
        repeatMode = o.optInt("repeat_mode"),
        parentId = if (parents != null && parents.length() > 0) parents.getJSONObject(0).optLong("id") else 0,
        assignees = (0 until (assignees?.length() ?: 0)).map { userFromJson(assignees!!.getJSONObject(it)) },
        doneAt = parseDate(o.optString("done_at")),
        createdAt = parseDate(o.optString("created")),
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

fun attachmentFromJson(o: JSONObject): Attachment {
    val file = o.optJSONObject("file") ?: o
    return Attachment(
        id = o.getLong("id"),
        name = file.optString("name"),
        size = file.optLong("size"),
        mime = file.optString("mime"),
        created = parseDate(o.optString("created")),
    )
}

fun Attachment.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("created", created?.toString() ?: ZERO_DATE)
        .put("file", JSONObject().put("name", name).put("size", size).put("mime", mime))

private val NOTIFICATION_VERBS =
    mapOf(
        "task.assigned" to "vous a assigné",
        "task.comment" to "a commenté",
        "task.deadline.reached" to "échéance atteinte :",
        "task.reminder" to "rappel :",
        "task.mentioned" to "vous a mentionné dans",
        "project.created" to "a créé le projet",
        "team.member.added" to "vous a ajouté à l'équipe",
    )

/**
 * Le contenu de `notification` dépend du type : on en tire au mieux « qui », « quoi » (type) et la tâche, sans
 * jamais échouer sur un type inconnu (on affiche alors son nom brut).
 */
fun notificationFromJson(o: JSONObject): AppNotification {
    val body = o.optJSONObject("notification")
    val doer = body?.optJSONObject("doer")
    val who = doer?.optString("name").orEmpty().ifBlank { doer?.optString("username").orEmpty() }
    val task = body?.optJSONObject("task")
    val project = body?.optJSONObject("project")
    val name = o.optString("name")
    val verb = NOTIFICATION_VERBS[name] ?: name.ifBlank { "notification" }
    val subject = task?.optString("title").orEmpty().ifBlank { project?.optString("title").orEmpty() }
    return AppNotification(
        id = o.getLong("id"),
        text = listOf(who, verb, subject).filter { it.isNotBlank() }.joinToString(" "),
        read = o.optBoolean("read") || parseDate(o.optString("read_at")) != null,
        created = parseDate(o.optString("created")),
        taskId = task?.optLong("id") ?: 0,
    )
}

fun AppNotification.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("text", text)
        .put("read", read)
        .put("created", created?.toString() ?: ZERO_DATE)
        .put("task_id", taskId)

fun appNotificationFromStoredJson(o: JSONObject) =
    AppNotification(
        o.getLong("id"),
        o.optString("text"),
        o.optBoolean("read"),
        parseDate(o.optString("created")),
        o.optLong("task_id"),
    )
