package com.lielu.todo.data.vikunja

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class VikunjaJsonTest {
    @Test
    fun parseTokenLitLeToken() {
        assertEquals("tok123", VikunjaJson.parseToken("""{"token":"tok123"}"""))
    }

    @Test
    fun parseUserPrefereUsernameSinonEmail() {
        assertEquals("moi", VikunjaJson.parseUser("""{"username":"moi","email":"m@x.eu"}"""))
        assertEquals("m@x.eu", VikunjaJson.parseUser("""{"email":"m@x.eu"}"""))
        assertEquals("", VikunjaJson.parseUser("{}"))
    }

    @Test
    fun parseProjectsConstruitLaMapIdVersTitre() {
        val array = JSONArray("""[{"id":1,"title":"Courses"},{"id":2,"title":"Boulot"}]""")

        assertEquals(mapOf(1L to "Courses", 2L to "Boulot"), VikunjaJson.parseProjects(array))
    }

    @Test
    fun parseTaskLitLesChampsEtResoutLeProjet() {
        val task =
            VikunjaJson.parseTask(
                org.json.JSONObject(
                    """{"id":7,"title":"Acheter du lait","done":false,""" +
                        """"due_date":"2026-11-05T10:00:00Z",""" +
                        """"project_id":2}""",
                ),
                mapOf(2L to "Courses"),
            )


        assertEquals(7L, task.id)
        assertEquals("Acheter du lait", task.title)
        assertEquals(false, task.done)
        assertEquals("2026-11-05T10:00:00Z", task.dueDate)
        assertEquals("Courses", task.projectTitle)
    }

    @Test
    fun parseTaskTolereLesChampsAbsents() {
        val task = VikunjaJson.parseTask(org.json.JSONObject("""{"id":1,"title":"x"}"""), emptyMap())

        assertEquals(false, task.done)
        assertEquals(null, task.dueDate)
        assertEquals("", task.projectTitle)
    }
}
