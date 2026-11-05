package com.lielu.todo.ui.notes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lielu.todo.data.notes.Folder
import com.lielu.todo.data.notes.NotesRepository
import com.lielu.todo.data.notes.NotesSync
import com.lielu.todo.data.settings.JoplinSettings
import com.lielu.todo.data.settings.SettingsRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
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

    private val _body = MutableStateFlow(TextFieldValue(""))
    val body: StateFlow<TextFieldValue> = _body

    /** Faux si la note a disparu entre-temps (suppression synchronisée) : l'écran referme alors la page. */
    private val _exists = MutableStateFlow(true)
    val exists: StateFlow<Boolean> = _exists

    /** Carnets connus et carnet courant de la note (pour le sélecteur). */
    val folders: StateFlow<List<Folder>> =
        notesRepository.folders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _parentId = MutableStateFlow("")
    val parentId: StateFlow<String> = _parentId

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
            _body.value = TextFieldValue(note.body, TextRange(note.body.length))
            _parentId.value = note.parentId
            initial = note.title to note.body
        }
        combine(_title, _body) { title, body -> title to body.text }
            .debounce(400)
            .onEach { (title, bodyText) ->
                if (initial != null && (title to bodyText) != initial) save(title, bodyText)
            }.launchIn(viewModelScope)
    }

    fun onTitleChanged(value: String) {
        _title.value = value
    }

    fun onBodyChanged(value: TextFieldValue) {
        _body.value = value
    }

    /** Entoure la sélection courante (ou insère les marqueurs à la position du curseur) avec [prefix]/[suffix]. */
    fun wrapSelection(
        prefix: String,
        suffix: String,
    ) {
        val current = _body.value
        val selection = current.selection
        val text = current.text
        val newText: String
        val newCursor: Int
        if (!selection.collapsed) {
            val selected = text.substring(selection.min, selection.max)
            newText = text.replaceRange(selection.min, selection.max, "$prefix$selected$suffix")
            newCursor = selection.min + prefix.length + selected.length + suffix.length
        } else {
            newText = text.replaceRange(selection.min, selection.max, "$prefix$suffix")
            newCursor = selection.min + prefix.length
        }
        _body.value = TextFieldValue(newText, TextRange(newCursor))
    }

    /** Insère un modèle Markdown [snippet] à la position du curseur (sur une nouvelle ligne si en milieu de ligne). */
    fun insertSnippet(snippet: String) {
        val current = _body.value
        val pos = current.selection.min
        val text = current.text
        val lineStart = text.lastIndexOf('\n', pos - 1) + 1
        val atLineStart = text.substring(lineStart, pos).isBlank()
        val insertion = if (atLineStart) snippet else "\n$snippet"
        val newText = text.replaceRange(pos, pos, insertion)
        val newCursor = pos + insertion.length
        _body.value = TextFieldValue(newText, TextRange(newCursor))
    }

    fun onFolderSelected(folderId: String) {
        viewModelScope.launch {
            _parentId.value = folderId
            notesRepository.setParent(noteId, folderId)
            syncIfEnabled()
        }
    }

    fun onDeleteConfirmed(onDeleted: () -> Unit) {
        viewModelScope.launch {
            notesRepository.delete(noteId)
            syncIfEnabled()
            onDeleted()
        }
    }

    /** Appelé quand l'écran se ferme : supprime la note si elle est restée vide (titre et corps vides). */
    fun onCleared() {
        viewModelScope.launch {
            val note = notesRepository.getById(noteId) ?: return@launch
            if (note.title.isBlank() && note.body.isBlank()) {
                notesRepository.delete(noteId)
                syncIfEnabled()
            }
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
