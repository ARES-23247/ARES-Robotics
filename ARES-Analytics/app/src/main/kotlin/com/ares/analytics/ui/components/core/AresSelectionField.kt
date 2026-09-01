package com.ares.analytics.ui.components.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary

enum class AresSelectionFieldLayout {
    STACKED,
    INLINE,
}

/** Common builder selection field with consistent popup ownership and dismissal behavior. */
@Composable
fun AresSelectionField(
    label: String?,
    selected: String,
    choices: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    layout: AresSelectionFieldLayout = AresSelectionFieldLayout.STACKED,
    shape: Shape = ButtonDefaults.outlinedShape,
    menuOffset: DpOffset = DpOffset(0.dp, 0.dp),
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = choices.isNotEmpty(),
            shape = shape,
        ) {
            when (layout) {
                AresSelectionFieldLayout.STACKED -> Column(Modifier.fillMaxWidth()) {
                    label?.let { Text(it, color = AresTextSecondary, fontSize = 9.sp) }
                    Text(selected, maxLines = 1, fontSize = 11.sp)
                }
                AresSelectionFieldLayout.INLINE -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (label.isNullOrBlank()) selected else "$label: $selected",
                        color = AresTextPrimary,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, offset = menuOffset) {
            choices.forEach { (key, display) ->
                DropdownMenuItem(
                    text = { Text(display, fontSize = 11.sp) },
                    onClick = {
                        expanded = false
                        onSelect(key)
                    },
                )
            }
        }
    }
}
