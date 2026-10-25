package com.lielu.todo.backup

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Ce qu'un import a effectivement restauré, pour le message affiché à l'utilisateur. */
data class BackupImportResult(
    val restoredSections: List<String>,
    /** Sections présentes dans le fichier mais inconnues de cette version de l'app. */
    val ignoredSections: List<String>,
    val exportedAt: Long,
)

/**
 * Exporte et importe toutes les [BackupSection] en un fichier JSON unique. Sans état ni dépendance Android :
 * il sert à l'export manuel (Storage Access Framework), à la sauvegarde automatique et aux tests.
 */
class BackupManager(
    private val sections: List<BackupSection>,
    private val appId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        val duplicates = sections.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Identifiants de section en double : $duplicates" }
    }

    suspend fun exportToString(): String =
        BackupFormat.encode(
            appId = appId,
            exportedAt = clock(),
            sections = sections.associate { it.id to it.export() },
        )

    suspend fun exportTo(output: OutputStream) {
        output.write(exportToString().toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    suspend fun importFrom(input: InputStream): BackupImportResult =
        importFromString(input.bufferedReader(StandardCharsets.UTF_8).readText())

    /**
     * Remplace les données de chaque section reconnue. Le fichier est entièrement décodé et validé avant le
     * premier changement ; si une section échoue ensuite, les précédentes restent restaurées (chaque section
     * est atomique pour elle-même, pas l'ensemble).
     */
    suspend fun importFromString(text: String): BackupImportResult {
        val envelope = BackupFormat.decode(text)
        val known = sections.associateBy { it.id }
        val toRestore = envelope.sections.filterKeys { it in known }
        if (toRestore.isEmpty()) {
            throw InvalidBackupException("Cette sauvegarde ne contient aucune donnée reconnue par cette app.")
        }
        val restored = ArrayList<String>()
        for (section in sections) {
            val data = toRestore[section.id] ?: continue
            section.restore(data)
            restored += section.id
        }
        return BackupImportResult(
            restoredSections = restored,
            ignoredSections = envelope.sections.keys.filter { it !in known },
            exportedAt = envelope.exportedAt,
        )
    }
}
