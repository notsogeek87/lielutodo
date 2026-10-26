package com.lielu.todo.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lielu.todo.data.todo.TodoRepository
import com.lielu.todo.ui.common.EmptyState
import com.lielu.todo.ui.common.ago
import java.io.IOException

/** Notifications de collaboration (assignations, commentaires, mentions…) : lisibles et marquables hors ligne. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    repository: TodoRepository,
    onOpenTask: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    var offline by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        offline =
            try {
                repository.refreshNotifications()
                false
            } catch (e: IOException) {
                true
            }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    if (state.unreadNotifications > 0) {
                        IconButton(onClick = { repository.markAllNotificationsRead() }) {
                            Icon(Icons.Filled.DoneAll, contentDescription = "Tout marquer comme lu")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (offline) {
                item {
                    Text(
                        "Hors ligne : notifications en cache.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(state.notifications, key = { it.id }) { n ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                repository.markNotificationRead(n.id)
                                if (n.taskId != 0L) onOpenTask(n.taskId)
                            }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            n.text,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (n.read) FontWeight.Normal else FontWeight.Bold,
                        )
                        n.created?.let {
                            Text(
                                ago(System.currentTimeMillis() - it.toEpochMilli()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    if (!n.read) Text("●", color = MaterialTheme.colorScheme.primary)
                }
            }
            if (state.notifications.isEmpty()) item { EmptyState("Aucune notification.") }
        }
    }
}
