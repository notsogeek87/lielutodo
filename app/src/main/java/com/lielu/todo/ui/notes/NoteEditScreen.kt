package com.lielu.todo.ui.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditScreen(
    viewModel: NoteEditViewModel,
    onBackClick: () -> Unit,
) {
    val title by viewModel.title.collectAsState()
    val body by viewModel.body.collectAsState()
    val exists by viewModel.exists.collectAsState()
    val startsEmpty by viewModel.startsEmpty.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val parentId by viewModel.parentId.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var autoPreviewDone by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    if (!exists) {
        LaunchedEffect(Unit) { onBackClick() }
    }
    // Nouvelle note : on peut écrire tout de suite, curseur dans le titre et clavier ouvert.
    LaunchedEffect(startsEmpty) {
        if (startsEmpty == true) {
            titleFocus.requestFocus()
            keyboard?.show()
        }
        // Note existante : ouverte en lecture (rendu mis en forme), le crayon passe en édition Markdown.
        if (startsEmpty == false && !autoPreviewDone) {
            autoPreviewDone = true
            preview = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    if (!preview) {
                        IconButton(onClick = viewModel::undo, enabled = canUndo) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Annuler")
                        }
                        IconButton(onClick = viewModel::redo, enabled = canRedo) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Rétablir")
                        }
                    }
                    IconButton(onClick = { preview = !preview }) {
                        Icon(
                            if (preview) Icons.Default.Edit else Icons.Default.Visibility,
                            contentDescription = if (preview) "Modifier" else "Aperçu",
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Plus d'options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Enregistrer maintenant") },
                                enabled = saveState == SaveState.PENDING,
                                onClick = {
                                    menuOpen = false
                                    viewModel.onSaveClicked()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Supprimer la note") },
                                onClick = {
                                    menuOpen = false
                                    showDeleteConfirm = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        bottomBar = {
            if (!preview) {
                MarkdownToolbar(viewModel)
            }
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
        ) {
            if (preview) {
                NotePreview(
                    title = title,
                    body = body.text,
                    loadResource = viewModel::loadResource,
                    modifier = Modifier.weight(1f),
                )
            } else {
                BasicTextField(
                    value = title,
                    onValueChange = viewModel::onTitleChanged,
                    singleLine = true,
                    textStyle =
                        MaterialTheme.typography.headlineSmall.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions =
                        KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { bodyFocus.requestFocus() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                    decorationBox = { inner ->
                        if (title.isEmpty()) Placeholder("Titre", MaterialTheme.typography.headlineSmall)
                        inner()
                    },
                )
                FolderChip(
                    folders = folders,
                    parentId = parentId,
                    onSelected = viewModel::onFolderSelected,
                )
                BasicTextField(
                    value = body,
                    onValueChange = viewModel::onBodyChanged,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    // Toute la hauteur restante est zone de saisie : toucher sous le texte place le curseur à la fin.
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 8.dp)
                            .focusRequester(bodyFocus),
                    decorationBox = { inner ->
                        if (body.text.isEmpty()) {
                            Placeholder(
                                "Écrivez ici… (Markdown : la barre du bas met en forme la ligne ou la sélection)",
                                MaterialTheme.typography.bodyLarge,
                            )
                        }
                        inner()
                    },
                )
            }
            StatusLine(saveState = saveState, body = body.text)
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Supprimer la note") },
            text = {
                Text(
                    "La note sera supprimée ici et placée dans la corbeille de Joplin à la prochaine " +
                        "synchronisation. Continuer ?",
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

@Composable
private fun Placeholder(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
) {
    Text(text, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Carnet de la note (Joplin exige un carnet : pas de choix « aucun »). */
@Composable
private fun FolderChip(
    folders: List<com.lielu.todo.data.notes.Folder>,
    parentId: String,
    onSelected: (String) -> Unit,
) {
    if (folders.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val current = folders.firstOrNull { it.id == parentId }?.title ?: "Choisir un carnet"
    Box(modifier = Modifier.padding(top = 4.dp)) {
        AssistChip(
            onClick = { open = true },
            label = { Text(current) },
            leadingIcon = {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            folders.forEach { folder ->
                DropdownMenuItem(
                    text = { Text(folder.title) },
                    onClick = {
                        open = false
                        onSelected(folder.id)
                    },
                )
            }
        }
    }
}

/** « Enregistré · Non synchronisée · 12 mots », sous la note. */
@Composable
private fun StatusLine(
    saveState: SaveState,
    body: String,
) {
    val words = body.split(Regex("\\s+")).count { it.isNotBlank() }
    val state =
        when (saveState) {
            SaveState.PENDING -> "Enregistrement…"
            SaveState.NOT_SYNCED -> "Enregistrée · pas encore envoyée à Joplin"
            SaveState.SAVED -> "Enregistrée"
        }
    Text(
        "$state · $words mot${if (words > 1) "s" else ""}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

/** Barre de mise en forme, collée au-dessus du clavier (et non perdue en haut d'une longue note). */
@Composable
private fun MarkdownToolbar(viewModel: NoteEditViewModel) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
    ) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                ToolButton(Icons.Default.Title, "Titre de section", viewModel::toggleHeading)
                ToolButton(Icons.Default.FormatBold, "Gras", viewModel::toggleBold)
                ToolButton(Icons.Default.FormatItalic, "Italique", viewModel::toggleItalic)
                ToolButton(Icons.Default.StrikethroughS, "Barré", viewModel::toggleStrike)
                ToolButton(Icons.Default.CheckBox, "Case à cocher", viewModel::toggleCheckbox)
                ToolButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Liste à puces", viewModel::toggleBullet)
                ToolButton(Icons.Default.FormatListNumbered, "Liste numérotée", viewModel::toggleNumbered)
                ToolButton(Icons.Default.FormatQuote, "Citation", viewModel::toggleQuote)
                ToolButton(Icons.Default.Code, "Code", viewModel::toggleCode)
                ToolButton(Icons.Default.Link, "Lien", viewModel::insertLink)
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = description) }
}

/** Aperçu rendu (Markwon : barré et cases à cocher compris), aux couleurs du thème de l'app, images jointes comprises. */
@Composable
private fun NotePreview(
    title: String,
    body: String,
    loadResource: suspend (String) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val outlineColor = MaterialTheme.colorScheme.outline.toArgb()
    val checkColor = MaterialTheme.colorScheme.onPrimary.toArgb()
    val context = LocalContext.current
    val markwon =
        remember(context, linkColor, outlineColor, checkColor) {
            Markwon
                .builder(context)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TaskListPlugin.create(linkColor, outlineColor, checkColor))
                .build()
        }
    val parts = remember(body) { NoteImages.split(body) }
    Column(modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            title.ifBlank { "Sans titre" },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        parts.forEachIndexed { index, part ->
            val top = if (index == 0) 12.dp else 4.dp
            when (part) {
                is NotePart.Text ->
                    AndroidView(
                        factory = { TextView(it) },
                        update = { view ->
                            view.setTextColor(textColor)
                            view.setLinkTextColor(linkColor)
                            markwon.setMarkdown(view, part.markdown)
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = top),
                    )
                is NotePart.Image ->
                    NoteImage(part, loadResource, Modifier.fillMaxWidth().padding(top = top, bottom = 4.dp))
            }
        }
    }
}

private sealed interface ImageState {
    data object Loading : ImageState

    data object Missing : ImageState

    data class Ready(
        val bitmap: ImageBitmap,
    ) : ImageState
}

/** Image jointe Joplin : téléchargée (ou relue hors ligne), réduite à la largeur de l'écran pour économiser la mémoire. */
@Composable
private fun NoteImage(
    image: NotePart.Image,
    loadResource: suspend (String) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    val maxWidth = LocalContext.current.resources.displayMetrics.widthPixels
    val state by produceState<ImageState>(ImageState.Loading, image.resourceId) {
        val bytes = loadResource(image.resourceId)
        val bitmap = bytes?.let { withContext(Dispatchers.Default) { decodeScaled(it, maxWidth) } }
        value = bitmap?.let { ImageState.Ready(it.asImageBitmap()) } ?: ImageState.Missing
    }
    when (val s = state) {
        is ImageState.Ready ->
            Image(
                bitmap = s.bitmap,
                contentDescription = image.alt.ifBlank { "Image" },
                contentScale = ContentScale.FillWidth,
                modifier = modifier.clip(MaterialTheme.shapes.medium),
            )
        ImageState.Loading ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = modifier.height(160.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            }
        ImageState.Missing ->
            Text(
                "🖼 ${image.alt.ifBlank {
                    "Image"
                }} — indisponible (serveur Joplin injoignable ou notes non connectées)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = modifier,
            )
    }
}

/** Décode en divisant la taille par 2 tant que l'image dépasse [maxWidth] (photos de plusieurs Mo). */
private fun decodeScaled(
    bytes: ByteArray,
    maxWidth: Int,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
