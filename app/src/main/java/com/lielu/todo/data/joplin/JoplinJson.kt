package com.lielu.todo.data.joplin

import org.json.JSONArray
import org.json.JSONObject

/** Décodage des réponses JSON de l'API Joplin Server, isolé pour être testé sans réseau. */
object JoplinJson {
    /** Réponse de `POST /api/sessions` : l'id de session à passer en `X-API-AUTH`. */
    fun parseSession(json: JSONObject): String = json.getString("id")

    /** Page de `GET /api/items/root:/:/delta` : items, has_more, cursor. */
    fun parseDeltaPage(json: JSONObject): DeltaPage {
        val array = json.optJSONArray("items") ?: JSONArray()
        val items =
            (0 until array.length()).map {
                val change = array.getJSONObject(it)
                DeltaItem(
                    name = change.optString("item_name").ifBlank { change.optString("name") },
                    type = change.optInt("type", 1),
                    updatedTime = change.optLong("updated_time", 0L),
                )
            }
        return DeltaPage(
            items = items,
            hasMore = json.optBoolean("has_more", false),
            cursor = json.optString("cursor").ifBlank { null },
        )
    }

    /** `GET /api/shares/{id}/users` : `{items:[{id, status, user:{email}}]}`. */
    fun parseRecipients(json: JSONObject): List<ShareRecipient> {
        val array = json.optJSONArray("items") ?: JSONArray()
        return (0 until array.length()).map {
            val item = array.getJSONObject(it)
            ShareRecipient(
                shareUserId = item.getString("id"),
                email = item.optJSONObject("user")?.optString("email").orEmpty(),
                status = ShareStatus.of(item.optInt("status", 0)),
            )
        }
    }

    /** `GET /api/share_users` : `{items:[{id, status, share:{folder_id, user:{full_name, email}}}]}`. */
    fun parseInvitations(json: JSONObject): List<ShareInvitation> {
        val array = json.optJSONArray("items") ?: JSONArray()
        return (0 until array.length()).map {
            val item = array.getJSONObject(it)
            val share = item.optJSONObject("share")
            val owner = share?.optJSONObject("user")
            ShareInvitation(
                shareUserId = item.getString("id"),
                status = ShareStatus.of(item.optInt("status", 0)),
                folderId = share?.optString("folder_id").orEmpty(),
                ownerName = owner?.optString("full_name").orEmpty(),
                ownerEmail = owner?.optString("email").orEmpty(),
            )
        }
    }

    /** Code d'erreur applicatif d'une réponse d'erreur (ex. resyncRequired), s'il est présent. */
    fun parseErrorCode(body: String?): String? =
        body
            ?.let { runCatching { JSONObject(it).optJSONObject("error")?.optString("code") }.getOrNull() }
            ?.ifBlank { null }
}
