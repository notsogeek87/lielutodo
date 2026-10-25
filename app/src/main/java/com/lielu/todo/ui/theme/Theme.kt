package com.lielu.todo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Palette façon Todoist : rouge corail sur fond neutre, cartes un ton au-dessus du fond. Les couleurs vivent
// ici et nulle part ailleurs : un écran ne code pas de couleur en dur. `BackgroundLight`/`BackgroundDark`
// doivent suivre `window_background` dans res/values*/colors.xml.
private val BrandLight = Color(0xFFDB4C3F)
private val BrandDark = Color(0xFFE5574B)
private val BackgroundLight = Color(0xFFFFFFFF)
private val BackgroundDark = Color(0xFF1E1E1E)

private val LightColors =
    lightColorScheme(
        primary = BrandLight,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFCE4E1),
        onPrimaryContainer = Color(0xFFB03A2E),
        secondaryContainer = Color(0xFFFCE4E1),
        onSecondaryContainer = Color(0xFFB03A2E),
        background = BackgroundLight,
        onBackground = Color(0xFF202020),
        surface = BackgroundLight,
        onSurface = Color(0xFF202020),
        surfaceVariant = Color(0xFFF4F4F4),
        onSurfaceVariant = Color(0xFF666666),
        surfaceContainerLowest = BackgroundLight,
        surfaceContainerLow = Color(0xFFF7F7F7),
        surfaceContainer = Color(0xFFF4F4F4),
        surfaceContainerHigh = Color(0xFFF0F0F0),
        surfaceContainerHighest = Color(0xFFEBEBEB),
        outline = Color(0xFF8A8A8A),
        outlineVariant = Color(0xFFE6E6E6),
    )

private val DarkColors =
    darkColorScheme(
        primary = BrandDark,
        onPrimary = Color.White,
        primaryContainer = Color(0xFF4A2421),
        onPrimaryContainer = Color(0xFFFFB4AB),
        secondaryContainer = Color(0xFF4A2421),
        onSecondaryContainer = Color(0xFFFFB4AB),
        background = BackgroundDark,
        onBackground = Color(0xFFF2F2F2),
        surface = BackgroundDark,
        onSurface = Color(0xFFF2F2F2),
        surfaceVariant = Color(0xFF2B2B2B),
        onSurfaceVariant = Color(0xFFB5B5B5),
        surfaceContainerLowest = Color(0xFF171717),
        surfaceContainerLow = Color(0xFF242424),
        surfaceContainer = Color(0xFF282828),
        surfaceContainerHigh = Color(0xFF303030),
        surfaceContainerHighest = Color(0xFF383838),
        outline = Color(0xFF8C8C8C),
        outlineVariant = Color(0xFF363636),
    )

private val AppShapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(24.dp),
    )

/** Couleurs propres à l'app (priorités, échéances) : les écrans ne codent aucune couleur en dur. */
data class TodoColors(
    val priorityUrgent: Color,
    val priorityHigh: Color,
    val priorityMedium: Color,
    val priorityNone: Color,
    val overdue: Color,
    val today: Color,
    val tomorrow: Color,
    val upcoming: Color,
)

private val LightTodoColors =
    TodoColors(
        priorityUrgent = Color(0xFFD1453B),
        priorityHigh = Color(0xFFEB8909),
        priorityMedium = Color(0xFF246FE0),
        priorityNone = Color(0xFF8A8A8A),
        overdue = Color(0xFFD1453B),
        today = Color(0xFF058527),
        tomorrow = Color(0xFFAD6200),
        upcoming = Color(0xFF692FC2),
    )

private val DarkTodoColors =
    TodoColors(
        priorityUrgent = Color(0xFFFF7066),
        priorityHigh = Color(0xFFFF9A14),
        priorityMedium = Color(0xFF4B9BFF),
        priorityNone = Color(0xFF8C8C8C),
        overdue = Color(0xFFFF7066),
        today = Color(0xFF6ACC7F),
        tomorrow = Color(0xFFFF9A14),
        upcoming = Color(0xFFB69CFF),
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
            shapes = AppShapes,
            content = content,
        )
    }
}

/** Couleur hexadécimale Vikunja (« e8445a », avec ou sans #) ; [fallback] si vide ou invalide. */
fun parseHexColor(
    hex: String,
    fallback: Color,
): Color {
    val clean = hex.trim().removePrefix("#")
    if (clean.length != 6) return fallback
    val value = clean.toLongOrNull(16) ?: return fallback
    return Color(0xFF000000 or value)
}
