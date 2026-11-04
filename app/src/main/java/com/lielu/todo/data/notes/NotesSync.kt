package com.lielu.todo.data.notes

import com.lielu.todo.data.joplin.JoplinApi
import com.lielu.todo.data.joplin.JoplinApiException
import com.lielu.todo.data.joplin.JoplinParser
import com.lielu.todo.data.joplin.RemoteNote
import com.lielu.todo.data.settings.JoplinSettings
import com.lielu.todo.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first

sealed interface SyncResult {
    data class Success(
        val pushed: Int,
        val pulled: Int,
        val deleted: Int,
        val changesSeen: Int = 0,
        val samples: List<String> = emptyList(),
        val usedFallback: Boolean = false,
        val rawDelta: String? = null,
    ) : SyncResult

    data class Failure(
        val message: String,
    ) : SyncResult
}

/**
 * Synchronise les notes avec Joplin Server via le protocole de synchro (delta incrémental + cursor) :
 * lecture des items modifiés, écriture des notes locales modifiées, suppressions des deux côtés.
 * Le plan est calculé par [SyncPlanner] ; ici on l'exécute via [JoplinApi] et on met le cache à jour.
 */
class NotesSync(
    private val settings: SettingsRepository,
    private val notes: NotesRepository,
    private val apiFactory: (JoplinSettings) -> JoplinApi,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun syncNow(): SyncResult {
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
            val (pulled, remoteDeleted, defaultFolder, newCursor, changesSeen, samples, usedFallback) = pullChanges(api)
            val plan = SyncPlanner.plan(notes.getAllIncludingDeleted(), pulled, remoteDeleted)
            var pushed = 0
            for (note in plan.toPush) {
                val parent = note.parentId.ifBlank { defaultFolder }
                val updatedMs = clock()
                val item =
                    api.putContent(
                        "notes/${note.id}.md",
                        JoplinParser.serialize(note.id, parent, note.title, note.body, updatedMs),
                    )
                val serverTime = item.optLong("jop_updated_time", item.optLong("updated_time", updatedMs))
                notes.upsert(note.copy(userUpdatedTime = serverTime, dirty = false, deleted = false))
                pushed++
            }
            for (id in plan.toDeleteRemote) {
                try {
                    api.deleteItem("notes/$id.md")
                } catch (e: JoplinApiException) {
                    if (e.statusCode != 404) throw e
                }
                notes.deleteById(id)
            }
            for (remote in plan.toApplyRemote) {
                val existing = notes.getById(remote.id)
                notes.upsert(
                    (existing ?: Note(id = remote.id, title = "", body = "")).copy(
                        title = remote.title,
                        body = remote.body,
                        parentId = remote.parentId,
                        userUpdatedTime = remote.userUpdatedTime,
                        dirty = false,
                        deleted = false,
                    ),
                )
            }
            for (id in plan.toDeleteLocal) notes.deleteById(id)
            val rawDelta =
                if (plan.toApplyRemote.isEmpty()) {
                    runCatching { api.debugRawDelta().take(300) }.getOrNull()
                } else {
                    null
                }
            settings.setJoplinCursor(newCursor)
            settings.recordSyncSuccess(clock())
            SyncResult.Success(
                pushed = pushed,
                pulled = plan.toApplyRemote.size,
                deleted = plan.toDeleteRemote.size + plan.toDeleteLocal.size,
                changesSeen = changesSeen,
                samples = samples,
                usedFallback = usedFallback,
                rawDelta = rawDelta,
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
    private suspend fun pullChanges(api: JoplinApi): PullResult {
        val initialCursor = settings.joplinCursor.first()
        var cursor: String? = initialCursor
        var didResync = false
        val pulled = LinkedHashMap<String, RemoteNote>()
        val remoteDeleted = mutableSetOf<String>()
        val samples = mutableListOf<String>()
        var defaultFolder = ""
        var changesSeen = 0
        while (true) {
            val page =
                try {
                    api.delta(cursor)
                } catch (e: JoplinApiException) {
                    if (e.errorCode == "resyncRequired" && !didResync) {
                        didResync = true
                        cursor = null
                        continue
                    }
                    throw e
                }
            changesSeen += page.items.size
            if (samples.size < 3 && page.items.isNotEmpty()) samples += page.items.take(3).map { it.name }
            for (change in page.items) {
                val name = change.name
                if (name.startsWith("locks/") || name.startsWith("temp/") || name.startsWith(".resource/")) continue
                if (name.startsWith("folders/") && name.endsWith(".info")) {
                    val parsed = JoplinParser.parse(api.itemContent(name))
                    if (parsed != null && parsed.type == 2 && defaultFolder.isEmpty()) defaultFolder = parsed.id
                    continue
                }
                if (!name.startsWith("notes/") || !name.endsWith(".md")) continue
                val id = name.removePrefix("notes/").removeSuffix(".md")
                if (change.type == 3) {
                    remoteDeleted += id
                    pulled.remove(id)
                } else {
                    val parsed = JoplinParser.parse(api.itemContent(name)) ?: continue
                    if (parsed.type != 1) continue
                    if (parsed.deletedTime != 0L) {
                        remoteDeleted += id
                        pulled.remove(id)
                    } else {
                        pulled[id] =
                            RemoteNote(
                                parsed.id,
                                parsed.title,
                                parsed.body,
                                parsed.parentId,
                                parsed.updatedTime,
                            )
                    }
                }
            }
            if (page.cursor != null) cursor = page.cursor
            if (!page.hasMore) break
        }
        var usedFallback = false
        if (initialCursor == null && changesSeen == 0) {
            // Delta vide en synchro complète : repli sur la liste des enfants de la racine.
            usedFallback = true
            listRootChildren(api, pulled, remoteDeleted, samples)
        }
        return PullResult(pulled.values.toList(), remoteDeleted, defaultFolder, cursor, changesSeen, samples, usedFallback)
    }

    /** Repli : liste paginée de `GET /api/items/root:/:/children`, mêmes filtres que le delta. */
    private suspend fun listRootChildren(
        api: JoplinApi,
        pulled: MutableMap<String, RemoteNote>,
        remoteDeleted: MutableSet<String>,
        samples: MutableList<String>,
    ) {
        var page = 1
        while (true) {
            val children = api.rootChildren(page)
            if (samples.size < 3 && children.items.isNotEmpty()) samples += children.items.take(3).map { it.name }
            for (change in children.items) {
                val name = change.name
                if (!name.startsWith("notes/") || !name.endsWith(".md")) continue
                val id = name.removePrefix("notes/").removeSuffix(".md")
                val parsed = JoplinParser.parse(api.itemContent(name)) ?: continue
                if (parsed.type != 1 || parsed.deletedTime != 0L) continue
                pulled[id] = RemoteNote(parsed.id, parsed.title, parsed.body, parsed.parentId, parsed.updatedTime)
            }
            if (!children.hasMore) break
            page++
        }
    }

    private data class PullResult(
        val pulled: List<RemoteNote>,
        val remoteDeleted: Set<String>,
        val defaultFolder: String,
        val cursor: String?,
        val changesSeen: Int,
        val samples: List<String>,
        val usedFallback: Boolean,
    )

    private suspend fun fail(message: String): SyncResult {
        settings.recordSyncFailure(message)
        return SyncResult.Failure(message)
    }
}
