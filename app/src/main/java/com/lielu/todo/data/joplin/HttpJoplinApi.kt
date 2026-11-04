package com.lielu.todo.data.joplin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI

/**
 * [JoplinApi] sur l'API Data de Joplin Server, en HTTP natif (aucune dépendance ajoutée). Authentification
 * par e-mail + mot de passe, comme le client officiel : `POST <serverUrl>/api/sessions` renvoie un id de
 * session, envoyé ensuite en en-tête `X-API-AUTH` sur chaque appel `/api/*`.
 */
class HttpJoplinApi(
    serverUrl: String,
    private val email: String,
    private val password: String,
    private val timeoutMs: Int = 15_000,
) : JoplinApi {
    private val baseUrl = serverUrl.trim().trimEnd('/') + "/api"

    /** Session ouverte à la demande ; une nouvelle instance = une nouvelle connexion. */
    private var sessionId: String? = null

    override suspend fun listNotes(): List<RemoteNote> =
        withContext(Dispatchers.IO) {
            val notes = mutableListOf<RemoteNote>()
            var page = 1
            while (true) {
                val response =
                    request("GET", "/notes?fields=id,title,body,parent_id,user_updated_time&limit=100&page=$page")
                val (items, hasMore) = JoplinJson.parseNotesPage(response)
                notes += items
                if (!hasMore) break
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
            JoplinJson.parseNote(request("POST", "/notes", notePayload(title, body, parentId)))
        }

    override suspend fun updateNote(
        id: String,
        title: String,
        body: String,
        parentId: String,
    ): RemoteNote =
        withContext(Dispatchers.IO) {
            JoplinJson.parseNote(request("PUT", "/notes/$id", notePayload(title, body, parentId)))
        }

    override suspend fun deleteNote(id: String) {
        withContext(Dispatchers.IO) { request("DELETE", "/notes/$id") }
    }

    private fun notePayload(
        title: String,
        body: String,
        parentId: String,
    ): JSONObject =
        JSONObject()
            .put("title", title)
            .put("body", body)
            .put("parent_id", parentId)

    /** Ouvre une session avec l'e-mail et le mot de passe ; lève [JoplinApiException] si refusé (403). */
    private fun login(): String {
        val credentials =
            JSONObject()
                .put("email", email)
                .put("password", password)
        return JoplinJson.parseSession(request("POST", "/sessions", credentials, authenticate = false))
    }

    private fun request(
        method: String,
        path: String,
        payload: JSONObject? = null,
        authenticate: Boolean = true,
    ): JSONObject = requestOnce(method, path, payload, authenticate, allowRelogin = authenticate)

    private fun requestOnce(
        method: String,
        path: String,
        payload: JSONObject?,
        authenticate: Boolean,
        allowRelogin: Boolean,
    ): JSONObject {
        if (authenticate && sessionId == null) sessionId = login()
        val connection =
            (URI("$baseUrl$path").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                if (authenticate) setRequestProperty("X-API-AUTH", sessionId)
                if (payload != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
        try {
            if (payload != null) {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
            }
            val status = connection.responseCode
            if (status == 401 || status == 403) {
                // Session expirée ou invalide : on rouvre une session et on rejoue UNE fois.
                if (allowRelogin && authenticate && path != "/sessions") {
                    sessionId = null
                    return requestOnce(method, path, payload, authenticate, allowRelogin = false)
                }
                val detail =
                    runCatching { connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } }
                        .getOrNull()
                throw JoplinApiException("Authentification refusée ($status) ${detail?.take(200).orEmpty()}", status)
            }
            if (status !in 200..299) {
                val detail =
                    runCatching { connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } }
                        .getOrNull()
                throw JoplinApiException("Joplin a répondu $status ${detail?.take(200).orEmpty()}", status)
            }
            val text = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (e: JoplinApiException) {
            throw e
        } catch (e: Exception) {
            throw JoplinApiException("Serveur Joplin injoignable : ${e.message}", cause = e)
        } finally {
            connection.disconnect()
        }
    }
}
