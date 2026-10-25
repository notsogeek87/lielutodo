package com.lielu.todo.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Un fichier de sauvegarde trouvé dans le dossier ; [handle] est opaque (un URI pour le dossier SAF). */
data class BackupFile(
    val name: String,
    val handle: String,
)

/** Dossier où la sauvegarde automatique écrit ses copies datées. Abstrait pour pouvoir être testé sans Android. */
interface BackupFolder {
    /** Sauvegardes dont le nom commence par [prefix], de la plus récente à la plus ancienne. */
    suspend fun list(prefix: String): List<BackupFile>

    suspend fun write(
        name: String,
        content: ByteArray,
    )

    suspend fun read(file: BackupFile): ByteArray

    suspend fun delete(file: BackupFile)
}

/** [BackupFolder] sur un dossier choisi avec `ACTION_OPEN_DOCUMENT_TREE` (Drive, Fichiers, carte SD…). */
class SafBackupFolder private constructor(
    private val context: Context,
    private val root: DocumentFile,
) : BackupFolder {
    override suspend fun list(prefix: String): List<BackupFile> =
        withContext(Dispatchers.IO) {
            root
                .listFiles()
                .filter { it.isFile && it.name?.startsWith(prefix) == true }
                // Le nom embarque l'horodatage : plus fiable que lastModified(), que certains fournisseurs
                // (Drive notamment) ne renseignent pas.
                .sortedByDescending { it.name }
                .map { BackupFile(name = it.name.orEmpty(), handle = it.uri.toString()) }
        }

    override suspend fun write(
        name: String,
        content: ByteArray,
    ) = withContext(Dispatchers.IO) {
        val file =
            root.createFile("application/json", name)
                ?: error("Impossible de créer le fichier dans le dossier de sauvegarde")
        val stream =
            context.contentResolver.openOutputStream(file.uri)
                ?: error("Impossible d'écrire dans le dossier de sauvegarde")
        stream.use { it.write(content) }
    }

    override suspend fun read(file: BackupFile): ByteArray =
        withContext(Dispatchers.IO) {
            val stream =
                context.contentResolver.openInputStream(Uri.parse(file.handle))
                    ?: error("Impossible de lire ${file.name}")
            stream.use { it.readBytes() }
        }

    override suspend fun delete(file: BackupFile) {
        withContext(Dispatchers.IO) {
            DocumentFile.fromSingleUri(context, Uri.parse(file.handle))?.delete()
        }
    }

    companion object {
        /**
         * `null` si le dossier n'est plus utilisable : autorisation retirée (réinstallation, restauration
         * Android Auto Backup sur un autre appareil), dossier supprimé ou support amovible absent.
         */
        fun open(
            context: Context,
            uriString: String,
        ): SafBackupFolder? {
            val uri = Uri.parse(uriString)
            val hasPermission =
                context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
            if (!hasPermission) return null
            val root = DocumentFile.fromTreeUri(context, uri) ?: return null
            if (!root.isDirectory || !root.canWrite()) return null
            return SafBackupFolder(context, root)
        }
    }
}
