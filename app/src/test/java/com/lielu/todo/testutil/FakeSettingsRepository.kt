package com.lielu.todo.testutil

import com.lielu.todo.data.settings.AutoBackupSettings
import com.lielu.todo.data.settings.BackupStatus
import com.lielu.todo.data.settings.JoplinSettings
import com.lielu.todo.data.settings.ServiceConnection
import com.lielu.todo.data.settings.SettingsRepository
import com.lielu.todo.data.settings.SyncStatus
import com.lielu.todo.data.settings.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Remplaçant en mémoire de [SettingsRepository]. */
class FakeSettingsRepository : SettingsRepository {
    private val theme = MutableStateFlow(ThemeMode.SYSTEM)
    private val auto = MutableStateFlow(AutoBackupSettings())
    private val status = MutableStateFlow(BackupStatus())
    private val welcome = MutableStateFlow(false)
    private val joplinState = MutableStateFlow(JoplinSettings())
    private val syncState = MutableStateFlow(SyncStatus())
    private val vikunjaState = MutableStateFlow(ServiceConnection())
    private val koplionState = MutableStateFlow(ServiceConnection())

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

    override val joplin: Flow<JoplinSettings> = joplinState

    override suspend fun setJoplinServerUrl(url: String) {
        joplinState.value = joplinState.value.copy(serverUrl = url.trim())
    }

    override suspend fun setJoplinEmail(email: String) {
        joplinState.value = joplinState.value.copy(email = email.trim())
    }

    override suspend fun setJoplinPassword(password: String) {
        joplinState.value = joplinState.value.copy(password = password)
    }

    override suspend fun setJoplinSyncEnabled(enabled: Boolean) {
        joplinState.value = joplinState.value.copy(syncEnabled = enabled)
    }

    override val syncStatus: Flow<SyncStatus> = syncState

    override suspend fun recordSyncSuccess(timestamp: Long) {
        syncState.value = SyncStatus(lastSuccessAt = timestamp)
    }

    override suspend fun recordSyncFailure(message: String) {
        syncState.value = syncState.value.copy(lastError = message)
    }

    override val vikunja: Flow<ServiceConnection> = vikunjaState

    override suspend fun setVikunja(connection: ServiceConnection) {
        vikunjaState.value = connection
    }

    override val koplion: Flow<ServiceConnection> = koplionState

    override suspend fun setKoplion(connection: ServiceConnection) {
        koplionState.value = connection
    }
}
