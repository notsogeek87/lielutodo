package com.lielu.todo.data.api

import org.json.JSONObject
import java.io.IOException

data class ApiResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String>,
)

/** Erreur renvoyée par le serveur (4xx/5xx). `code` = statut HTTP. */
class ApiException(
    val code: Int,
    message: String,
) : IOException(message)

/** Appel HTTP vers le serveur Vikunja ; abstrait pour que les tests n'aient pas besoin de réseau. */
interface HttpApi {
    /** Lève [ApiException] pour un statut >= 400 et [IOException] pour un problème réseau. */
    suspend fun call(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
    ): ApiResponse
}
