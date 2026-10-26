package com.lielu.todo.data.api

import kotlinx.coroutines.Dispatchers
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
    ): ApiResponse = execute(session.baseUrl, session.token, method, path, query, body)

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
        private const val HTTP_ERROR = 400

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
