package com.lielu.todo.todo

import com.lielu.todo.data.todo.QuickAdd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** L'heure est obligatoire dès qu'une tâche a une date : ne plus jamais enregistrer un jour « sans heure ». */
class TimeRequiredTest {
    private val day = LocalDate.of(2026, 10, 27)

    @Test
    fun `une date sans heure doit demander l'heure`() {
        assertTrue(QuickAdd(title = "A", date = day).needsTime)
    }

    @Test
    fun `date et heure, heure seule ou rien ne demandent rien`() {
        assertFalse(QuickAdd(title = "A", date = day, time = LocalTime.of(9, 0)).needsTime)
        assertFalse(QuickAdd(title = "A", time = LocalTime.of(9, 0)).needsTime)
        assertFalse(QuickAdd(title = "A").needsTime)
    }

    @Test
    fun `chaque ecran qui choisit une date demande aussi l'heure`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main").exists() }
        val sources = File(root, "app/src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        val offenders =
            sources
                .filter { "DatePickerSheet(" in it.readText() && !it.name.startsWith("Pickers") }
                .filter { "TimePickerDialog(" !in it.readText() }
                .map { it.name }
        assertEquals("DatePickerSheet sans TimePickerDialog : heure oubliée", emptyList<String>(), offenders)
    }

    @Test
    fun `le detail d'une tache ne propose pas Sans heure`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main").exists() }
        val detail = File(root, "app/src/main/java/com/lielu/todo/ui/task/TaskDetailScreen.kt").readText()
        assertFalse("onNoTime" in detail)
    }
}
