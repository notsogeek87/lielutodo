package com.lielu.todo.ui.notes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Édition Markdown de la saisie d'une note, sans état (testée unitairement) : chaque fonction prend le texte et la
 * sélection courants et rend le nouveau couple. Aucune ne pose de texte d'exemple à effacer : on agit sur la ligne ou
 * la sélection de l'utilisateur.
 */
object MarkdownEditing {
    /** Marqueurs de début de ligne reconnus (un seul à la fois par ligne), du plus long au plus court. */
    private val blockMarker = Regex("""^(\s*)(#{1,6} |[-*+] \[[ xX]] |[-*+] |\d+[.)] |> )""")

    /** Ligne de liste à prolonger : indentation, marqueur, contenu. */
    private val listLine = Regex("""^(\s*)([-*+] \[[ xX]] |[-*+] |(\d+)([.)]) |> )(.*)$""")

    const val HEADING = "## "
    const val BULLET = "- "
    const val NUMBERED = "1. "
    const val CHECKBOX = "- [ ] "
    const val QUOTE = "> "

    /**
     * Ajoute [marker] au début de chaque ligne touchée par la sélection (la ligne du curseur si rien n'est
     * sélectionné), en remplaçant un autre marqueur déjà présent ; si toutes l'ont déjà, il est retiré (bascule).
     */
    fun toggleLinePrefix(
        value: TextFieldValue,
        marker: String,
    ): TextFieldValue {
        val text = value.text
        val (start, end) = lineBounds(text, value.selection)
        val lines = text.substring(start, end).split('\n')
        val allHave = lines.all { markerOf(it) == marker.trimEnd() }
        var number = 1
        val newLines =
            lines.map { line ->
                val indent = line.takeWhile { it == ' ' || it == '\t' }
                val content = line.removePrefix(indent).removePrefix(markerMatch(line).orEmpty())
                when {
                    allHave -> indent + content
                    marker == NUMBERED -> indent + "${number++}. " + content
                    else -> indent + marker + content
                }
            }
        val replaced = newLines.joinToString("\n")
        val newText = text.replaceRange(start, end, replaced)
        val selection =
            if (value.selection.collapsed && lines.size == 1) {
                // Le curseur reste au même endroit du contenu, décalé de la longueur du marqueur ajouté/retiré.
                val delta = replaced.length - (end - start)
                TextRange((value.selection.start + delta).coerceIn(start, start + replaced.length))
            } else {
                TextRange(start, start + replaced.length)
            }
        return TextFieldValue(newText, selection)
    }

    /** Case à cocher de la ligne du curseur : « - [ ] » ↔ « - [x] » ; une ligne sans case en reçoit une. */
    fun toggleCheckbox(value: TextFieldValue): TextFieldValue {
        val text = value.text
        val (start, end) = lineBounds(text, value.selection)
        val line = text.substring(start, end)
        if (line.contains('\n')) return toggleLinePrefix(value, CHECKBOX)
        val box = Regex("""^(\s*[-*+] )\[([ xX])]""").find(line) ?: return toggleLinePrefix(value, CHECKBOX)
        val checked = box.groupValues[2] != " "
        val markIndex = start + box.groupValues[1].length + 1
        val newText = text.replaceRange(markIndex, markIndex + 1, if (checked) " " else "x")
        return value.copy(text = newText)
    }

    /**
     * Gras, italique, barré, code : entoure la sélection de [prefix]/[suffix], ou les retire si elle est déjà
     * entourée. Sans sélection, agit sur le mot sous le curseur ; hors d'un mot, pose les marqueurs et place le
     * curseur entre les deux.
     */
    fun toggleWrap(
        value: TextFieldValue,
        prefix: String,
        suffix: String = prefix,
    ): TextFieldValue {
        val text = value.text
        var start = value.selection.min
        var end = value.selection.max
        if (start == end) {
            while (start > 0 && text[start - 1].isWordChar()) start--
            while (end < text.length && text[end].isWordChar()) end++
            if (start == end) {
                val newText = text.replaceRange(start, end, prefix + suffix)
                return TextFieldValue(newText, TextRange(start + prefix.length))
            }
        }
        val before = text.substring(0, start)
        val after = text.substring(end)
        val selected = text.substring(start, end)
        // « *mot* » dans « **mot** » est du gras, pas de l'italique : on ne le retire pas pour un « * ».
        val insideLonger = prefix == "*" && before.endsWith("**") && !before.endsWith("***")
        if (!insideLonger && before.endsWith(prefix) && after.startsWith(suffix)) {
            val newText = before.removeSuffix(prefix) + selected + after.removePrefix(suffix)
            val newStart = start - prefix.length
            return TextFieldValue(newText, TextRange(newStart, newStart + selected.length))
        }
        if (selected.length >= prefix.length + suffix.length &&
            selected.startsWith(prefix) &&
            selected.endsWith(suffix)
        ) {
            val inner = selected.substring(prefix.length, selected.length - suffix.length)
            return TextFieldValue(before + inner + after, TextRange(start, start + inner.length))
        }
        val newText = before + prefix + selected + suffix + after
        return TextFieldValue(newText, TextRange(start + prefix.length, start + prefix.length + selected.length))
    }

    /**
     * Lien : la sélection (ou le mot sous le curseur) devient le texte du lien et le curseur se place sur l'adresse ;
     * sans texte, « lien » est posé sélectionné pour être remplacé à la frappe.
     */
    fun insertLink(value: TextFieldValue): TextFieldValue {
        val text = value.text
        var start = value.selection.min
        var end = value.selection.max
        if (start == end) {
            while (start > 0 && text[start - 1].isWordChar()) start--
            while (end < text.length && text[end].isWordChar()) end++
        }
        val label = text.substring(start, end)
        if (label.isEmpty()) {
            val newText = text.replaceRange(start, end, "[lien](https://)")
            return TextFieldValue(newText, TextRange(start + 1, start + 5))
        }
        val inserted = "[$label](https://)"
        val newText = text.replaceRange(start, end, inserted)
        return TextFieldValue(newText, TextRange(start + inserted.length - 1))
    }

    /**
     * Appelée à chaque frappe : si l'utilisateur vient de passer à la ligne au bout d'un élément de liste (puce,
     * numéro, case, citation), la nouvelle ligne reçoit le même marqueur (numéro + 1, case vide) ; sur un élément
     * vide, le marqueur est retiré (fin de la liste), comme dans les éditeurs de notes courants.
     */
    fun continueList(
        old: TextFieldValue,
        new: TextFieldValue,
    ): TextFieldValue {
        if (!new.selection.collapsed || new.text.length != old.text.length + 1) return new
        val cursor = new.selection.start
        if (cursor == 0 || new.text[cursor - 1] != '\n') return new
        if (new.text.removeRange(cursor - 1, cursor) != old.text) return new
        val lineStart = new.text.lastIndexOf('\n', cursor - 2) + 1
        val previous = new.text.substring(lineStart, cursor - 1)
        val match = listLine.matchEntire(previous) ?: return new
        val indent = match.groupValues[1]
        val marker = match.groupValues[2]
        val content = match.groupValues[5]
        if (content.isBlank()) {
            // Élément vide + Entrée : on sort de la liste (la ligne vide garde son retour, sans marqueur).
            val newText = new.text.replaceRange(lineStart, cursor, "")
            return TextFieldValue(newText, TextRange(lineStart))
        }
        val next =
            when {
                match.groupValues[3].isNotEmpty() -> "${match.groupValues[3].toInt() + 1}${match.groupValues[4]} "
                marker.contains('[') -> marker.substring(0, 2) + "[ ] "
                else -> marker
            }
        val insertion = indent + next
        val newText = new.text.replaceRange(cursor, cursor, insertion)
        return TextFieldValue(newText, TextRange(cursor + insertion.length))
    }

    /** Titre déduit de la première ligne non vide du corps (sans marqueur Markdown), pour une note sans titre. */
    fun titleFromBody(body: String): String {
        val first = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        val stripped = first.removePrefix(markerMatch(first).orEmpty()).replace(Regex("""[*_~`]"""), "").trim()
        return if (stripped.length <= MAX_TITLE) stripped else stripped.take(MAX_TITLE).trimEnd() + "…"
    }

    private const val MAX_TITLE = 80

    /** Aperçu lisible du corps pour la liste des notes : marqueurs Markdown retirés, cases en ☐/☑, sur une ligne. */
    fun plainPreview(
        body: String,
        maxLength: Int = 160,
    ): String =
        body
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val box = Regex("""^[-*+] \[([ xX])] """).find(line)
                val content = line.removePrefix(markerMatch(line).orEmpty())
                val clean = content.replace(Regex("""(\*\*|__|~~|`|\*)"""), "")
                when {
                    box == null -> clean
                    box.groupValues[1] == " " -> "☐ $clean"
                    else -> "☑ $clean"
                }
            }.joinToString(" · ")
            .take(maxLength)

    /** Début de la première ligne et fin de la dernière ligne touchées par [selection]. */
    private fun lineBounds(
        text: String,
        selection: TextRange,
    ): Pair<Int, Int> {
        val start = text.lastIndexOf('\n', (selection.min - 1).coerceAtLeast(-1)).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', selection.max).let { if (it < 0) text.length else it }
        return (if (selection.min == 0) 0 else start) to end
    }

    private fun markerMatch(line: String): String? = blockMarker.find(line)?.groupValues?.get(2)

    /** Marqueur de la ligne, normalisé pour comparer (« 3. » compte comme liste numérotée « 1. »). */
    private fun markerOf(line: String): String? {
        val marker = markerMatch(line)?.trimEnd() ?: return null
        return when {
            marker.first().isDigit() -> NUMBERED.trimEnd()
            marker.contains('[') -> CHECKBOX.trimEnd()
            else -> marker
        }
    }

    private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '\'' || this == '-'
}
