package com.lielu.todo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.lielu.todo.data.settings.SettingsRepository
import com.lielu.todo.data.settings.ThemeMode

/**
 * Applique le thème choisi dans Paramètres. [ThemeMode.SYSTEM] (valeur initiale tant que le DataStore n'a pas
 * livré la sienne) retombe sur isSystemInDarkTheme() : jamais de flash clair/sombre incorrect.
 */
@Composable
fun ThemedContent(
    settings: SettingsRepository,
    content: @Composable () -> Unit,
) {
    val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val darkTheme =
        when (themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }
    AppTheme(darkTheme = darkTheme, content = content)
}
