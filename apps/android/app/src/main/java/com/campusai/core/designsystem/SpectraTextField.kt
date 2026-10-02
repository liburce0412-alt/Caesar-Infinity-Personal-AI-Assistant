package com.campusai.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.input.VisualTransformation

/** Shared clear-glass input. Native editing, password masking and IME semantics are preserved. */
@Composable
fun SpectraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = RoundedCornerShape(SpectraTheme.tokens.radii.input),
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val dark = MaterialTheme.colorScheme.background.luminance() < .35f
    val tint by animateColorAsState(
        MaterialTheme.colorScheme.surface.copy(alpha = when {
            !SpectraTheme.tokens.motion.enabled -> 1f
            focused -> if (dark) .94f else .96f
            else -> if (dark) .78f else .82f
        }),
        animationSpec = tween(SpectraTheme.tokens.motion.resolve(160)),
        label = "glass-input-focus",
    )
    OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = modifier,
        enabled = enabled, readOnly = readOnly, label = label, placeholder = placeholder,
        leadingIcon = leadingIcon, trailingIcon = trailingIcon, supportingText = supportingText,
        isError = isError, visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        singleLine = singleLine, maxLines = maxLines, minLines = minLines, shape = shape,
        interactionSource = source,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = tint, unfocusedContainerColor = tint,
            disabledContainerColor = tint, errorContainerColor = tint,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            errorBorderColor = MaterialTheme.colorScheme.error,
            errorLabelColor = MaterialTheme.colorScheme.error,
        ),
    )
}
