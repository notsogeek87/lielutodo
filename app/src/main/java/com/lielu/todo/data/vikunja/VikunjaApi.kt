package com.lielu.todo.data.vikunja

import com.lielu.todo.data.settings.ServiceConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI

/** Erreur de l'API Vikunja (HTTP non 2xx, réseau…), avec le code HTTP quand il y en a un. */
class VikunjaApiException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Vérifie les identifiants Vikunja : `POST /api/v1/login` renvoie un jeton, puis
 * `GET /api/v1/user` (Bearer) renvoie l'utilisateur — succès = la connexion est bonne.
 */
class VikunjaChecker {
    /** Retourne le nom de l'utilisateur Vikunja si les identifiants sont valides. */
    suspend fun testConnection(connection: ServiceConnection): String =
        withContext(Dispatchers.IO) {
            val baseUrl = connection.serverUrl.trim().trimEnd('/') + "/api/v1"
            val login =
                JSONObject()
                    .put("username", connection.email)
                    .put("password", connection.password)
            val token = VikunjaJson.parseToken(request(baseUrl, "POST", "/login", login, authHeader = null))
            val user = request(baseUrl, "GET", "/user", null, authHeader = "Bearer $token")
            VikunjaJson.parseUser(user)
        }

    private fun request(
        baseUrl: String,
        method: String,
        path: String,
        payload: JSONObject?,
        authHeader: String?,
    ): JSONObject {
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
            val text = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (e: VikunjaApiException) {
            throw e
        } catch (e: Exception) {
            throw VikunjaApiException("Serveur Vikunja injoignable : ${e.message}", cause = e)
        } finally {
            connection.disconnect()
        }
    }
}

/** Décodage des réponses JSON de l'API Vikunja, isolé pour être testé sans réseau. */
object VikunjaJson {
    fun parseToken(json: JSONObject): String = json.getString("token")

    fun parseUser(json: JSONObject): String = json.optString("username").ifBlank { json.optString("email") }
}
