package com.lielu.todo.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupFormatTest {
    @Test
    fun encodeThenDecodeRoundTrips() {
        val text =
            BackupFormat.encode(
                appId = "com.lielu.todo",
                exportedAt = 1_700_000_000_000,
                sections = mapOf("settings" to JSONObject().put("themeMode", "DARK")),
            )

        val envelope = BackupFormat.decode(text)

        assertEquals(BackupFormat.VERSION, envelope.version)
        assertEquals("com.lielu.todo", envelope.appId)
        assertEquals(1_700_000_000_000, envelope.exportedAt)
        assertEquals("DARK", envelope.sections.getValue("settings").getString("themeMode"))
    }

    @Test
    fun decodeRejectsNonJson() {
        assertThrows(InvalidBackupException::class.java) { BackupFormat.decode("pas du json") }
    }

    @Test
    fun decodeRejectsOtherFormats() {
        // Un export d'une autre app (ex. l'ancien format de rangele) ne doit pas être pris pour une sauvegarde.
        assertThrows(InvalidBackupException::class.java) { BackupFormat.decode("""{"version":1,"products":[]}""") }
    }

    @Test
    fun decodeRejectsNewerVersion() {
        val text = """{"format":"lielu-backup","version":${BackupFormat.VERSION + 1},"sections":{}}"""
        assertThrows(InvalidBackupException::class.java) { BackupFormat.decode(text) }
    }

    @Test
    fun decodeRejectsMissingSections() {
        assertThrows(InvalidBackupException::class.java) {
            BackupFormat.decode("""{"format":"lielu-backup","version":1}""")
        }
    }

    @Test
    fun decodeToleratesMissingAppIdAndDate() {
        val envelope = BackupFormat.decode("""{"format":"lielu-backup","version":1,"sections":{"a":{}}}""")

        assertNull(envelope.appId)
        assertEquals(0L, envelope.exportedAt)
    }
}
