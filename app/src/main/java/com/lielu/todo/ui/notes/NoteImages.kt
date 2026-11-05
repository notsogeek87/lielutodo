package com.lielu.todo.ui.notes

/** Morceau de l'aperçu d'une note : du Markdown, ou une image jointe Joplin (`![nom](:/<id de ressource>)`). */
sealed interface NotePart {
    data class Text(
        val markdown: String,
    ) : NotePart

    data class Image(
        val resourceId: String,
        val alt: String,
    ) : NotePart
}

/**
 * Images jointes d'une note Joplin : le Markdown cite la ressource par `:/<id>`, que Markwon ne sait pas charger.
 * L'aperçu découpe donc le corps en texte et images ; chaque image est téléchargée depuis Joplin (même règle que le
 * site, `web/js/notes/markdown.js`).
 */
object NoteImages {
    private val IMAGE = Regex("""!\[([^\]]*)]\(:/([0-9a-fA-F]{32})\)""")

    fun hasImages(body: String): Boolean = IMAGE.containsMatchIn(body)

    fun split(body: String): List<NotePart> {
        val parts = mutableListOf<NotePart>()
        var last = 0
        for (m in IMAGE.findAll(body)) {
            val before = body.substring(last, m.range.first)
            if (before.isNotBlank()) parts += NotePart.Text(before.trimEnd(' '))
            parts += NotePart.Image(m.groupValues[2].lowercase(), m.groupValues[1])
            last = m.range.last + 1
        }
        val rest = body.substring(last)
        if (rest.isNotBlank() || parts.isEmpty()) parts += NotePart.Text(rest)
        return parts
    }
}
