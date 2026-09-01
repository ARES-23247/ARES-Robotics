package com.ares.analytics.ui.components.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.viewmodel.controls.ControlsEditorState
import com.ares.analytics.viewmodel.controls.ControlsEditorViewModel
import com.ares.analytics.viewmodel.controls.ControlsProblemSeverity
import com.areslib.controls.ControlEvent
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControlTargetKind
import com.areslib.controls.RoutineInvocationPolicy

@Composable
internal fun CapabilityCoverageCard(state: ControlsEditorState, viewModel: ControlsEditorViewModel) {
    val coverage = state.coverage
    var expanded by remember(state.selectedSchemeId) { mutableStateOf(coverage.missingSafetyActions.isNotEmpty()) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("TeleOp capability reachability", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                Text(
                    "${coverage.boundCount} of ${coverage.totalCount} catalog actions have a direct enabled binding in this scheme.",
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                )
            }
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    if (expanded) "Collapse missing capabilities" else "Expand missing capabilities",
                    tint = AresCyan,
                )
            }
        }
        if (coverage.missingSafetyActions.isNotEmpty()) {
            Text(
                "${coverage.missingSafetyActions.size} safety/recovery action${if (coverage.missingSafetyActions.size == 1) " is" else "s are"} not directly reachable.",
                color = AresGold,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            "Choose a controller button first. Bind opens a normal draft for review; it never changes the project automatically.",
            color = AresTextTertiary,
            fontSize = 10.sp,
        )
        if (expanded) {
            val missing = (coverage.missingSafetyActions + coverage.missingActions)
                .distinctBy { it.key }
                .take(6)
            if (missing.isEmpty()) {
                Text("Every TeleOp catalog action is directly reachable in this scheme.", color = AresGreen, fontSize = 11.sp)
            } else {
                missing.forEach { action ->
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(action.displayName, color = AresTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text("${action.category} · ${action.key}", color = AresTextSecondary, fontSize = 9.sp)
                        }
                        OutlinedButton(onClick = { viewModel.createBindingForAction(action.key) }) {
                            Text(
                                state.selectedControl?.let { "Bind to ${it.displayName}" } ?: "Choose button",
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
                if (coverage.missingActions.size > missing.size) {
                    Text(
                        "+ ${coverage.missingActions.size - missing.size} more; use the action picker or search to bind them.",
                        color = AresTextSecondary,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

internal data class BindingLearningTrace(
    val input: String,
    val event: String,
    val target: String,
    val runtimePath: String,
    val hasBlockingProblem: Boolean,
)

/** A structural explanation of the selected canonical binding; this never evaluates an input. */
internal fun bindingLearningTrace(state: ControlsEditorState): BindingLearningTrace? {
    val binding = state.draftBinding ?: state.selectedBindingId?.let { selectedId ->
        state.selectedScheme?.bindings?.firstOrNull { it.bindingId == selectedId }
    } ?: return null
    val sourceController = state.selectedScheme?.controllers
        ?.firstOrNull { it.slot == binding.source.controllerSlot }
    val sourceProfileId = sourceController?.profileId
    val sourceProfile = state.profiles.firstOrNull { it.documentId == sourceProfileId }
    val controls = binding.source.controlIds.map { controlId ->
        val control = sourceProfile?.controls?.firstOrNull { it.controlId == controlId }
        val mapping = control?.mappings?.firstOrNull { it.platform == state.targetPlatform }
        val physicalIndex = mapping?.buttonIndex?.let { "button $it" }
            ?: mapping?.axisIndex?.let { "axis $it" }
            ?: "not mapped"
        "${sourceController?.displayName ?: binding.source.controllerSlot}.${control?.displayName ?: controlId} " +
            "($physicalIndex on ${state.targetPlatform.name})"
    }
    val target = when (binding.target.kind) {
        ControlTargetKind.ACTION -> buildString {
            append(binding.target.key)
            if (binding.target.arguments.isNotEmpty()) {
                append(binding.target.arguments.entries.sortedBy { it.key }.joinToString(", ", "(", ")") { "${it.key}=${it.value}" })
            }
        }
        ControlTargetKind.ROUTINE -> "routine ${binding.target.key} · ${binding.target.routinePolicy.studentLabel()}"
        ControlTargetKind.CANCEL_ROUTINE -> "cancel routine ${binding.target.key}"
        ControlTargetKind.DRIVE -> "drivetrain ${binding.target.key} axis"
    }
    val runtimePath = when (binding.target.kind) {
        ControlTargetKind.ACTION -> "Generated binding runtime → typed action task → Redux → subsystem controller → cached IO"
        ControlTargetKind.ROUTINE -> "Generated binding runtime → routine scheduler → typed tasks/resources → Redux"
        ControlTargetKind.CANCEL_ROUTINE -> "Generated binding runtime → routine scheduler cancellation → owned-resource cleanup"
        ControlTargetKind.DRIVE -> "Generated binding runtime → shaped axis accumulator → drive sink → field-centric drivetrain"
    }
    return BindingLearningTrace(
        input = controls.joinToString(" + "),
        event = "${binding.source.kind.studentLabel()} · ${binding.event.studentLabel()}",
        target = target,
        runtimePath = runtimePath,
        hasBlockingProblem = state.problems.any {
            it.severity == ControlsProblemSeverity.ERROR && (it.bindingId == null || it.bindingId == binding.bindingId)
        },
    )
}

@Composable
internal fun BindingLearningTraceCard(state: ControlsEditorState) {
    val trace = bindingLearningTrace(state) ?: return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Binding runtime trace", color = AresTextPrimary, fontWeight = FontWeight.Bold)
        Text(
            "Structural preview only—it does not read a controller, dispatch an action, run simulation, or command hardware.",
            color = AresGold,
            fontSize = 11.sp,
        )
        TraceLine("INPUTS", trace.input)
        TraceLine("TRIGGER EVENT", trace.event)
        TraceLine("TARGET BEHAVIOR", trace.target)
        TraceLine("RUNTIME PIPELINE", trace.runtimePath)
        if (trace.hasBlockingProblem) {
            Text("Fix errors in the readiness rail before testing this binding.", color = AresError, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TraceLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, color = AresCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = AresTextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

private fun ControlSourceKind.studentLabel(): String = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
private fun ControlEvent.studentLabel(): String = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
private fun RoutineInvocationPolicy.studentLabel(): String = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
