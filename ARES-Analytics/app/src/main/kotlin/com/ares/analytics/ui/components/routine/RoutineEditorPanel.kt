package com.ares.analytics.ui.components.routine

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.PathPlannerIntent
import com.ares.analytics.viewmodel.PathPlannerState
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.ares.analytics.viewmodel.routine.GuidedFirstRoutinePlan
import com.ares.analytics.viewmodel.routine.defaultGuidedFirstRoutinePlan
import com.ares.analytics.viewmodel.routine.validateGuidedFirstRoutinePlan
import com.ares.analytics.service.AresGenerationPhase
import com.ares.analytics.service.project.persistence.ProjectRevisionSummary
import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityParameterDescriptor
import com.areslib.catalog.CapabilityParameterType
import com.areslib.catalog.ConditionDescriptor
import com.areslib.routine.*

/** The empty draft created by New is only a launch surface; replacing it loses no student work. */
internal fun shouldConfirmRoutineReplacement(state: PathPlannerState): Boolean =
    state.routineDirty && !(
        state.routine.name == "New Routine" &&
            state.routine.description.isNullOrBlank() &&
            state.routine.steps.isEmpty() &&
            state.autonomousEntry == null &&
            !state.availableInAutonomousSelector &&
            state.routineRevisions.isEmpty()
        )

internal fun routineReferenceIsMissing(selectedKey: String?, selectedDisplayName: String?): Boolean =
    !selectedKey.isNullOrBlank() && selectedDisplayName == null

internal fun routineReferenceLabel(
    selectedKey: String?,
    selectedDisplayName: String?,
    itemsAvailable: Boolean,
    emptyLabel: String,
    placeholder: String,
): String = when {
    routineReferenceIsMissing(selectedKey, selectedDisplayName) -> "Missing: $selectedKey"
    selectedDisplayName != null -> selectedDisplayName
    !itemsAvailable -> emptyLabel
    else -> placeholder
}
/** Primary trigger-neutral routine editor shared by autonomous, teleop macros, and tests. */
@Composable
fun RoutineEditorPanel(
    state: PathPlannerState,
    projectPath: String?,
    league: League,
    onRobotDimensionsChanged: (RobotDimensions) -> Unit,
    onIntent: (PathPlannerIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var openExpanded by remember { mutableStateOf(false) }
    var historyExpanded by remember { mutableStateOf(false) }
    var setupExpanded by remember { mutableStateOf(false) }
    var pendingReplacement by remember { mutableStateOf<PendingRoutineReplacement?>(null) }
    var firstRoutineGuideOpen by remember { mutableStateOf(false) }
    val hasErrors = state.routineValidation.any { it.severity == RoutineValidationSeverity.ERROR }
    val hasPlayablePreview = state.routinePreviewWarning == null &&
        state.trajectory != null && state.estimatedDuration > 0.0
    val generationStatus = when {
        state.generationPhase == AresGenerationPhase.RUNNING -> state.generationMessage ?: "Generating robot code..."
        state.generationPhase == AresGenerationPhase.FAILED -> state.generationMessage ?: "Robot code generation failed"
        state.saveStatus.contains("unsaved", ignoreCase = true) -> state.saveStatus
        state.generationPhase == AresGenerationPhase.SUCCEEDED -> "Robot code generated and ready to build"
        else -> state.saveStatus.takeIf(String::isNotBlank)
    }

    fun applyReplacement(action: PendingRoutineReplacement) {
        when (action) {
            PendingRoutineReplacement.New -> onIntent(PathPlannerIntent.CreateRoutine())
            is PendingRoutineReplacement.Open -> onIntent(PathPlannerIntent.LoadRoutine(projectPath, action.documentId))
            is PendingRoutineReplacement.Guided -> onIntent(PathPlannerIntent.CreateGuidedFirstRoutine(action.plan))
        }
        pendingReplacement = null
    }

    fun requestReplacement(action: PendingRoutineReplacement) {
        if (shouldConfirmRoutineReplacement(state)) pendingReplacement = action else applyReplacement(action)
    }

    pendingReplacement?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingReplacement = null },
            title = { Text("Discard unsaved routine changes?") },
            text = {
                Text(
                    "The visible routine has changes that are not saved in the project. Continue only if you want to replace this draft."
                )
            },
            confirmButton = {
                Button(
                    onClick = { applyReplacement(action) },
                    colors = ButtonDefaults.buttonColors(containerColor = AresError, contentColor = AresOnAccent),
                ) { Text("Discard and continue") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingReplacement = null }) { Text("Keep editing") }
            },
        )
    }

    if (firstRoutineGuideOpen) {
        FirstRoutineGuideDialog(
            league = league,
            dimensions = state.robotDimensions,
            onDismiss = { firstRoutineGuideOpen = false },
            onApply = { plan ->
                firstRoutineGuideOpen = false
                requestReplacement(PendingRoutineReplacement.Guided(plan))
            },
        )
    }

    Box(modifier) {
        Column(
            Modifier.fillMaxSize()
            .background(AresSurface)
            .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
        ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.routine.name,
                    onValueChange = { onIntent(PathPlannerIntent.UpdateRoutineName(it)) },
                    label = { Text("Routine name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = routineTextFieldColors()
                )
                Box {
                    OutlinedButton(onClick = { openExpanded = true }) {
                        Text("Open")
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(openExpanded, { openExpanded = false }) {
                        if (state.availableRoutines.isEmpty()) {
                            DropdownMenuItem({ Text("No saved routines") }, {}, enabled = false)
                        }
                        state.availableRoutines.forEach { saved ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(saved.name)
                                        Text("Revision ${saved.revision}", style = MaterialTheme.typography.labelSmall)
                                    }
                                },
                                onClick = {
                                    requestReplacement(PendingRoutineReplacement.Open(saved.documentId))
                                    openExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { requestReplacement(PendingRoutineReplacement.New) }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New")
                }
                Button(
                    onClick = { onIntent(PathPlannerIntent.SaveAndGenerateRoutine(projectPath, league)) },
                    enabled = projectPath != null && !hasErrors && state.generationPhase != AresGenerationPhase.RUNNING,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent)
                ) {
                    Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (state.generationPhase == AresGenerationPhase.RUNNING) "Generating..." else "Save & Generate")
                }
                Box {
                    TextButton(onClick = { historyExpanded = true }, enabled = state.routineRevisions.isNotEmpty()) {
                        Icon(Icons.Default.History, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("R${state.routine.revision}")
                    }
                    DropdownMenu(historyExpanded, { historyExpanded = false }) {
                        state.routineRevisions.forEach { revision ->
                            DropdownMenuItem(
                                text = { Text("Restore revision ${revision.revision}") },
                                enabled = revision.revision != state.routine.revision,
                                onClick = {
                                    onIntent(PathPlannerIntent.RestoreRoutine(projectPath, revision.contentHash))
                                    historyExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (state.routinePreviewWarning == null) {
                        "${state.routine.steps.size} ${if (state.routine.steps.size == 1) "step" else "steps"}  •  " +
                            "drive preview ${formatRoutineNumber(state.estimatedDuration)} s"
                    } else {
                        "${state.routine.steps.size} ${if (state.routine.steps.size == 1) "step" else "steps"}  •  preview unavailable"
                    },
                    color = AresTextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onIntent(PathPlannerIntent.TogglePlayback) },
                        enabled = hasPlayablePreview,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            if (state.isPlaying) "Pause preview" else "Play preview",
                            tint = if (hasPlayablePreview) AresCyan else AresTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (state.routinePreviewWarning == null) {
                        Text("${formatRoutineNumber(state.playbackTime)} s", color = AresTextSecondary)
                    }
                }
            }
            state.routinePreviewWarning?.let { warning ->
                Text(
                    warning,
                    style = MaterialTheme.typography.labelSmall,
                    color = AresGold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            generationStatus?.let { status ->
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        status.contains("unsaved", ignoreCase = true) -> AresGold
                        state.generationPhase == AresGenerationPhase.FAILED -> AresError
                        state.generationPhase == AresGenerationPhase.SUCCEEDED -> AresGreen
                        else -> statusColor(status)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        HorizontalDivider(color = AresBorder)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.routineValidation.isNotEmpty()) {
                item { RoutineValidationCard(state.routineValidation) }
            }
            if (state.routine.steps.isEmpty()) {
                item {
                    FirstRoutineGuideCard(
                        projectSelected = projectPath != null && !state.projectLoading,
                        onStart = { firstRoutineGuideOpen = true },
                    )
                }
            }
            item {
                RoutineSetupCard(
                    state = state,
                    projectPath = projectPath,
                    league = league,
                    expanded = setupExpanded,
                    onExpandedChanged = { setupExpanded = it },
                    onRobotDimensionsChanged = onRobotDimensionsChanged,
                    onIntent = onIntent
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("ROUTINE STEPS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = AresTextSecondary)
                    Text("${state.routine.steps.size}", style = MaterialTheme.typography.labelSmall, color = AresCyan)
                }
            }
            if (state.routine.steps.isEmpty()) {
                item { EmptyRoutineCard() }
            }
            itemsIndexed(state.routine.steps, key = { _, step -> step.stepId }) { index, step ->
                RoutineStepCard(
                    index = index,
                    step = step,
                    stepCount = state.routine.steps.size,
                    actions = state.routineActions,
                    conditions = state.routineConditions,
                    routines = state.availableRoutines.filter { it.documentId != state.routine.documentId },
                    issues = state.routineValidation.filter { it.path.contains("/${step.stepId}") },
                    onUpdate = { onIntent(PathPlannerIntent.UpdateRoutineStep(step.stepId, it)) },
                    onMove = { onIntent(PathPlannerIntent.MoveRoutineStep(step.stepId, it)) },
                    onRemove = { onIntent(PathPlannerIntent.RemoveRoutineStep(step.stepId)) },
                    onAddChild = { elseBranch, kind ->
                        onIntent(PathPlannerIntent.AddRoutineChild(step.stepId, elseBranch, kind))
                    },
                    onUpdateChild = { childStepId, _, updated ->
                        onIntent(PathPlannerIntent.UpdateRoutineChild(childStepId, updated))
                    },
                    onRemoveChild = { childStepId, _ ->
                        onIntent(PathPlannerIntent.RemoveRoutineChild(childStepId))
                    }
                )
            }
        }

        HorizontalDivider(color = AresBorder)
        AddRoutineStepBar(
            hasActions = state.routineActions.isNotEmpty(),
            hasConditions = state.routineConditions.isNotEmpty(),
            hasOtherRoutines = state.availableRoutines.any { it.documentId != state.routine.documentId },
            onAdd = { onIntent(PathPlannerIntent.AddRoutineStep(it)) }
        )
        }

        if (state.projectLoading) {
            Surface(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                color = AresSurface.copy(alpha = .96f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, AresBorder),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(color = AresCyan)
                        Text("Loading this robot project", fontWeight = FontWeight.Bold, color = AresTextPrimary)
                        Text(
                            "ARES is reading canonical routines, actions, conditions, field dimensions, and autonomous choices before enabling edits.",
                            color = AresTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private sealed interface PendingRoutineReplacement {
    data object New : PendingRoutineReplacement
    data class Open(val documentId: String) : PendingRoutineReplacement
    data class Guided(val plan: GuidedFirstRoutinePlan) : PendingRoutineReplacement
}

@Composable
private fun FirstRoutineGuideCard(
    projectSelected: Boolean,
    onStart: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AresCyan.copy(alpha = .10f)),
        border = BorderStroke(1.dp, AresCyan),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("New to autonomous routines?", fontWeight = FontWeight.Bold, color = AresTextPrimary)
            Text(
                "Build one short simulator-first drive with a starting pose, a field goal, the Safe motion preset, and an explicit review boundary.",
                style = MaterialTheme.typography.bodySmall,
                color = AresTextSecondary,
            )
            Button(
                onClick = onStart,
                enabled = projectSelected,
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) {
                Icon(Icons.Default.School, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Start guided first routine")
            }
            if (!projectSelected) {
                Text(
                    "Choose a robot project first so validation uses the correct league, field, and generated catalog.",
                    color = AresGold,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun FirstRoutineGuideDialog(
    league: League,
    dimensions: RobotDimensions,
    onDismiss: () -> Unit,
    onApply: (GuidedFirstRoutinePlan) -> Unit,
) {
    val initial = remember(league, dimensions) { defaultGuidedFirstRoutinePlan(league, dimensions) }
    var page by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf(initial.name) }
    var startX by remember { mutableStateOf(formatRoutineNumber(initial.startingPose.xMeters)) }
    var startY by remember { mutableStateOf(formatRoutineNumber(initial.startingPose.yMeters)) }
    var startHeading by remember { mutableStateOf(formatRoutineNumber(Math.toDegrees(initial.startingPose.headingRadians))) }
    var targetX by remember { mutableStateOf(formatRoutineNumber(initial.targetPose.xMeters)) }
    var targetY by remember { mutableStateOf(formatRoutineNumber(initial.targetPose.yMeters)) }
    var targetHeading by remember { mutableStateOf(formatRoutineNumber(Math.toDegrees(initial.targetPose.headingRadians))) }
    var alliance by remember { mutableStateOf(initial.authoredAlliance) }
    var mirror by remember { mutableStateOf(initial.mirrorForOppositeAlliance) }
    var previewCommitment by remember { mutableStateOf(false) }

    fun number(text: String): Double? = text.trim().toDoubleOrNull()?.takeIf(Double::isFinite)
    fun headingRadians(text: String): Double? = number(text)?.let { degrees ->
        val wrapped = ((degrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        Math.toRadians(wrapped)
    }
    val parsedPlan = remember(name, startX, startY, startHeading, targetX, targetY, targetHeading, alliance, mirror) {
        val sx = number(startX)
        val sy = number(startY)
        val sh = headingRadians(startHeading)
        val tx = number(targetX)
        val ty = number(targetY)
        val th = headingRadians(targetHeading)
        if (listOf(sx, sy, sh, tx, ty, th).any { it == null }) null else GuidedFirstRoutinePlan(
            name = name,
            startingPose = RoutinePose(sx!!, sy!!, sh!!),
            targetPose = RoutinePose(tx!!, ty!!, th!!),
            authoredAlliance = alliance,
            mirrorForOppositeAlliance = mirror,
        )
    }
    val validationErrors = remember(parsedPlan, league, dimensions) {
        parsedPlan?.let { validateGuidedFirstRoutinePlan(it, league, dimensions) }
            ?: listOf("Use finite numbers for every position and heading field.")
    }
    val pageReady = when (page) {
        0 -> name.isNotBlank() && name.trim().length <= 80
        1 -> validationErrors.isEmpty()
        2 -> previewCommitment
        else -> validationErrors.isEmpty() && previewCommitment
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("First routine guide")
                Text("Step ${page + 1} of 4", style = MaterialTheme.typography.labelMedium, color = AresTextSecondary)
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().widthIn(max = 680.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (page) {
                    0 -> {
                        Text("1 · Name the purpose", fontWeight = FontWeight.Bold)
                        Text(
                            "This guide creates one unsaved match-autonomous draft. It does not generate code or command a robot.",
                            color = AresTextSecondary,
                        )
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Routine name") },
                            supportingText = { Text("Use a name that describes the intended move, not a match result.") },
                            isError = name.isBlank() || name.trim().length > 80,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = routineTextFieldColors(),
                        )
                    }
                    1 -> {
                        Text("2 · Place the robot and goal", fontWeight = FontWeight.Bold)
                        Text(
                            "Coordinates are meters; heading is degrees counter-clockwise. The whole robot footprint must stay inside the ${league.name} field.",
                            color = AresTextSecondary,
                        )
                        GuidedPoseTextEditors("Starting pose", startX, startY, startHeading, { startX = it }, { startY = it }, { startHeading = it })
                        GuidedPoseTextEditors("Drive goal", targetX, targetY, targetHeading, { targetX = it }, { targetY = it }, { targetHeading = it })
                        validationErrors.forEach { error ->
                            Text("Needs attention: $error", color = AresError, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    2 -> {
                        Text("3 · Choose match behavior", fontWeight = FontWeight.Bold)
                        Text(
                            "The guide fixes motion to the conservative Safe preset. That limits the preview; it does not calibrate traction or prove clearance.",
                            color = AresTextSecondary,
                        )
                        RoutineAlliancePicker(alliance) { alliance = it }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = mirror, onCheckedChange = { mirror = it })
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Mirror for the opposite alliance")
                                Text("ARES mirrors the authored starting pose and route at runtime.", style = MaterialTheme.typography.labelSmall, color = AresTextSecondary)
                            }
                        }
                        Row(verticalAlignment = Alignment.Top) {
                            Checkbox(checked = previewCommitment, onCheckedChange = { previewCommitment = it })
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "I will inspect the field preview for boundaries and obstacles before saving. I understand simulation does not prove physical safety.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (!previewCommitment) {
                            Text("Review required: confirm the preview and physical-safety boundary before continuing.", color = AresGold, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    else -> {
                        Text("4 · Review the draft", fontWeight = FontWeight.Bold)
                        Text("Name: ${name.ifBlank { "Not entered" }}")
                        Text("Start: $startX m, $startY m, $startHeading°")
                        Text("Goal: $targetX m, $targetY m, $targetHeading°")
                        Text("Motion: Safe · Authored for ${alliance.name.lowercase()} · Mirror: ${if (mirror) "yes" else "no"}")
                        HorizontalDivider(color = AresBorder)
                        Text(
                            "Apply creates an unsaved .aresroutine draft and autonomous-catalog entry in memory. Review the canvas and validation card; only Save & Generate writes canonical files.",
                            color = AresTextSecondary,
                        )
                        Text(
                            "A generated, simulated routine still requires supervised field-clearance and restrained-hardware validation before physical use.",
                            color = AresGold,
                            fontWeight = FontWeight.SemiBold,
                        )
                        validationErrors.forEach { error ->
                            Text("Needs attention: $error", color = AresError, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (page < 3) page++ else parsedPlan?.let(onApply)
                },
                enabled = pageReady,
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) { Text(if (page < 3) "Continue" else "Apply unsaved draft") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (page > 0) TextButton(onClick = { page-- }) { Text("Back") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun GuidedPoseTextEditors(
    title: String,
    x: String,
    y: String,
    heading: String,
    onX: (String) -> Unit,
    onY: (String) -> Unit,
    onHeading: (String) -> Unit,
) {
    Text(title, fontWeight = FontWeight.SemiBold, color = AresTextPrimary)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GuidedNumberField(x, "X", "m", onX, Modifier.fillMaxWidth())
        GuidedNumberField(y, "Y", "m", onY, Modifier.fillMaxWidth())
        GuidedNumberField(heading, "Heading", "°", onHeading, Modifier.fillMaxWidth())
    }
}

@Composable
private fun GuidedNumberField(
    value: String,
    label: String,
    unit: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
) {
    val valid = value.trim().toDoubleOrNull()?.isFinite() == true
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = { Text(unit) },
        isError = !valid,
        supportingText = if (valid) null else ({ Text("Enter a number") }),
        singleLine = true,
        modifier = modifier,
        colors = routineTextFieldColors(),
    )
}

@Composable
private fun RoutineSetupCard(
    state: PathPlannerState,
    projectPath: String?,
    league: League,
    expanded: Boolean,
    onExpandedChanged: (Boolean) -> Unit,
    onRobotDimensionsChanged: (RobotDimensions) -> Unit,
    onIntent: (PathPlannerIntent) -> Unit
) {
    val entry = state.autonomousEntry
    val dimensions = state.robotDimensions
    val modeLabel = if (state.availableInAutonomousSelector) "Match autonomous" else "Reusable routine"
    val footprintLabel = "${formatRoutineNumber(dimensions.lengthMeters)} × ${formatRoutineNumber(dimensions.widthMeters)} m"

    Card(colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated), border = BorderStroke(1.dp, AresBorder)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Routine setup", fontWeight = FontWeight.Bold, color = AresTextPrimary)
                    Text(
                        "$modeLabel  •  $footprintLabel footprint",
                        style = MaterialTheme.typography.labelSmall,
                        color = AresTextSecondary
                    )
                }
                Text("Match auto", style = MaterialTheme.typography.labelSmall, color = AresTextSecondary)
                Switch(
                    checked = state.availableInAutonomousSelector,
                    onCheckedChange = { checked ->
                        if (checked) onExpandedChanged(true)
                        onIntent(PathPlannerIntent.SetAutonomousAvailability(checked, league))
                    },
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                IconButton(onClick = { onExpandedChanged(!expanded) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        if (expanded) "Hide routine setup" else "Show routine setup"
                    )
                }
            }

            if (expanded) {
                HorizontalDivider(color = AresBorder.copy(alpha = .7f))
                OutlinedTextField(
                    value = state.routine.description.orEmpty(),
                    onValueChange = { onIntent(PathPlannerIntent.UpdateRoutineDescription(it)) },
                    label = { Text("What this routine does (optional)") },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    colors = routineTextFieldColors()
                )

                Text("Robot footprint", fontWeight = FontWeight.SemiBold, color = AresTextPrimary)
                Text(
                    "Used to keep every drive goal safely inside the field.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AresTextSecondary
                )
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                    RoutineDecimalEditor(dimensions.lengthMeters, "Length", "m", Modifier.weight(1f)) {
                        if (it in RobotDimensions.MIN_SIZE_METERS..RobotDimensions.MAX_SIZE_METERS) {
                            onRobotDimensionsChanged(dimensions.copy(lengthMeters = it))
                        }
                    }
                    RoutineDecimalEditor(dimensions.widthMeters, "Width", "m", Modifier.weight(1f)) {
                        if (it in RobotDimensions.MIN_SIZE_METERS..RobotDimensions.MAX_SIZE_METERS) {
                            onRobotDimensionsChanged(dimensions.copy(widthMeters = it))
                        }
                    }
                }

                HorizontalDivider(color = AresBorder.copy(alpha = .7f))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Starting position",
                            color = if (entry != null) AresCyan else AresTextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (entry != null) "Initial field pose for match autonomous"
                            else "No starting position (routine runs from current robot pose)",
                            style = MaterialTheme.typography.labelSmall,
                            color = AresTextSecondary
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (entry != null) "Set" else "None",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (entry != null) AresCyan else AresTextSecondary
                        )
                        Spacer(Modifier.width(6.dp))
                        Switch(
                            checked = entry != null,
                            onCheckedChange = { checked ->
                                onIntent(PathPlannerIntent.SetAutonomousAvailability(checked, league))
                            }
                        )
                    }
                }

                if (entry != null) {
                    Text("Autonomous starting pose & heading", color = AresCyan, style = MaterialTheme.typography.labelMedium)
                    RoutinePoseEditors(entry.startingPose) {
                        onIntent(PathPlannerIntent.UpdateAutonomousEntry(entry.copy(startingPose = it), league))
                    }
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                        RoutineAlliancePicker(entry.authoredAlliance) {
                            onIntent(PathPlannerIntent.UpdateAutonomousEntry(entry.copy(authoredAlliance = it), league))
                        }
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = entry.mirrorForOppositeAlliance,
                                onCheckedChange = {
                                    onIntent(PathPlannerIntent.UpdateAutonomousEntry(entry.copy(mirrorForOppositeAlliance = it), league))
                                }
                            )
                            Text("Mirror alliance", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Text(state.capabilityStatus, style = MaterialTheme.typography.labelSmall, color = AresTextSecondary)
            }
        }
    }
}

@Composable
private fun RoutineAlliancePicker(value: RoutineAlliance, onChange: (RoutineAlliance) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Authored for ${value.name.lowercase()}") }
        DropdownMenu(expanded, { expanded = false }) {
            RoutineAlliance.entries.forEach { alliance ->
                DropdownMenuItem({ Text(alliance.name.lowercase().replaceFirstChar(Char::uppercase)) }, {
                    onChange(alliance)
                    expanded = false
                })
            }
        }
    }
}

@Composable
private fun RoutineValidationCard(issues: List<RoutineValidationIssue>) {
    val hasErrors = issues.any { it.severity == RoutineValidationSeverity.ERROR }
    val accent = if (hasErrors) AresError else AresGold
    Card(colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = .1f)), border = BorderStroke(1.dp, accent)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (hasErrors) "Needs attention" else "Review before deployment", color = accent, fontWeight = FontWeight.Bold)
            issues.take(5).forEach { Text("• ${it.message}", style = MaterialTheme.typography.bodySmall, color = AresTextPrimary) }
            if (issues.size > 5) Text("+ ${issues.size - 5} more", style = MaterialTheme.typography.labelSmall, color = AresTextSecondary)
        }
    }
}

@Composable
private fun EmptyRoutineCard() {
    Card(colors = CardDefaults.cardColors(containerColor = AresCyan.copy(alpha = .08f)), border = BorderStroke(1.dp, AresCyan.copy(alpha = .5f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Add what the robot should do", color = AresCyan, fontWeight = FontWeight.Bold)
            Text("A routine can become an autonomous choice, a controller macro, or a reusable building block later.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RoutineStepCard(
    index: Int,
    step: RoutineStep,
    stepCount: Int,
    actions: List<ActionDescriptor>,
    conditions: List<ConditionDescriptor>,
    routines: List<RoutineDocument>,
    issues: List<RoutineValidationIssue>,
    onUpdate: (RoutineStep) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onAddChild: (Boolean, RoutineStepKind) -> Unit,
    onUpdateChild: (String, Boolean, RoutineStep) -> Unit,
    onRemoveChild: (String, Boolean) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated), border = BorderStroke(1.dp, AresBorder)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(28.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = AresCyan.copy(alpha = .14f),
                    border = BorderStroke(1.dp, AresCyan.copy(alpha = .55f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("${index + 1}", color = AresCyan, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(routineStepTitle(step.kind), fontWeight = FontWeight.Bold, color = AresTextPrimary)
                    Text(routineStepSubtitle(step), style = MaterialTheme.typography.labelSmall, color = AresTextSecondary)
                }
                Row {
                    IconButton({ onMove(-1) }, enabled = index > 0, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, "Move earlier", modifier = Modifier.size(18.dp))
                    }
                    IconButton({ onMove(1) }, enabled = index < stepCount - 1, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, "Move later", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onRemove, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, "Remove", tint = AresError, modifier = Modifier.size(17.dp))
                    }
                }
            }
            val stepErrors = issues.filter { it.severity == RoutineValidationSeverity.ERROR }
            if (stepErrors.isNotEmpty()) {
                Surface(
                    color = AresError.copy(alpha = .08f),
                    border = BorderStroke(1.dp, AresError.copy(alpha = .45f)),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        stepErrors.take(3).forEach { issue ->
                            Text(issue.message, color = AresError, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            when (step.kind) {
                RoutineStepKind.ACTION -> {
                    ActionPicker(actions, step.actionKey) { onUpdate(step.copy(actionKey = it.key, arguments = defaultsFor(it.parameters))) }
                    actions.firstOrNull { it.key == step.actionKey }?.let { descriptor ->
                        Text(descriptor.description, style = MaterialTheme.typography.bodySmall, color = AresTextSecondary)
                        ParameterEditors(descriptor.parameters, step.arguments, issues) { onUpdate(step.copy(arguments = it)) }
                    }
                }
                RoutineStepKind.DRIVE_TO -> step.drive?.let { drive ->
                    RoutinePoseEditors(drive.target) { onUpdate(step.copy(drive = drive.copy(target = it))) }
                    MotionPresetPicker(drive.motionPresetKey) { onUpdate(step.copy(drive = drive.copy(motionPresetKey = it))) }
                    DriveMechanismActionsEditor(drive, actions) { updatedDrive ->
                        onUpdate(step.copy(drive = updatedDrive))
                    }
                }
                RoutineStepKind.WAIT -> RoutineDecimalEditor(step.durationSeconds ?: 0.0, "Duration", "s") {
                    onUpdate(step.copy(durationSeconds = it.coerceAtLeast(0.0)))
                }
                RoutineStepKind.WAIT_UNTIL -> {
                    ConditionPicker(conditions, step.conditionKey) {
                        onUpdate(step.copy(conditionKey = it.key, arguments = defaultsFor(it.parameters)))
                    }
                    RoutineDecimalEditor(step.timeoutSeconds ?: 0.0, "Safety timeout", "s") {
                        onUpdate(step.copy(timeoutSeconds = it.coerceAtLeast(.01)))
                    }
                    conditions.firstOrNull { it.key == step.conditionKey }?.let { descriptor ->
                        ParameterEditors(descriptor.parameters, step.arguments, issues) { onUpdate(step.copy(arguments = it)) }
                    }
                }
                RoutineStepKind.CALL -> RoutinePicker(routines, step.routineId) { onUpdate(step.copy(routineId = it.documentId)) }
                RoutineStepKind.REPEAT -> {
                    RoutineDecimalEditor((step.repeatCount ?: 1).toDouble(), "Repeat", "times") {
                        onUpdate(step.copy(repeatCount = it.toInt().coerceIn(1, 1000)))
                    }
                    ChildLane("Steps to repeat", index, step.children, false, actions, conditions, routines, onAddChild, onUpdateChild, onRemoveChild)
                }
                RoutineStepKind.BRANCH -> {
                    ConditionPicker(conditions, step.conditionKey) {
                        onUpdate(step.copy(conditionKey = it.key, arguments = defaultsFor(it.parameters)))
                    }
                    conditions.firstOrNull { it.key == step.conditionKey }?.let { descriptor ->
                        ParameterEditors(descriptor.parameters, step.arguments, issues) { onUpdate(step.copy(arguments = it)) }
                    }
                    ChildLane("When true", index, step.children, false, actions, conditions, routines, onAddChild, onUpdateChild, onRemoveChild)
                    ChildLane("Otherwise", index, step.elseChildren, true, actions, conditions, routines, onAddChild, onUpdateChild, onRemoveChild)
                }
                RoutineStepKind.DEADLINE -> {
                    Text("This step ends when its deadline finishes.", style = MaterialTheme.typography.bodySmall, color = AresTextSecondary)
                    step.deadline?.let { deadline ->
                        SimpleChildEditor(deadline, actions, conditions, routines, { onUpdate(step.copy(deadline = it)) }, {})
                    }
                    ChildLane("Run alongside deadline", index, step.children, false, actions, conditions, routines, onAddChild, onUpdateChild, onRemoveChild)
                }
                RoutineStepKind.TOGETHER,
                RoutineStepKind.FIRST_TO_FINISH -> ChildLane(
                    if (step.kind == RoutineStepKind.TOGETHER) "Parallel steps" else "Race steps",
                    index,
                    step.children,
                    false,
                    actions,
                    conditions,
                    routines,
                    onAddChild,
                    onUpdateChild,
                    onRemoveChild
                )
            }
        }
    }
}

@Composable
private fun ChildLane(
    label: String,
    parentIndex: Int,
    children: List<RoutineStep>,
    elseBranch: Boolean,
    actions: List<ActionDescriptor>,
    conditions: List<ConditionDescriptor>,
    routines: List<RoutineDocument>,
    onAdd: (Boolean, RoutineStepKind) -> Unit,
    onUpdate: (String, Boolean, RoutineStep) -> Unit,
    onRemove: (String, Boolean) -> Unit
) {
    Text(label, color = AresCyan, fontWeight = FontWeight.SemiBold)
    children.forEach { child ->
        SimpleChildEditor(
            child,
            actions,
            conditions,
            routines,
            { onUpdate(child.stepId, elseBranch, it) },
            { onRemove(child.stepId, elseBranch) }
        )
    }
    CompactStepPicker(
        label = "Add child step",
        unavailableReason = { kind ->
            when {
                kind == RoutineStepKind.ACTION && actions.isEmpty() -> "Declare a project action first"
                kind in setOf(RoutineStepKind.WAIT_UNTIL, RoutineStepKind.BRANCH) && conditions.isEmpty() -> "Declare a project condition first"
                kind == RoutineStepKind.CALL && routines.isEmpty() -> "Save another routine first"
                else -> null
            }
        }
    ) { onAdd(elseBranch, it) }
}

@Composable
private fun SimpleChildEditor(
    step: RoutineStep,
    actions: List<ActionDescriptor>,
    conditions: List<ConditionDescriptor>,
    routines: List<RoutineDocument>,
    onUpdate: (RoutineStep) -> Unit,
    onRemove: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().background(AresBackground.copy(alpha = .45f), RoundedCornerShape(8.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(routineStepTitle(step.kind), style = MaterialTheme.typography.labelLarge)
            IconButton(onRemove, Modifier.size(28.dp)) { Icon(Icons.Default.Delete, "Remove child", tint = AresError, modifier = Modifier.size(16.dp)) }
        }
        when (step.kind) {
            RoutineStepKind.ACTION -> ActionPicker(actions, step.actionKey) { onUpdate(step.copy(actionKey = it.key, arguments = defaultsFor(it.parameters))) }
            RoutineStepKind.WAIT -> RoutineDecimalEditor(step.durationSeconds ?: 0.0, "Duration", "s") { onUpdate(step.copy(durationSeconds = it.coerceAtLeast(0.0))) }
            RoutineStepKind.DRIVE_TO -> step.drive?.let { drive -> RoutinePoseEditors(drive.target) { onUpdate(step.copy(drive = drive.copy(target = it))) } }
            RoutineStepKind.WAIT_UNTIL -> ConditionPicker(conditions, step.conditionKey) { onUpdate(step.copy(conditionKey = it.key, arguments = defaultsFor(it.parameters))) }
            RoutineStepKind.CALL -> RoutinePicker(routines, step.routineId) { onUpdate(step.copy(routineId = it.documentId)) }
            else -> Text("Nested ${routineStepTitle(step.kind).lowercase()} group", style = MaterialTheme.typography.bodySmall, color = AresTextSecondary)
        }
    }
}

@Composable
private fun RoutinePoseEditors(pose: RoutinePose, onChanged: (RoutinePose) -> Unit) {
    val currentHeadingDeg = Math.toDegrees(pose.headingRadians)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            RoutineDecimalEditor(pose.xMeters, "X", "m", Modifier.weight(1f)) { onChanged(pose.copy(xMeters = it)) }
            RoutineDecimalEditor(pose.yMeters, "Y", "m", Modifier.weight(1f)) { onChanged(pose.copy(yMeters = it)) }
            RoutineDecimalEditor(currentHeadingDeg, "Heading", "°", Modifier.weight(1f)) {
                val wrapped = ((it + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
                onChanged(pose.copy(headingRadians = Math.toRadians(wrapped)))
            }
        }
        // Quick heading preset chips (0°, 90°, 180°, -90°)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Heading:", style = MaterialTheme.typography.labelSmall, color = AresTextSecondary, fontSize = 10.sp)
            listOf(
                0.0 to "0° (→)",
                90.0 to "90° (↑)",
                180.0 to "180° (←)",
                -90.0 to "-90° (↓)"
            ).forEach { (deg, label) ->
                val isSelected = Math.abs(((currentHeadingDeg - deg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0) < 1.0
                Surface(
                    color = if (isSelected) AresCyan.copy(alpha = 0.2f) else AresSurfaceElevated,
                    border = BorderStroke(1.dp, if (isSelected) AresCyan else AresBorder),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.clickable {
                        onChanged(pose.copy(headingRadians = Math.toRadians(deg)))
                    }
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) AresCyan else AresTextSecondary,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ParameterEditors(
    descriptors: List<CapabilityParameterDescriptor>,
    arguments: Map<String, String>,
    issues: List<RoutineValidationIssue> = emptyList(),
    onChanged: (Map<String, String>) -> Unit
) {
    descriptors.forEach { descriptor ->
        val value = arguments[descriptor.key] ?: defaultValue(descriptor)
        val fieldError = issues.firstOrNull {
            it.severity == RoutineValidationSeverity.ERROR && it.path.endsWith(".arguments.${descriptor.key}")
        }
        when (descriptor.type) {
            CapabilityParameterType.BOOLEAN -> Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Switch(value == "true", { checked -> onChanged(arguments + (descriptor.key to checked.toString())) })
                    Spacer(Modifier.width(8.dp)); Column { Text(descriptor.displayName); Text(descriptor.description, style = MaterialTheme.typography.labelSmall, color = AresTextSecondary) }
                }
                fieldError?.let { Text(it.message, color = AresError, style = MaterialTheme.typography.labelSmall) }
            }
            CapabilityParameterType.ENUM -> EnumParameterPicker(descriptor, value) { onChanged(arguments + (descriptor.key to it)) }
            CapabilityParameterType.NUMBER,
            CapabilityParameterType.TEXT -> {
                val unit = descriptor.unit
                OutlinedTextField(
                    value = value,
                    onValueChange = { onChanged(arguments + (descriptor.key to it)) },
                    label = { Text(descriptor.displayName) },
                    supportingText = { Text(descriptor.description) },
                    isError = fieldError != null,
                    suffix = if (unit == null) null else ({ Text(unit) }),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = routineTextFieldColors()
                )
            }
        }
    }
}

@Composable
private fun EnumParameterPicker(descriptor: CapabilityParameterDescriptor, value: String, onChanged: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { Text("${descriptor.displayName}: $value"); Text(descriptor.description, style = MaterialTheme.typography.labelSmall) }
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            descriptor.options.forEach { option -> DropdownMenuItem({ Text(option) }, { onChanged(option); expanded = false }) }
        }
    }
}

@Composable
private fun DriveActionList(label: String, keys: List<String>, actions: List<ActionDescriptor>, onChanged: (List<String>) -> Unit) {
    DescriptorPicker(
        selected = null,
        emptyLabel = "No project actions declared",
        placeholder = label,
        items = actions,
        category = ActionDescriptor::category,
        title = ActionDescriptor::displayName,
        description = ActionDescriptor::description
    ) { selected -> if (selected.key !in keys) onChanged(keys + selected.key) }
    keys.forEach { key ->
        val descriptor = actions.firstOrNull { it.key == key }
        val missing = descriptor == null
        Row(Modifier.fillMaxWidth().background(AresBackground.copy(alpha = .4f), RoundedCornerShape(6.dp)).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (missing) {
                Icon(Icons.Default.ErrorOutline, "Missing project action", tint = AresError, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                descriptor?.displayName ?: "Missing action: $key",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (missing) AresError else AresTextPrimary,
            )
            IconButton({ onChanged(keys - key) }, Modifier.size(30.dp)) { Icon(Icons.Default.Delete, "Remove", tint = AresError, modifier = Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun DriveMechanismActionsEditor(
    drive: RoutineDriveStep,
    actions: List<ActionDescriptor>,
    onChanged: (RoutineDriveStep) -> Unit,
) {
    val configuredCount = drive.markers.size + drive.duringActionKeys.size + drive.arrivalActionKeys.size
    var expanded by remember(configuredCount) { mutableStateOf(configuredCount > 0) }
    Surface(
        color = AresBackground.copy(alpha = .30f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, AresBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.PrecisionManufacturing, null, tint = AresCyan, modifier = Modifier.size(17.dp))
                Column(Modifier.weight(1f)) {
                    Text("Mechanism actions", style = MaterialTheme.typography.labelLarge, color = AresTextPrimary)
                    Text(
                        when {
                            configuredCount > 0 -> "$configuredCount configured for this drive"
                            actions.isEmpty() -> "Add a subsystem in Robot Studio to unlock named actions"
                            else -> "Optional progress, during-motion, and arrival actions"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (actions.isEmpty() && configuredCount == 0) AresTextSecondary else AresCyan,
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    if (expanded) "Collapse mechanism actions" else "Expand mechanism actions",
                    tint = AresTextSecondary,
                )
            }
            if (expanded) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (actions.isEmpty() && configuredCount == 0) {
                        Text(
                            "Create and save a mechanism in Robot Studio, then Save & Generate. Return here to select its typed actions—no action IDs need to be typed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AresTextSecondary,
                        )
                    } else {
                        DriveMarkerEditor(drive.markers, actions) {
                            onChanged(drive.copy(markers = it))
                        }
                        DriveActionList("Run while driving", drive.duringActionKeys, actions) {
                            onChanged(drive.copy(duringActionKeys = it))
                        }
                        DriveActionList("Run on arrival", drive.arrivalActionKeys, actions) {
                            onChanged(drive.copy(arrivalActionKeys = it))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveMarkerEditor(
    markers: List<RoutineDriveMarker>,
    actions: List<ActionDescriptor>,
    onChanged: (List<RoutineDriveMarker>) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Progress actions", style = MaterialTheme.typography.labelMedium, color = AresTextPrimary)
        Text(
            "Trigger a named robot action once when this drive reaches a chosen percentage.",
            style = MaterialTheme.typography.labelSmall,
            color = AresTextSecondary,
        )
        DescriptorPicker(
            selected = null,
            emptyLabel = "No project actions declared",
            placeholder = "Add progress action",
            items = actions,
            category = ActionDescriptor::category,
            title = ActionDescriptor::displayName,
            description = ActionDescriptor::description,
        ) { action ->
            onChanged(markers + RoutineDriveMarker(progress = 0.5, actionKey = action.key))
        }
        markers.forEachIndexed { index, marker ->
            val descriptor = actions.firstOrNull { it.key == marker.actionKey }
            Surface(
                color = AresBackground.copy(alpha = .4f),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, if (descriptor == null) AresError else AresBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionPicker(actions, marker.actionKey) { action ->
                        onChanged(markers.mapIndexed { markerIndex, existing ->
                            if (markerIndex == index) existing.copy(actionKey = action.key) else existing
                        })
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RoutineDecimalEditor(
                            value = marker.progress * 100.0,
                            label = "Trigger at",
                            suffix = "%",
                            modifier = Modifier.weight(1f),
                        ) { percentage ->
                            onChanged(markers.mapIndexed { markerIndex, existing ->
                                if (markerIndex == index) {
                                    existing.copy(progress = (percentage / 100.0).coerceIn(0.0, 1.0))
                                } else {
                                    existing
                                }
                            })
                        }
                        IconButton(
                            onClick = { onChanged(markers.filterIndexed { markerIndex, _ -> markerIndex != index }) },
                        ) {
                            Icon(Icons.Default.Delete, "Remove progress action", tint = AresError)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MotionPresetPicker(selected: String, onSelected: (String) -> Unit) {
    val presets = listOf("safe", "balanced", "fast", "adaptive")
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Motion: ${selected.replaceFirstChar(Char::uppercase)}"); Spacer(Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            presets.forEach { preset -> DropdownMenuItem({ Text(preset.replaceFirstChar(Char::uppercase)) }, { onSelected(preset); expanded = false }) }
        }
    }
}

@Composable
private fun AddRoutineStepBar(hasActions: Boolean, hasConditions: Boolean, hasOtherRoutines: Boolean, onAdd: (RoutineStepKind) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            { onAdd(RoutineStepKind.DRIVE_TO) },
            Modifier.weight(1.15f),
            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent)
        ) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add drive goal", fontWeight = FontWeight.Bold) }
        CompactStepPicker(
            label = "Other step",
            modifier = Modifier.weight(.85f),
            unavailableReason = { kind ->
                when {
                    kind == RoutineStepKind.ACTION && !hasActions -> "Declare a project action first"
                    kind in setOf(RoutineStepKind.WAIT_UNTIL, RoutineStepKind.BRANCH) && !hasConditions -> "Declare a project condition first"
                    kind == RoutineStepKind.CALL && !hasOtherRoutines -> "Save another routine first"
                    else -> null
                }
            },
            onAdd = onAdd
        )
    }
}

@Composable
private fun CompactStepPicker(
    label: String,
    modifier: Modifier = Modifier,
    unavailableReason: (RoutineStepKind) -> String? = { null },
    onAdd: (RoutineStepKind) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton({ expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(label); Spacer(Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            RoutineStepKind.entries.forEach { kind ->
                val reason = unavailableReason(kind)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(routineStepTitle(kind))
                            Text(
                                reason ?: routineStepDescription(kind),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (reason == null) AresTextSecondary else AresGold
                            )
                        }
                    },
                    onClick = { onAdd(kind); expanded = false },
                    enabled = reason == null
                )
            }
        }
    }
}

@Composable
private fun RoutineDecimalEditor(value: Double, label: String, suffix: String, modifier: Modifier = Modifier, onChanged: (Double) -> Unit) {
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
