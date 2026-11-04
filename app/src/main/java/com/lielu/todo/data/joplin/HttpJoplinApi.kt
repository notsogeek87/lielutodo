package com.lielu.todo.data.joplin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI

/**
 * [JoplinApi] sur l'API Data de Joplin Server, en HTTP natif (aucune dépendance ajoutée) : base
 * `<serverUrl>/api`, en-tête `Authorization: token <apiToken>` sur chaque requête.
 */
class HttpJoplinApi(
    serverUrl: String,
    private val apiToken: String,
    private val timeoutMs: Int = 15_000,
) : JoplinApi {
    private val baseUrl = serverUrl.trim().trimEnd('/') + "/api"

    override suspend fun listNotes(): List<RemoteNote> =
        withContext(Dispatchers.IO) {
            val notes = mutableListOf<RemoteNote>()
            var page = 1
            while (true) {
                val response =
                    request("GET", "/notes?fields=id,title,body,parent_id,user_updated_time&limit=100&page=$page")
                val (items, hasMore) = JoplinJson.parseNotesPage(response)
                notes += items
                if (!hasMore) return@withContext notes
                page++
            }
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

    private fun request(
        method: String,
        path: String,
        payload: JSONObject? = null,
    ): JSONObject {
        val connection =
            (URI("$baseUrl$path").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Authorization", "token $apiToken")
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
