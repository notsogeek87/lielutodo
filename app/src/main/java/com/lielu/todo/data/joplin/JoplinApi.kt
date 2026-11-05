package com.lielu.todo.data.joplin

/** Une note distante aplatie (titre = première ligne du contenu, corps = le reste) et son contenu brut. */
data class RemoteNote(
    val id: String,
    val title: String,
    val body: String,
    val parentId: String,
    /** `updated_time` de la version distante (ms). */
    val userUpdatedTime: Long,
    val raw: String? = null,
)

/** Un changement renvoyé par le delta : [name] = « <id>.md »…, [type] 1=create 2=update 3=delete. */
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

    // Partage de carnet (Joplin Server, en ligne uniquement). Par défaut non pris en charge : les faux serveurs
    // des tests qui ne s'en servent pas n'ont rien à implémenter.

    /** `POST /api/shares {folder_id}` : crée le partage d'un carnet à la racine, renvoie son id. */
    suspend fun createShare(folderId: String): String = unsupported()

    /** `POST /api/shares/{id}/users {email}` : invite un utilisateur du même serveur. */
    suspend fun addShareRecipient(
        shareId: String,
        email: String,
    ): Unit = unsupported()

    /** `GET /api/shares/{id}/users` : participants et état de leur invitation. */
    suspend fun shareRecipients(shareId: String): List<ShareRecipient> = unsupported()

    /** `DELETE /api/share_users/{id}` : retire un participant (ses copies des notes partagées disparaissent). */
    suspend fun removeShareRecipient(shareUserId: String): Unit = unsupported()

    /** `DELETE /api/shares/{id}` : arrête le partage. */
    suspend fun deleteShare(shareId: String): Unit = unsupported()

    /** `GET /api/share_users` : carnets que d'autres m'ont partagés (invitations). */
    suspend fun invitations(): List<ShareInvitation> = unsupported()

    /** `PATCH /api/share_users/{id} {status}` : accepter (1) ou refuser (2) une invitation. */
    suspend fun respondToInvitation(
        shareUserId: String,
        accept: Boolean,
    ): Unit = unsupported()
}

private fun unsupported(): Nothing = throw UnsupportedOperationException("Partage non pris en charge par ce client")

/** État d'une invitation Joplin Server. */
enum class ShareStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    ;

    companion object {
        fun of(code: Int): ShareStatus =
            when (code) {
                1 -> ACCEPTED
                2 -> REJECTED
                else -> PENDING
            }
    }
}

/** Un participant d'un carnet que je partage. */
data class ShareRecipient(
    val shareUserId: String,
    val email: String,
    val status: ShareStatus,
)

/** Un carnet qu'un autre utilisateur m'a partagé. */
data class ShareInvitation(
    val shareUserId: String,
    val status: ShareStatus,
    val folderId: String,
    val ownerName: String,
    val ownerEmail: String,
)
