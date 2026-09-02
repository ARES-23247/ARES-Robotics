package com.ares.analytics.ui.screens

import javax.swing.SwingUtilities
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.*
import com.ares.analytics.shared.models.*
import com.ares.analytics.ui.screens.fieldeditor.AprilTagRow
import com.ares.analytics.ui.screens.fieldeditor.FieldEditorCommandBar
import com.ares.analytics.ui.screens.fieldeditor.FieldLayoutCanvas
import com.ares.analytics.ui.screens.fieldeditor.FieldPrefabPalette
import com.ares.analytics.ui.screens.fieldeditor.FieldValidationPanel
import com.ares.analytics.ui.screens.fieldeditor.GamePieceRow
import com.ares.analytics.ui.screens.fieldeditor.ObstacleRow
import com.ares.analytics.ui.screens.fieldeditor.FieldWaypointRow
import com.ares.analytics.ui.screens.fieldeditor.FieldEditorTransforms
import com.ares.analytics.ui.screens.fieldeditor.FieldImageSettingsSection
import com.ares.analytics.ui.components.pathplanner.GamePieceCatalogDialog
import com.ares.analytics.ui.util.DesktopFileChoosers
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.FieldEditorIntent
import com.ares.analytics.viewmodel.FieldEditorViewModel
import com.ares.analytics.viewmodel.AprilTagExportFormat
import com.ares.analytics.viewmodel.field.FieldEditorLayout
import com.ares.analytics.viewmodel.field.AprilTagMapPresetCatalog

/**
 * Visual field layout and obstacle configuration editor screen for FTC ($3.6576 \times 3.6576\text{ m}$) and FRC ($16.541 \times 8.211\text{ m}$) game fields.
 *
 * Configures 3D AprilTag placements, obstacle polygons, game piece initial coordinates, and target field waypoints.
 * Renders interactive canvas preview synchronized with JSON layout file import/export.
 *
 * @param viewModel State management view model [FieldEditorViewModel].
 * @param league The current competition [League].
 * @param projectPath The root directory path for saving/loading configuration files.
 *
 * @see com.ares.analytics.viewmodel.FieldEditorViewModel
 * @see com.ares.analytics.ui.screens.fieldeditor.FieldLayoutCanvas
 */
@Composable
fun FieldEditorScreen(
    viewModel: FieldEditorViewModel,
    league: League,
    projectPath: String? = null
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(projectPath, league) {
        viewModel.onIntent(FieldEditorIntent.LoadConfig(projectPath, league))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    var obstaclesCollapsed by remember { mutableStateOf(false) }
    var gamePiecesCollapsed by remember { mutableStateOf(false) }
    var showGamePieceCatalog by remember { mutableStateOf(false) }
    var aprilTagsCollapsed by remember { mutableStateOf(false) }
    var seasonMapMenuExpanded by remember { mutableStateOf(false) }
    var waypointsCollapsed by remember { mutableStateOf(false) }

    if (showGamePieceCatalog) {
        GamePieceCatalogDialog(
            gamePieceTypes = state.gamePieceTypes,
            onTypesChanged = { viewModel.onIntent(FieldEditorIntent.SetGamePieceTypes(it)) },
            onDismiss = { showGamePieceCatalog = false },
        )
    }
    val fieldWidthM = if (state.fieldImageConfig.widthMeters > 0.0) state.fieldImageConfig.widthMeters else (if (league == League.FTC) 3.65 else 16.5)
    val fieldHeightM = if (state.fieldImageConfig.heightMeters > 0.0) state.fieldImageConfig.heightMeters else (if (league == League.FTC) 3.65 else 8.2)

    Row(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val command = event.isCtrlPressed || event.isMetaPressed
                val spacing = state.gridSpacingMeters * if (event.isShiftPressed) 10.0 else 1.0
                when {
                    command && event.key == Key.Z && event.isShiftPressed -> viewModel.onIntent(FieldEditorIntent.Redo)
                    command && event.key == Key.Z -> viewModel.onIntent(FieldEditorIntent.Undo)
                    command && event.key == Key.Y -> viewModel.onIntent(FieldEditorIntent.Redo)
                    command && event.key == Key.C -> viewModel.onIntent(FieldEditorIntent.CopySelection)
                    command && event.key == Key.V -> viewModel.onIntent(FieldEditorIntent.PasteSelection)
                    command && event.key == Key.D -> viewModel.onIntent(FieldEditorIntent.DuplicateSelection)
                    command && event.key == Key.A -> viewModel.onIntent(FieldEditorIntent.SelectAll)
                    command && event.key == Key.S -> viewModel.onIntent(FieldEditorIntent.SaveDocument)
                    event.key == Key.Delete || event.key == Key.Backspace -> viewModel.onIntent(FieldEditorIntent.DeleteSelection)
                    event.key == Key.DirectionLeft -> viewModel.onIntent(FieldEditorIntent.NudgeSelection(-spacing, 0.0))
                    event.key == Key.DirectionRight -> viewModel.onIntent(FieldEditorIntent.NudgeSelection(spacing, 0.0))
                    event.key == Key.DirectionUp -> viewModel.onIntent(FieldEditorIntent.NudgeSelection(0.0, spacing))
                    event.key == Key.DirectionDown -> viewModel.onIntent(FieldEditorIntent.NudgeSelection(0.0, -spacing))
                    else -> return@onKeyEvent false
                }
                true
            },
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Left side controls
        Surface(
            modifier = Modifier.width(320.dp).fillMaxHeight().border(1.dp, AresBorder, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            color = AresSurface
        ) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                FieldImageSettingsSection(
                    config = state.fieldImageConfig,
                    league = league,
                    projectPath = projectPath,
                    onUpdateConfig = { viewModel.onIntent(FieldEditorIntent.UpdateFieldImageConfig(it, projectPath, league)) },
                    onClearImage = { viewModel.onIntent(FieldEditorIntent.ClearFieldImage) },
                    onUploadImage = { viewModel.onIntent(FieldEditorIntent.ImportFieldImage(it, projectPath, league)) },
                )

                HorizontalDivider(color = AresBorder)

                Button(
                    onClick = {
                        viewModel.onIntent(FieldEditorIntent.SaveDocument)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Configuration", color = AresBackground, fontWeight = FontWeight.Bold)
                }

                if (state.saveStatus.isNotEmpty()) {
                    Text(
                        text = state.saveStatus,
                        color = if (state.saveStatus.contains("failed") || state.saveStatus.contains("error")) AresError else AresGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    LaunchedEffect(state.saveStatus) {
                        kotlinx.coroutines.delay(3000)
                        viewModel.onIntent(FieldEditorIntent.ClearSaveStatus)
                    }
                }

                HorizontalDivider(color = AresBorder)
                FieldPrefabPalette(
                    league = league,
                    onAddPrefab = { viewModel.onIntent(FieldEditorIntent.AddPrefab(it)) }
                )

                HorizontalDivider(color = AresBorder)
                FieldValidationPanel(
                    issues = state.validationIssues,
                    onSelectIssue = { viewModel.onIntent(FieldEditorIntent.SelectElements(it)) }
                )

                // Drawn Obstacles Section
                if (state.obstacles.isNotEmpty()) {
                    HorizontalDivider(color = AresBorder)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = state.selectedElement == null) { obstaclesCollapsed = !obstaclesCollapsed }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (state.selectedElement == null) {
                                Icon(
                                    imageVector = if (obstaclesCollapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = AresTextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = if (state.selectedElement != null) "Selected Item Properties" else "Drawn Obstacles (${state.obstacles.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = AresTextPrimary
                            )
                        }
                        if (state.selectedElement != null) {
                            TextButton(
                                onClick = { viewModel.onIntent(FieldEditorIntent.SelectElement(null)) },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text("Clear Selection", fontSize = 10.sp, color = AresCyan)
                            }
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = {
                                        val copies = state.obstacles.map { obs ->
                                            val mirrored = FieldEditorTransforms.mirrorObstacleX(obs, fieldWidthM, league)
                                            when (mirrored) {
                                                is Obstacle.Circle -> mirrored.copy(id = "circle_${System.currentTimeMillis()}_${obs.id.hashCode()}", name = "${obs.name} Mirrored X")
                                                is Obstacle.Rectangle -> mirrored.copy(id = "rect_${System.currentTimeMillis()}_${obs.id.hashCode()}", name = "${obs.name} Mirrored X")
                                                is Obstacle.Polygon -> mirrored.copy(id = "poly_${System.currentTimeMillis()}_${obs.id.hashCode()}", name = "${obs.name} Mirrored X")
                                            }
                                        }
                                        viewModel.onIntent(FieldEditorIntent.SetObstacles(state.obstacles + copies))
                                    },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(12.dp), tint = AresCyan)
                                    Spacer(Modifier.width(2.dp))
                                    Text("Copy X", fontSize = 10.sp, color = AresCyan)
                                }
                                TextButton(
                                    onClick = {
                                        val copies = state.obstacles.map { obs ->
                                            val mirrored = FieldEditorTransforms.mirrorObstacleY(obs, fieldHeightM, league)
                                            when (mirrored) {
                                                is Obstacle.Circle -> mirrored.copy(id = "circle_${System.currentTimeMillis()}_${obs.id.hashCode()}", name = "${obs.name} Mirrored Y")
                                                is Obstacle.Rectangle -> mirrored.copy(id = "rect_${System.currentTimeMillis()}_${obs.id.hashCode()}", name = "${obs.name} Mirrored Y")
                                                is Obstacle.Polygon -> mirrored.copy(id = "poly_${System.currentTimeMillis()}_${obs.id.hashCode()}", name = "${obs.name} Mirrored Y")
                                            }
                                        }
                                        viewModel.onIntent(FieldEditorIntent.SetObstacles(state.obstacles + copies))
                                    },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(12.dp), tint = AresCyan)
                                    Spacer(Modifier.width(2.dp))
                                    Text("Copy Y", fontSize = 10.sp, color = AresCyan)
                                }
                            }
                        }
                    }

                    if (!obstaclesCollapsed || state.selectedElement != null) {
                        state.obstacles.forEachIndexed { index, obs ->
                            if (state.selectedElement == null || state.selectedElement == obs.id) {
                                key(obs.id) {
                                    ObstacleRow(
                                        index = index,
                                        obs = obs,
                                        fieldWidthM = fieldWidthM,
                                        fieldHeightM = fieldHeightM,
                                        league = league,
                                        measurementUnit = state.measurementUnit,
                                        onUpdate = { i, updated ->
                                            viewModel.onIntent(FieldEditorIntent.UpdateObstacle(i, updated))
                                        },
                                        onDelete = { i ->
                                            viewModel.onIntent(FieldEditorIntent.DeleteObstacle(i))
                                        },
                                        onAdd = { copy ->
                                            viewModel.onIntent(FieldEditorIntent.AddObstacle(copy))
                                        },
                                        onMirrorX = FieldEditorTransforms::mirrorObstacleX,
                                        onMirrorY = FieldEditorTransforms::mirrorObstacleY
                                    )
                                }
                            }
                        }
                    }
                }

                // Canonical game-piece physics catalog
                HorizontalDivider(color = AresBorder)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Game-piece catalog", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AresTextPrimary)
                        Text(
                            "${state.gamePieceTypes.size} persisted type${if (state.gamePieceTypes.size == 1) "" else "s"} · dimensions, mass, friction, bounce, and color",
                            style = MaterialTheme.typography.labelSmall,
                            color = AresTextSecondary,
                        )
                    }
                    OutlinedButton(onClick = { showGamePieceCatalog = true }) {
                        Icon(Icons.Default.Inventory2, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Edit catalog")
                    }
                }

                // Placed Game Pieces Section
                if (state.gamePieces.isNotEmpty()) {
                    HorizontalDivider(color = AresBorder)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { gamePiecesCollapsed = !gamePiecesCollapsed }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (gamePiecesCollapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = AresTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Placed Game Pieces (${state.gamePieces.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = AresTextPrimary
                        )
                    }
                    if (!gamePiecesCollapsed) {
                        state.gamePieces.forEachIndexed { index, gp ->
                            key(gp.id) {
                                GamePieceRow(
                                    index = index,
                                    gp = gp,
                                    gamePieceTypes = state.gamePieceTypes,
                                    measurementUnit = state.measurementUnit,
                                    onUpdate = { i, updated ->
                                        viewModel.onIntent(FieldEditorIntent.UpdateGamePiece(i, updated))
                                    },
                                    onDelete = { i ->
                                        viewModel.onIntent(FieldEditorIntent.DeleteGamePiece(i))
                                    }
                                )
                            }
                        }
                    }
                }

                // AprilTags Section
                HorizontalDivider(color = AresBorder)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .clickable { aprilTagsCollapsed = !aprilTagsCollapsed }
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
                            .clickable { waypointsCollapsed = !waypointsCollapsed }
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
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FieldEditorCommandBar(
                selectionCount = state.selectedElementIds.size,
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                clipboardCount = state.clipboardCount,
                snapEnabled = state.snapEnabled,
                gridSpacingMeters = state.gridSpacingMeters,
                unit = state.measurementUnit,
                simulatorStatus = state.simulatorStatus,
                onUndo = { viewModel.onIntent(FieldEditorIntent.Undo) },
                onRedo = { viewModel.onIntent(FieldEditorIntent.Redo) },
                onCopy = { viewModel.onIntent(FieldEditorIntent.CopySelection) },
                onPaste = { viewModel.onIntent(FieldEditorIntent.PasteSelection) },
                onDuplicate = { viewModel.onIntent(FieldEditorIntent.DuplicateSelection) },
                onDelete = { viewModel.onIntent(FieldEditorIntent.DeleteSelection) },
                onSelectAll = { viewModel.onIntent(FieldEditorIntent.SelectAll) },
                onSnapChanged = { viewModel.onIntent(FieldEditorIntent.SetSnapEnabled(it)) },
                onGridSpacingChanged = { viewModel.onIntent(FieldEditorIntent.SetGridSpacing(it)) },
                onUnitChanged = { viewModel.onIntent(FieldEditorIntent.SetMeasurementUnit(it)) },
                onPushToSimulator = { viewModel.onIntent(FieldEditorIntent.PushToSimulator) },
            )
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().border(1.dp, AresBorder, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
            ) {
                FieldLayoutCanvas(
                    league = league,
                    fieldImage = state.fieldImage,
                    fieldImageConfig = state.fieldImageConfig,
                    layout = FieldEditorLayout(
                        obstacles = state.obstacles,
                        gamePieces = state.gamePieces,
                        aprilTags = state.aprilTags,
                        fieldWaypoints = state.fieldWaypoints
                    ),
                    gamePieceTypes = state.gamePieceTypes,
                    selectedIds = state.selectedElementIds,
                    snapEnabled = state.snapEnabled,
                    gridSpacingMeters = state.gridSpacingMeters,
                    validationIssues = state.validationIssues,
                    onSelectionChanged = { ids, additive -> viewModel.onIntent(FieldEditorIntent.SelectElements(ids, additive)) },
                    onLayoutChanged = { viewModel.onIntent(FieldEditorIntent.SetLayout(it)) }
                )
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
