package com.lielu.todo.ui.settings

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lielu.githubupdater.UpdateInfo
import com.lielu.githubupdater.UpdateManager
import com.lielu.todo.backup.AutoBackupResult
import com.lielu.todo.backup.AutoBackupRunner
import com.lielu.todo.backup.BackupManager
import com.lielu.todo.data.notes.NotesSync
import com.lielu.todo.data.notes.SyncResult
import com.lielu.todo.data.settings.AutoBackupSettings
import com.lielu.todo.data.settings.BackupStatus
import com.lielu.todo.data.settings.JoplinSettings
import com.lielu.todo.data.settings.SettingsRepository
import com.lielu.todo.data.settings.SyncStatus
import com.lielu.todo.data.settings.ThemeMode
import com.lielu.todo.ui.describeImport
import com.lielu.todo.work.AutoBackupScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val autoBackup: AutoBackupSettings = AutoBackupSettings(),
    val backupStatus: BackupStatus = BackupStatus(),
    val backupInProgress: Boolean = false,
    val joplin: JoplinSettings = JoplinSettings(),
    val syncStatus: SyncStatus = SyncStatus(),
    val message: String? = null,
)

private data class OpState(
    val inProgress: Boolean = false,
    val message: String? = null,
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val backupManager: BackupManager,
    private val autoBackupRunner: AutoBackupRunner,
    private val scheduler: AutoBackupScheduler,
    private val updateManager: UpdateManager,
    val updatesEnabled: Boolean,
    private val notesSync: NotesSync,
) : ViewModel() {
    /** État brut de la bibliothèque ; l'écran le dessine à sa façon. */
    val updateState = updateManager.state

    private val opState = MutableStateFlow(OpState())

    // combine() typé s'arrête à 5 flux : l'état de base (réglages) est combiné d'abord, puis avec l'état d'opération.
    private val baseState: Flow<SettingsUiState> =
        combine(
            settingsRepository.themeMode,
            settingsRepository.autoBackup,
            settingsRepository.backupStatus,
            settingsRepository.joplin,
            settingsRepository.syncStatus,
        ) { themeMode, autoBackup, status, joplin, syncStatus ->
            SettingsUiState(
                themeMode = themeMode,
                autoBackup = autoBackup,
                backupStatus = status,
                joplin = joplin,
                syncStatus = syncStatus,
            )
        }

    val uiState: StateFlow<SettingsUiState> =
        combine(baseState, opState) { base, op ->
            base.copy(backupInProgress = op.inProgress, message = op.message)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onThemeModeChanged(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    /** [uri] vient de `ACTION_OPEN_DOCUMENT_TREE` ; l'autorisation est rendue permanente pour le travail planifié. */
    fun onFolderPicked(
        contentResolver: ContentResolver,
        uri: Uri,
    ) {
        viewModelScope.launch {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                settingsRepository.setAutoBackupFolder(uri.toString())
                settingsRepository.setAutoBackupEnabled(true)
                scheduler.apply(settingsRepository.autoBackup.first())
                opState.update { it.copy(message = "Dossier de sauvegarde enregistré.") }
            } catch (e: SecurityException) {
                opState.update { it.copy(message = "Ce dossier n'autorise pas l'écriture : choisissez-en un autre.") }
            }
        }
    }

    fun onAutoBackupToggled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoBackupEnabled(enabled)
            scheduler.apply(settingsRepository.autoBackup.first())
        }
    }

    fun onKeepChanged(keep: Int) {
        viewModelScope.launch { settingsRepository.setAutoBackupKeep(keep) }
    }

    fun onBackupNowRequested() {
        viewModelScope.launch {
            opState.value = OpState(inProgress = true)
            val message =
                when (val result = autoBackupRunner.backupNow()) {
                    is AutoBackupResult.Success -> "Sauvegarde enregistrée (${result.fileName})."
                    is AutoBackupResult.Failure -> result.message
                }
            opState.value = OpState(message = message)
        }
    }

    fun onRestoreLatestRequested() {
        viewModelScope.launch {
            opState.value = OpState(inProgress = true)
            val message =
                try {
                    describeImport(autoBackupRunner.restoreLatest())
                } catch (e: Exception) {
                    "Échec de la restauration : ${e.message}"
                }
            opState.value = OpState(message = message)
        }
    }

    /** [uri] vient de `ACTION_CREATE_DOCUMENT` : il peut pointer vers Google Drive, Fichiers, etc. */
    fun onExportRequested(
        contentResolver: ContentResolver,
        uri: Uri,
    ) {
        viewModelScope.launch {
            opState.value = OpState(inProgress = true)
            val message =
                try {
                    val stream =
                        contentResolver.openOutputStream(uri) ?: error("Impossible d'ouvrir le fichier de destination")
                    stream.use { backupManager.exportTo(it) }
                    settingsRepository.recordBackupSuccess(System.currentTimeMillis())
                    "Sauvegarde enregistrée."
                } catch (e: Exception) {
                    "Échec de la sauvegarde : ${e.message}"
                }
            opState.value = OpState(message = message)
        }
    }

    fun onImportRequested(
        contentResolver: ContentResolver,
        uri: Uri,
    ) {
        viewModelScope.launch {
            opState.value = OpState(inProgress = true)
            val message =
                try {
                    val stream =
                        contentResolver.openInputStream(uri) ?: error("Impossible de lire le fichier sélectionné")
                    describeImport(stream.use { backupManager.importFrom(it) })
                } catch (e: Exception) {
                    "Échec de la restauration : ${e.message}"
                }
            opState.value = OpState(message = message)
        }
    }

    fun onJoplinServerUrlChanged(url: String) {
        viewModelScope.launch { settingsRepository.setJoplinServerUrl(url) }
    }

    fun onJoplinApiTokenChanged(token: String) {
        viewModelScope.launch { settingsRepository.setJoplinApiToken(token) }
    }

    fun onJoplinSyncToggled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setJoplinSyncEnabled(enabled) }
    }

    fun onSyncNowRequested() {
        viewModelScope.launch {
            opState.value = OpState(inProgress = true)
            val message =
                when (val result = notesSync.syncNow()) {
                    is SyncResult.Success ->
                        "Synchronisation : ${result.pushed} envoyée(s), ${result.pulled} reçue(s), " +
                            "${result.deleted} supprimée(s)."
                    is SyncResult.Failure -> result.message
                }
            opState.value = OpState(message = message)
        }
    }

    fun onMessageShown() {
        opState.update { it.copy(message = null) }
    }

    fun onCheckUpdateRequested() {
        // Les erreurs sont aussi publiées dans updateState : on ne garde que l'affichage.
        viewModelScope.launch { runCatching { updateManager.checkForUpdate(force = true) } }
    }

    fun onInstallUpdateRequested(update: UpdateInfo) {
        viewModelScope.launch {
            runCatching {
                val apk = updateManager.downloadUpdate(update)
                install(apk)
            }
        }
    }

    /** Relance l'installation d'un APK déjà téléchargé (ex. après avoir autorisé les sources inconnues). */
    fun onInstallDownloadedRequested(apk: File) {
        runCatching { install(apk) }
    }

    private fun install(apk: File) {
        if (updateManager.canInstallPackages()) {
            updateManager.installUpdate(apk)
        } else {
            updateManager.openInstallPermissionSettings()
        }
    }
}
