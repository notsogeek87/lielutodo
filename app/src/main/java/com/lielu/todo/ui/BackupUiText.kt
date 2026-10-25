package com.lielu.todo.ui

import com.lielu.todo.backup.BackupImportResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Messages affichés à l'utilisateur après une sauvegarde ou une restauration. */
fun describeImport(result: BackupImportResult): String {
    val sections = result.restoredSections.size
    return "Sauvegarde restaurée ($sections section${if (sections > 1) "s" else ""})."
}

fun formatDateTime(timestamp: Long): String =
    DateTimeFormatter
        .ofPattern("dd/MM/yyyy 'à' HH:mm")
        .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
