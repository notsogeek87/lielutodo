package com.lielu.todo.todo

import com.lielu.todo.data.model.htmlForServer
import com.lielu.todo.data.model.htmlToText
import com.lielu.todo.data.model.taskFromJson
import com.lielu.todo.data.model.textToHtml
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Mêmes cas que `web/test/richtext.test.mjs` (fichier partagé) : les deux ports doivent rester identiques. */
class RichTextTest {
    private val cases = JSONObject(File("../web/test/fixtures/richtext.json").readText())

    private fun pairs(key: String): List<Pair<String, String>> {
        val a = cases.getJSONArray(key)
        return (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } }
    }

    @Test
    fun htmlBecomesReadableText() =
        pairs("toText").forEach { (html, text) ->
            assertEquals(html, text, htmlToText(html))
        }

    @Test
    fun textBecomesParagraphs() = pairs("toHtml").forEach { (text, html) -> assertEquals(html, textToHtml(text)) }

    @Test
    fun anyTextSurvivesTheRoundTrip() {
        val a: JSONArray = cases.getJSONArray("roundTrip")
        (0 until a.length()).map { a.getString(it) }.forEach { assertEquals(it, htmlToText(textToHtml(it))) }
    }

    @Test
    fun unchangedDescriptionKeepsTheServerFormatting() {
        val rich = "<p><strong>Important</strong> : voir <a href=\"https://x.fr\">le site</a></p>"
        assertEquals(rich, htmlForServer(htmlToText(rich), rich))
        assertEquals("<p>Autre</p>", htmlForServer("Autre", rich))

        // Une tâche relue du serveur puis renvoyée sans changement garde sa mise en forme ; le cache relit le texte.
        val task = taskFromJson(JSONObject().put("id", 1).put("project_id", 2).put("description", rich))
        assertEquals("Important : voir le site (https://x.fr)", task.description)
        assertEquals(rich, task.toRequestJson().getString("description"))
        val edited = task.copy(description = "Nouvelle\nligne")
        assertEquals("<p>Nouvelle</p><p>ligne</p>", edited.toRequestJson().getString("description"))
        assertEquals("Nouvelle\nligne", taskFromJson(edited.toJson()).description)
    }
}
