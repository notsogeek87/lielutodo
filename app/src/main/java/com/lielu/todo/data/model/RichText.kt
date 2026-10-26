package com.lielu.todo.data.model

/**
 * Descriptions et commentaires : Vikunja les stocke en HTML (éditeur du site web), l'app les édite en texte.
 * Port fidèle de `web/js/richtext.js` : toute évolution de l'un se répercute sur l'autre (mêmes cas de test).
 *
 * Garantie : `htmlToText(textToHtml(t)) == t` pour tout texte, pour que le cache et les sauvegardes (qui passent
 * par le JSON serveur) relisent exactement ce qui a été saisi.
 */

private val TAG = Regex("</?[a-zA-Z][^>]*>")

/** Le texte vient-il de l'éditeur HTML ? Un texte brut (anciennes versions de l'app) est rendu tel quel. */
fun looksLikeHtml(s: String): Boolean = TAG.containsMatchIn(s)

fun htmlToText(html: String): String {
    if (!looksLikeHtml(html)) return html
    var s = html.replace(Regex(">\\s*\\n\\s*<"), "><").replace('\n', ' ')
    // Puces : un repère de début de ligne, résolu à la fin (pas de ligne vide en tête ni en double).
    // Cases à cocher (liste de tâches de l'éditeur) avant les puces ordinaires.
    s = s.replace(Regex("<li[^>]*data-checked=\"true\"[^>]*>", RegexOption.IGNORE_CASE), "$ITEM- [x] ")
    s = s.replace(Regex("<li[^>]*data-checked=\"false\"[^>]*>", RegexOption.IGNORE_CASE), "$ITEM- [ ] ")
    s = s.replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "$ITEM- ")
    // Lien dont le texte n'est pas l'adresse : on garde l'adresse, elle doit rester ouvrable.
    s =
        s.replace(Regex("<a[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", RegexOption.IGNORE_CASE)) { m ->
            val href = m.groupValues[1]
            val label = m.groupValues[2]
            if (label.replace(TAG, "") == href || href.isBlank()) label else "$label ($href)"
        }
    s = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    // Fin de bloc suivie d'un AUTRE bloc (balise ouvrante) : nouvelle ligne. Pas avant `</li>` : la puce suivante
    // en ajoute déjà une.
    s = s.replace(Regex("</(p|h[1-6]|blockquote|pre|ul|ol)>\\s*<(?!/)", RegexOption.IGNORE_CASE), "</$1>\n<")
    s = s.replace(TAG, "")
    s = s.removePrefix(ITEM).replace("\n$ITEM", "\n").replace(ITEM, "\n")
    return decodeEntities(s)
}

private const val ITEM = "\u0000"

fun textToHtml(text: String): String {
    if (text.isEmpty()) return ""
    return text.split('\n').joinToString("") { line -> "<p>${encodeEntities(line)}</p>" }
}

/** Valeur à renvoyer au serveur : l'HTML d'origine si le texte n'a pas changé (sa mise en forme est gardée). */
fun htmlForServer(
    text: String,
    serverHtml: String,
): String = if (htmlToText(serverHtml) == text) serverHtml else textToHtml(text)

private fun encodeEntities(s: String): String =
    s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private fun decodeEntities(s: String): String =
    s
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&amp;", "&")
