package com.lielu.todo.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lielu.todo.calendar.CalendarSync
import com.lielu.todo.calendar.DeviceCalendar
import com.lielu.todo.data.model.Label
import com.lielu.todo.data.model.Project
import com.lielu.todo.data.model.SavedFilter
import com.lielu.todo.data.settings.AppPrefs
import com.lielu.todo.data.settings.LauncherIcon
import com.lielu.todo.data.todo.TodoRepository
import com.lielu.todo.data.todo.TodoState
import com.lielu.todo.reminder.ReminderScheduler
import com.lielu.todo.ui.deviceHasSecureLock
import com.lielu.todo.ui.theme.ProjectPalette
import com.lielu.todo.ui.theme.parseHexColor
import kotlinx.coroutines.launch
import java.io.IOException

private enum class Creating { PROJECT, LABEL, FILTER }

@Composable
fun BrowseTab(
    repository: TodoRepository,
    state: TodoState,
    userName: String,
    serverLabel: String,
    snackbar: SnackbarHostState,
    onOpenProject: (Long) -> Unit,
    onOpenLabel: (Long) -> Unit,
    onOpenFilter: (Long) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenCompleted: () -> Unit,
    reminderScheduler: ReminderScheduler,
    calendarSync: CalendarSync,
    prefs: AppPrefs,
    onOpenNotifications: () -> Unit,
    onOpenActivity: () -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf<Creating?>(null) }
    var managingLabel by remember { mutableStateOf<Label?>(null) }
    var managingFilter by remember { mutableStateOf<SavedFilter?>(null) }
    var showIcons by remember { mutableStateOf(false) }
    var showFailed by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var projectsOpen by rememberSaveable { mutableStateOf(true) }
    var labelsOpen by rememberSaveable { mutableStateOf(true) }
    var filtersOpen by rememberSaveable { mutableStateOf(true) }
    val openCount =
        state.tasks
            .filter { !it.done }
            .groupingBy { it.projectId }
            .eachCount()
    val others = orderedProjects(state.projects.filter { it.id != state.inboxId })
    val muted = MaterialTheme.colorScheme.outline

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item { BrowseTopBar(userName.ifBlank { "Mon compte" }, onSettings) }
        item {
            BrowseCard {
                if (state.failed.isNotEmpty()) {
                    BrowseRow(
                        Icons.Outlined.SyncProblem,
                        "Modifications non envoyées",
                        count = state.failed.size,
                        iconColor = MaterialTheme.colorScheme.error,
                        onClick = { showFailed = true },
                    )
                }
                val favorites = state.tasks.count { it.isFavorite && !it.done }
                BrowseRow(
                    Icons.Outlined.StarOutline,
                    "Favoris",
                    count = favorites.takeIf { it > 0 },
                    onClick = onOpenFavorites,
                )
                BrowseRow(Icons.Outlined.CheckCircle, "Terminées", onClick = onOpenCompleted)
                BrowseRow(
                    Icons.Outlined.Notifications,
                    "Notifications",
                    count = state.unreadNotifications.takeIf { it > 0 },
                    onClick = onOpenNotifications,
                )
                BrowseRow(Icons.Outlined.BarChart, "Productivité et activité", onClick = onOpenActivity)
                BrowseRow(Icons.Outlined.Settings, "Paramètres et sauvegarde", onClick = onSettings)
                BrowseRow(Icons.AutoMirrored.Outlined.Logout, "Se déconnecter", onClick = {
                    if (state.ops.isEmpty() && state.failed.isEmpty()) onLogout() else confirmLogout = true
                })
            }
        }
        item {
            BrowseHeader(
                "Mes projets",
                expanded = projectsOpen,
                onToggle = { projectsOpen = !projectsOpen },
                onAdd = { creating = Creating.PROJECT },
            )
        }
        if (projectsOpen) {
            item {
                BrowseCard {
                    if (others.isEmpty()) {
                        Text(
                            "Aucun projet pour l'instant.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    others.forEach { (project, depth) ->
                        BrowseRow(
                            icon = null,
                            title = project.title,
                            count = openCount[project.id],
                            indent = depth,
                            iconColor = parseHexColor(project.hexColor, muted),
                            onClick = { onOpenProject(project.id) },
                        )
                    }
                }
            }
        }
        item {
            BrowseHeader(
                "Étiquettes",
                expanded = labelsOpen,
                onToggle = { labelsOpen = !labelsOpen },
                onAdd = { creating = Creating.LABEL },
            )
        }
        if (labelsOpen) {
            item {
                BrowseCard {
                    if (state.labels.isEmpty()) {
                        Text(
                            "Aucune étiquette pour l'instant.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    state.labels.forEach { label ->
                        val count = state.tasks.count { t -> !t.done && t.labels.any { it.id == label.id } }
                        BrowseRow(
                            Icons.AutoMirrored.Outlined.Label,
                            label.title,
                            count = count.takeIf { it > 0 },
                            iconColor = parseHexColor(label.hexColor, muted),
                            onClick = { onOpenLabel(label.id) },
                            onLongClick = { managingLabel = label },
                        )
                    }
                }
            }
        }
        item {
            BrowseHeader(
                "Filtres",
                expanded = filtersOpen,
                onToggle = { filtersOpen = !filtersOpen },
                onAdd = { creating = Creating.FILTER },
            )
        }
        if (filtersOpen) {
            item {
                BrowseCard {
                    if (state.filters.isEmpty()) {
                        Text(
                            "Aucun filtre pour l'instant.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    state.filters.forEach { filter ->
                        val count = state.filterResults[filter.id].orEmpty().count { !it.done }
                        BrowseRow(
                            Icons.Outlined.FilterList,
                            filter.title,
                            count = count.takeIf { it > 0 },
                            onClick = { onOpenFilter(filter.id) },
                            onLongClick = { managingFilter = filter },
                        )
                    }
                }
            }
        }
        item {
            var daily by remember { mutableStateOf(reminderScheduler.dailyEnabled) }
            BrowseCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Résumé quotidien à 8 h",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = daily,
                        onCheckedChange = {
                            daily = it
                            reminderScheduler.dailyEnabled = it
                        },
                    )
                }
            }
        }
        item { CalendarSyncCard(calendarSync, repository, prefs, snackbar) }
        item {
            var lock by remember { mutableStateOf(prefs.lockEnabled) }
            var sound by remember { mutableStateOf(prefs.completionSound) }
            BrowseCard {
                SwitchRow("Verrouiller l'app (code ou empreinte)", lock) { wanted ->
                    if (wanted && !deviceHasSecureLock(context)) {
                        scope.launch {
                            snackbar.showSnackbar(
                                "Définissez d'abord un verrouillage d'écran sur l'appareil.",
                            )
                        }
                    } else {
                        lock = wanted
                        prefs.lockEnabled = wanted
                    }
                }
                SwitchRow("Son à la fin d'une tâche", sound) {
                    sound = it
                    prefs.completionSound = it
                }
                BrowseRow(Icons.Outlined.Palette, "Icône de l'app : ${prefs.launcherIcon.label}", onClick = {
                    showIcons =
                        true
                })
            }
        }
        item {
            Text(
                serverLabel,
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }

    managingLabel?.let { label ->
        LabelManageDialog(
            label = label,
            onSave = { title, hex ->
                repository.updateLabel(label.id, title, hex)
                managingLabel = null
            },
            onDelete = {
                repository.deleteLabel(label.id)
                managingLabel = null
            },
            onDismiss = { managingLabel = null },
        )
    }
    managingFilter?.let { filter ->
        FilterManageDialog(
            filter = filter,
            onSave = { title, query ->
                managingFilter = null
                scope.launch {
                    try {
                        repository.updateFilter(filter.id, title, query)
                    } catch (e: IOException) {
                        snackbar.showSnackbar(
                            "Modification impossible (serveur injoignable ou requête refusée) : ${e.message}",
                        )
                    }
                }
            },
            onDelete = {
                managingFilter = null
                scope.launch {
                    try {
                        repository.deleteFilter(filter.id)
                    } catch (e: IOException) {
                        snackbar.showSnackbar("Suppression impossible (serveur injoignable) : ${e.message}")
                    }
                }
            },
            onDismiss = { managingFilter = null },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Se déconnecter ?") },
            text = {
                Text(
                    "${state.ops.size + state.failed.size} modification(s) ne sont pas encore sur le serveur. " +
                        "Elles restent sur ce téléphone et partiront quand vous vous reconnecterez avec ce compte ; " +
                        "un autre compte les effacerait.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    onLogout()
                }) { Text("Se déconnecter") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Annuler") } },
        )
    }
    // La fenêtre se ferme d'elle-même quand il n'y a plus rien à décider.
    LaunchedEffect(state.failed.isEmpty()) { if (state.failed.isEmpty()) showFailed = false }
    if (showFailed && state.failed.isNotEmpty()) {
        FailedOpsDialog(state, repository, onDismiss = { showFailed = false })
    }
    if (showIcons) {
        AlertDialog(
            onDismissRequest = { showIcons = false },
            title = { Text("Icône de l'app") },
            text = {
                Column {
                    LauncherIcon.entries.forEach { icon ->
                        TextButton(onClick = {
                            prefs.launcherIcon = icon
                            showIcons = false
                        }) { Text(icon.label + if (icon == prefs.launcherIcon) "  ✓" else "") }
                    }
                    Text(
                        "Le lanceur peut mettre quelques secondes à afficher la nouvelle icône.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showIcons = false }) { Text("Fermer") } },
        )
    }
    creating?.let { kind ->
        if (kind == Creating.FILTER) {
            FilterDialog(
                onConfirm = { name, query ->
                    creating = null
                    scope.launch {
                        try {
                            repository.createFilter(name, query)
                        } catch (e: IOException) {
                            snackbar.showSnackbar(
                                "Création impossible (serveur injoignable ou requête refusée) : ${e.message}",
                            )
                        }
                    }
                },
                onDismiss = { creating = null },
            )
        } else {
            NameDialog(
                title = if (kind == Creating.PROJECT) "Nouveau projet" else "Nouvelle étiquette",
                withColor = kind == Creating.PROJECT,
                onConfirm = { name, color ->
                    creating = null
                    // Hors ligne aussi : le projet ou l'étiquette existe tout de suite et part au prochain envoi.
                    if (kind ==
                        Creating.PROJECT
                    ) {
                        repository.createProject(name, color)
                    } else {
                        repository.createLabel(name)
                    }
                },
                onDismiss = { creating = null },
            )
        }
    }
}

/** Projets dans l'ordre de l'arborescence, avec leur profondeur (sous-projets sous leur parent). */
internal fun orderedProjects(projects: List<Project>): List<Pair<Project, Int>> {
    val byParent = projects.groupBy { it.parentId }
    val ids = projects.map { it.id }.toSet()
    val result = mutableListOf<Pair<Project, Int>>()

    fun add(
        project: Project,
        depth: Int,
    ) {
        result += project to depth
        byParent[project.id].orEmpty().sortedBy { it.position }.forEach { add(it, depth + 1) }
    }
    // Un projet dont le parent n'est pas dans la liste (archivé, inaccessible) est traité comme une racine.
    projects.filter { it.parentId == 0L || it.parentId !in ids }.sortedBy { it.position }.forEach { add(it, 0) }
    return result
}

/** En-tête de l'onglet Parcourir : pastille d'initiale, nom du compte, réglages. */
@Composable
private fun BrowseTopBar(
    userName: String,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                userName.first().uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Text(
            userName,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = "Paramètres", tint = MaterialTheme.colorScheme.onSurface)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Groupe de lignes sur une carte arrondie, un ton au-dessus du fond. */
@Composable
private fun BrowseCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(content = content)
    }
}

@Composable
private fun BrowseHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Ajouter : $title") }
        IconButton(onClick = onToggle) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Replier : $title" else "Déplier : $title",
            )
        }
    }
}

/** Ligne de carte. Sans [icon], le repère « # » des projets Todoist est dessiné dans [iconColor]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowseRow(
    icon: ImageVector?,
    title: String,
    count: Int? = null,
    indent: Int = 0,
    iconColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(start = 16.dp + 20.dp * indent, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
        } else {
            Text(
                "#",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = iconColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(24.dp),
            )
        }
        Text(
            title,
            modifier = Modifier.weight(1f).padding(start = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun FilterDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("done = false && priority >= 3") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouveau filtre") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Nom") },
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Requête Vikunja") },
                    supportingText = {
                        Text("Ex. : due_date < now && done = false  ·  labels in urgent  ·  priority >= 3")
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), query.trim()) },
                enabled =
                    name.isNotBlank() && query.isNotBlank(),
            ) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun NameDialog(
    title: String,
    withColor: Boolean,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Nom") },
                )
                if (withColor) {
                    Text(
                        "Couleur",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    ProjectPalette.chunked(5).forEach { row ->
                        Row {
                            row.forEach { hex ->
                                TextButton(onClick = { color = hex }) {
                                    Text(
                                        if (color == hex) "◉" else "●",
                                        color = parseHexColor(hex, MaterialTheme.colorScheme.outline),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), color) },
                enabled = name.isNotBlank(),
            ) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Couleur d'un projet : hex sans « # » tel que Vikunja le stocke ; `null` si vide ou invalide (couleur du thème). */
internal fun hexColor(hex: String): androidx.compose.ui.graphics.Color? =
    hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let {
        androidx.compose.ui.graphics.Color(
            0xFF000000 or it,
        )
    }

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Recopie des tâches datées dans un agenda de l'appareil. À l'activation : autorisation, puis choix de l'agenda.
 * Les événements n'ont pas de rappel (l'app sonne déjà) et suivent la tâche : date changée, terminée, supprimée.
 */
@Composable
private fun CalendarSyncCard(
    calendarSync: CalendarSync,
    repository: TodoRepository,
    prefs: AppPrefs,
    snackbar: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(prefs.calendarSyncEnabled) }
    var calendarName by remember { mutableStateOf(prefs.calendarName) }
    var choices by remember { mutableStateOf<List<DeviceCalendar>?>(null) }

    suspend fun report(name: String) {
        val result = calendarSync.sync(repository.state.value)
        snackbar.showSnackbar(result.describe(name))
    }

    fun openChoices() {
        scope.launch {
            val found = calendarSync.calendars()
            if (found.isEmpty()) {
                snackbar.showSnackbar("Aucun agenda modifiable trouvé sur cet appareil.")
            } else {
                choices = found
            }
        }
    }

    val permission =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .RequestMultiplePermissions(),
        ) { granted ->
            if (granted.values.all { it }) {
                openChoices()
            } else {
                scope.launch { snackbar.showSnackbar("Sans l'autorisation Agenda, la recopie ne peut pas marcher.") }
            }
        }

    BrowseCard {
        SwitchRow("Ajouter les tâches datées à mon agenda", enabled) { wanted ->
            if (!wanted) {
                enabled = false
                prefs.calendarSyncEnabled = false
                scope.launch { calendarSync.sync(repository.state.value) }
            } else if (calendarSync.hasPermission()) {
                openChoices()
            } else {
                permission.launch(
                    arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR),
                )
            }
        }
        if (enabled) {
            BrowseRow(
                Icons.Outlined.CalendarMonth,
                "Agenda : ${calendarName.ifBlank { "à choisir" }}",
                onClick = { openChoices() },
            )
            BrowseRow(
                Icons.Outlined.CalendarMonth,
                "Synchroniser maintenant",
                onClick = { scope.launch { report(calendarName) } },
            )
        }
    }

    choices?.let { list ->
        AlertDialog(
            onDismissRequest = { choices = null },
            title = { Text("Agenda à utiliser") },
            text = {
                Column {
                    Text(
                        "Les tâches y apparaissent sans rappel (l'app sonne déjà). Modifier l'événement dans " +
                            "l'agenda ne change pas la tâche.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    list.forEach { calendar ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        prefs.calendarId = calendar.id
                                        prefs.calendarName = calendar.name
                                        prefs.calendarSyncEnabled = true
                                        calendarName = calendar.name
                                        enabled = true
                                        choices = null
                                        scope.launch { report(calendar.name) }
                                    }.padding(vertical = 10.dp),
                        ) {
                            Column {
                                Text(calendar.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    calendar.account,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choices = null }) { Text("Annuler") } },
        )
    }
}

/** Renommer, recolorer ou supprimer une étiquette (appui long sur sa ligne) ; marche aussi hors ligne. */
@Composable
private fun LabelManageDialog(
    label: Label,
    onSave: (String, String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(label.title) }
    var hex by remember { mutableStateOf(label.hexColor) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirmDelete) "Supprimer « ${label.title} » ?" else "Étiquette") },
        text = {
            if (confirmDelete) {
                Text("Elle sera retirée de toutes les tâches qui la portent.")
            } else {
                Column {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        singleLine = true,
                        label = { Text("Nom") },
                    )
                    Row(modifier = Modifier.padding(top = 8.dp)) {
                        ProjectPalette.take(5).forEach { color ->
                            TextButton(onClick = { hex = color }) {
                                Text(
                                    if (hex ==
                                        color
                                    ) {
                                        "◉"
                                    } else {
                                        "●"
                                    },
                                    color = parseHexColor(color, MaterialTheme.colorScheme.outline),
                                )
                            }
                        }
                    }
                    Row {
                        ProjectPalette.drop(5).forEach { color ->
                            TextButton(onClick = { hex = color }) {
                                Text(
                                    if (hex ==
                                        color
                                    ) {
                                        "◉"
                                    } else {
                                        "●"
                                    },
                                    color = parseHexColor(color, MaterialTheme.colorScheme.outline),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (confirmDelete) {
                TextButton(onClick = onDelete) { Text("Supprimer") }
            } else {
                TextButton(
                    onClick = { onSave(title.trim(), hex) },
                    enabled = title.isNotBlank(),
                ) { Text("Enregistrer") }
            }
        },
        dismissButton = {
            Row {
                if (!confirmDelete) TextButton(onClick = { confirmDelete = true }) { Text("Supprimer") }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
    )
}

/** Modifier ou supprimer un filtre enregistré (en ligne : le serveur valide la requête). */
@Composable
private fun FilterManageDialog(
    filter: SavedFilter,
    onSave: (String, String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(filter.title) }
    var query by remember { mutableStateOf(filter.query) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirmDelete) "Supprimer « ${filter.title} » ?" else "Filtre") },
        text = {
            if (confirmDelete) {
                Text("Les tâches ne sont pas touchées, seul le filtre disparaît.")
            } else {
                Column {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        singleLine = true,
                        label = { Text("Nom") },
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Requête Vikunja") },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (confirmDelete) {
                TextButton(onClick = onDelete) { Text("Supprimer") }
            } else {
                TextButton(
                    onClick = { onSave(title.trim(), query.trim()) },
                    enabled = title.isNotBlank() && query.isNotBlank(),
                ) { Text("Enregistrer") }
            }
        },
        dismissButton = {
            Row {
                if (!confirmDelete) TextButton(onClick = { confirmDelete = true }) { Text("Supprimer") }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
    )
}
