package com.lielu.todo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Palette neutre du modèle (violet doux). Les couleurs vivent ici et nulle part ailleurs : un écran ne code
// pas de couleur en dur. `BackgroundLight`/`BackgroundDark` doivent suivre `window_background` dans
// res/values*/colors.xml.
private val BrandLight = Color(0xFFC9352B)
private val BrandDark = Color(0xFFFFB4AB)
private val BackgroundLight = Color(0xFFFBF8FF)
private val BackgroundDark = Color(0xFF141218)

private val LightColors =
    lightColorScheme(
        primary = BrandLight,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFDAD5),
        onPrimaryContainer = Color(0xFF410002),
        background = BackgroundLight,
        onBackground = Color(0xFF1D1B20),
        surface = BackgroundLight,
        onSurface = Color(0xFF1D1B20),
        surfaceVariant = Color(0xFFE7E0EB),
        onSurfaceVariant = Color(0xFF49454E),
        outline = Color(0xFF7A757F),
    )

private val DarkColors =
    darkColorScheme(
        primary = BrandDark,
        onPrimary = Color(0xFF690005),
        primaryContainer = Color(0xFF93000A),
        onPrimaryContainer = Color(0xFFFFDAD5),
        background = BackgroundDark,
        onBackground = Color(0xFFE6E0E9),
        surface = BackgroundDark,
        onSurface = Color(0xFFE6E0E9),
        surfaceVariant = Color(0xFF49454E),
        onSurfaceVariant = Color(0xFFCAC4CF),
        outline = Color(0xFF948F99),
    )

/** Couleurs propres à l'app (priorités, retard) : les écrans ne codent aucune couleur en dur. */
data class TodoColors(
    val priorityUrgent: Color,
    val priorityHigh: Color,
    val priorityMedium: Color,
    val priorityNone: Color,
    val overdue: Color,
    val today: Color,
)

private val LightTodoColors =
    TodoColors(
        priorityUrgent = Color(0xFFD1453B),
        priorityHigh = Color(0xFFEB8909),
        priorityMedium = Color(0xFF246FE0),
        priorityNone = Color(0xFF7A757F),
        overdue = Color(0xFFD1453B),
        today = Color(0xFF058527),
    )

private val DarkTodoColors =
    TodoColors(
        priorityUrgent = Color(0xFFFF7066),
        priorityHigh = Color(0xFFFF9A14),
        priorityMedium = Color(0xFF4B9BFF),
        priorityNone = Color(0xFF948F99),
        overdue = Color(0xFFFF7066),
        today = Color(0xFF6ACC7F),
    )

val LocalTodoColors = staticCompositionLocalOf { LightTodoColors }

/** Couleur de la case d'une tâche selon sa priorité Vikunja (0 à 5), comme les p1 à p4 de Todoist. */
fun TodoColors.forPriority(priority: Int): Color =
    when {
        priority >= 4 -> priorityUrgent
        priority == 3 -> priorityHigh
        priority == 2 -> priorityMedium
        else -> priorityNone
    }

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalTodoColors provides if (darkTheme) DarkTodoColors else LightTodoColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}

/** Couleurs proposées pour un projet (hex sans « # », comme Vikunja les stocke). */
val ProjectPalette =
    listOf("db4c3f", "eb8909", "fad000", "7ecc49", "299438", "158fad", "246fe0", "884dff", "b8256f", "808080")
