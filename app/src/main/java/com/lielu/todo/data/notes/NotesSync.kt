package com.lielu.todo.data.notes

import com.lielu.todo.data.joplin.JoplinApi
import com.lielu.todo.data.joplin.JoplinApiException
import com.lielu.todo.data.joplin.JoplinParser
import com.lielu.todo.data.joplin.RemoteNote
import com.lielu.todo.data.joplin.ShareInvitation
import com.lielu.todo.data.joplin.ShareRecipient
import com.lielu.todo.data.joplin.ShareStatus
import com.lielu.todo.data.settings.JoplinSettings
import com.lielu.todo.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

sealed interface SyncResult {
    data class Success(
        val pushed: Int,
        val pulled: Int,
        val deleted: Int,
        /** Conflits résolus en gardant les deux versions (copie « (conflit) »). */
        val conflicts: Int = 0,
        /** Notes chiffrées de bout en bout (Joplin E2EE), illisibles ici : ignorées. */
        val encryptedSkipped: Int = 0,
    ) : SyncResult

    data class Failure(
        val message: String,
    ) : SyncResult
}

/**
 * Synchronise les notes avec Joplin Server via le protocole de synchro (delta incrémental + cursor) :
 * lecture des items modifiés, écriture des notes locales modifiées, suppressions des deux côtés.
 * Le plan est calculé par [SyncPlanner] ; ici on l'exécute via [JoplinApi] et on met le cache à jour.
 *
 * Une seule synchro à la fois (écran, éditeur et tâche de fond peuvent la demander ensemble). Une note modifiée
 * pendant son envoi reste « à envoyer » : elle repart au passage suivant, jamais écrasée par la version envoyée.
 */
class NotesSync(
    private val settings: SettingsRepository,
    private val notes: NotesRepository,
    private val apiFactory: (JoplinSettings) -> JoplinApi,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val running = Mutex()

    /**
     * Client réutilisé tant que les réglages ne changent pas : il garde sa session Joplin. Joplin Server limite les
     * connexions (429 « Too many login attempts ») : rouvrir une session à chaque synchro finirait refusé.
     */
    private var cachedApi: Pair<JoplinSettings, JoplinApi>? = null

    private fun api(config: JoplinSettings): JoplinApi =
        cachedApi?.takeIf { it.first == config }?.second ?: apiFactory(config).also { cachedApi = config to it }

    suspend fun syncNow(): SyncResult = running.withLock { sync() }

    private suspend fun sync(): SyncResult {
        val config = settings.joplin.first()
        if (!config.syncEnabled || !config.isConfigured) {
            return fail("Synchronisation Joplin non configurée (voir Paramètres).")
        }
        val api = api(config)
        return try {
            val ping = api.ping()
            if (!ping.contains("Joplin")) {
                return fail(
                    "Cette adresse ne répond pas comme un serveur Joplin (ping : « $ping »). " +
                        "Vérifiez l'adresse (ex. https://notes.lielu.eu, sans /api à la fin).",
                )
            }
            var cursor = settings.joplinCursor.first()
            // Cache vide (première version, restauration…) alors qu'un cursor est posé : le delta incrémental ne
            // renverrait rien, on repart d'une synchro complète. Hors de ce cas, la synchro reste incrémentale.
            if (cursor != null && (notes.getFolders().isEmpty() || notes.getAllActive().isEmpty())) cursor = null
            val pull = pullChanges(api, cursor)
            if (pull.folders.isNotEmpty()) notes.upsertFolders(pull.folders)
            if (pull.deletedFolders.isNotEmpty()) notes.removeFolders(pull.deletedFolders)

            val plan = SyncPlanner.plan(notes.getAllIncludingDeleted(), pull.pulled, pull.remoteDeleted)
            val pulledById = pull.pulled.associateBy { it.id }
            var folderFallback =
                pull.defaultFolder.ifEmpty {
                    notes
                        .getFolders()
                        .firstOrNull()
                        ?.id
                        .orEmpty()
                }

            // Conflits : la version distante est gardée à part, dans une nouvelle note poussée avec les autres.
            val conflictCopies =
                plan.conflicts.map { remote ->
                    val now = clock()
                    Note(
                        id = UUID.randomUUID().toString().replace("-", ""),
                        title = "${remote.title.ifBlank { "Sans titre" }} (conflit ${conflictLabel(now)})",
                        body = remote.body,
                        parentId = remote.parentId,
                        userUpdatedTime = now,
                        createdTime = now,
                        dirty = true,
                    ).also { notes.upsert(it) }
                }

            // Carnets créés ou modifiés ici (partage compris) : envoyés avant les notes qu'ils contiennent.
            for (folder in notes.getFolders().filter { it.dirty }) {
                val now = clock()
                val content =
                    folder.serverRaw?.let {
                        JoplinParser.patch(it, folder.title, "", folder.parentId, now, shareId = folder.shareId)
                    } ?: JoplinParser.serializeFolder(
                        folder.id,
                        folder.title,
                        folder.parentId,
                        updatedTimeMs = now,
                        createdTimeMs = folder.createdTime.takeIf { it > 0 } ?: now,
                        shareId = folder.shareId,
                    )
                api.putContent("${folder.id}.md", content)
                notes.updateFolder(folder.id) { current ->
                    when {
                        current == null -> null
                        current.title != folder.title || current.shareId != folder.shareId ->
                            current.copy(serverRaw = content)
                        else -> current.copy(dirty = false, serverRaw = content)
                    }
                }
                if (folderFallback.isEmpty()) folderFallback = folder.id
            }
            val shareIdByFolder = notes.getFolders().associate { it.id to it.shareId }

            var pushed = 0
            for (note in plan.toPush + conflictCopies) {
                var parent = note.parentId.ifBlank { folderFallback }
                if (parent.isEmpty()) {
                    folderFallback = findFirstFolder(api)
                    parent = folderFallback
                }
                if (parent.isEmpty()) {
                    // Aucun carnet nulle part (compte Joplin neuf) : Joplin en exige un, on le crée.
                    val created = notes.createFolder("Notes")
                    val now = clock()
                    val content = JoplinParser.serializeFolder(created.id, created.title, "", now, now)
                    api.putContent("${created.id}.md", content)
                    notes.updateFolder(created.id) { it?.copy(dirty = false, serverRaw = content) }
                    folderFallback = created.id
                    parent = created.id
                }
                // Une note d'un carnet partagé porte son share_id, sinon les autres participants ne la voient pas.
                val shareId = shareIdByFolder[parent].orEmpty()
                val now = clock()
                val base = pulledById[note.id]?.raw ?: note.serverRaw
                val content =
                    if (base != null) {
                        JoplinParser.patch(base, note.title, note.body, parent, now, shareId = shareId)
                    } else {
                        JoplinParser.serialize(
                            note.id,
                            parent,
                            note.title,
                            note.body,
                            updatedTimeMs = now,
                            createdTimeMs = note.createdTime.takeIf { it > 0 } ?: note.userUpdatedTime,
                            shareId = shareId,
                        )
                    }
                api.putContent(pull.nameById[note.id] ?: "${note.id}.md", content)
                notes.update(note.id) { current ->
                    when {
                        current == null -> null
                        // Modifiée pendant l'envoi : reste à envoyer, avec la nouvelle base serveur.
                        current.title != note.title ||
                            current.body != note.body ||
                            current.parentId != note.parentId ||
                            current.deleted ->
                            current.copy(serverRaw = content, serverUpdatedTime = now)
                        else ->
                            current.copy(
                                parentId = parent,
                                dirty = false,
                                serverRaw = content,
                                serverUpdatedTime = now,
                                userUpdatedTime = now,
                            )
                    }
                }
                pushed++
            }

            for (note in plan.toDeleteRemote) {
                val base = pulledById[note.id]?.raw ?: note.serverRaw
                if (base != null) {
                    // Corbeille Joplin plutôt que suppression définitive : récupérable depuis Joplin.
                    val now = clock()
                    api.putContent(
                        pull.nameById[note.id] ?: "${note.id}.md",
                        JoplinParser.patch(base, note.title, note.body, note.parentId, now, deletedTimeMs = now),
                    )
                } else {
                    try {
                        api.deleteItem(pull.nameById[note.id] ?: "${note.id}.md")
                    } catch (e: JoplinApiException) {
                        if (e.statusCode != 404) throw e
                    }
                }
                notes.update(note.id) { current -> current?.takeIf { !it.deleted } }
            }

            for (remote in plan.toApplyRemote) {
                notes.update(remote.id) { current ->
                    if (current != null && current.dirty) {
                        current // modifiée ici entre-temps : la prochaine synchro tranchera
                    } else {
                        (current ?: Note(id = remote.id, title = "", body = "", createdTime = remote.userUpdatedTime))
                            .copy(
                                title = remote.title,
                                body = remote.body,
                                parentId = remote.parentId,
                                userUpdatedTime = remote.userUpdatedTime,
                                dirty = false,
                                deleted = false,
                                serverRaw = remote.raw,
                                serverUpdatedTime = remote.userUpdatedTime,
                            )
                    }
                }
            }
            for (id in plan.toDeleteLocal) {
                notes.update(id) { current -> current?.takeIf { it.dirty } }
            }

            settings.setJoplinCursor(pull.cursor)
            settings.recordSyncSuccess(clock())
            SyncResult.Success(
                pushed = pushed,
                pulled = plan.toApplyRemote.size,
                deleted = plan.toDeleteRemote.size + plan.toDeleteLocal.size,
                conflicts = plan.conflicts.size,
                encryptedSkipped = pull.encryptedSkipped,
            )
        } catch (e: Exception) {
            fail("Échec de la synchronisation : ${e.message}")
        }
    }

    /**
     * Boucle le delta (tant que has_more), avec resync complète si le cursor est rejeté (resyncRequired).
     * Si une synchro complète (sans cursor) ne renvoie aucun changement, on retombe sur la liste des
     * enfants de la racine (certaines versions de serveur ne remplissent pas le delta initial).
     */
    private suspend fun pullChanges(
        api: JoplinApi,
        startCursor: String?,
    ): PullResult {
        var cursor: String? = startCursor
        var fullSync = startCursor == null
        var didResync = false
        val acc = PullAccumulator()
        var changesSeen = 0
        while (true) {
            val page =
                try {
                    api.delta(cursor)
                } catch (e: JoplinApiException) {
                    if (e.errorCode == "resyncRequired" && !didResync) {
                        didResync = true
                        fullSync = true
                        cursor = null
                        continue
                    }
                    throw e
                }
            changesSeen += page.items.size
            for (change in page.items) {
                val name = change.name
                if (isIgnored(name)) continue
                // Sur Joplin Server, un item est un fichier « <id>.md » à la racine ; le type réel vient du
                // contenu (type_), pas du nom.
                val idFromName = name.removeSuffix(".md").substringAfterLast('/')
                if (change.type == 3) {
                    acc.remoteDeleted += idFromName
                    acc.deletedFolders += idFromName
                    acc.pulled.remove(idFromName)
                    continue
                }
                if (!name.endsWith(".md")) continue
                acc.add(name, api.itemContent(name))
            }
            if (page.cursor != null) cursor = page.cursor
            if (!page.hasMore) break
        }
        if (fullSync && changesSeen == 0) listRootChildren(api, acc)
        return acc.result(cursor)
    }

    /** Repli : liste paginée de `GET /api/items/root:/:/children`, mêmes filtres que le delta. */
    private suspend fun listRootChildren(
        api: JoplinApi,
        acc: PullAccumulator,
    ) {
        var page = 1
        while (true) {
            val children = api.rootChildren(page)
            for (change in children.items) {
                if (isIgnored(change.name) || !change.name.endsWith(".md")) continue
                acc.add(change.name, api.itemContent(change.name))
            }
            if (!children.hasMore) break
            page++
        }
    }

    /** Premier carnet trouvé à la racine (repli si ni le delta ni le cache n'en contiennent). */
    private suspend fun findFirstFolder(api: JoplinApi): String {
        val children = api.rootChildren(1)
        for (change in children.items) {
            if (isIgnored(change.name) || !change.name.endsWith(".md")) continue
            val parsed = JoplinParser.parse(api.itemContent(change.name))
            if (parsed != null && parsed.type == 2 && parsed.deletedTime == 0L) return parsed.id
        }
        return ""
    }

    private fun isIgnored(name: String): Boolean =
        name.startsWith("locks/") || name.startsWith("temp/") || name.startsWith(".resource/")

    /** Ce que le delta (ou la liste de repli) a rapporté, item par item. */
    private class PullAccumulator {
        val pulled = LinkedHashMap<String, RemoteNote>()
        val remoteDeleted = mutableSetOf<String>()
        val nameById = mutableMapOf<String, String>()
        val folders = mutableListOf<Folder>()
        val deletedFolders = mutableSetOf<String>()
        var defaultFolder = ""
        var encryptedSkipped = 0

        fun add(
            name: String,
            content: String,
        ) {
            val parsed = JoplinParser.parse(content) ?: return
            if (parsed.encrypted) {
                if (parsed.type == 1) encryptedSkipped++
                return
            }
            when (parsed.type) {
                2 ->
                    if (parsed.deletedTime != 0L) {
                        deletedFolders += parsed.id
                    } else {
                        folders +=
                            Folder(
                                parsed.id,
                                parsed.title,
                                parsed.parentId,
                                shareId = parsed.shareId,
                                serverRaw = content,
                            )
                        if (defaultFolder.isEmpty()) defaultFolder = parsed.id
                    }
                1 ->
                    if (parsed.deletedTime != 0L) {
                        remoteDeleted += parsed.id
                        pulled.remove(parsed.id)
                    } else {
                        nameById[parsed.id] = name
                        pulled[parsed.id] =
                            RemoteNote(
                                parsed.id,
                                parsed.title,
                                parsed.body,
                                parsed.parentId,
                                parsed.updatedTime,
                                raw = content,
                            )
                    }
            }
        }

        fun result(cursor: String?) =
            PullResult(
                pulled = pulled.values.toList(),
                remoteDeleted = remoteDeleted,
                defaultFolder = defaultFolder,
                cursor = cursor,
                nameById = nameById,
                folders = folders,
                deletedFolders = deletedFolders - folders.map { it.id }.toSet(),
                encryptedSkipped = encryptedSkipped,
            )
    }

    private data class PullResult(
        val pulled: List<RemoteNote>,
        val remoteDeleted: Set<String>,
        val defaultFolder: String,
        val cursor: String?,
        val nameById: Map<String, String>,
        val folders: List<Folder>,
        val deletedFolders: Set<String>,
        val encryptedSkipped: Int,
    )

    private fun conflictLabel(epochMs: Long): String =
        DateTimeFormatter
            .ofPattern("dd/MM HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs))

    // ----- Partage de carnet (en ligne : le serveur doit valider) -----

    /**
     * Partage le carnet [folderId] avec le compte [email] du même Joplin Server. Ordre vérifié contre un vrai
     * serveur : le carnet est d'abord synchronisé, le partage créé, le carnet et ses notes renvoyés avec leur
     * `share_id`, et SEULEMENT ensuite l'invitation envoyée. L'invité l'accepte dans Joplin ou dans LieluTodo.
     */
    suspend fun shareFolder(
        folderId: String,
        email: String,
    ): Result<Unit> =
        sharing { api ->
            val folder = notes.getFolders().firstOrNull { it.id == folderId } ?: error("Carnet introuvable.")
            require(
                folder.parentId.isBlank(),
            ) { "Joplin ne partage que les carnets à la racine (pas les sous-carnets)." }
            require(email.contains('@')) { "Adresse e-mail invalide." }
            syncOrThrow()
            var shareId =
                notes
                    .getFolders()
                    .firstOrNull { it.id == folderId }
                    ?.shareId
                    .orEmpty()
            if (shareId.isBlank()) {
                shareId = api.createShare(folderId)
                markShareId(folderId, shareId)
                syncOrThrow()
            }
            api.addShareRecipient(shareId, email)
        }

    /** Participants du carnet partagé (vide s'il n'est pas partagé). */
    suspend fun shareRecipients(folderId: String): Result<List<ShareRecipient>> =
        sharing { api ->
            val shareId =
                notes
                    .getFolders()
                    .firstOrNull { it.id == folderId }
                    ?.shareId
                    .orEmpty()
            if (shareId.isBlank()) emptyList() else api.shareRecipients(shareId)
        }

    suspend fun removeShareRecipient(shareUserId: String): Result<Unit> =
        sharing { api -> api.removeShareRecipient(shareUserId) }

    /** Arrête le partage : les participants perdent l'accès, le carnet et ses notes restent ici et sur le serveur. */
    suspend fun stopSharing(folderId: String): Result<Unit> =
        sharing { api ->
            val shareId =
                notes
                    .getFolders()
                    .firstOrNull { it.id == folderId }
                    ?.shareId
                    .orEmpty()
            if (shareId.isNotBlank()) {
                api.deleteShare(shareId)
                markShareId(folderId, "")
                syncOrThrow()
            }
        }

    /** Toutes mes invitations (en attente, acceptées, refusées) : une refusée peut encore être acceptée. */
    suspend fun allInvitations(): Result<List<ShareInvitation>> = sharing { api -> api.invitations() }

    /** Carnets que d'autres m'ont partagés et que je n'ai pas encore acceptés ni refusés. */
    suspend fun pendingInvitations(): Result<List<ShareInvitation>> =
        sharing { api -> api.invitations().filter { it.status == ShareStatus.PENDING } }

    /** Accepter (le carnet arrive à la synchro qui suit aussitôt) ou refuser une invitation. */
    suspend fun respondToInvitation(
        shareUserId: String,
        accept: Boolean,
    ): Result<Unit> =
        sharing { api ->
            api.respondToInvitation(shareUserId, accept)
            if (accept) syncOrThrow()
        }

    /** Le carnet et toutes ses notes prennent [shareId] et repartent au serveur à la prochaine synchro. */
    private suspend fun markShareId(
        folderId: String,
        shareId: String,
    ) {
        notes.updateFolder(folderId) { it?.copy(shareId = shareId, dirty = true) }
        notes
            .getAllIncludingDeleted()
            .filter { it.parentId == folderId && !it.deleted }
            .forEach { note -> notes.update(note.id) { it?.copy(dirty = true) } }
    }

    private suspend fun syncOrThrow() {
        val result = sync()
        if (result is SyncResult.Failure) throw IllegalStateException(result.message)
    }

    private suspend fun <T> sharing(block: suspend (JoplinApi) -> T): Result<T> =
        running.withLock {
            val config = settings.joplin.first()
            if (!config.syncEnabled || !config.isConfigured) {
                return@withLock Result.failure(
                    IllegalStateException("Synchronisation Joplin non configurée (voir Paramètres)."),
                )
            }
            try {
                Result.success(block(api(config)))
            } catch (e: JoplinApiException) {
                Result.failure(IllegalStateException(shareErrorMessage(e), e))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun shareErrorMessage(e: JoplinApiException): String {
        val text = e.message.orEmpty()
        return when {
            text.contains("User not found") -> "Aucun compte avec cette adresse sur ce serveur Joplin."
            text.contains("already", ignoreCase = true) -> "Ce carnet est déjà partagé avec cette personne."
            e.statusCode == 403 -> "Le serveur Joplin refuse le partage pour ce compte."
            e.statusCode == null -> "Serveur Joplin injoignable : le partage demande d'être en ligne."
            else -> "Partage refusé par le serveur Joplin (${e.statusCode})."
        }
    }

    private suspend fun fail(message: String): SyncResult {
        settings.recordSyncFailure(message)
        return SyncResult.Failure(message)
    }
}
