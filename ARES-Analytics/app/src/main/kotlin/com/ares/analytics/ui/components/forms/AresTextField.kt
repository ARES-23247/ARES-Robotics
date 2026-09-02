package com.ares.analytics.ui.components.forms

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import com.ares.analytics.ui.theme.*

/**
 * Standardized single-line or multi-line text input field for ARES-Analytics.
 * Enforces unified theme borders, container backgrounds, and cyan focus state.
 */
@Composable
fun AresTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String? = null,
    placeholder: String? = null,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium.copy(color = AresTextPrimary),
    labelFontSize: TextUnit = TextUnit.Unspecified,
    placeholderFontSize: TextUnit = TextUnit.Unspecified,
    containerColor: Color = AresSurfaceElevated
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label?.let { { Text(it, fontSize = labelFontSize, color = AresTextSecondary) } },
        placeholder = placeholder?.let { { Text(it, fontSize = placeholderFontSize, color = AresTextTertiary) } },
        textStyle = textStyle,
        modifier = modifier,
        singleLine = singleLine,
        readOnly = readOnly,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        maxLines = maxLines,
        minLines = minLines,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AresCyan,
            focusedContainerColor = containerColor,
            unfocusedBorderColor = AresBorder,
            unfocusedContainerColor = containerColor,
            focusedLabelColor = AresCyan,
            disabledTextColor = AresCyan,
            disabledBorderColor = AresBorder,
            disabledLabelColor = AresTextSecondary,
            errorBorderColor = AresError,
            errorLabelColor = AresError,
            errorSupportingTextColor = AresError,
            errorCursorColor = AresError
        )
    )
}
