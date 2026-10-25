package com.lielu.todo.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** En-tête de groupe de tâches : titre en gras, compteur discret, filet dessous (comme Todoist). */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    count: Int? = null,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            text = if (count != null) "$title  $count" else title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Grand titre d'un écran de tâches (« Aujourd'hui »), avec un sous-titre « N tâches ». */
@Composable
fun ScreenTitle(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.outline,
        modifier = modifier.fillMaxWidth().padding(32.dp),
    )
}
