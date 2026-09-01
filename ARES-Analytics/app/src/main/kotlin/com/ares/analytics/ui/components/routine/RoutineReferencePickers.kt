package com.ares.analytics.ui.components.routine

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.areslib.catalog.ConditionDescriptor
import com.areslib.routine.RoutineDocument

@Composable
internal fun ConditionPicker(
    conditions: List<ConditionDescriptor>,
    selectedKey: String?,
    onSelected: (ConditionDescriptor) -> Unit,
) = ConditionDescriptorPicker(
    selected = conditions.firstOrNull { it.key == selectedKey }?.displayName,
    missingSelectedKey = selectedKey,
    conditions = conditions,
    onSelected = onSelected,
)

@Composable
private fun ConditionDescriptorPicker(
    selected: String?,
    missingSelectedKey: String?,
    conditions: List<ConditionDescriptor>,
    onSelected: (ConditionDescriptor) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val missing = routineReferenceIsMissing(missingSelectedKey, selected)
    val label = routineReferenceLabel(
        selectedKey = missingSelectedKey,
        selectedDisplayName = selected,
        itemsAvailable = conditions.isNotEmpty(),
        emptyLabel = "No project conditions declared",
        placeholder = "Choose robot state condition",
    )
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = conditions.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (missing) AresError else AresTextPrimary,
                disabledContentColor = if (missing) AresError else AresTextSecondary,
            ),
            border = BorderStroke(1.dp, if (missing) AresError else AresBorder),
        ) {
            if (missing) {
                Icon(Icons.Default.ErrorOutline, "Missing project reference", Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            conditions.groupBy(ConditionDescriptor::category).forEach { (group, descriptors) ->
                DropdownMenuItem(
                    text = { Text(group, color = AresCyan, fontWeight = FontWeight.Bold) },
                    onClick = {},
                    enabled = false,
                )
                descriptors.forEach { item ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(item.displayName)
                                Text(item.description, style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        onClick = { onSelected(item); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
internal fun RoutinePicker(
    routines: List<RoutineDocument>,
    selectedId: String?,
    onSelected: (RoutineDocument) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = routines.firstOrNull { it.documentId == selectedId }
    val missing = routineReferenceIsMissing(selectedId, selected?.name)
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = routines.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (missing) AresError else AresTextPrimary,
                disabledContentColor = if (missing) AresError else AresTextSecondary,
            ),
            border = BorderStroke(1.dp, if (missing) AresError else AresBorder),
        ) {
            if (missing) {
                Icon(Icons.Default.ErrorOutline, "Missing routine reference", Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                routineReferenceLabel(
                    selectedKey = selectedId,
                    selectedDisplayName = selected?.name,
                    itemsAvailable = routines.isNotEmpty(),
                    emptyLabel = "No other routines saved",
                    placeholder = "Choose routine",
                ),
            )
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            routines.forEach { routine ->
                DropdownMenuItem(
                    text = { Text(routine.name) },
                    onClick = { onSelected(routine); expanded = false },
                )
            }
        }
    }
}
