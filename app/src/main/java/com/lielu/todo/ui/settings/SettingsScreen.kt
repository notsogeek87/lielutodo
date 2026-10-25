package com.lielu.todo.ui.settings

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lielu.githubupdater.UpdateInfo
import com.lielu.githubupdater.UpdateState
import com.lielu.todo.BackupFileNames
import com.lielu.todo.BuildConfig
import com.lielu.todo.R
import com.lielu.todo.data.settings.AutoBackupSettings
import com.lielu.todo.data.settings.BackupStatus
import com.lielu.todo.data.settings.ThemeMode
import com.lielu.todo.ui.formatDateTime
import com.lielu.todo.ui.update.updateErrorMessage
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
    var showRestoreConfirm by remember { mutableStateOf<RestoreSource?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val folderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) viewModel.onFolderPicked(context.contentResolver, uri)
        }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) viewModel.onExportRequested(context.contentResolver, uri)
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.onImportRequested(context.contentResolver, uri)
        }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.onMessageShown()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paramètres") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Text("Apparence", style = MaterialTheme.typography.titleMedium)
            Text(
                "« Système » suit le réglage clair/sombre de l'appareil.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            ThemeModeSelector(selected = uiState.themeMode, onSelected = viewModel::onThemeModeChanged)

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            AutoBackupSection(
                config = uiState.autoBackup,
                status = uiState.backupStatus,
                busy = uiState.backupInProgress,
                onToggle = { enabled ->
                    // Sans dossier, activer ne sert à rien : on demande d'abord où écrire.
                    if (enabled && uiState.autoBackup.folderUri == null) {
                        folderLauncher.launch(null)
                    } else {
                        viewModel.onAutoBackupToggled(enabled)
                    }
                },
                onChooseFolder = { folderLauncher.launch(null) },
                onKeepChanged = viewModel::onKeepChanged,
                onBackupNow = viewModel::onBackupNowRequested,
                onRestoreLatest = { showRestoreConfirm = RestoreSource.LATEST_AUTO },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Export / import manuel", style = MaterialTheme.typography.titleMedium)
            Text(
                "Enregistrez un fichier où vous voulez (Drive, Fichiers…), ou restaurez-en un.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { exportLauncher.launch(defaultExportFileName(BackupFileNames.prefix(context))) },
                    enabled = !uiState.backupInProgress,
                ) { Text("Exporter…") }
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedButton(
                    onClick = { showRestoreConfirm = RestoreSource.FILE },
                    enabled = !uiState.backupInProgress,
                ) { Text("Importer…") }
            }

            if (viewModel.updatesEnabled) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                UpdateSection(
                    state = updateState,
                    appName = appName,
                    onCheck = viewModel::onCheckUpdateRequested,
                    onInstall = viewModel::onInstallUpdateRequested,
                    onInstallDownloaded = viewModel::onInstallDownloadedRequested,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text(
                "$appName ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    showRestoreConfirm?.let { source ->
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = null },
            title = { Text("Restaurer une sauvegarde") },
            text = {
                Text(
                    "Cela remplacera les données actuelles par celles de la sauvegarde. Cette action est irréversible. Continuer ?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = null
                    when (source) {
                        RestoreSource.FILE -> importLauncher.launch(arrayOf("application/json"))
                        RestoreSource.LATEST_AUTO -> viewModel.onRestoreLatestRequested()
                    }
                }) { Text("Restaurer") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = null }) { Text("Annuler") }
            },
        )
    }
}

private enum class RestoreSource { FILE, LATEST_AUTO }

@Composable
private fun AutoBackupSection(
    config: AutoBackupSettings,
    status: BackupStatus,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onChooseFolder: () -> Unit,
    onKeepChanged: (Int) -> Unit,
    onBackupNow: () -> Unit,
    onRestoreLatest: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Sauvegarde automatique", style = MaterialTheme.typography.titleMedium)
            Text(
                "Une copie par jour dans le dossier de votre choix.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = config.enabled && config.folderUri != null, onCheckedChange = onToggle)
    }

    Text(
        folderLabel(config.folderUri),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        statusLabel(status),
        style = MaterialTheme.typography.labelSmall,
        color =
            if (status.lastError !=
                null
            ) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        modifier = Modifier.padding(top = 4.dp),
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Copies conservées", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(
            onClick = { onKeepChanged(config.keep - 1) },
            enabled = config.keep > AutoBackupSettings.MIN_KEEP,
        ) { Text("−") }
        Text("${config.keep}")
        TextButton(
            onClick = { onKeepChanged(config.keep + 1) },
            enabled = config.keep < AutoBackupSettings.MAX_KEEP,
        ) { Text("+") }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onChooseFolder, enabled = !busy) {
                Text(if (config.folderUri == null) "Choisir un dossier…" else "Changer de dossier…")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = onBackupNow,
                enabled = !busy && config.folderUri != null,
            ) { Text("Sauvegarder maintenant") }
        }
        OutlinedButton(onClick = onRestoreLatest, enabled = !busy && config.folderUri != null) {
            Text("Restaurer la dernière sauvegarde")
        }
        if (busy) CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}

private fun folderLabel(folderUri: String?): String {
    if (folderUri == null) return "Aucun dossier choisi."
    val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(folderUri)) }.getOrNull()
    return "Dossier : ${id ?: folderUri}"
}

private fun statusLabel(status: BackupStatus): String =
    when {
        status.lastError != null -> status.lastError
        status.lastSuccessAt != null -> "Dernière sauvegarde : ${formatDateTime(status.lastSuccessAt)}"
        else -> "Aucune sauvegarde effectuée."
    }

/** Section « Mise à jour » : dessine l'état exposé par la bibliothèque lielugit-updater. */
@Composable
private fun UpdateSection(
    state: UpdateState,
    appName: String,
    onCheck: () -> Unit,
    onInstall: (UpdateInfo) -> Unit,
    onInstallDownloaded: (File) -> Unit,
) {
    Text("Mise à jour", style = MaterialTheme.typography.titleMedium)
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading
    val status =
        when (state) {
            UpdateState.Idle -> "Vérifie si une nouvelle version est publiée sur GitHub."
            UpdateState.Checking -> "Recherche de mise à jour…"
            UpdateState.UpToDate -> "Vous utilisez la dernière version."
            is UpdateState.UpdateAvailable -> "Version ${state.update.versionName} disponible."
            is UpdateState.Downloading -> "Téléchargement… ${state.progress.percentage?.let { "$it %" }.orEmpty()}"
            is UpdateState.Downloaded -> "Mise à jour téléchargée, prête à installer."
            UpdateState.Installing -> "Installation en cours…"
            is UpdateState.Error -> updateErrorMessage(state.error, appName)
        }
    Text(
        status,
        style = MaterialTheme.typography.labelSmall,
        color =
            if (state is UpdateState.Error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
    )
    if (state is UpdateState.Downloading) {
        LinearProgressIndicator(
            progress = { (state.progress.percentage ?: 0) / 100f },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            is UpdateState.UpdateAvailable ->
                Button(onClick = { onInstall(state.update) }) { Text("Télécharger et installer") }
            is UpdateState.Downloaded ->
                Button(onClick = { onInstallDownloaded(state.file) }) { Text("Installer") }
            else -> Unit
        }
        if (state is UpdateState.UpdateAvailable || state is UpdateState.Downloaded) Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onCheck, enabled = !busy) { Text("Rechercher une mise à jour") }
    }
}

/** Sélecteur « Système / Clair / Sombre » de l'apparence de l'app, en boutons segmentés. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeSelector(
    selected: ThemeMode,
    onSelected: (ThemeMode) -> Unit,
) {
    val options =
        listOf(
            ThemeMode.SYSTEM to "Système",
            ThemeMode.LIGHT to "Clair",
            ThemeMode.DARK to "Sombre",
        )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelected(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label)
            }
        }
    }
}

private fun defaultExportFileName(prefix: String): String {
    val date = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(Instant.now().atZone(ZoneId.systemDefault()))
    return "$prefix-$date.json"
}
