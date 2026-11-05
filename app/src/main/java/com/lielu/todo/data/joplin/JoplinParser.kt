package com.lielu.todo.data.joplin

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Parsing/sérialisation du format d'item Joplin (repris de `BaseItem.unserialize`) : le contenu d'un item
 * est `titre`, ligne vide, corps Markdown, ligne vide, puis un bloc de propriétés `clé: valeur` en fin de
 * fichier. Ex. de propriétés d'une note : id, parent_id, updated_time, is_todo, type_ (1 = note).
 */
object JoplinParser {
    /** Un item parsé. [updatedTime]/[deletedTime]/[todoDue] en millisecondes (0 = absent). */
    data class Item(
        val id: String,
        val type: Int,
        val title: String,
        val body: String,
        val parentId: String,
        val updatedTime: Long,
        val deletedTime: Long,
        val isTodo: Boolean,
        val todoCompleted: Boolean,
        val todoDue: Long,
    )

    private val isoFormat =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC)

    fun parse(content: String): Item? {
        val lines = content.split('\n')
        val props = LinkedHashMap<String, String>()
        var i = lines.size - 1
        // Ignorer les lignes vides finales (le fichier sérialisé se termine par un retour à la ligne).
        while (i >= 0 && lines[i].isBlank()) i--
        while (i >= 0 && lines[i].isNotBlank()) {
            val colon = lines[i].indexOf(':')
            if (colon <= 0) break
            props[lines[i].substring(0, colon).trim()] = lines[i].substring(colon + 1).trim()
            i--
        }
        val contentLines = if (i >= 0) lines.subList(0, i) else emptyList()
        val type = props["type_"]?.toIntOrNull() ?: return null
        val id = props["id"].orEmpty()
        if (id.length != 32 || id.any { !it.isDigit() && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        val title = contentLines.firstOrNull().orEmpty()
        val bodyStart = if (contentLines.size > 1 && contentLines[1].isBlank()) 2 else 1
        val body =
            if (bodyStart >= contentLines.size) {
                ""
            } else {
                contentLines.subList(bodyStart, contentLines.size).joinToString("\n")
            }
        return Item(
            id = id,
            type = type,
            title = title,
            body = body,
            parentId = props["parent_id"].orEmpty(),
            updatedTime = parseTime(props["updated_time"]),
            deletedTime = parseTime(props["deleted_time"]),
            isTodo = props["is_todo"] == "1",
            todoCompleted = props["todo_completed"]?.toLongOrNull() != 0L && props["todo_completed"] != null,
            todoDue = parseTime(props["todo_due"]),
        )
    }

    /** Sérialise une note au format item (V2) : contenu puis bloc de propriétés. */
    fun serialize(
        id: String,
        parentId: String,
        title: String,
        body: String,
        updatedTimeMs: Long,
    ): String {
        val props =
            listOf(
                "id: $id",
                "parent_id: $parentId",
                "created_time: ${formatTime(updatedTimeMs)}",
                "updated_time: ${formatTime(updatedTimeMs)}",
                "is_todo: 0",
                "todo_completed: 0",
                "todo_due: 0",
                "encryption_applied: 0",
                "type_: 1",
            )
        return "$title\n\n$body\n\n${props.joinToString("\n")}\n"
    }

    fun formatTime(epochMs: Long): String = isoFormat.format(Instant.ofEpochMilli(epochMs))

    private fun parseTime(value: String?): Long =
        value
            ?.toLongOrNull()
            ?.let { if (it > 10_000_000_000L) it else it * 1000 }
            ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrElse { 0L }
}
