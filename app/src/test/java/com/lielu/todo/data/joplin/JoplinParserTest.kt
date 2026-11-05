package com.lielu.todo.data.joplin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JoplinParserTest {
    private val sample =
        """
        Courses du samedi

        - pain
        - lait

        id: 0f3c2a1b9d8e4f7a6b5c4d3e2f1a0987
        parent_id: 9a1b2c3d4e5f60718293a4b5c6d7e8f9
        created_time: 2026-11-02T08:12:44.000Z
        updated_time: 2026-11-03T18:01:10.000Z
        is_todo: 1
        todo_due: 0
        todo_completed: 0
        encryption_applied: 0
        type_: 1
        """.trimIndent()

    @Test
    fun parseLitTitreCorpsEtProprietes() {
        val item = JoplinParser.parse(sample)

        assertNotNull(item)
        assertEquals("0f3c2a1b9d8e4f7a6b5c4d3e2f1a0987", item!!.id)
        assertEquals(1, item.type)
        assertEquals("Courses du samedi", item.title)
        assertEquals("- pain\n- lait", item.body)
        assertEquals("9a1b2c3d4e5f60718293a4b5c6d7e8f9", item.parentId)
        assertEquals(1793728870000L, item.updatedTime)
        assertTrue(item.isTodo)
    }

    @Test
    fun parseRejetteUnIdInvalide() {
        val invalide = sample.replace("0f3c2a1b9d8e4f7a6b5c4d3e2f1a0987", "trop-court")
        assertNull(JoplinParser.parse(invalide))
    }

    @Test
    fun parseRejetteSansType() {
        assertNull(JoplinParser.parse(sample.replace("type_: 1", "")))
    }

    @Test
    fun parseTolereUnContenuVide() {
        val item = JoplinParser.parse("id: ${"a".repeat(32)}\ntype_: 1\n")

        assertNotNull(item)
        assertEquals("", item!!.title)
        assertEquals("", item.body)
    }

    @Test
    fun serializeRespecteLeFormat() {
        val texte = JoplinParser.serialize("a".repeat(32), "b".repeat(32), "Titre", "Corps", 1793728870000L)

        assertTrue(texte.startsWith("Titre\n\nCorps\n\n"))
        assertTrue(texte.contains("id: ${"a".repeat(32)}\n"))
        assertTrue(texte.contains("type_: 1\n"))
        assertTrue(texte.contains("updated_time: 2026-11-03T18:01:10.000Z\n"))
        // aller-retour : le sérialisé se reparse en la même note
        val reparsed = JoplinParser.parse(texte)
        assertNotNull(reparsed)
        assertEquals("Titre", reparsed!!.title)
        assertEquals("Corps", reparsed.body)
        assertEquals(1793728870000L, reparsed.updatedTime)
    }
}
