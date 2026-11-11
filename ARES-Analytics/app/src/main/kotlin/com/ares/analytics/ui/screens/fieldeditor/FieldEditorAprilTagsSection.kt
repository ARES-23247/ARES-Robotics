package com.ares.analytics.ui.screens.fieldeditor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.AprilTagPlacement
import com.ares.analytics.shared.FieldImageConfig
import com.ares.analytics.shared.FTCCoordinateSystem
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.components.forms.AresTextField
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.util.DesktopFileChoosers
import com.ares.analytics.viewmodel.AprilTagExportFormat
import com.ares.analytics.viewmodel.field.AprilTagMapPresetCatalog
import com.ares.analytics.viewmodel.field.FieldMeasurementUnit
import com.ares.analytics.viewmodel.FieldEditorIntent
import com.ares.analytics.viewmodel.FieldEditorState
import com.ares.analytics.viewmodel.FieldEditorViewModel
import java.io.File
import javax.swing.SwingUtilities

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FieldEditorAprilTagsAndWaypointsSection(
    state: FieldEditorState,
    league: League,
    projectPath: String?,
    aprilTagsCollapsed: Boolean,
    onToggleAprilTagsCollapsed: () -> Unit,
    waypointsCollapsed: Boolean,
    onToggleWaypointsCollapsed: () -> Unit,
    viewModel: FieldEditorViewModel,
) {
    var seasonMapMenuExpanded by remember { mutableStateOf(false) }

    // AprilTags Section
    HorizontalDivider(color = AresBorder)
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .clickable { onToggleAprilTagsCollapsed() }
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (aprilTagsCollapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = AresTextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "AprilTags (${state.aprilTags.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = AresTextPrimary
            )
        }

        if (!projectPath.isNullOrEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box {
                    OutlinedButton(
                        onClick = { seasonMapMenuExpanded = true },
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Season maps", fontSize = 11.sp)
                    }
                    DropdownMenu(
                        expanded = seasonMapMenuExpanded,
                        onDismissRequest = { seasonMapMenuExpanded = false },
                    ) {
                        AprilTagMapPresetCatalog.forLeague(league).forEach { preset ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(preset.displayName, color = AresTextPrimary, fontSize = 12.sp)
                                        Text(preset.sourceLabel, color = AresTextSecondary, fontSize = 10.sp)
                                    }
                                },
                                onClick = {
                                    seasonMapMenuExpanded = false
                                    viewModel.onIntent(
                                        FieldEditorIntent.PreviewAprilTagMap(
                                            content = preset.readContent(),
                                            fileName = preset.displayName,
                                            projectPath = projectPath,
                                            league = league,
                                        )
                                    )
                                },
                            )
                        }
                    }
                }
                Button(
                    onClick = {
                        DesktopFileChoosers.chooseOpenFile(
                            dialogTitle = "Import an AprilTag map for review",
                            filterDescription = "AprilTag maps (.fmap, WPILib .json, ARES field .json)",
                            extensions = listOf("fmap", "json"),
                        )?.let { selectedFile ->
                            viewModel.onIntent(
                                FieldEditorIntent.PreviewAprilTagMap(
                                    content = selectedFile.readText(),
                                    fileName = selectedFile.name,
                                    projectPath = projectPath,
                                    league = league,
                                )
                            )
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AresSurfaceElevated)
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(14.dp), tint = AresTextPrimary)
                    Spacer(Modifier.width(4.dp))
                    Text("Import map", fontSize = 11.sp, color = AresTextPrimary)
                }
                TextButton(
                    onClick = {
                        chooseAprilTagExport("apriltags-wpilib.json", "json") { file ->
                            viewModel.onIntent(FieldEditorIntent.ExportAprilTagMap(AprilTagExportFormat.WPILIB_JSON, file))
                        }
                    },
                    modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                ) { Text("WPILib export", fontSize = 11.sp) }
                TextButton(
                    onClick = {
                        chooseAprilTagExport("apriltags.fmap", "fmap") { file ->
                            viewModel.onIntent(FieldEditorIntent.ExportAprilTagMap(AprilTagExportFormat.LIMELIGHT_FMAP, file))
                        }
                    },
                    modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                ) { Text("Limelight export", fontSize = 11.sp) }
            }
            if (league == League.FTC && state.aprilTags.isEmpty()) {
                Text(
                    "Choose a reviewed Season map or import your own before pushing this field to the simulator or generating VisionPortal code.",
                    color = AresGold,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
    state.aprilTagImportPreview?.let { preview ->
        Card(
            colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Review import · ${preview.tags.size} tags · ${preview.format.name.replace('_', ' ')}",
                    color = AresTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(preview.sourceName, color = AresTextSecondary, fontSize = 11.sp)
                preview.fieldLengthMeters?.let { length ->
                    preview.fieldWidthMeters?.let { width ->
                        Text(
                            "Source field: ${"%.4f".format(length)} m × ${"%.4f".format(width)} m",
                            color = AresTextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
                preview.warnings.forEach { warning ->
                    Text("Warning: $warning", color = AresAmber, fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.onIntent(FieldEditorIntent.ApplyAprilTagImport(true)) }) {
                        Text("Replace layout")
                    }
                    OutlinedButton(onClick = { viewModel.onIntent(FieldEditorIntent.ApplyAprilTagImport(false)) }) {
                        Text("Merge new IDs")
                    }
                    TextButton(onClick = { viewModel.onIntent(FieldEditorIntent.DismissAprilTagImport) }) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
    if (!aprilTagsCollapsed && state.aprilTags.isNotEmpty()) {
        state.aprilTags.forEachIndexed { index, at ->
            key(at.id) {
                AprilTagRow(
                    index = index,
                    at = at,
                    measurementUnit = state.measurementUnit,
                    onUpdate = { i, updated ->
                        viewModel.onIntent(FieldEditorIntent.UpdateAprilTag(i, updated))
                    },
                    onDelete = { i ->
                        viewModel.onIntent(FieldEditorIntent.DeleteAprilTag(i))
                    }
                )
            }
        }
    }

    // Placed Waypoints Section
    if (state.fieldWaypoints.isNotEmpty()) {
        HorizontalDivider(color = AresBorder)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleWaypointsCollapsed() }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (waypointsCollapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = AresTextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "Placed Waypoints (${state.fieldWaypoints.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = AresTextPrimary
            )
        }
        if (!waypointsCollapsed) {
            state.fieldWaypoints.forEachIndexed { index, wp ->
                key(wp.id) {
                    FieldWaypointRow(
                        index = index,
                        wp = wp,
                        measurementUnit = state.measurementUnit,
                        onUpdate = { i, updated ->
                            viewModel.onIntent(FieldEditorIntent.UpdateFieldWaypoint(i, updated))
                        },
                        onDelete = { i ->
                            viewModel.onIntent(FieldEditorIntent.DeleteFieldWaypoint(i))
                        }
                    )
                }
            }
        }
    }
}

private fun chooseAprilTagExport(defaultName: String, extension: String, onSelected: (File) -> Unit) {
    SwingUtilities.invokeLater {
        DesktopFileChoosers.chooseSaveFile(
            dialogTitle = "Export reviewed AprilTag map",
            defaultFileName = defaultName,
            filterDescription = "AprilTag map (.$extension)",
            extensions = listOf(extension)
        )?.let(onSelected)
    }
}

/**
 * Control section for uploading, cropping, rotating, and orienting field background images.
 */
