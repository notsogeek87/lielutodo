package com.lielu.todo.ui.common

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Champ sans cadre (comme [borderlessFieldColors]) bâti sur [TextFieldState].
 *
 * Les champs « par valeur » (`value` + `onValueChange`) font l'aller-retour clavier -> état -> recomposition à chaque
 * frappe : sur un écran lourd, le clavier et le champ se désynchronisent (mot effacé en acceptant une correction,
 * retour à la ligne en trop). Le [TextFieldState] reçoit les éditions du clavier directement, sans attendre l'écran.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BorderlessStateTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    placeholder: (@Composable () -> Unit)? = null,
    textStyle: TextStyle = LocalTextStyle.current,
    minLines: Int = 1,
) {
    val colors = borderlessFieldColors()
    val interactionSource = remember { MutableInteractionSource() }
    BasicTextField(
        state = state,
        modifier = modifier,
        textStyle = textStyle.merge(TextStyle(color = MaterialTheme.colorScheme.onSurface)),
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = minLines),
        interactionSource = interactionSource,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorator = { innerTextField ->
            TextFieldDefaults.DecorationBox(
                value = state.text.toString(),
                innerTextField = innerTextField,
                enabled = true,
                singleLine = false,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                placeholder = placeholder,
                colors = colors,
            )
        },
    )
}
