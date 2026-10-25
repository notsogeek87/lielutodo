package com.lielu.todo.backup

import com.lielu.todo.data.settings.ThemeMode
import com.lielu.todo.testutil.FakeSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupManagerTest {
    /** Section factice : garde son contenu dans une variable, pour vérifier export puis restauration. */
    private class MemorySection(
        override val id: String,
        var content: String,
    ) : BackupSection {
        override suspend fun export(): JSONObject = JSONObject().put("content", content)

        override suspend fun restore(data: JSONObject) {
            content = data.getString("content")
        }
    }

    @Test
    fun exportThenImportRestoresEverySection() =
        runTest {
            val settings = FakeSettingsRepository().apply { setThemeMode(ThemeMode.DARK) }
            val memory = MemorySection("memory", "avant")
            val manager = BackupManager(listOf(SettingsBackupSection(settings), memory), appId = "test")
            val file = ByteArrayOutputStream().also { manager.exportTo(it) }.toByteArray()

            settings.setThemeMode(ThemeMode.LIGHT)
            memory.content = "après"
            val result = manager.importFrom(ByteArrayInputStream(file))

            assertEquals(listOf("settings", "memory"), result.restoredSections)
            assertEquals(ThemeMode.DARK, settings.themeMode.first())
            assertEquals("avant", memory.content)
        }

    @Test
    fun importIgnoresUnknownSectionsAndReportsThem() =
        runTest {
            val memory = MemorySection("memory", "x")
            val text =
                BackupFormat.encode(
                    "test",
                    1L,
                    mapOf("memory" to JSONObject().put("content", "y"), "futur" to JSONObject()),
                )

            val result = BackupManager(listOf(memory), "test").importFromString(text)

            assertEquals(listOf("memory"), result.restoredSections)
            assertEquals(listOf("futur"), result.ignoredSections)
            assertEquals("y", memory.content)
        }

    @Test
    fun importWithNoRecognisedSectionFailsWithoutTouchingData() =
        runTest {
            val memory = MemorySection("memory", "intact")
            val text = BackupFormat.encode("test", 1L, mapOf("autre" to JSONObject()))

            val error = runCatching { BackupManager(listOf(memory), "test").importFromString(text) }.exceptionOrNull()

            assertTrue(error is InvalidBackupException)
            assertEquals("intact", memory.content)
        }

    @Test
    fun invalidFileFailsBeforeAnyChange() =
        runTest {
            val memory = MemorySection("memory", "intact")

            val error = runCatching { BackupManager(listOf(memory), "test").importFromString("{}") }.exceptionOrNull()

            assertTrue(error is InvalidBackupException)
            assertEquals("intact", memory.content)
        }

    @Test
    fun duplicateSectionIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupManager(listOf(MemorySection("a", ""), MemorySection("a", "")), "test")
        }
    }
}
