package com.lielu.todo.data.todo

import com.lielu.todo.data.model.Task
import java.text.Normalizer

private fun fold(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

private fun stripTags(html: String): String = html.replace(Regex("<[^>]+>"), " ")

/**
 * Recherche locale (donc utilisable serveur coupé) dans les titres, descriptions et étiquettes. Chaque mot de la
 * requête doit apparaître ; accents et majuscules sont ignorés ; les titres l'emportent sur les descriptions.
 */
fun searchTasks(
    tasks: List<Task>,
    query: String,
): List<Task> {
    val words = fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return tasks
        .mapNotNull { task ->
            val title = fold(task.title)
            val rest = fold(stripTags(task.description) + " " + task.labels.joinToString(" ") { it.title })
            if (words.all { it in title || it in rest }) task to words.count { it in title } else null
        }.sortedWith(compareByDescending<Pair<Task, Int>> { it.second }.thenBy { it.first.done })
        .map { it.first }
}
