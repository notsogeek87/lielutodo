package com.lielu.todo.data.joplin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JoplinJsonTest {
    @Test
    fun parseSessionLitIdDeSession() {
        assertEquals("abc123", JoplinJson.parseSession(JSONObject().put("id", "abc123").put("user_id", "u1")))
    }

    @Test
    fun parseDeltaPageLitItemsHasMoreEtCursor() {
        val page =
            JSONObject()
                .put(
                    "items",
                    JSONArray().put(
                        JSONObject().put("item_name", "notes/abc.md").put("type", 2).put("updated_time", 123L),
                    ),
                )
                .put("has_more", true)
                .put("cursor", "cur-9")

        val parsed = JoplinJson.parseDeltaPage(page)

        assertEquals(1, parsed.items.size)
        assertEquals(DeltaItem("notes/abc.md", 2, 123L), parsed.items.first())
        assertTrue(parsed.hasMore)
        assertEquals("cur-9", parsed.cursor)
    }

    @Test
    fun parseDeltaPageSansCursorRenvoieNull() {
        val parsed = JoplinJson.parseDeltaPage(JSONObject().put("items", JSONArray()))

        assertNull(parsed.cursor)
        assertEquals(false, parsed.hasMore)
    }

    @Test
    fun parseErrorCodeLitLeCodeApplicatif() {
        assertEquals("resyncRequired", JoplinJson.parseErrorCode("""{"error":{"code":"resyncRequired"}}"""))
        assertNull(JoplinJson.parseErrorCode("pas du json"))
        assertNull(JoplinJson.parseErrorCode(null))
    }
}
