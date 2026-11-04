package com.lielu.todo.data.joplin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoplinJsonTest {
    @Test
    fun parseNotesPageLitLesItemsEtHasMore() {
        val item =
            JSONObject()
                .put("id", "a")
                .put("title", "Titre")
                .put("body", "Corps")
                .put("parent_id", "dossier")
                .put("user_updated_time", 123L)
        val page = JSONObject().put("items", JSONArray().put(item)).put("has_more", true)

        val (notes, hasMore) = JoplinJson.parseNotesPage(page)

        assertTrue(hasMore)
        assertEquals(1, notes.size)
        assertEquals(RemoteNote("a", "Titre", "Corps", "dossier", 123L), notes.first())
    }

    @Test
    fun parseNotesPageSansItemsNiHasMoreEstUnePageVideTerminee() {
        val (notes, hasMore) = JoplinJson.parseNotesPage(JSONObject())

        assertTrue(notes.isEmpty())
        assertFalse(hasMore)
    }

    @Test
    fun parseNoteMetDesValeursParDefautAuxChampsAbsents() {
        val note = JoplinJson.parseNote(JSONObject().put("id", "a"))

        assertEquals("a", note.id)
        assertEquals("", note.title)
        assertEquals("", note.body)
        assertEquals("", note.parentId)
        assertEquals(0L, note.userUpdatedTime)
    }
}
