package com.lielu.todo.todo

import com.lielu.todo.data.model.taskFromJson
import com.lielu.todo.data.todo.searchTasks
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskSearchTest {
    private fun task(
        id: Int,
        title: String,
        description: String = "",
        label: String? = null,
    ) = taskFromJson(
        JSONObject()
            .put("id", id)
            .put("title", title)
            .put("description", description)
            .put("project_id", 1)
            .put("labels", JSONArray(listOfNotNull(label?.let { JSONObject().put("id", 1).put("title", it) }))),
    )

    private val tasks =
        listOf(
            task(1, "Appeler le médecin"),
            task(2, "Courses", description = "<p>acheter du pain et du lait</p>"),
            task(3, "Pain maison", label = "cuisine"),
        )

    @Test
    fun ignoresAccentsAndCaseAndFindsDescriptionsAndLabels() {
        assertEquals(listOf(1L), searchTasks(tasks, "MEDECIN").map { it.id })
        assertEquals(listOf(3L), searchTasks(tasks, "cuisine").map { it.id })
        assertEquals(listOf(2L), searchTasks(tasks, "lait").map { it.id })
    }

    @Test
    fun everyWordMustMatchAndTitlesComeFirst() {
        assertEquals(listOf(3L, 2L), searchTasks(tasks, "pain").map { it.id })
        assertEquals(emptyList<Long>(), searchTasks(tasks, "pain voiture").map { it.id })
        assertEquals(emptyList<Long>(), searchTasks(tasks, "  ").map { it.id })
    }
}
