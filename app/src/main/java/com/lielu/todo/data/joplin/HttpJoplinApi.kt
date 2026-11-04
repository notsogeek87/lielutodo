package com.lielu.todo.data.joplin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI

/**
 * [JoplinApi] sur le protocole de synchro de Joplin Server (HTTP natif, aucune dépendance ajoutée) :
 * `POST /api/sessions` renvoie un id de session envoyé en `X-API-AUTH` (plus `X-API-MIN-VERSION: 2.6.0`
 * comme le client officiel) ; en cas d'erreur d'auth, on rouvre une session et on rejoue une fois.
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

    override suspend fun delta(cursor: String?): DeltaPage =
        withContext(Dispatchers.IO) {
            val query = if (cursor == null) "" else "?cursor=$cursor"
            JoplinJson.parseDeltaPage(requestJson("GET", "/items/root:/:/delta$query"))
        }

    override suspend fun itemContent(name: String): String =
        withContext(Dispatchers.IO) {
            requestText("GET", "/items/root:/$name:/content")
        }

    override suspend fun putContent(
        name: String,
        content: String,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            requestJson("PUT", "/items/root:/$name:/content", rawBody = content)
        }

    override suspend fun deleteItem(name: String) {
        withContext(Dispatchers.IO) {
            requestText("DELETE", "/items/root:/$name:")
        }
    }

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
                if (authenticate) {
                    setRequestProperty("X-API-AUTH", sessionId)
                    setRequestProperty("X-API-MIN-VERSION", "2.6.0")
                }
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
            val detail = errorDetail(connection)
            if (status == 401 || status == 403) {
                // Session expirée ou invalide : on rouvre une session et on rejoue UNE fois.
                if (allowRelogin && authenticate && path != "/sessions") {
                    sessionId = null
                    return requestOnce(method, path, payload, rawBody, authenticate, allowRelogin = false)
                }
                throw JoplinApiException(
                    "Authentification refusée ($status) sur $baseUrl$path ${detail.orEmpty()}",
                    status,
                    JoplinJson.parseErrorCode(detail),
                )
            }
            if (status !in 200..299) {
                throw JoplinApiException(
                    "Joplin a répondu $status sur $baseUrl$path ${detail.orEmpty()}",
                    status,
                    JoplinJson.parseErrorCode(detail),
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
            ?.take(300)
}
