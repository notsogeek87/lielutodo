package com.lielu.todo.backup

import com.lielu.todo.testutil.FakeBackupFolder
import com.lielu.todo.testutil.FakeSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class AutoBackupRunnerTest {
    private val settings = FakeSettingsRepository()
    private val folder = FakeBackupFolder()
    private var now = Instant.parse("2026-03-01T10:00:00Z").toEpochMilli()

    private fun runner(folderAvailable: Boolean = true) =
        AutoBackupRunner(
            settings = settings,
            backupManager = BackupManager(listOf(SettingsBackupSection(settings)), appId = "test"),
            openFolder = { if (folderAvailable) folder else null },
            fileNamePrefix = "app",
            clock = { now },
            zone = ZoneOffset.UTC,
        )

    private suspend fun configure(
        enabled: Boolean = true,
        keep: Int = 3,
    ) {
        settings.setAutoBackupFolder("content://tree/backups")
        settings.setAutoBackupEnabled(enabled)
        settings.setAutoBackupKeep(keep)
    }

    @Test
    fun scheduledRunWritesDatedFileAndRecordsSuccess() =
        runTest {
            configure()

            val result = runner().runScheduled()

            assertEquals(AutoBackupResult.Success("app-auto-20260301-100000.json"), result)
            assertEquals(listOf("app-auto-20260301-100000.json"), folder.files.keys.toList())
            assertEquals(now, settings.backupStatus.first().lastSuccessAt)
        }

    @Test
    fun scheduledRunDoesNothingWhenDisabled() =
        runTest {
            configure(enabled = false)

            assertNull(runner().runScheduled())
            assertTrue(folder.files.isEmpty())
        }

    @Test
    fun keepsOnlyTheMostRecentCopies() =
        runTest {
            configure(keep = 2)
            val runner = runner()

            repeat(4) {
                now += 60_000
                runner.backupNow()
            }

            assertEquals(
                listOf("app-auto-20260301-100300.json", "app-auto-20260301-100400.json"),
                folder.files.keys.sorted(),
            )
        }

    @Test
    fun pruningNeverTouchesFilesOutsideTheAutoPrefix() =
        runTest {
            configure(keep = 1)
            folder.files["app-2026-01-01.json"] = ByteArray(0) // export manuel posé dans le même dossier
            val runner = runner()

            now += 60_000
            runner.backupNow()
            now += 60_000
            runner.backupNow()

            assertTrue(folder.files.containsKey("app-2026-01-01.json"))
            assertEquals(2, folder.files.size)
        }

    @Test
    fun inaccessibleFolderIsReportedAndRecorded() =
        runTest {
            configure()

            val result = runner(folderAvailable = false).backupNow()

            assertTrue(result is AutoBackupResult.Failure)
            assertNotNull(settings.backupStatus.first().lastError)
            assertNull(settings.backupStatus.first().lastSuccessAt)
        }

    @Test
    fun failedWriteKeepsPreviousBackupsAndIsReported() =
        runTest {
            configure(keep = 1)
            val runner = runner()
            runner.backupNow()
            folder.failWrites = true
            now += 60_000

            val result = runner.backupNow()

            assertTrue(result is AutoBackupResult.Failure)
            assertEquals(listOf("app-auto-20260301-100000.json"), folder.files.keys.toList())
        }

    @Test
    fun restoreLatestReadsTheNewestCopy() =
        runTest {
            configure()
            val runner = runner()
            runner.backupNow()

            val result = runner.restoreLatest()

            assertEquals(listOf("settings"), result.restoredSections)
        }

    @Test
    fun restoreLatestFailsClearlyWhenFolderIsEmpty() =
        runTest {
            configure()

            val error = runCatching { runner().restoreLatest() }.exceptionOrNull()

            assertTrue(error is InvalidBackupException)
        }
}
