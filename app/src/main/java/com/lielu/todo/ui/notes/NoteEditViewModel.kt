package com.lielu.todo.ui.notes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lielu.todo.data.notes.Folder
import com.lielu.todo.data.notes.NotesRepository
import com.lielu.todo.data.notes.NotesSync
import com.lielu.todo.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** État d'enregistrement affiché sous la note. */
enum class SaveState { SAVED, PENDING, NOT_SYNCED }

@OptIn(FlowPreview::class)
class NoteEditViewModel(
    private val noteId: String,
    private val notesRepository: NotesRepository,
    private val notesSync: NotesSync,
    private val settingsRepository: SettingsRepository,
    private val appScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title

    private val _body = MutableStateFlow(TextFieldValue(""))
    val body: StateFlow<TextFieldValue> = _body

    /** Faux si la note a disparu entre-temps (suppression synchronisée) : l'écran referme alors la page. */
    private val _exists = MutableStateFlow(true)
    val exists: StateFlow<Boolean> = _exists

    /** Note encore vide à l'ouverture (nouvelle note) : l'écran met le curseur dans le titre et ouvre le clavier. */
    private val _startsEmpty = MutableStateFlow<Boolean?>(null)
    val startsEmpty: StateFlow<Boolean?> = _startsEmpty

    /** Carnets connus (pour le sélecteur). */
    val folders: StateFlow<List<Folder>> =
        notesRepository.folders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _parentId = MutableStateFlow("")
    val parentId: StateFlow<String> = _parentId

    /** `true` s'il y a des modifications pas encore enregistrées (bouton « Enregistrer » actif). */
    private val _hasUnsavedChanges = MutableStateFlow(false)
    val hasUnsavedChanges: StateFlow<Boolean> = _hasUnsavedChanges

    /** Enregistrée ici mais pas encore envoyée au serveur Joplin. */
    private val notSynced: StateFlow<Boolean> =
        notesRepository.notes
            .map { list -> list.firstOrNull { it.id == noteId }?.dirty == true }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val saveState: StateFlow<SaveState> =
        combine(_hasUnsavedChanges, notSynced) { unsaved, dirty ->
            when {
                unsaved -> SaveState.PENDING
                dirty -> SaveState.NOT_SYNCED
                else -> SaveState.SAVED
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SaveState.SAVED)

    /** Contenu au chargement : on ne sauvegarde que ce qui a vraiment changé, pas le premier affichage. */
    private var initial: Pair<String, String>? = null

    // Annuler / rétablir sur le corps : une étape par rafale de frappe (pause > HISTORY_PAUSE_MS) ou par action
    // de la barre d'outils.
    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()
    private var lastEditAt = 0L
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo

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
            _startsEmpty.value = note.title.isBlank() && note.body.isBlank()
        }
        combine(_title, _body) { title, body -> title to body.text }
            .onEach { (title, bodyText) ->
                _hasUnsavedChanges.value = initial != null && (title to bodyText) != initial
            }.launchIn(viewModelScope)
        combine(_title, _body) { title, body -> title to body.text }
            .debounce(400)
            .onEach { (title, bodyText) ->
                if (initial != null && (title to bodyText) != initial) save(title, bodyText)
            }.launchIn(viewModelScope)
    }

    fun onTitleChanged(value: String) {
        // Le titre Joplin tient sur une ligne : un retour collé passe dans le corps.
        _title.value = value.replace('\n', ' ')
    }

    /** Frappe dans le corps : prolonge les listes à la ligne et note l'étape pour « Annuler ». */
    fun onBodyChanged(value: TextFieldValue) {
        val current = _body.value
        if (value.text == current.text) {
            _body.value = value // simple déplacement du curseur ou de la sélection
            return
        }
        val now = clock()
        if (now - lastEditAt > HISTORY_PAUSE_MS || undoStack.isEmpty()) pushUndo(current)
        lastEditAt = now
        _body.value = MarkdownEditing.continueList(current, value)
    }

    // Barre d'outils : chaque action est une étape d'annulation à elle seule.
    fun toggleBold() = edit { MarkdownEditing.toggleWrap(it, "**") }

    fun toggleItalic() = edit { MarkdownEditing.toggleWrap(it, "*") }

    fun toggleStrike() = edit { MarkdownEditing.toggleWrap(it, "~~") }

    fun toggleCode() = edit { MarkdownEditing.toggleWrap(it, "`") }

    fun toggleHeading() = edit { MarkdownEditing.toggleLinePrefix(it, MarkdownEditing.HEADING) }

    fun toggleBullet() = edit { MarkdownEditing.toggleLinePrefix(it, MarkdownEditing.BULLET) }

    fun toggleNumbered() = edit { MarkdownEditing.toggleLinePrefix(it, MarkdownEditing.NUMBERED) }

    fun toggleCheckbox() = edit { MarkdownEditing.toggleCheckbox(it) }

    fun toggleQuote() = edit { MarkdownEditing.toggleLinePrefix(it, MarkdownEditing.QUOTE) }

    fun insertLink() = edit { MarkdownEditing.insertLink(it) }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_body.value)
        _body.value = previous
        lastEditAt = 0L
        refreshHistory()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_body.value)
        _body.value = next
        lastEditAt = 0L
        refreshHistory()
    }

    private fun edit(transform: (TextFieldValue) -> TextFieldValue) {
        val current = _body.value
        val next = transform(current)
        if (next == current) return
        pushUndo(current)
        lastEditAt = 0L // la frappe suivante ouvre une nouvelle étape
        _body.value = next
    }

    private fun pushUndo(value: TextFieldValue) {
        undoStack.addLast(value)
        if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        refreshHistory()
    }

    private fun refreshHistory() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    /** Fichier d'une image jointe (ressource Joplin), gardé hors ligne une fois téléchargé ; null si indisponible. */
    suspend fun loadResource(id: String): ByteArray? = notesSync.resource(id)

    fun onFolderSelected(folderId: String) {
        viewModelScope.launch {
            _parentId.value = folderId
            notesRepository.setParent(noteId, folderId)
        }
    }

    fun onDeleteConfirmed(onDeleted: () -> Unit) {
        viewModelScope.launch {
            notesRepository.delete(noteId)
            onDeleted()
        }
    }

    /** Bouton « Enregistrer » : sauvegarde immédiatement (sans attendre le debounce). */
    fun onSaveClicked() {
        viewModelScope.launch { save(_title.value, _body.value.text) }
    }

    /**
     * Fermeture définitive de l'éditeur (pas une rotation) : enregistre la saisie pas encore enregistrée, supprime
     * la note si elle est restée vide, puis synchronise. Lancé dans le scope de l'application : celui du
     * ViewModel est déjà annulé à ce moment-là, la dernière frappe serait perdue.
     */
    override fun onCleared() {
        appScope.launch { finish() }
    }

    suspend fun finish() {
        var title = _title.value
        val body = _body.value.text
        // Note sans titre : Joplin (et la liste) affichent la première ligne du texte.
        if (title.isBlank() && body.isNotBlank()) title = MarkdownEditing.titleFromBody(body)
        if (initial != null && (title to body) != initial) save(title, body)
        val note = notesRepository.getById(noteId) ?: return
        if (!note.deleted && note.title.isBlank() && note.body.isBlank()) {
            if (note.serverRaw == null) {
                notesRepository.deleteById(noteId) // jamais envoyée : rien à faire côté serveur
                return
            }
            notesRepository.delete(noteId)
        }
        syncIfEnabled()
    }

    private suspend fun save(
        title: String,
        body: String,
    ) {
        notesRepository.save(noteId, title, body)
        initial = title to body
        _hasUnsavedChanges.value = false
    }

    private suspend fun syncIfEnabled() {
        val joplin = settingsRepository.joplin.first()
        if (joplin.syncEnabled && joplin.isConfigured) notesSync.syncNow()
    }

    private companion object {
        const val HISTORY_PAUSE_MS = 1_000L
        const val MAX_HISTORY = 100
    }
}
