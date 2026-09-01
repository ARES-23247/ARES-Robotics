package com.ares.analytics.ui.components.core

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.ares.analytics.ui.theme.AresCyan

/**
 * Shared decimal editor for Studio authoring surfaces.
 *
 * This component owns only text preservation and finite-number parsing. Feature-specific ranges,
 * units, and safety constraints remain in the owning builder or view model.
 */
@Composable
fun AresDoubleField(
    label: String,
    value: Double,
    modifier: Modifier = Modifier,
    labelFontSize: TextUnit = TextUnit.Unspecified,
    unit: String? = null,
    onValueChange: (Double) -> Unit,
) {
    AresNumberField(
        label = label,
        value = value,
        modifier = modifier,
        labelFontSize = labelFontSize,
        unit = unit,
        blankIsValid = false,
        onValueChange = { parsed -> parsed?.let(onValueChange) },
    )
}

/** Decimal editor whose canonical value may be absent when the field is blank. */
@Composable
fun AresNullableDoubleField(
    label: String,
    value: Double?,
    modifier: Modifier = Modifier,
    labelFontSize: TextUnit = TextUnit.Unspecified,
    unit: String? = null,
    onValueChange: (Double?) -> Unit,
) {
    AresNumberField(
        label = label,
        value = value,
        modifier = modifier,
        labelFontSize = labelFontSize,
        unit = unit,
        blankIsValid = true,
        onValueChange = onValueChange,
    )
}

@Composable
private fun AresNumberField(
    label: String,
    value: Double?,
    modifier: Modifier,
    labelFontSize: TextUnit,
    unit: String?,
    blankIsValid: Boolean,
    onValueChange: (Double?) -> Unit,
) {
    var raw by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    val parsed = parseFiniteDoubleInput(raw)
    val invalid = parsed == null && !(blankIsValid && raw.isBlank())

    OutlinedTextField(
        value = raw,
        onValueChange = { next ->
            raw = next
            when {
                blankIsValid && next.isBlank() -> onValueChange(null)
                else -> parseFiniteDoubleInput(next)?.let(onValueChange)
            }
        },
        modifier = modifier,
        label = { Text(label, fontSize = labelFontSize) },
        trailingIcon = unit?.let { suffix ->
            { Text(suffix, color = AresCyan, modifier = Modifier.padding(end = 8.dp)) }
        },
        isError = invalid,
        singleLine = true,
    )
}

internal fun parseFiniteDoubleInput(raw: String): Double? =
    raw.toDoubleOrNull()?.takeIf(Double::isFinite)
