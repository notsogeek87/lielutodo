package com.lielu.todo.data.notes

import com.lielu.todo.data.joplin.JoplinApi
import com.lielu.todo.data.joplin.JoplinApiException
import com.lielu.todo.data.joplin.JoplinParser
import com.lielu.todo.data.joplin.RemoteNote
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

    suspend fun syncNow(): SyncResult = running.withLock { sync() }

    private suspend fun sync(): SyncResult {
        val config = settings.joplin.first()
        if (!config.syncEnabled || !config.isConfigured) {
            return fail("Synchronisation Joplin non configurée (voir Paramètres).")
        }
        val api = apiFactory(config)
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

            var pushed = 0
            for (note in plan.toPush + conflictCopies) {
                var parent = note.parentId.ifBlank { folderFallback }
                if (parent.isEmpty()) {
                    folderFallback = findFirstFolder(api)
                    parent = folderFallback
                }
                if (parent.isEmpty()) {
                    val noteLabel = note.title.ifBlank { "Sans titre" }
                    return fail(
                        "Aucun carnet Joplin trouvé sur le serveur : la note « $noteLabel » " +
                            "n'a pas été envoyée (Joplin exige un carnet).",
                    )
                }
                val now = clock()
                val base = pulledById[note.id]?.raw ?: note.serverRaw
                val content =
                    if (base != null) {
                        JoplinParser.patch(base, note.title, note.body, parent, now)
                    } else {
                        JoplinParser.serialize(
                            note.id,
                            parent,
                            note.title,
                            note.body,
                            updatedTimeMs = now,
                            createdTimeMs = note.createdTime.takeIf { it > 0 } ?: note.userUpdatedTime,
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
                        folders += Folder(parsed.id, parsed.title, parsed.parentId)
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

    private suspend fun fail(message: String): SyncResult {
        settings.recordSyncFailure(message)
        return SyncResult.Failure(message)
    }
}
