package com.lielu.todo.testutil

import com.lielu.todo.data.settings.AutoBackupSettings
import com.lielu.todo.data.settings.BackupStatus
import com.lielu.todo.data.settings.SettingsRepository
import com.lielu.todo.data.settings.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Remplaçant en mémoire de [SettingsRepository]. */
class FakeSettingsRepository : SettingsRepository {
    private val theme = MutableStateFlow(ThemeMode.SYSTEM)
    private val auto = MutableStateFlow(AutoBackupSettings())
    private val status = MutableStateFlow(BackupStatus())
    private val welcome = MutableStateFlow(false)

    override val themeMode: Flow<ThemeMode> = theme

    override suspend fun setThemeMode(mode: ThemeMode) {
        theme.value = mode
    }

    override val autoBackup: Flow<AutoBackupSettings> = auto

    override suspend fun setAutoBackupEnabled(enabled: Boolean) {
        auto.value = auto.value.copy(enabled = enabled)
    }

    override suspend fun setAutoBackupFolder(uri: String?) {
        auto.value = auto.value.copy(folderUri = uri, enabled = auto.value.enabled && uri != null)
    }

    override suspend fun setAutoBackupKeep(keep: Int) {
        auto.value = auto.value.copy(keep = keep)
    }

    override val backupStatus: Flow<BackupStatus> = status

    override suspend fun recordBackupSuccess(timestamp: Long) {
        status.value = BackupStatus(lastSuccessAt = timestamp)
    }

    override suspend fun recordBackupFailure(message: String) {
        status.value = status.value.copy(lastError = message)
    }

    override val welcomeShown: Flow<Boolean> = welcome

    override suspend fun setWelcomeShown() {
        welcome.value = true
    }
}
