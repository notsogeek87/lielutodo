package com.lielu.todo.ui.notes

import android.widget.TextView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditScreen(
    viewModel: NoteEditViewModel,
    onBackClick: () -> Unit,
) {
    val title by viewModel.title.collectAsState()
    val body by viewModel.body.collectAsState()
    val exists by viewModel.exists.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val parentId by viewModel.parentId.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var folderMenuOpen by remember { mutableStateOf(false) }
    val currentFolderTitle = folders.firstOrNull { it.id == parentId }?.title ?: "Sans carnet"

    if (!exists) {
        LaunchedEffect(Unit) { onBackClick() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Note") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { preview = !preview }) {
                        Icon(
                            if (preview) Icons.Default.Edit else Icons.Default.Visibility,
                            contentDescription = if (preview) "Éditer le Markdown" else "Aperçu rendu",
                        )
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Supprimer la note")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { paddingValues ->
        if (preview) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                AndroidView(
                    factory = { context ->
                        TextView(context).apply { Markwon.create(context).setMarkdown(this, body) }
                    },
                    update = { view -> Markwon.create(view.context).setMarkdown(view, body) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = viewModel::onTitleChanged,
                    label = { Text("Titre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (folders.isNotEmpty()) {
                    TextButton(
                        onClick = { folderMenuOpen = true },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text("Carnet : $currentFolderTitle ▾")
                    }
                    DropdownMenu(
                        expanded = folderMenuOpen,
                        onDismissRequest = { folderMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Sans carnet") },
                            onClick = {
                                folderMenuOpen = false
                                viewModel.onFolderSelected("")
                            },
                        )
                        folders.forEach { folder ->
                            DropdownMenuItem(
                                text = { Text(folder.title) },
                                onClick = {
                                    folderMenuOpen = false
                                    viewModel.onFolderSelected(folder.id)
                                },
                            )
                        }
                    }
                }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MarkdownButton("B") { viewModel.onBodyChanged(wrapLastLine(body, "**", "**")) }
                    MarkdownButton("I") { viewModel.onBodyChanged(wrapLastLine(body, "*", "*")) }
                    MarkdownButton("S") { viewModel.onBodyChanged(wrapLastLine(body, "~~", "~~")) }
                    MarkdownButton("H") { viewModel.onBodyChanged(appendBlock(body, "## Titre")) }
                    MarkdownButton("•") { viewModel.onBodyChanged(appendBlock(body, "- élément")) }
                    MarkdownButton("☐") { viewModel.onBodyChanged(appendBlock(body, "- [ ] tâche")) }
                    MarkdownButton("</>") { viewModel.onBodyChanged(appendBlock(body, "`code`")) }
                    MarkdownButton("🔗") { viewModel.onBodyChanged(appendBlock(body, "[texte](https://…)")) }
                    MarkdownButton("❝") { viewModel.onBodyChanged(appendBlock(body, "> citation")) }
                }
                OutlinedTextField(
                    value = body,
                    onValueChange = viewModel::onBodyChanged,
                    label = { Text("Note (Markdown)") },
                    minLines = 12,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Supprimer la note") },
            text = {
                Text(
                    "La note sera supprimée ici et, à la prochaine synchronisation, sur le serveur Joplin. " +
                        "Continuer ?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.onDeleteConfirmed(onDeleted = onBackClick)
                }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Annuler") }
            },
        )
    }
}

/** Petit bouton de la barre d'outils Markdown (libellé texte, ex. « B » pour gras). */
@Composable
private fun MarkdownButton(
    label: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

/** Entoure la dernière ligne non vide de [body] avec [prefix]/[suffix] (gras, italique, barré…). */
private fun wrapLastLine(
    body: String,
    prefix: String,
    suffix: String,
): String {
    val lines = body.split('\n')
    val last = lines.indexOfLast { it.isNotBlank() }
    if (last < 0) return prefix + suffix // rien à entourer : on pose juste les marqueurs
    lines[last] = "$prefix${lines[last]}$suffix"
    return lines.joinToString("\n")
}

/** Ajoute un modèle Markdown [snippet] en fin de note (sur une nouvelle ligne si besoin). */
private fun appendBlock(
    body: String,
    snippet: String,
): String = if (body.isEmpty() || body.endsWith("\n")) body + snippet else "$body\n$snippet"
