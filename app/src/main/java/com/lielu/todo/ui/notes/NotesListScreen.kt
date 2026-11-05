package com.lielu.todo.ui.notes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lielu.todo.data.joplin.ShareStatus
import com.lielu.todo.data.notes.Folder
import com.lielu.todo.data.notes.Note
import com.lielu.todo.ui.common.SectionHeader
import com.lielu.todo.ui.formatDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(
    viewModel: NotesViewModel,
    onNoteClick: (String) -> Unit,
    onBackClick: (() -> Unit)? = null,
    /** Affiché comme onglet de l'accueil : pas de flèche retour, les marges système sont déjà prises. */
    embedded: Boolean = false,
) {
    val notes by viewModel.notes.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val syncReady by viewModel.syncReady.collectAsState()
    val invitations by viewModel.invitations.collectAsState()
    val share by viewModel.share.collectAsState()
    val message by viewModel.message.collectAsState()
    val savedFolder by viewModel.selectedFolder.collectAsState()
    // Carnet mémorisé mais supprimé entre-temps : retour à « Tous ».
    val selectedFolder = savedFolder?.takeIf { id -> folders.isEmpty() || folders.any { it.id == id } }
    var searchQuery by remember { mutableStateOf("") }
    var showNewFolder by remember { mutableStateOf(false) }
    val showInvitations by viewModel.showInvitations.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.onMessageShown()
    }

    Scaffold(
        contentWindowInsets = if (embedded) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            TopAppBar(
                title = { Text("Notes") },
                windowInsets = if (embedded) WindowInsets(0) else TopAppBarDefaults.windowInsets,
                navigationIcon = {
                    if (onBackClick != null) {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    }
                },
                actions = {
                    // Toujours visible : on y retrouve les invitations même sans notification.
                    IconButton(onClick = viewModel::onOpenInvitations) {
                        BadgedBox(badge = { if (invitations.isNotEmpty()) Badge { Text("${invitations.size}") } }) {
                            Icon(Icons.Default.People, contentDescription = "Carnets partagés")
                        }
                    }
                    IconButton(onClick = { showNewFolder = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "Nouveau carnet")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.onAddNote(selectedFolder, onNoteClick) }) {
                Icon(Icons.Default.Add, contentDescription = "Nouvelle note")
            }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Rechercher dans les notes") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (folders.isNotEmpty()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedFolder == null,
                        onClick = { viewModel.onSelectFolder(null) },
                        label = { Text("Tous") },
                    )
                    folders.forEach { folder ->
                        FilterChip(
                            selected = selectedFolder == folder.id,
                            onClick = { viewModel.onSelectFolder(folder.id) },
                            label = { Text(folder.title) },
                            leadingIcon =
                                if (folder.shareId.isNotBlank()) {
                                    { Icon(Icons.Default.People, contentDescription = "Partagé") }
                                } else {
                                    null
                                },
                        )
                    }
                }
            }
            // Carnet choisi : son partage (un sous-carnet ne se partage pas, règle Joplin).
            folders.firstOrNull { it.id == selectedFolder }?.let { folder ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            folder.shareId.isNotBlank() -> "Carnet partagé"
                            folder.dirty -> "Carnet pas encore envoyé à Joplin"
                            else -> "Carnet personnel"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (syncReady && folder.parentId.isBlank()) {
                        TextButton(onClick = { viewModel.onOpenShare(folder) }) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null)
                            Text("Partager", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
            val query = searchQuery.trim().lowercase()
            val visible =
                notes
                    .filter { selectedFolder == null || it.parentId == selectedFolder }
                    .filter { note ->
                        query.isEmpty() ||
                            note.title.lowercase().contains(query) ||
                            note.body.lowercase().contains(query)
                    }
            val folderTitle = folders.firstOrNull { it.id == selectedFolder }?.title
            if (notes.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (folderTitle != null) {
                            "Aucune note dans « $folderTitle »."
                        } else {
                            "Aucune note. Touchez + pour en créer une, ou synchronisez votre serveur Joplin " +
                                "depuis les paramètres."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (visible.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Aucun résultat pour « $searchQuery ».",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (selectedFolder == null && folders.isNotEmpty()) {
                        // Regroupé par carnet, comme dans Joplin.
                        val byFolder = visible.groupBy { it.parentId }
                        val ordered =
                            folders.filter { byFolder.containsKey(it.id) } +
                                listOf(Folder("", "Sans carnet")).filter { byFolder.containsKey("") }
                        ordered.forEach { folder ->
                            val group = byFolder[folder.id].orEmpty()
                            item(key = "header-${folder.id}") {
                                SectionHeader(title = folder.title, count = group.size)
                            }
                            items(group, key = { it.id }) { note ->
                                NoteCard(note = note, onClick = { onNoteClick(note.id) })
                            }
                        }
                    } else {
                        items(visible, key = { it.id }) { note ->
                            NoteCard(note = note, onClick = { onNoteClick(note.id) })
                        }
                    }
                }
            }
        }
    }
    NotesDialogs(
        viewModel = viewModel,
        share = share,
        invitations = invitations,
        showNewFolder = showNewFolder,
        onNewFolderDismiss = { showNewFolder = false },
        onFolderCreated = viewModel::onSelectFolder,
        showInvitations = showInvitations,
        onInvitationsDismiss = viewModel::onCloseInvitations,
    )
}

@Composable
private fun NotesDialogs(
    viewModel: NotesViewModel,
    share: ShareUiState?,
    invitations: List<com.lielu.todo.data.joplin.ShareInvitation>,
    showNewFolder: Boolean,
    onNewFolderDismiss: () -> Unit,
    onFolderCreated: (String) -> Unit,
    showInvitations: Boolean,
    onInvitationsDismiss: () -> Unit,
) {
    if (showNewFolder) {
        var title by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onNewFolderDismiss,
            title = { Text("Nouveau carnet") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Nom du carnet") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = title.isNotBlank(),
                    onClick = {
                        viewModel.onCreateFolder(title, onFolderCreated)
                        onNewFolderDismiss()
                    },
                ) { Text("Créer") }
            },
            dismissButton = { TextButton(onClick = onNewFolderDismiss) { Text("Annuler") } },
        )
    }
    if (share != null) ShareDialog(viewModel, share)
    if (showInvitations) SharedFoldersDialog(viewModel, onInvitationsDismiss)
}

/**
 * « Carnets partagés » : l'endroit permanent où retrouver ses invitations, même si la notification a été balayée ou
 * l'invitation refusée par erreur (Joplin la garde : on peut encore l'accepter), les carnets que d'autres partagent
 * avec vous (et par qui), et ceux que vous partagez (avec accès à la gestion des membres).
 */
@Composable
private fun SharedFoldersDialog(
    viewModel: NotesViewModel,
    onDismiss: () -> Unit,
) {
    val all by viewModel.allInvitations.collectAsState()
    val loading by viewModel.invitationsLoading.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val syncReady by viewModel.syncReady.collectAsState()
    val pending = all.filter { it.status == ShareStatus.PENDING }
    val accepted = all.filter { it.status == ShareStatus.ACCEPTED }
    val refused = all.filter { it.status == ShareStatus.REJECTED }
    val receivedFolderIds = accepted.map { it.folderId }.toSet()
    val mine = folders.filter { it.shareId.isNotBlank() && it.id !in receivedFolderIds }
    val titleOf = { id: String -> folders.firstOrNull { it.id == id }?.title }
    val owner = { inv: com.lielu.todo.data.joplin.ShareInvitation -> inv.ownerName.ifBlank { inv.ownerEmail } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Carnets partagés") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!syncReady) {
                    Text("Connectez vos notes Joplin (Paramètres) pour voir les partages.")
                    return@Column
                }
                if (loading && all.isEmpty()) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                SharedSection("Invitations en attente")
                if (pending.isEmpty()) {
                    SharedHint("Aucune. Une invitation reçue apparaît ici, même si vous avez balayé la notification.")
                }
                pending.forEach { inv ->
                    SharedRow(
                        "${owner(inv)} vous partage un carnet",
                        inv.ownerEmail.takeIf { inv.ownerName.isNotBlank() },
                    )
                    Row {
                        TextButton(
                            onClick = { viewModel.onRespondToInvitation(inv, accept = true) },
                        ) { Text("Accepter") }
                        TextButton(
                            onClick = { viewModel.onRespondToInvitation(inv, accept = false) },
                        ) { Text("Refuser") }
                    }
                }
                SharedSection("Partagés avec vous")
                if (accepted.isEmpty()) SharedHint("Aucun carnet.")
                accepted.forEach { inv ->
                    SharedRow(titleOf(inv.folderId) ?: "Carnet partagé", "par ${owner(inv)}")
                }
                SharedSection("Vous partagez")
                if (mine.isEmpty()) SharedHint("Aucun carnet. Choisissez un carnet puis « Partager ».")
                mine.forEach { folder ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(folder.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            onDismiss()
                            viewModel.onOpenShare(folder)
                        }) { Text("Membres") }
                    }
                }
                if (refused.isNotEmpty()) {
                    SharedSection("Refusées")
                    refused.forEach { inv ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Carnet de ${owner(inv)}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { viewModel.onRespondToInvitation(inv, accept = true) }) {
                                Text("Accepter")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@Composable
private fun SharedSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SharedHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SharedRow(
    title: String,
    subtitle: String?,
) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Partage d'un carnet : participants (en attente / accepté), invitation par e-mail, arrêt du partage. */
@Composable
private fun ShareDialog(
    viewModel: NotesViewModel,
    share: ShareUiState,
) {
    var email by remember { mutableStateOf("") }
    var confirmStop by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = viewModel::onCloseShare,
        title = { Text("Partager « ${share.folderTitle} »") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Invitez un compte du même serveur Joplin. Il accepte l'invitation dans Joplin ou LieluTodo, " +
                        "puis lit et modifie les notes de ce carnet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("E-mail du compte Joplin") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    enabled = !share.loading && email.contains('@'),
                    onClick = {
                        viewModel.onInvite(email)
                        email = ""
                    },
                ) { Text("Inviter") }
                if (share.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                share.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                share.recipients.forEach { recipient ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(recipient.email, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when (recipient.status) {
                                    ShareStatus.ACCEPTED -> "A accepté"
                                    ShareStatus.REJECTED -> "A refusé"
                                    ShareStatus.PENDING -> "Invitation en attente"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { viewModel.onRemoveRecipient(recipient) }, enabled = !share.loading) {
                            Icon(Icons.Default.PersonRemove, contentDescription = "Retirer ${recipient.email}")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::onCloseShare) { Text("Fermer") } },
        dismissButton = {
            if (share.recipients.isNotEmpty() || !share.loading) {
                TextButton(onClick = { confirmStop = true }, enabled = !share.loading) { Text("Arrêter le partage") }
            }
        },
    )
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Arrêter le partage ?") },
            text = { Text("Les participants n'auront plus accès à ce carnet. Vos notes restent intactes.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    viewModel.onStopSharing()
                }) { Text("Arrêter") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun NoteCard(
    note: Note,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                note.title.ifBlank { "Sans titre" },
                style = MaterialTheme.typography.titleMedium,
            )
            if (note.body.isNotBlank()) {
                Text(
                    MarkdownEditing.plainPreview(note.body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatDateTime(note.userUpdatedTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (note.dirty) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Default.CloudOff,
                            contentDescription = "Non synchronisée",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(0.dp),
                        )
                        Text(
                            "Non synchronisée",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
