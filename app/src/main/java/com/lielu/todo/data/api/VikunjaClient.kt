package com.lielu.todo.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Client HTTP minimal (HttpURLConnection + org.json) : aucune dépendance réseau de plus à maintenir. */
class VikunjaClient(
    private val session: SessionStore,
) : HttpApi {
    override suspend fun call(
        method: String,
        path: String,
        query: Map<String, String>,
        body: JSONObject?,
    ): ApiResponse {
        val sentToken = session.token
        try {
            return execute(session.baseUrl, sentToken, method, path, query, body)
        } catch (e: ApiException) {
            if (e.code != HTTP_UNAUTHORIZED || sentToken == null || !reauthenticate(sentToken)) throw e
        }
        return execute(session.baseUrl, session.token, method, path, query, body)
    }

    private val reauthLock = Mutex()

    /**
     * Jeton expiré : se reconnecte avec le mot de passe mémorisé. `false` si impossible (pas de mot de passe,
     * refusé, TOTP exigé) ; une panne réseau remonte telle quelle (hors ligne, pas déconnecté).
     */
    private suspend fun reauthenticate(rejectedToken: String): Boolean =
        reauthLock.withLock {
            if (session.token != rejectedToken) return@withLock session.token != null
            val password = session.password ?: return@withLock false
            try {
                session.updateToken(login(session.baseUrl, session.username, password, ""))
                true
            } catch (e: ApiException) {
                if (e.code >= HTTP_SERVER_ERROR) throw e
                session.forgetPassword()
                false
            }
        }

    override suspend fun upload(
        path: String,
        fileName: String,
        mime: String,
        bytes: ByteArray,
    ): ApiResponse =
        withContext(Dispatchers.IO) {
            val boundary = "----lielutodo" + System.nanoTime()
            val safeName = fileName.replace("\"", "_").replace("\r", "").replace("\n", "")
            val head =
                "--$boundary\r\nContent-Disposition: form-data; name=\"files\"; filename=\"$safeName\"\r\n" +
                    "Content-Type: $mime\r\n\r\n"
            val tail = "\r\n--$boundary--\r\n"
            // UTF-8 : un nom de fichier accentué doit arriver intact.
            val headBytes = head.toByteArray(Charsets.UTF_8)
            val tailBytes = tail.toByteArray(Charsets.UTF_8)
            val connection = URL("${session.baseUrl}/api/v1$path").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "PUT"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                // Un envoi de fichier peut être long : bien plus que les appels JSON.
                connection.readTimeout = UPLOAD_TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/json")
                session.token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(headBytes.size + bytes.size + tailBytes.size)
                connection.outputStream.use {
                    it.write(headBytes)
                    it.write(bytes)
                    it.write(tailBytes)
                }
                val code = connection.responseCode
                val stream = if (code >= HTTP_ERROR) connection.errorStream else connection.inputStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code >= HTTP_ERROR) throw ApiException(code, errorMessage(code, text))
                ApiResponse(code, text, emptyMap())
            } finally {
                connection.disconnect()
            }
        }

    override suspend fun download(path: String): ByteArray =
        withContext(Dispatchers.IO) {
            val connection = URL("${session.baseUrl}/api/v1$path").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = UPLOAD_TIMEOUT_MS
                session.token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                val code = connection.responseCode
                if (code >= HTTP_ERROR) {
                    val text =
                        connection.errorStream
                            ?.bufferedReader(Charsets.UTF_8)
                            ?.use { it.readText() }
                            .orEmpty()
                    throw ApiException(code, errorMessage(code, text))
                }
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }

    /** Renvoie le jeton. Un code TOTP manquant ou faux donne une [ApiException] 412. */
    suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        totp: String,
    ): String {
        val body =
            JSONObject()
                .put("username", username)
                .put("password", password)
                .put("long_token", true)
        if (totp.isNotBlank()) body.put("totp_passcode", totp.trim())
        val response = execute(normalizeUrl(baseUrl), null, "POST", "/login", emptyMap(), body)
        return JSONObject(response.body).getString("token")
    }

    private suspend fun execute(
        baseUrl: String,
        token: String?,
        method: String,
        path: String,
        query: Map<String, String>,
        body: JSONObject?,
    ): ApiResponse =
        withContext(Dispatchers.IO) {
            val queryString =
                query.entries.joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                }
            val url = URL("$baseUrl/api/v1$path" + if (queryString.isEmpty()) "" else "?$queryString")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/json")
                if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                val stream = if (code >= HTTP_ERROR) connection.errorStream else connection.inputStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code >= HTTP_ERROR) throw ApiException(code, errorMessage(code, text))
                val headers =
                    connection.headerFields
                        .filterKeys { it != null }
                        .mapKeys { it.key.lowercase() }
                        .mapValues { it.value.firstOrNull().orEmpty() }
                ApiResponse(code, text, headers)
            } finally {
                connection.disconnect()
            }
        }

    private fun errorMessage(
        code: Int,
        text: String,
    ): String =
        runCatching { JSONObject(text).optString("message") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Erreur $code"

    companion object {
        // Court : serveur coupé = on veut passer au cache vite, pas attendre 20 s à chaque ouverture.
        private const val CONNECT_TIMEOUT_MS = 6_000
        private const val TIMEOUT_MS = 20_000
        private const val UPLOAD_TIMEOUT_MS = 120_000
        private const val HTTP_ERROR = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_SERVER_ERROR = 500

        fun normalizeUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            val withScheme =
                if (trimmed.startsWith("http://") ||
                    trimmed.startsWith("https://")
                ) {
                    trimmed
                } else {
                    "https://$trimmed"
                }
            return withScheme.removeSuffix("/api/v1")
        }

        fun isNetworkError(e: Throwable) = e is IOException && e !is ApiException
    }
}
