package com.lielu.todo.data.vikunja

import com.lielu.todo.data.settings.ServiceConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI

/** Une tâche Vikunja, aplatie pour l'affichage (titre du projet résolu). */
data class VikunjaTask(
    val id: Long,
    val title: String,
    val done: Boolean,
    val dueDate: String?,
    val projectTitle: String,
)

/** Erreur de l'API Vikunja (HTTP non 2xx, réseau…), avec le code HTTP quand il y en a un. */
class VikunjaApiException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Client de l'API v1 de Vikunja : authentification par `POST /api/v1/login` (e-mail + mot de passe →
 * jeton JWT, en-tête `Authorization: Bearer …`), puis lecture des projets et des tâches.
 */
class VikunjaApi {
    /** Retourne le nom de l'utilisateur si les identifiants sont valides (bouton « Tester la connexion »). */
    suspend fun testConnection(connection: ServiceConnection): String =
        withContext(Dispatchers.IO) {
            val baseUrl = baseUrl(connection)
            val token = login(baseUrl, connection)
            VikunjaJson.parseUser(request(baseUrl, "GET", "/user", null, "Bearer $token").body)
        }

    /** Toutes les tâches de l'utilisateur, non faites d'abord, triées par échéance. */
    suspend fun listTasks(connection: ServiceConnection): List<VikunjaTask> =
        withContext(Dispatchers.IO) {
            val baseUrl = baseUrl(connection)
            val token = login(baseUrl, connection)
            val projects =
                VikunjaJson.parseProjects(
                    JSONArray(request(baseUrl, "GET", "/projects?per_page=50", null, "Bearer $token").body),
                )
            val tasks = mutableListOf<VikunjaTask>()
            var page = 1
            while (true) {
                val response =
                    request(baseUrl, "GET", "/tasks/all?page=$page&per_page=50", null, "Bearer $token")
                val array = JSONArray(response.body)
                if (array.length() == 0) break
                for (i in 0 until array.length()) {
                    tasks += VikunjaJson.parseTask(array.getJSONObject(i), projects)
                }
                if (page >= response.totalPages) break
                page++
            }
            tasks.sortedWith(compareBy({ it.done }, { it.dueDate ?: "9999-12-31" }))
        }

    private fun baseUrl(connection: ServiceConnection): String = connection.serverUrl.trim().trimEnd('/') + "/api/v1"

    private fun login(
        baseUrl: String,
        connection: ServiceConnection,
    ): String {
        val credentials =
            JSONObject()
                .put("username", connection.email)
                .put("password", connection.password)
        return VikunjaJson.parseToken(request(baseUrl, "POST", "/login", credentials, null).body)
    }

    private fun request(
        baseUrl: String,
        method: String,
        path: String,
        payload: JSONObject?,
        authHeader: String?,
    ): HttpResponse {
        val connection =
            (URI("$baseUrl$path").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 15_000
                if (authHeader != null) setRequestProperty("Authorization", authHeader)
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
                throw VikunjaApiException("Vikunja a répondu $status ${detail?.take(200).orEmpty()}", status)
            }
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val totalPages = connection.getHeaderField("x-pagination-total-pages")?.toIntOrNull() ?: 1
            return HttpResponse(body, totalPages)
        } catch (e: VikunjaApiException) {
            throw e
        } catch (e: Exception) {
            throw VikunjaApiException("Serveur Vikunja injoignable : ${e.message}", cause = e)
        } finally {
            connection.disconnect()
        }
    }

    private class HttpResponse(
        val body: String,
        val totalPages: Int,
    )
}

/** Décodage des réponses JSON de l'API Vikunja, isolé pour être testé sans réseau. */
object VikunjaJson {
    fun parseToken(body: String): String = JSONObject(body).getString("token")

    fun parseUser(body: String): String {
        val json = JSONObject(body)
        return json.optString("username").ifBlank { json.optString("email") }
    }

    fun parseProjects(array: JSONArray): Map<Long, String> {
        val projects = mutableMapOf<Long, String>()
        for (i in 0 until array.length()) {
            val project = array.getJSONObject(i)
            projects[project.optLong("id")] = project.optString("title")
        }
        return projects
    }

    fun parseTask(
        json: JSONObject,
        projects: Map<Long, String>,
    ): VikunjaTask =
        VikunjaTask(
            id = json.optLong("id"),
            title = json.optString("title"),
            done = json.optBoolean("done", false),
            dueDate = json.optString("due_date").ifBlank { null },
            projectTitle = projects[json.optLong("project_id")] ?: "",
        )
}
