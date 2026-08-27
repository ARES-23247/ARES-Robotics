package com.ares.analytics.ui.screens.fieldeditor

import com.ares.analytics.ui.theme.AresOnAccent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.League
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.viewmodel.field.FieldMeasurementUnit
import com.ares.analytics.viewmodel.field.FieldPrefabCatalog
import com.ares.analytics.viewmodel.field.FieldValidationIssue
import com.ares.analytics.viewmodel.field.FieldValidationSeverity

@Composable
fun FieldEditorCommandBar(
    selectionCount: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    clipboardCount: Int,
    snapEnabled: Boolean,
    gridSpacingMeters: Double,
    unit: FieldMeasurementUnit,
    simulatorStatus: String,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onSnapChanged: (Boolean) -> Unit,
    onGridSpacingChanged: (Double) -> Unit,
    onUnitChanged: (FieldMeasurementUnit) -> Unit,
    onPushToSimulator: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AresSurfaceElevated,
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                IconButton(onClick = onCopy, enabled = selectionCount > 0) { Icon(Icons.Default.ContentCopy, "Copy selection") }
                IconButton(onClick = onPaste, enabled = clipboardCount > 0) { Icon(Icons.Default.ContentPaste, "Paste") }
                Button(
                    onClick = onDuplicate,
                    enabled = selectionCount > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = AresSurfaceElevated),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) { Text("Duplicate", fontSize = 11.sp) }
                IconButton(onClick = onDelete, enabled = selectionCount > 0) { Icon(Icons.Default.Delete, "Delete selection") }
                IconButton(onClick = onSelectAll) { Icon(Icons.Default.SelectAll, "Select all") }

                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.GridOn, null, tint = AresTextSecondary, modifier = Modifier.size(18.dp))
                Switch(checked = snapEnabled, onCheckedChange = onSnapChanged)
                Text(
                    "Grid ${formatMeasurement(gridSpacingMeters, unit)}",
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.width(86.dp)
                )
                Slider(
                    value = gridSpacingMeters.toFloat(),
                    onValueChange = { onGridSpacingChanged(it.toDouble()) },
                    valueRange = 0.01f..0.5f,
                    steps = 48,
                    modifier = Modifier.weight(1f)
                )
                FieldMeasurementUnit.entries.forEach { candidate ->
                    AssistChip(
                        onClick = { onUnitChanged(candidate) },
                        label = { Text(candidate.abbreviation, fontSize = 10.sp) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (candidate == unit) AresCyan.copy(alpha = 0.22f) else AresSurfaceElevated
                        )
                    )
                }
                Button(onClick = onPushToSimulator, colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent)) {
                    Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Push to Sim", fontSize = 11.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("$selectionCount selected", color = if (selectionCount > 0) AresCyan else AresTextSecondary, fontSize = 10.sp)
                Text("Ctrl/Cmd-Z undo · Shift-click add · drag empty space for box select · arrows nudge", color = AresTextSecondary, fontSize = 10.sp)
                Text("OpMode lifecycle is controlled from the Dashboard simulator strip", color = AresTextSecondary, fontSize = 10.sp)
                if (simulatorStatus.isNotBlank()) Text(simulatorStatus, color = AresTextSecondary, fontSize = 10.sp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FieldPrefabPalette(league: League, onAddPrefab: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val entries = remember(league, query) {
        FieldPrefabCatalog.forLeague(league).filter {
            query.isBlank() || it.name.contains(query, ignoreCase = true) || it.category.contains(query, ignoreCase = true)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Element Palette", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AresTextPrimary)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search elements") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        entries.groupBy { it.category }.forEach { (category, prefabs) ->
            Text(category, color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                prefabs.forEach { prefab ->
                    AssistChip(onClick = { onAddPrefab(prefab.id) }, label = { Text(prefab.name, fontSize = 10.sp) })
                }
            }
        }
    }
}

@Composable
fun FieldValidationPanel(issues: List<FieldValidationIssue>, onSelectIssue: (Set<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Validation", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AresTextPrimary)
            Text(if (issues.isEmpty()) "No issues" else "${issues.size} issue${if (issues.size == 1) "" else "s"}", color = if (issues.isEmpty()) AresCyan else AresAmber, fontSize = 11.sp)
        }
        issues.take(8).forEach { issue ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = issue.elementIds.isNotEmpty()) { onSelectIssue(issue.elementIds) }.padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(if (issue.severity == FieldValidationSeverity.ERROR) "●" else "▲", color = if (issue.severity == FieldValidationSeverity.ERROR) AresError else AresAmber, fontSize = 10.sp)
                Text(issue.message, color = AresTextSecondary, fontSize = 10.sp, lineHeight = 13.sp)
            }
        }
        if (issues.size > 8) Text("+${issues.size - 8} more", color = AresTextSecondary, fontSize = 10.sp)
        HorizontalDivider(color = AresBorder)
    }
}

private fun formatMeasurement(meters: Double, unit: FieldMeasurementUnit): String =
    "%.2f %s".format(unit.fromMeters(meters), unit.abbreviation)
