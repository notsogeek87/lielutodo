package com.lielu.todo.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteImagesTest {
    private val id = "208a92216ff547e1b0383d7cdc31c298"

    @Test
    fun `image jointe Joplin decoupee entre deux textes`() {
        val parts = NoteImages.split("Affiche 2\n![signal-2026.png](:/$id)\nfin")
        assertEquals(
            listOf(
                NotePart.Text("Affiche 2\n"),
                NotePart.Image(id, "signal-2026.png"),
                NotePart.Text("\nfin"),
            ),
            parts,
        )
    }

    @Test
    fun `note sans image reste un seul texte`() {
        assertEquals(
            listOf(NotePart.Text("**gras** [lien](https://lielu.eu)")),
            NoteImages.split("**gras** [lien](https://lielu.eu)"),
        )
        assertEquals(listOf(NotePart.Text("")), NoteImages.split(""))
        assertFalse(NoteImages.hasImages("[pdf](:/$id)"))
        assertTrue(NoteImages.hasImages("![](:/${id.uppercase()})"))
    }

    @Test
    fun `images seules et id en majuscules`() {
        val parts = NoteImages.split("![a](:/${id.uppercase()})![b](:/$id)")
        assertEquals(listOf(NotePart.Image(id, "a"), NotePart.Image(id, "b")), parts)
    }
}
