package com.lielu.todo.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lielu.todo.data.notes.NotesRepository
import com.lielu.todo.data.notes.NotesSync
import com.lielu.todo.data.settings.JoplinSettings
import com.lielu.todo.data.settings.SettingsRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class NoteEditViewModel(
    private val noteId: String,
    private val notesRepository: NotesRepository,
    private val notesSync: NotesSync,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title

    private val _body = MutableStateFlow("")
    val body: StateFlow<String> = _body

    /** Faux si la note a disparu entre-temps (suppression synchronisée) : l'écran referme alors la page. */
    private val _exists = MutableStateFlow(true)
    val exists: StateFlow<Boolean> = _exists

    /** Contenu au chargement : on ne sauvegarde que ce qui a vraiment changé, pas le premier affichage. */
    private var initial: Pair<String, String>? = null

    init {
        viewModelScope.launch {
            val note = notesRepository.getById(noteId)
            if (note == null) {
                _exists.value = false
                return@launch
            }
            _title.value = note.title
            _body.value = note.body
            initial = note.title to note.body
        }
        combine(_title, _body) { title, body -> title to body }
            .debounce(400)
            .onEach { (title, body) ->
                if (initial != null && (title to body) != initial) save(title, body)
            }.launchIn(viewModelScope)
    }

    fun onTitleChanged(value: String) {
        _title.value = value
    }

    fun onBodyChanged(value: String) {
        _body.value = value
    }

    fun onDeleteConfirmed(onDeleted: () -> Unit) {
        viewModelScope.launch {
            notesRepository.delete(noteId)
            syncIfEnabled()
            onDeleted()
        }
    }

    private suspend fun save(
        title: String,
        body: String,
    ) {
        notesRepository.save(noteId, title, body)
        syncIfEnabled()
    }

    private suspend fun syncIfEnabled() {
        val joplin: JoplinSettings = settingsRepository.joplin.first()
        if (joplin.syncEnabled && joplin.isConfigured) notesSync.syncNow()
    }
}
