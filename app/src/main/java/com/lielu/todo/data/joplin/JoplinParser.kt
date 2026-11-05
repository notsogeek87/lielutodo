package com.lielu.todo.data.joplin

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Parsing/sérialisation du format d'item Joplin (repris de `BaseItem.unserialize`) : le contenu d'un item
 * est `titre`, ligne vide, corps Markdown, ligne vide, puis un bloc de propriétés `clé: valeur` en fin de
 * fichier. Ex. de propriétés d'une note : id, parent_id, updated_time, is_todo, type_ (1 = note, 2 = carnet).
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
        /** Chiffrement de bout en bout Joplin : titre et corps sont alors illisibles ici. */
        val encrypted: Boolean = false,
    )

    private val isoFormat =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC)

    /** Contenu (lignes avant le bloc de propriétés) et propriétés, dans l'ordre du fichier. */
    private fun split(content: String): Pair<List<String>, LinkedHashMap<String, String>> {
        val lines = content.split('\n')
        val reversed = ArrayList<Pair<String, String>>()
        var i = lines.size - 1
        // Ignorer les lignes vides finales (le fichier sérialisé se termine par un retour à la ligne).
        while (i >= 0 && lines[i].isBlank()) i--
        while (i >= 0 && lines[i].isNotBlank()) {
            val colon = lines[i].indexOf(':')
            if (colon <= 0) break
            reversed += lines[i].substring(0, colon).trim() to lines[i].substring(colon + 1).trim()
            i--
        }
        val props = LinkedHashMap<String, String>()
        reversed.asReversed().forEach { (key, value) -> props[key] = value }
        return (if (i >= 0) lines.subList(0, i) else emptyList()) to props
    }

    fun parse(content: String): Item? {
        val (contentLines, props) = split(content)
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
            encrypted = props["encryption_applied"] == "1",
        )
    }

    /** Sérialise une NOUVELLE note au format item (V2) : contenu puis bloc de propriétés. */
    fun serialize(
        id: String,
        parentId: String,
        title: String,
        body: String,
        updatedTimeMs: Long,
        createdTimeMs: Long = updatedTimeMs,
    ): String {
        val created = formatTime(createdTimeMs)
        val updated = formatTime(updatedTimeMs)
        val props =
            listOf(
                "id: $id",
                "parent_id: $parentId",
                "created_time: $created",
                "updated_time: $updated",
                "is_conflict: 0",
                "author: ",
                "source_url: ",
                "is_todo: 0",
                "todo_due: 0",
                "todo_completed: 0",
                "source: lielutodo",
                "source_application: com.lielu.todo",
                "application_data: ",
                "order: 0",
                "user_created_time: $created",
                "user_updated_time: $updated",
                "encryption_cipher_text: ",
                "encryption_applied: 0",
                "markup_language: 1",
                "is_shared: 0",
                "type_: 1",
            )
        return assemble(title, body, props.joinToString("\n"))
    }

    /**
     * Version modifiée d'un item existant : titre, corps et carnet remplacés, dates de modification mises à jour,
     * TOUTES les autres propriétés gardées (to-do, échéance, auteur, source, partage…). Joplin remplace l'item
     * entier : renvoyer un jeu de propriétés réduit effacerait le reste. [deletedTimeMs] non nul = mise à la
     * corbeille Joplin (récupérable depuis Joplin).
     */
    fun patch(
        raw: String,
        title: String,
        body: String,
        parentId: String,
        updatedTimeMs: Long,
        deletedTimeMs: Long? = null,
    ): String {
        val (_, props) = split(raw)
        val updated = formatTime(updatedTimeMs)
        props["parent_id"] = parentId
        props["updated_time"] = updated
        props["user_updated_time"] = updated
        if (deletedTimeMs != null) props["deleted_time"] = deletedTimeMs.toString()
        // type_ reste la dernière ligne, comme dans les fichiers de Joplin.
        val type = props.remove("type_") ?: "1"
        props["type_"] = type
        return assemble(title, body, props.entries.joinToString("\n") { "${it.key}: ${it.value}" })
    }

    private fun assemble(
        title: String,
        body: String,
        props: String,
    ): String = if (body.isEmpty()) "$title\n\n$props\n" else "$title\n\n$body\n\n$props\n"

    fun formatTime(epochMs: Long): String = isoFormat.format(Instant.ofEpochMilli(epochMs))

    private fun parseTime(value: String?): Long =
        value
            ?.toLongOrNull()
            ?.let { if (it > 10_000_000_000L) it else it * 1000 }
            ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrElse { 0L }
}
