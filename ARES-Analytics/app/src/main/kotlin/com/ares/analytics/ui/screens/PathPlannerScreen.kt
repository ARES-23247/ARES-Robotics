package com.ares.analytics.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.components.pathplanner.FieldCanvas
import com.ares.analytics.ui.components.pathplanner.Waypoint
import com.ares.analytics.ui.components.routine.RoutineBuilderPane
import com.ares.analytics.ui.components.routine.RoutineBuilderResponsiveBody
import com.ares.analytics.ui.components.routine.RoutineEditorPanel
import com.ares.analytics.ui.components.routine.routineBuilderLayoutPresentation
import com.ares.analytics.ui.components.core.chooseProjectDirectory
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresThemeSettings
import com.ares.analytics.viewmodel.AutonomousTourTarget
import com.ares.analytics.viewmodel.PathPlannerIntent
import com.ares.analytics.viewmodel.PathPlannerViewModel
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.ares.analytics.viewmodel.routine.routineDriveStepsInExecutionOrder

/**
 * Unified, offline-first routine builder.
 *
 * Drive geometry is embedded directly in the routine. Autonomous start metadata appears only when
 * a student explicitly makes the routine a match choice; the same document can otherwise be called
 * from controller bindings or another routine.
 */
@Composable
fun PathPlannerScreen(
    viewModel: PathPlannerViewModel,
    league: League,
    projectPath: String? = null,
    robotDimensions: RobotDimensions = RobotDimensions.defaultFor(league),
    onProjectPathChanged: (String) -> Unit = {},
    onRobotDimensionsChanged: (RobotDimensions) -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    var pendingProjectPath by remember { mutableStateOf<String?>(null) }
    var selectedPane by remember { mutableStateOf(RoutineBuilderPane.ROUTINE) }

    pendingProjectPath?.let { selectedPath ->
        AlertDialog(
            onDismissRequest = { pendingProjectPath = null },
            title = { Text("Discard unsaved routine changes?") },
            text = {
                Text(
                    "Changing the project folder replaces the visible routine draft. Save it first, " +
                        "or explicitly discard it before switching."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingProjectPath = null
                        onProjectPathChanged(selectedPath)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AresError, contentColor = AresOnAccent),
                ) { Text("Discard and change folder") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingProjectPath = null }) { Text("Keep editing") }
            },
        )
    }

    LaunchedEffect(projectPath, league) {
        viewModel.onIntent(PathPlannerIntent.RefreshProject(projectPath, league))
    }
    LaunchedEffect(league, robotDimensions) {
        viewModel.onIntent(PathPlannerIntent.ConfigureField(league, robotDimensions))
    }

    val routineWaypoints = remember(state.routine, state.autonomousEntry, state.routinePreviewWarning) {
        if (state.routinePreviewWarning != null) return@remember emptyList()
        buildList {
            state.autonomousEntry?.startingPose?.let { start ->
                add(
                    Waypoint(
                        x = start.xMeters,
                        y = start.yMeters,
                        headingRad = start.headingRadians,
                        rotationDeg = Math.toDegrees(start.headingRadians)
                    )
                )
            }
            state.routine.steps.routineDriveStepsInExecutionOrder().forEach { drive ->
                val target = drive.target
                add(
                    Waypoint(
                        x = target.xMeters,
                        y = target.yMeters,
                        headingRad = target.headingRadians,
                        rotationDeg = Math.toDegrees(target.headingRadians)
                    )
                )
            }
        }
    }
    val previewPath = remember(state.trajectory) {
        state.trajectory?.states?.map { Waypoint(it.x, it.y, it.headingRad) }.orEmpty()
    }
    val playbackPose = remember(state.trajectory, state.playbackTime) {
        val trajectory = state.trajectory
        if (trajectory == null || trajectory.states.isEmpty()) {
            null
        } else {
            val sample = trajectory.states.firstOrNull { it.timeSeconds >= state.playbackTime }
                ?: trajectory.states.last()
            Waypoint(sample.x, sample.y, sample.headingRad)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val presentation = routineBuilderLayoutPresentation(maxWidth.value, AresThemeSettings.largeTextMode)
        val highlightEditor = state.tourStep?.target == AutonomousTourTarget.EDITOR
        val highlightCanvas = state.tourStep?.target == AutonomousTourTarget.CANVAS

        LaunchedEffect(presentation.useTabbedBody, state.tourStep?.target) {
            if (presentation.useTabbedBody) {
                selectedPane = when (state.tourStep?.target) {
                    AutonomousTourTarget.CANVAS -> RoutineBuilderPane.FIELD_PREVIEW
                    else -> RoutineBuilderPane.ROUTINE
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoutineBuilderHeader(
                projectPath = projectPath,
                league = league,
                stackActions = presentation.stackHeaderActions,
                onStartTour = { viewModel.onIntent(PathPlannerIntent.StartGuidedTour) },
                onChangeProject = {
                    chooseProjectDirectory(projectPath)?.let { selected ->
                        if (state.routineDirty) pendingProjectPath = selected.path
                        else onProjectPathChanged(selected.path)
                    }
                },
            )

            RoutineBuilderResponsiveBody(
                presentation = presentation,
                selectedPane = selectedPane,
                onPaneSelected = { selectedPane = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                editor = { paneModifier ->
                    Box(
                        modifier = paneModifier.border(
                            width = if (highlightEditor) 2.dp else 0.dp,
                            color = if (highlightEditor) AresCyan else androidx.compose.ui.graphics.Color.Transparent,
                            shape = RoundedCornerShape(12.dp),
                        ),
                    ) {
                        RoutineEditorPanel(
                            state = state,
                            projectPath = projectPath,
                            league = state.activeLeague,
                            onRobotDimensionsChanged = { dimensions ->
                                viewModel.onIntent(PathPlannerIntent.UpdateCanonicalRobotDimensions(projectPath, dimensions))
                                onRobotDimensionsChanged(dimensions)
                            },
                            onIntent = viewModel::onIntent,
                        )
                    }
                },
                fieldPreview = { paneModifier ->
                    Box(
                        modifier = paneModifier
                            .border(
                                width = if (highlightCanvas) 2.dp else 1.dp,
                                color = if (highlightCanvas) AresCyan else AresBorder,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .clip(RoundedCornerShape(12.dp)),
                    ) {
                        FieldCanvas(
                            league = state.activeLeague,
                            waypoints = routineWaypoints,
                            actualPath = previewPath,
                            contextPath = null,
                            contextWaypoints = null,
                            onWaypointsChanged = {
                                viewModel.onIntent(PathPlannerIntent.UpdateRoutineFieldWaypoints(it, state.activeLeague))
                            },
                            projectPath = projectPath,
                            showPathControls = false,
                            showObstacleControls = false,
                            playbackPose = playbackPose,
                            aprilTags = null,
                            onAprilTagsChanged = null,
                            initialViewRotation = state.viewRotation,
                            onViewRotationChanged = {
                                viewModel.onIntent(PathPlannerIntent.UpdateViewRotation(it))
                            },
                            autoGoalMode = true,
                            robotDimensions = state.robotDimensions,
                            showToolbar = false,
                        )
                    }
                },
            )
        }

        // Floating guided tour overlay for novices
        com.ares.analytics.ui.components.routine.FirstAutonomousTourOverlay(
            currentStep = state.tourStep,
            onIntent = viewModel::onIntent
        )
    }
}

@Composable
internal fun RoutineBuilderHeader(
    projectPath: String?,
    league: League,
    stackActions: Boolean,
    onStartTour: () -> Unit,
    onChangeProject: () -> Unit,
) {
    val title: @Composable () -> Unit = {
        Column {
            Text(
                "Routine Builder",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = AresTextPrimary,
            )
            Text(
                "Build autonomous routines, controller macros, and reusable robot behaviors in one place.",
                style = MaterialTheme.typography.bodySmall,
                color = AresTextSecondary,
            )
        }
    }
    val actions: @Composable (Modifier) -> Unit = { modifier ->
        Row(
            modifier = modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onStartTour,
                border = androidx.compose.foundation.BorderStroke(1.dp, AresCyan.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AresCyan),
            ) {
                androidx.compose.material3.Icon(
                    Icons.Default.School,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("New to Auto? Take Tour", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }

            Surface(
                color = AresSurfaceElevated,
                shape = RoundedCornerShape(999.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AresBorder),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (projectPath == null) "Select a project" else "Offline project catalog · ${league.name}",
                        modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (projectPath == null) AresTextSecondary else AresCyan,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    androidx.compose.material3.TextButton(onClick = onChangeProject) {
                        Text("Change folder", maxLines = 1)
                    }
                }
            }
        }
    }

    if (stackActions) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            title()
            actions(Modifier.fillMaxWidth())
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { title() }
            actions(Modifier)
        }
    }
}
