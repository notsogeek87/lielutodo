package com.lielu.todo.ui.notes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownEditingTest {
    private fun value(
        text: String,
        start: Int,
        end: Int = start,
    ) = TextFieldValue(text, TextRange(start, end))

    @Test
    fun graSurLeMotSousLeCurseur() {
        val result = MarkdownEditing.toggleWrap(value("le mot ici", 4), "**")

        assertEquals("le **mot** ici", result.text)
        assertEquals(TextRange(5, 8), result.selection)
    }

    @Test
    fun graDejaPresentEstRetire() {
        val result = MarkdownEditing.toggleWrap(value("le **mot** ici", 6), "**")

        assertEquals("le mot ici", result.text)
    }

    @Test
    fun italiqueSurUnMotEnGrasNeLeDefaitPas() {
        val result = MarkdownEditing.toggleWrap(value("**mot**", 3), "*")

        assertEquals("***mot***", result.text)
    }

    @Test
    fun sansMotPoseLesMarqueursCurseurAuMilieu() {
        val result = MarkdownEditing.toggleWrap(value("a ", 2), "~~")

        assertEquals("a ~~~~", result.text)
        assertEquals(TextRange(4), result.selection)
    }

    @Test
    fun puceAjouteeSurLaLigneDuCurseurSansTexteDExemple() {
        val result = MarkdownEditing.toggleLinePrefix(value("un\ndeux\ntrois", 5), MarkdownEditing.BULLET)

        assertEquals("un\n- deux\ntrois", result.text)
        assertEquals(TextRange(7), result.selection)
    }

    @Test
    fun puceSurPlusieursLignesPuisRetiree() {
        val avec = MarkdownEditing.toggleLinePrefix(value("a\nb", 0, 3), MarkdownEditing.BULLET)
        assertEquals("- a\n- b", avec.text)

        val sans = MarkdownEditing.toggleLinePrefix(avec, MarkdownEditing.BULLET)

        assertEquals("a\nb", sans.text)
    }

    @Test
    fun listeNumeroteeNumeroteChaqueLigne() {
        val result = MarkdownEditing.toggleLinePrefix(value("a\nb\nc", 0, 5), MarkdownEditing.NUMBERED)

        assertEquals("1. a\n2. b\n3. c", result.text)
    }

    @Test
    fun titreRemplaceUnAutreMarqueur() {
        val result = MarkdownEditing.toggleLinePrefix(value("- courses", 3), MarkdownEditing.HEADING)

        assertEquals("## courses", result.text)
    }

    @Test
    fun caseACocherSeCocheEtSeDecoche() {
        val coche = MarkdownEditing.toggleCheckbox(value("- [ ] pain", 8))
        assertEquals("- [x] pain", coche.text)

        assertEquals("- [ ] pain", MarkdownEditing.toggleCheckbox(coche).text)
        assertEquals("- [ ] lait", MarkdownEditing.toggleCheckbox(value("lait", 0)).text)
    }

    @Test
    fun lienSurLeMotSousLeCurseurPlaceLeCurseurSurLAdresse() {
        val result = MarkdownEditing.insertLink(value("voir site", 7))

        assertEquals("voir [site](https://)", result.text)
        assertEquals(TextRange(20), result.selection)
    }

    @Test
    fun lienSansTexteSelectionneLeLibelle() {
        val result = MarkdownEditing.insertLink(value("", 0))

        assertEquals("[lien](https://)", result.text)
        assertEquals(TextRange(1, 5), result.selection)
    }

    @Test
    fun entreeProlongeUneListeNumerotee() {
        val result = MarkdownEditing.continueList(value("1. a", 4), value("1. a\n", 5))

        assertEquals("1. a\n2. ", result.text)
        assertEquals(TextRange(8), result.selection)
    }

    @Test
    fun entreeSurUnElementVideSortDeLaListe() {
        val result = MarkdownEditing.continueList(value("- a\n- ", 6), value("- a\n- \n", 7))

        assertEquals("- a\n", result.text)
        assertEquals(TextRange(4), result.selection)
    }

    @Test
    fun entreeHorsListeNeChangeRien() {
        val nouveau = value("texte\n", 6)

        assertEquals(nouveau, MarkdownEditing.continueList(value("texte", 5), nouveau))
    }

    @Test
    fun titreDeduitDeLaPremiereLigne() {
        assertEquals("Courses", MarkdownEditing.titleFromBody("\n\n# Courses\n- pain"))
        assertEquals("", MarkdownEditing.titleFromBody("  \n"))
    }

    @Test
    fun apercuSansMarkdown() {
        assertEquals(
            "Courses · ☐ pain · ☑ lait · très important",
            MarkdownEditing.plainPreview("## Courses\n\n- [ ] pain\n- [x] lait\n**très** important"),
        )
    }
}
