package com.lielu.todo.testutil

import com.lielu.todo.backup.BackupFile
import com.lielu.todo.backup.BackupFolder

/** Dossier de sauvegarde en mémoire ; [failWrites] simule un support en lecture seule ou plein. */
class FakeBackupFolder : BackupFolder {
    val files = linkedMapOf<String, ByteArray>()
    var failWrites = false

    override suspend fun list(prefix: String): List<BackupFile> =
        files.keys
            .filter { it.startsWith(prefix) }
            .sortedDescending()
            .map { BackupFile(it, it) }

    override suspend fun write(
        name: String,
        content: ByteArray,
    ) {
        check(!failWrites) { "disque plein" }
        files[name] = content
    }

    override suspend fun read(file: BackupFile): ByteArray = files.getValue(file.handle)

    override suspend fun delete(file: BackupFile) {
        files.remove(file.handle)
    }
}
