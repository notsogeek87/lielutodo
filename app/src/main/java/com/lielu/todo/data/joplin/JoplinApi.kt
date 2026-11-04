package com.lielu.todo.data.joplin

/** Une note distante aplatie (titre = première ligne du contenu, corps = le reste). */
data class RemoteNote(
    val id: String,
    val title: String,
    val body: String,
    val parentId: String,
    val userUpdatedTime: Long,
)

/** Un changement renvoyé par le delta : [name] = « notes/<id>.md »…, [type] 1=create 2=update 3=delete. */
data class DeltaItem(
    val name: String,
    val type: Int,
    val updatedTime: Long,
)

/** Une page de delta : les changements, s'il en reste ([hasMore]) et le cursor à conserver. */
data class DeltaPage(
    val items: List<DeltaItem>,
    val hasMore: Boolean,
    val cursor: String?,
)

/** Erreur de l'API Joplin Server, avec le code HTTP et le code d'erreur applicatif (ex. resyncRequired). */
class JoplinApiException(
    message: String,
    val statusCode: Int? = null,
    val errorCode: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Client du protocole de synchro de Joplin Server : `POST /api/sessions` (e-mail + mot de passe → id de
 * session, en-tête `X-API-AUTH`), puis delta incrémental (`api/items/root:/:/delta`), lecture/écriture
 * d'items (`…/content`), suppression. Le client officiel ajoute aussi `X-API-MIN-VERSION: 2.6.0`.
 */
interface JoplinApi {
    /** `GET /api/ping` (public) : doit contenir « JoplinServer » si l'adresse est bonne. */
    suspend fun ping(): String

    /** Page de delta depuis [cursor] (null = resync complète). */
    suspend fun delta(cursor: String?): DeltaPage

    /** Enfants paginés de la racine (repli si le delta initial est vide). */
    suspend fun rootChildren(page: Int): DeltaPage

    /** Contenu sérialisé brut d'un item (ex. « notes/<id>.md »). */
    suspend fun itemContent(name: String): String

    /** Écrit le contenu sérialisé d'un item ; renvoie l'item résultant (avec jop_updated_time). */
    suspend fun putContent(
        name: String,
        content: String,
    ): org.json.JSONObject

    suspend fun deleteItem(name: String)
}
