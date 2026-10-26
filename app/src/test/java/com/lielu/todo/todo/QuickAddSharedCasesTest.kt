package com.lielu.todo.todo

import com.lielu.todo.data.model.Label
import com.lielu.todo.data.model.Project
import com.lielu.todo.data.model.objects
import com.lielu.todo.data.todo.QuickAddParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Mêmes cas que `web/test/parser.test.mjs` (fichier partagé) : les deux parseurs doivent rester identiques. */
class QuickAddSharedCasesTest {
    @Test
    fun bothParsersGiveTheSameResults() {
        val doc = JSONObject(File("../web/test/fixtures/quickadd.json").readText())
        val today = LocalDate.parse(doc.getString("today"))
        val projects =
            doc.getJSONArray("projects").objects().map {
                Project(it.getLong("id"), it.getString("title"), 0, "", false, false, 0.0)
            }
        val labels = doc.getJSONArray("labels").objects().map { Label(it.getLong("id"), it.getString("title"), "") }
        val failures =
            doc.getJSONArray("cases").objects().mapNotNull { c ->
                val r = QuickAddParser.parse(c.getString("input"), today, projects, labels)
                val got =
                    listOf(
                        r.title,
                        r.date?.toString(),
                        r.time?.toString(),
                        r.priority,
                        r.projectId,
                        r.labels.map { it.id },
                        r.repeatAfter,
                        r.repeatMode,
                    )
                val expected =
                    listOf(
                        c.getString("title"),
                        c.optString("date").takeIf { !c.isNull("date") },
                        c.optString("time").takeIf { !c.isNull("time") },
                        if (c.isNull("priority")) null else c.getInt("priority"),
                        if (c.isNull("projectId")) null else c.getLong("projectId"),
                        (0 until c.getJSONArray("labels").length()).map { c.getJSONArray("labels").getLong(it) },
                        c.getLong("repeatAfter"),
                        c.getInt("repeatMode"),
                    )
                if (got == expected) null else "« ${c.getString("input")} » : attendu $expected, obtenu $got"
            }
        assertEquals("", failures.joinToString("\n"))
    }
}
