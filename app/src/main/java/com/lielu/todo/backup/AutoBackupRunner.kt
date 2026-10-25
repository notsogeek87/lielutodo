package com.lielu.todo.backup

import com.lielu.todo.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

sealed interface AutoBackupResult {
    data class Success(
        val fileName: String,
    ) : AutoBackupResult

    /** Pas de dossier, ou dossier devenu inaccessible : rien n'a été écrit. */
    data class Failure(
        val message: String,
    ) : AutoBackupResult
}

/**
 * Écrit les sauvegardes automatiques dans le dossier choisi et en restaure la plus récente. Partagé entre le
 * travail périodique ([com.lielu.todo.work.AutoBackupWorker]) et les boutons de Paramètres.
 */
class AutoBackupRunner(
    private val settings: SettingsRepository,
    private val backupManager: BackupManager,
    private val openFolder: (String) -> BackupFolder?,
    private val fileNamePrefix: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    // Une sauvegarde manuelle lancée pendant le passage quotidien ne doit pas élaguer en parallèle.
    private val lock = Mutex()

    private val autoPrefix = "$fileNamePrefix-auto-"

    /** Passage quotidien : ne fait rien si la sauvegarde automatique est désactivée. `null` = désactivée. */
    suspend fun runScheduled(): AutoBackupResult? {
        if (!settings.autoBackup.first().isActive) return null
        return backupNow()
    }

    /** Écrit une sauvegarde maintenant dans le dossier configuré (activée ou non), puis élague les plus vieilles. */
    suspend fun backupNow(): AutoBackupResult =
        lock.withLock {
            val result = writeBackup()
            when (result) {
                is AutoBackupResult.Success -> settings.recordBackupSuccess(clock())
                is AutoBackupResult.Failure -> settings.recordBackupFailure(result.message)
            }
            result
        }

    private suspend fun writeBackup(): AutoBackupResult {
        val config = settings.autoBackup.first()
        val uri = config.folderUri ?: return AutoBackupResult.Failure("Aucun dossier de sauvegarde choisi.")
        val folder =
            openFolder(uri)
                ?: return AutoBackupResult.Failure(
                    "Le dossier de sauvegarde n'est plus accessible : choisissez-le de nouveau.",
                )
        return try {
            val name = autoPrefix + TIMESTAMP.format(Instant.ofEpochMilli(clock()).atZone(zone)) + ".json"
            folder.write(name, backupManager.exportToString().toByteArray(StandardCharsets.UTF_8))
            // Élaguer après l'écriture : en cas d'échec d'écriture, on ne perd jamais l'ancienne sauvegarde.
            prune(folder, config.keep)
            AutoBackupResult.Success(name)
        } catch (e: Exception) {
            AutoBackupResult.Failure("Échec de la sauvegarde : ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun prune(
        folder: BackupFolder,
        keep: Int,
    ) {
        // Un échec de nettoyage ne doit pas faire échouer une sauvegarde qui, elle, a réussi.
        runCatching { folder.list(autoPrefix).drop(keep).forEach { folder.delete(it) } }
    }

    /** Restaure la sauvegarde automatique la plus récente du dossier configuré. */
    suspend fun restoreLatest(): BackupImportResult =
        lock.withLock {
            val uri =
                settings.autoBackup.first().folderUri
                    ?: throw InvalidBackupException("Aucun dossier de sauvegarde choisi.")
            val folder =
                openFolder(uri)
                    ?: throw InvalidBackupException("Le dossier de sauvegarde n'est plus accessible.")
            val latest =
                folder.list(autoPrefix).firstOrNull()
                    ?: throw InvalidBackupException("Aucune sauvegarde automatique dans ce dossier.")
            backupManager.importFromString(String(folder.read(latest), StandardCharsets.UTF_8))
        }

    private companion object {
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
