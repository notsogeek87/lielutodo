package com.lielu.todo.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lielu.todo.data.joplin.ShareInvitation
import com.lielu.todo.data.joplin.ShareRecipient
import com.lielu.todo.data.joplin.ShareStatus
import com.lielu.todo.data.notes.Folder
import com.lielu.todo.data.notes.Note
import com.lielu.todo.data.notes.NotesRepository
import com.lielu.todo.data.notes.NotesSync
import com.lielu.todo.data.settings.AppPrefs
import com.lielu.todo.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Fenêtre « Partager le carnet » : participants (chargés du serveur), saisie en cours, erreur éventuelle. */
data class ShareUiState(
    val folderId: String,
    val folderTitle: String,
    val recipients: List<ShareRecipient> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class NotesViewModel(
    private val notesRepository: NotesRepository,
    private val notesSync: NotesSync,
    private val settingsRepository: SettingsRepository,
    private val appScope: CoroutineScope,
    private val prefs: AppPrefs,
) : ViewModel() {
    /** Carnet choisi (null = tous) : gardé en naviguant et d'un lancement à l'autre. */
    private val _selectedFolder = MutableStateFlow(prefs.notesFolder.ifBlank { null })
    val selectedFolder: StateFlow<String?> = _selectedFolder

    fun onSelectFolder(folderId: String?) {
        _selectedFolder.value = folderId
        prefs.notesFolder = folderId.orEmpty()
    }

    /** Fenêtre des invitations reçues (ouverte aussi depuis la notification). */
    private val _showInvitations = MutableStateFlow(false)
    val showInvitations: StateFlow<Boolean> = _showInvitations

    /** Toutes les invitations (en attente, acceptées, refusées), pour l'écran « Carnets partagés ». */
    private val _allInvitations = MutableStateFlow<List<ShareInvitation>>(emptyList())
    val allInvitations: StateFlow<List<ShareInvitation>> = _allInvitations
    private val _invitationsLoading = MutableStateFlow(false)
    val invitationsLoading: StateFlow<Boolean> = _invitationsLoading

    fun onOpenInvitations() {
        _showInvitations.value = true
        refreshInvitations()
    }

    fun onCloseInvitations() {
        _showInvitations.value = false
        refreshInvitations()
    }

    val notes: StateFlow<List<Note>> =
        notesRepository.notes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Carnets Joplin connus (pour regrouper/filtrer les notes). */
    val folders: StateFlow<List<Folder>> =
        notesRepository.folders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Synchro Joplin réglée : le partage (qui exige le serveur) n'est proposé que dans ce cas. */
    val syncReady: StateFlow<Boolean> =
        settingsRepository.joplin
            .map { it.syncEnabled && it.isConfigured }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _invitations = MutableStateFlow<List<ShareInvitation>>(emptyList())
    val invitations: StateFlow<List<ShareInvitation>> = _invitations

    private val _share = MutableStateFlow<ShareUiState?>(null)
    val share: StateFlow<ShareUiState?> = _share

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    init {
        refreshInvitations()
    }

    fun onMessageShown() {
        _message.value = null
    }

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

    /** Nouveau carnet : créé ici tout de suite (hors ligne aussi), envoyé à la synchro. */
    fun onCreateFolder(
        title: String,
        onCreated: (String) -> Unit,
    ) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val folder = notesRepository.createFolder(clean)
            onCreated(folder.id)
            syncInBackground()
        }
    }

    fun onOpenShare(folder: Folder) {
        _share.value = ShareUiState(folder.id, folder.title)
        loadRecipients(folder.id)
    }

    fun onCloseShare() {
        _share.value = null
    }

    fun onInvite(email: String) {
        val state = _share.value ?: return
        _share.value = state.copy(loading = true, error = null)
        viewModelScope.launch {
            notesSync
                .shareFolder(state.folderId, email.trim())
                .onSuccess {
                    _message.value = "Invitation envoyée à ${email.trim()}."
                    loadRecipients(state.folderId)
                }.onFailure { e -> _share.value = _share.value?.copy(loading = false, error = e.message) }
        }
    }

    fun onRemoveRecipient(recipient: ShareRecipient) {
        val state = _share.value ?: return
        _share.value = state.copy(loading = true, error = null)
        viewModelScope.launch {
            notesSync
                .removeShareRecipient(recipient.shareUserId)
                .onSuccess { loadRecipients(state.folderId) }
                .onFailure { e -> _share.value = _share.value?.copy(loading = false, error = e.message) }
        }
    }

    fun onStopSharing() {
        val state = _share.value ?: return
        _share.value = state.copy(loading = true, error = null)
        viewModelScope.launch {
            notesSync
                .stopSharing(state.folderId)
                .onSuccess {
                    _share.value = null
                    _message.value = "« ${state.folderTitle} » n'est plus partagé."
                }.onFailure { e -> _share.value = _share.value?.copy(loading = false, error = e.message) }
        }
    }

    fun onRespondToInvitation(
        invitation: ShareInvitation,
        accept: Boolean,
    ) {
        viewModelScope.launch {
            notesSync
                .respondToInvitation(invitation.shareUserId, accept)
                .onSuccess {
                    _message.value = if (accept) "Carnet partagé ajouté." else "Invitation refusée."
                    refreshInvitations()
                }.onFailure { e -> _message.value = e.message }
        }
    }

    fun refreshInvitations() {
        viewModelScope.launch {
            if (!settingsRepository.joplin.first().let { it.syncEnabled && it.isConfigured }) return@launch
            _invitationsLoading.value = true
            notesSync.allInvitations().onSuccess { all ->
                _allInvitations.value = all
                _invitations.value = all.filter { it.status == ShareStatus.PENDING }
            }
            _invitationsLoading.value = false
        }
    }

    private fun loadRecipients(folderId: String) {
        viewModelScope.launch {
            notesSync
                .shareRecipients(folderId)
                .onSuccess { list -> _share.value = _share.value?.copy(recipients = list, loading = false) }
                .onFailure { e -> _share.value = _share.value?.copy(loading = false, error = e.message) }
        }
    }

    private fun syncInBackground() {
        appScope.launch {
            val joplin = settingsRepository.joplin.first()
            if (joplin.syncEnabled && joplin.isConfigured) notesSync.syncNow()
        }
    }
}
