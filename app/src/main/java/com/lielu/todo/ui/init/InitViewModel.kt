package com.lielu.todo.ui.init

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lielu.todo.backup.BackupManager
import com.lielu.todo.data.settings.SettingsRepository
import com.lielu.todo.ui.describeImport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InitViewModel(
    private val settingsRepository: SettingsRepository,
    private val backupManager: BackupManager,
) : ViewModel() {
    /**
     * Vrai tant que l'invitation de bienvenue n'a pas été traitée. Initialement faux : on n'affiche rien
     * avant que le DataStore ait répondu, pour ne pas faire clignoter l'invitation à chaque lancement.
     */
    val showWelcome: StateFlow<Boolean> =
        settingsRepository.welcomeShown
            .map { !it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun onWelcomeDismissed() {
        viewModelScope.launch { settingsRepository.setWelcomeShown() }
    }

    /** Restauration proposée au premier lancement (réinstallation, nouvel appareil) depuis un fichier choisi. */
    fun onImportRequested(
        contentResolver: ContentResolver,
        uri: Uri,
    ) {
        viewModelScope.launch {
            _message.value =
                try {
                    val stream =
                        contentResolver.openInputStream(uri) ?: error("Impossible de lire le fichier sélectionné")
                    val result = stream.use { backupManager.importFrom(it) }
                    settingsRepository.setWelcomeShown()
                    describeImport(result)
                } catch (e: Exception) {
                    "Échec de la restauration : ${e.message}"
                }
        }
    }

    fun onMessageShown() {
        _message.value = null
    }
}
