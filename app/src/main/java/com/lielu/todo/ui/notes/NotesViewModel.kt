package com.lielu.todo.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lielu.todo.data.notes.Note
import com.lielu.todo.data.notes.NotesRepository
import com.lielu.todo.data.notes.NotesSync
import com.lielu.todo.data.notes.SyncResult
import com.lielu.todo.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NotesViewModel(
    private val settingsRepository: SettingsRepository,
    private val notesRepository: NotesRepository,
    private val notesSync: NotesSync,
) : ViewModel() {
    val notes: StateFlow<List<Note>> =
        notesRepository.notes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Synchronisation Joplin active et configurée : le bouton « Synchroniser » n'a de sens que si oui. */
    val syncEnabled: StateFlow<Boolean> =
        settingsRepository.joplin
            .map { it.syncEnabled && it.isConfigured }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun onAddNote(onCreated: (String) -> Unit) {
        viewModelScope.launch { onCreated(notesRepository.create().id) }
    }

    fun onSyncClicked() {
        viewModelScope.launch {
            _message.value =
                when (val result = notesSync.syncNow()) {
                    is SyncResult.Success ->
                        "Synchronisation : ${result.pushed} envoyée(s), ${result.pulled} reçue(s), " +
                            "${result.deleted} supprimée(s)."
                    is SyncResult.Failure -> result.message
                }
        }
    }

    fun onMessageShown() {
        _message.value = null
    }
}
