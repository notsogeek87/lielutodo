package com.lielu.todo.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lielu.todo.data.notes.Folder
import com.lielu.todo.data.notes.Note
import com.lielu.todo.data.notes.NotesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NotesViewModel(
    private val notesRepository: NotesRepository,
) : ViewModel() {
    val notes: StateFlow<List<Note>> =
        notesRepository.notes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Carnets Joplin connus (pour regrouper/filtrer les notes). */
    val folders: StateFlow<List<Folder>> =
        notesRepository.folders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Crée une note, affectée au carnet filtré (ou au premier carnet) pour qu'elle soit visible dans Joplin. */
    fun onAddNote(
        selectedFolder: String?,
        onCreated: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val note = notesRepository.create()
            val folderId = selectedFolder ?: folders.value.firstOrNull()?.id ?: ""
            if (folderId.isNotEmpty()) notesRepository.setParent(note.id, folderId)
            onCreated(note.id)
        }
    }
}
