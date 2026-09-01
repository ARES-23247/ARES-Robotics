package com.ares.analytics.ui.components.routine

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.ares.analytics.ui.components.catalog.AresActionCatalogPicker
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.PathPlannerIntent
import com.ares.analytics.viewmodel.PathPlannerState
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.ares.analytics.viewmodel.routine.GuidedFirstRoutinePlan
import com.ares.analytics.viewmodel.routine.defaultGuidedFirstRoutinePlan
import com.ares.analytics.viewmodel.routine.validateGuidedFirstRoutinePlan
import com.areslib.catalog.*
import com.areslib.routine.*

@Composable
internal fun RoutineStepCard(
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
                    AresActionCatalogPicker(actions, step.actionKey) {
                        onUpdate(step.copy(actionKey = it.key, arguments = initialCapabilityArguments(it.parameters)))
                    }
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
                        onUpdate(step.copy(conditionKey = it.key, arguments = initialCapabilityArguments(it.parameters)))
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
                        onUpdate(step.copy(conditionKey = it.key, arguments = initialCapabilityArguments(it.parameters)))
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
internal fun ChildLane(
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
internal fun SimpleChildEditor(
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
            RoutineStepKind.ACTION -> AresActionCatalogPicker(actions, step.actionKey) {
                onUpdate(step.copy(actionKey = it.key, arguments = initialCapabilityArguments(it.parameters)))
            }
            RoutineStepKind.WAIT -> RoutineDecimalEditor(step.durationSeconds ?: 0.0, "Duration", "s") { onUpdate(step.copy(durationSeconds = it.coerceAtLeast(0.0))) }
            RoutineStepKind.DRIVE_TO -> step.drive?.let { drive -> RoutinePoseEditors(drive.target) { onUpdate(step.copy(drive = drive.copy(target = it))) } }
            RoutineStepKind.WAIT_UNTIL -> ConditionPicker(conditions, step.conditionKey) { onUpdate(step.copy(conditionKey = it.key, arguments = initialCapabilityArguments(it.parameters))) }
            RoutineStepKind.CALL -> RoutinePicker(routines, step.routineId) { onUpdate(step.copy(routineId = it.documentId)) }
            else -> Text("Nested ${routineStepTitle(step.kind).lowercase()} group", style = MaterialTheme.typography.bodySmall, color = AresTextSecondary)
        }
    }
}
