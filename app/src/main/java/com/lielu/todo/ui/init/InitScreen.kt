package com.lielu.todo.ui.init

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lielu.todo.BuildConfig
import com.lielu.todo.R

/**
 * Page d'accueil du modèle : volontairement vide. Une app créée depuis ce dépôt remplace le contenu de cet
 * écran ; la barre du haut (accès aux Paramètres : thème, sauvegardes, mise à jour) est à conserver.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InitScreen(
    viewModel: InitViewModel,
    onSettingsClick: () -> Unit,
) {
    val context = LocalContext.current
    val showWelcome by viewModel.showWelcome.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.onImportRequested(context.contentResolver, uri)
        }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.onMessageShown()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Paramètres")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("INIT", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    if (showWelcome) {
        AlertDialog(
            onDismissRequest = viewModel::onWelcomeDismissed,
            title = { Text("Bienvenue") },
            text = {
                Text(
                    "Si vous réinstallez l'app ou changez d'appareil, vous pouvez restaurer vos données " +
                        "depuis un fichier de sauvegarde. Vous pourrez aussi le faire plus tard dans Paramètres.",
                )
            },
            confirmButton = {
                TextButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                    Text("Restaurer un fichier…")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onWelcomeDismissed) { Text("Plus tard") }
            },
        )
    }
}
