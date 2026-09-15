package com.ares.analytics.ui.components.routine

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

@Composable
internal fun RoutineDecimalEditor(value: Double, label: String, suffix: String, modifier: Modifier = Modifier, onChanged: (Double) -> Unit) {
    var text by remember { mutableStateOf(formatRoutineNumber(value)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) text = formatRoutineNumber(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { updated -> text = updated; updated.toDoubleOrNull()?.takeIf(Double::isFinite)?.let(onChanged) },
        label = { Text(label) }, suffix = { Text(suffix) }, singleLine = true,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        colors = routineTextFieldColors()
    )
}
