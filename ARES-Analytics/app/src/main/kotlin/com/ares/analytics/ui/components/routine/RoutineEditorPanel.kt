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
internal fun CompactStepPicker(
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
