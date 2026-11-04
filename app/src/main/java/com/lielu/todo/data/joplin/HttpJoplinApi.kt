package com.lielu.todo.data.joplin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/**
 * [JoplinApi] contre un **Joplin Server** via son API de synchro (l'API « Data » /api/notes n'existe que
 * sur l'app desktop) : session par `POST /api/sessions` (e-mail + mot de passe → id, envoyé en `X-API-AUTH`),
 * puis items sous `api/items/root:/…`. Une note est l'item `notes/<id>.md` : métadonnées via `…/children`,
 * contenu Markdown via `…/content` (le titre Joplin est la première ligne du contenu).
 */
class HttpJoplinApi(
    serverUrl: String,
    private val email: String,
    private val password: String,
    private val timeoutMs: Int = 15_000,
) : JoplinApi {
    // Si l'utilisateur a collé l'URL de base de l'API (…/api), on ne la double pas.
    private val baseUrl =
        serverUrl.trim().trimEnd('/').let { if (it.endsWith("/api")) it else "$it/api" }

    /** Session ouverte à la demande ; une nouvelle instance = une nouvelle connexion. */
    private var sessionId: String? = null

    override suspend fun ping(): String =
        withContext(Dispatchers.IO) {
            requestText("GET", "/ping", authenticate = false)
        }

    override suspend fun listNotes(): List<RemoteNote> =
        withContext(Dispatchers.IO) {
            val notes = mutableListOf<RemoteNote>()
            var page = 1
            while (true) {
                val response =
                    requestJson(
                        "GET",
                        "/items/root:/notes/*:/children?limit=100&page=$page",
                        authenticate = true,
                    )
                val items = response.optJSONArray("items") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    if (item.optBoolean("deleted", false)) continue
                    val name = item.optString("name").ifBlank { item.optString("item_name") }
                    val id = item.optString("jop_id").ifBlank { itemNameToId(name) }
                    if (id.isBlank()) continue
                    val content = requestText("GET", "/items/root:/notes/$id.md:/content", authenticate = true)
                    notes += remoteNote(id, item, content)
                }
                if (!response.optBoolean("has_more", false)) break
                page++
            }
            notes
        }

    override suspend fun createNote(
        title: String,
        body: String,
        parentId: String,
    ): RemoteNote =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString().replace("-", "")
            val item = putContent(id, title, body)
            remoteNote(id, item, "$title\n$body")
        }

    override suspend fun updateNote(
        id: String,
        title: String,
        body: String,
        parentId: String,
    ): RemoteNote =
        withContext(Dispatchers.IO) {
            val item = putContent(id, title, body)
            remoteNote(id, item, "$title\n$body")
        }

    override suspend fun deleteNote(id: String) {
        withContext(Dispatchers.IO) {
            requestText("DELETE", "/items/root:/notes/$id.md:", authenticate = true)
        }
    }

    /** Écrit le contenu Markdown d'une note (création ou mise à jour) et renvoie l'item résultant. */
    private fun putContent(
        id: String,
        title: String,
        body: String,
    ): JSONObject {
        val markdown = if (body.isBlank()) title else "$title\n$body"
        return requestJson(
            "PUT",
            "/items/root:/notes/$id.md:/content",
            rawBody = markdown,
            authenticate = true,
        )
    }

    private fun remoteNote(
        id: String,
        item: JSONObject,
        content: String,
    ): RemoteNote {
        val firstLine =
            content
                .lineSequence()
                .firstOrNull()
                .orEmpty()
                .removePrefix("#")
                .trim()
        return RemoteNote(
            id = id,
            title = firstLine,
            body = content,
            parentId = item.optString("jop_parent_id"),
            userUpdatedTime = item.optLong("jop_updated_time", item.optLong("updated_time", 0L)),
        )
    }

    private fun itemNameToId(itemName: String): String = itemName.removePrefix("notes/").removeSuffix(".md")

    /** Ouvre une session avec l'e-mail et le mot de passe ; lève [JoplinApiException] si refusé (403). */
    private fun login(): String {
        val credentials =
            JSONObject()
                .put("email", email)
                .put("password", password)
        return JoplinJson.parseSession(requestJson("POST", "/sessions", credentials, authenticate = false))
    }

    private fun requestJson(
        method: String,
        path: String,
        payload: JSONObject? = null,
        rawBody: String? = null,
        authenticate: Boolean = true,
    ): JSONObject =
        requestOnce(method, path, payload, rawBody, authenticate, allowRelogin = authenticate)
            .let { if (it.isBlank()) JSONObject() else JSONObject(it) }

    private fun requestText(
        method: String,
        path: String,
        payload: JSONObject? = null,
        rawBody: String? = null,
        authenticate: Boolean = true,
    ): String = requestOnce(method, path, payload, rawBody, authenticate, allowRelogin = authenticate)

    private fun requestOnce(
        method: String,
        path: String,
        payload: JSONObject?,
        rawBody: String?,
        authenticate: Boolean,
        allowRelogin: Boolean,
    ): String {
        if (authenticate && sessionId == null) sessionId = login()
        val connection =
            (URI("$baseUrl$path").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                if (authenticate) setRequestProperty("X-API-AUTH", sessionId)
                when {
                    payload != null -> {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                    rawBody != null -> {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/octet-stream")
                    }
                }
            }
        try {
            when {
                payload != null ->
                    OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
                rawBody != null ->
                    connection.outputStream.use { it.write(rawBody.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status == 401 || status == 403) {
                // Session expirée ou invalide : on rouvre une session et on rejoue UNE fois.
                if (allowRelogin && authenticate && path != "/sessions") {
                    sessionId = null
                    return requestOnce(method, path, payload, rawBody, authenticate, allowRelogin = false)
                }
                val detail = errorDetail(connection)
                throw JoplinApiException(
                    "Authentification refusée ($status) sur $baseUrl$path ${detail.orEmpty()}",
                    status,
                )
            }
            if (status !in 200..299) {
                throw JoplinApiException(
                    "Joplin a répondu $status sur $baseUrl$path ${errorDetail(connection).orEmpty()}",
                    status,
                )
            }
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: JoplinApiException) {
            throw e
        } catch (e: Exception) {
            throw JoplinApiException("Serveur Joplin injoignable : ${e.message}", cause = e)
        } finally {
            connection.disconnect()
        }
    }

    private fun errorDetail(connection: HttpURLConnection): String? =
        runCatching { connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } }
            .getOrNull()
            ?.take(200)
}
