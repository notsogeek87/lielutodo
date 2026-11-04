package com.lielu.todo.data.joplin

/** Une note telle que l'API Data de Joplin Server la renvoie. */
data class RemoteNote(
    val id: String,
    val title: String,
    val body: String,
    val parentId: String,
    val userUpdatedTime: Long,
)

/** Erreur de l'API Joplin (HTTP non 2xx, réseau…), avec le code HTTP quand il y en a un. */
class JoplinApiException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Client de l'API Data de Joplin Server (https://joplinapp.org/api/data_api/) : lecture ET écriture
 * des notes, authentifiées par jeton personnel (`Authorization: token …`).
 */
interface JoplinApi {
    /** `GET /api/ping` (public) : doit contenir « JoplinServer » si l'adresse est bonne. */
    suspend fun ping(): String

    suspend fun listNotes(): List<RemoteNote>

    suspend fun createNote(
        title: String,
        body: String,
        parentId: String,
    ): RemoteNote

    suspend fun updateNote(
        id: String,
        title: String,
        body: String,
        parentId: String,
    ): RemoteNote

    suspend fun deleteNote(id: String)
}
