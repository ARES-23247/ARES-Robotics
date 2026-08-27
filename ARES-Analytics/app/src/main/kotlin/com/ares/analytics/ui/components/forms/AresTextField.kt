package com.ares.analytics.ui.components.forms

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import com.ares.analytics.ui.theme.*

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
    trailingIcon: @Composable (() -> Unit)? = null,
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
        trailingIcon = trailingIcon,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AresCyan,
            focusedContainerColor = containerColor,
            unfocusedBorderColor = AresBorder,
            unfocusedContainerColor = containerColor,
            focusedLabelColor = AresCyan,
            disabledTextColor = AresCyan,
            disabledBorderColor = AresBorder,
            disabledLabelColor = AresTextSecondary
        )
    )
}
