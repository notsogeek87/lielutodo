package com.lielu.todo.data.joplin

import org.json.JSONObject

/** Décodage des réponses JSON de l'API Joplin, isolé pour être testé sans réseau. */
object JoplinJson {
    /** Une page de `GET /api/notes` : ses notes, et s'il en reste d'autres (`has_more`). */
    fun parseNotesPage(page: JSONObject): Pair<List<RemoteNote>, Boolean> {
        val items = page.optJSONArray("items") ?: return emptyList<RemoteNote>() to false
        val notes = (0 until items.length()).map { parseNote(items.getJSONObject(it)) }
        return notes to page.optBoolean("has_more", false)
    }

    /** Réponse de `POST /api/sessions` : l'id de session à passer en `X-API-AUTH`. */
    fun parseSession(json: JSONObject): String = json.getString("id")

    fun parseNote(json: JSONObject): RemoteNote =
        RemoteNote(
            id = json.getString("id"),
            title = json.optString("title"),
            body = json.optString("body"),
            parentId = json.optString("parent_id"),
            userUpdatedTime = json.optLong("user_updated_time", 0L),
        )
}
