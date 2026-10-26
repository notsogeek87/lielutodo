package com.lielu.todo.todo

import com.lielu.todo.data.model.Label
import com.lielu.todo.data.model.Project
import com.lielu.todo.data.todo.QuickAddParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class QuickAddParserTest {
    // Un lundi : les jours de semaine du même nom tombent la semaine suivante.
    private val today = LocalDate.of(2026, 10, 26)
    private val projects = listOf(project(1, "Perso"), project(2, "Maison travaux"))
    private val labels = listOf(Label(10, "urgent", ""), Label(11, "Éco", ""))

    private fun project(
        id: Long,
        title: String,
    ) = Project(id, title, 0, "", false, false, 0.0)

    private fun parse(text: String) = QuickAddParser.parse(text, today, projects, labels)

    @Test
    fun plainTitleIsUntouched() {
        val r = parse("Acheter du pain")
        assertEquals("Acheter du pain", r.title)
        assertNull(r.date)
        assertNull(r.time)
        assertNull(r.priority)
    }

    @Test
    fun tomorrowWithTimeProjectLabelAndPriority() {
        val r = parse("Appeler Marie demain 9h30 #Perso @urgent p1")
        assertEquals("Appeler Marie", r.title)
        assertEquals(today.plusDays(1), r.date)
        assertEquals(LocalTime.of(9, 30), r.time)
        assertEquals(1L, r.projectId)
        assertEquals(listOf(10L), r.labels.map { it.id })
        assertEquals(4, r.priority)
    }

    @Test
    fun weekdayNamedTodayMeansNextWeek() {
        assertEquals(today.plusDays(7), parse("Réunion lundi").date)
        assertEquals(today.plusDays(3), parse("Match jeudi prochain").date)
    }

    @Test
    fun relativeAndNumericDates() {
        assertEquals(today.plusDays(2), parse("Rendre le livre après-demain").date)
        assertEquals(today.plusWeeks(2), parse("Relancer dans 2 semaines").date)
        assertEquals(LocalDate.of(2026, 12, 3), parse("Dentiste 3/12").date)
        assertEquals(LocalDate.of(2027, 3, 1), parse("Impôts 1/3").date)
        assertEquals(LocalDate.of(2026, 11, 5), parse("Facture le 5").date)
    }

    @Test
    fun projectNameIgnoresCaseSpacesAndAccents() {
        assertEquals(2L, parse("Peindre #MaisonTravaux").projectId)
        assertEquals(listOf(11L), parse("Trier @eco").labels.map { it.id })
    }

    @Test
    fun unknownTokensStayInTheTitle() {
        val r = parse("Voir #Inconnu @rien")
        assertEquals("Voir #Inconnu @rien", r.title)
        assertNull(r.projectId)
    }

    @Test
    fun timeWithoutDateRollsToTomorrowWhenPassed() {
        val zone = ZoneId.of("Europe/Zurich")
        val morning = parse("Café 9h").dueInstant(LocalDateTime.of(2026, 10, 26, 8, 0), zone)
        val evening = parse("Café 9h").dueInstant(LocalDateTime.of(2026, 10, 26, 18, 0), zone)
        assertEquals(LocalDateTime.of(2026, 10, 26, 9, 0).atZone(zone).toInstant(), morning)
        assertEquals(LocalDateTime.of(2026, 10, 27, 9, 0).atZone(zone).toInstant(), evening)
        assertNull(parse("Rien").dueInstant(LocalDateTime.of(2026, 10, 26, 8, 0), zone))
    }

    @Test
    fun recurrence() {
        assertEquals(86_400L, parse("Vitamines tous les jours").repeatAfter)
        assertEquals(today, parse("Vitamines tous les jours").date)
        assertEquals(2 * 604_800L, parse("Arroser tous les 2 semaines").repeatAfter)
        assertEquals(1, parse("Loyer chaque mois").repeatMode)
        val weekly = parse("Sport chaque lundi")
        assertEquals(604_800L, weekly.repeatAfter)
        assertEquals(today.plusDays(7), weekly.date)
        assertEquals("Sport", weekly.title)
    }
}
