package com.ares.analytics.ui.components.subsystems

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.screens.ConceptHelp
import com.ares.analytics.ui.screens.HomingConceptLab
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.viewmodel.SubsystemGeneratorState
import com.ares.analytics.viewmodel.SubsystemGeneratorViewModel
import com.areslib.subsystem.SubsystemContinuousInputDocument
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFeedforwardDocument
import com.areslib.subsystem.SubsystemFeedforwardKind
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemHomingComparison
import com.areslib.subsystem.SubsystemHomingEvidenceDocument
import com.areslib.subsystem.SubsystemHomingMethod
import com.areslib.subsystem.SubsystemMeasurementSource
import com.areslib.subsystem.SubsystemMotionProfileDocument
import com.areslib.subsystem.SubsystemStateFieldDocument
import com.areslib.subsystem.SubsystemValueType

@Composable
fun StateFieldInspectorBody(field: SubsystemStateFieldDocument, viewModel: SubsystemGeneratorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Configure state field role, value type, and default initial value.", color = AresTextSecondary, fontSize = 11.sp)
        TextInput("State value name", field.displayName) { value ->
            viewModel.updateStateField(field.fieldId) { it.copy(displayName = value) }
        }
        TextInput("Description", field.description) { value ->
            viewModel.updateStateField(field.fieldId) { it.copy(description = value) }
        }
        StableIdLabel("Code ID", field.fieldId, "Used by cached inputs, controller rules, and actions.")
        TextInput("Rename code ID (advanced)", field.fieldId) { value ->
            viewModel.renameStateFieldId(field.fieldId, value)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                EnumSelector("Role", field.role, SubsystemFieldRole.entries) { role ->
                    viewModel.updateStateField(field.fieldId) { it.copy(role = role) }
                }
            }
            ConceptHelp(
                "state roles",
                "Targets describe what the robot should do; measurements describe cached hardware observations; status reports derived conditions; configuration stores reviewed constants.",
                "state-roles",
                compact = true,
            )
        }
        EnumSelector("Value Type", field.type, SubsystemValueType.entries) { type ->
            viewModel.changeStateFieldType(field.fieldId, type)
        }
        when (field.type) {
            SubsystemValueType.DOUBLE -> DoubleInput("Default number", field.defaultNumber ?: 0.0) { value ->
                viewModel.updateStateField(field.fieldId) { it.copy(defaultNumber = value) }
            }
            SubsystemValueType.BOOLEAN -> ToggleRow("Default boolean", field.defaultBoolean ?: false) { value ->
                viewModel.updateStateField(field.fieldId) { it.copy(defaultBoolean = value) }
            }
            SubsystemValueType.INT -> IntInput("Default integer", field.defaultInt ?: 0) { value ->
                viewModel.updateStateField(field.fieldId) { it.copy(defaultInt = value) }
            }
            SubsystemValueType.STRING -> TextInput("Default text", field.defaultText.orEmpty()) { value ->
                viewModel.updateStateField(field.fieldId) { it.copy(defaultText = value) }
            }
        }
        if (field.type == SubsystemValueType.DOUBLE || field.type == SubsystemValueType.INT) {
            TextInput("Physical unit (optional)", field.unit.orEmpty()) { value ->
                viewModel.updateStateField(field.fieldId) { it.copy(unit = value.ifBlank { null }) }
            }
            NullableDoubleInput("Minimum bound", field.minimum) { value ->
                viewModel.updateStateField(field.fieldId) { it.copy(minimum = value) }
            }
            NullableDoubleInput("Maximum bound", field.maximum) { value ->
                viewModel.updateStateField(field.fieldId) { it.copy(maximum = value) }
            }
        }
    }
}

@Composable
fun SafetyInspector(state: SubsystemGeneratorState, viewModel: SubsystemGeneratorViewModel) {
    val document = state.draft?.document ?: return
    val safety = document.safety
    var showAdvanced by remember { mutableStateOf(false) }
    var showHomingLab by remember { mutableStateOf(false) }
    val motorIds = document.hardware.filter { it.kind == SubsystemHardwareKind.MOTOR && it.following == null }.map { it.hardwareId }
    val cachedFields = document.hardware.flatMap { device -> device.measurements.map { it.fieldId } }.distinct()

    EditorCard("Safety & Health Protections", Icons.Default.Warning) {
        Text("Outputs are held in safe neutral if feedback is stale, current is unsafe, or writes fail.", color = AresTextSecondary, fontSize = 11.sp)
        NullableDoubleInput("Feedback timeout (ms)", safety.feedbackTimeoutMs?.toDouble()) { value ->
            viewModel.edit { it.copy(safety = it.safety.copy(feedbackTimeoutMs = value?.toLong())) }
        }
        FieldGuidance("Closed-loop output stops when the newest complete cached snapshot is older than this lease.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                EnumSelector("Homing method", safety.homing.method, SubsystemHomingMethod.entries) { method ->
                    viewModel.setHomingMethod(method)
                }
            }
            ConceptHelp(
                "Homing",
                "Homing establishes a physical zero using a switch, current stall, velocity stall, combined evidence, or another cached signal.",
                "homing",
                compact = true,
            )
        }
        if (safety.homing.method != SubsystemHomingMethod.NONE) {
            if (motorIds.isNotEmpty()) {
                DropdownSelector("Homing motor", safety.homing.actuatorId ?: motorIds.first(), motorIds) { selected ->
                    viewModel.edit { it.copy(safety = it.safety.copy(homing = it.safety.homing.copy(actuatorId = selected))) }
                }
            }
            DoubleInput("Search output (V, limited to ±4)", safety.homing.searchOutput ?: -2.0) { value ->
                viewModel.edit { it.copy(safety = it.safety.copy(homing = it.safety.homing.copy(searchOutput = value))) }
            }
            LongInput("Evidence dwell (ms)", safety.homing.dwellMs) { value ->
                viewModel.edit { it.copy(safety = it.safety.copy(homing = it.safety.homing.copy(dwellMs = value))) }
            }
            LongInput("Hard timeout (ms)", safety.homing.timeoutMs) { value ->
                viewModel.edit { it.copy(safety = it.safety.copy(homing = it.safety.homing.copy(timeoutMs = value))) }
            }
            DoubleInput("Position assigned after neutral succeeds", safety.homing.zeroPosition) { value ->
                viewModel.edit { it.copy(safety = it.safety.copy(homing = it.safety.homing.copy(zeroPosition = value))) }
            }
            Text("HOMING EVIDENCE", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            safety.homing.evidence.forEachIndexed { index, evidence ->
                Surface(color = AresSurface, border = BorderStroke(1.dp, AresBorder), shape = RoundedCornerShape(6.dp)) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (cachedFields.isNotEmpty()) {
                            DropdownSelector("Cached signal", evidence.fieldId, cachedFields) { selected ->
                                viewModel.edit { doc ->
                                    doc.copy(safety = doc.safety.copy(homing = doc.safety.homing.copy(
                                        evidence = doc.safety.homing.evidence.mapIndexed { i, item -> if (i == index) item.copy(fieldId = selected) else item }
                                    )))
                                }
                            }
                        }
                        EnumSelector("Condition", evidence.comparison, SubsystemHomingComparison.entries) { comparison ->
                            viewModel.edit { doc ->
                                doc.copy(safety = doc.safety.copy(homing = doc.safety.homing.copy(
                                    evidence = doc.safety.homing.evidence.mapIndexed { i, item ->
                                        if (i == index) item.copy(
                                            comparison = comparison,
                                            threshold = item.threshold.takeUnless { comparison in setOf(SubsystemHomingComparison.TRUE, SubsystemHomingComparison.FALSE) },
                                        ) else item
                                    }
                                )))
                            }
                        }
                        if (evidence.comparison !in setOf(SubsystemHomingComparison.TRUE, SubsystemHomingComparison.FALSE)) {
                            DoubleInput("Threshold", evidence.threshold ?: 0.0) { value ->
                                viewModel.edit { doc ->
                                    doc.copy(safety = doc.safety.copy(homing = doc.safety.homing.copy(
                                        evidence = doc.safety.homing.evidence.mapIndexed { i, item -> if (i == index) item.copy(threshold = value) else item }
                                    )))
                                }
                            }
                        }
                        if (safety.homing.method == SubsystemHomingMethod.CUSTOM_MEASUREMENT) {
                            TextButton(onClick = {
                                viewModel.edit { doc -> doc.copy(safety = doc.safety.copy(homing = doc.safety.homing.copy(evidence = doc.safety.homing.evidence.filterIndexed { i, _ -> i != index }))) }
                            }) { Text("Remove evidence") }
                        }
                    }
                }
            }
            if (safety.homing.method == SubsystemHomingMethod.CUSTOM_MEASUREMENT && cachedFields.isNotEmpty()) {
                OutlinedButton(onClick = {
                    viewModel.edit { doc -> doc.copy(safety = doc.safety.copy(homing = doc.safety.homing.copy(
                        evidence = doc.safety.homing.evidence + SubsystemHomingEvidenceDocument(cachedFields.first(), SubsystemHomingComparison.AT_OR_ABOVE, 0.0)
                    ))) }
                }, modifier = Modifier.fillMaxWidth()) { Text("+ Add cached evidence") }
            }
            FieldGuidance("ARES requires fresh evidence for the full dwell, then commands neutral before assigning zero. A timeout latches a homing fault.")
            if (safety.homing.evidence.isNotEmpty()) {
                OutlinedButton(onClick = { showHomingLab = !showHomingLab }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showHomingLab) "Hide homing lab" else "Try the homing evidence without hardware")
                }
                if (showHomingLab) HomingConceptLab(safety.homing)
            }
        }
        ToggleRow("Latch failed output writes", safety.latchOutputFaults) { value ->
            viewModel.edit { it.copy(safety = it.safety.copy(latchOutputFaults = value)) }
        }
        ToggleRow("Zero-allocation hot loop path", safety.zeroAllocationPeriodic) { value ->
            viewModel.edit { it.copy(safety = it.safety.copy(zeroAllocationPeriodic = value)) }
        }
        OutlinedButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showAdvanced) "Hide advanced safety flags" else "Show advanced safety flags", fontSize = 11.sp)
        }
        if (showAdvanced) {
            ToggleRow("Require calibration", safety.requiresCalibration) { value ->
                viewModel.edit { it.copy(safety = it.safety.copy(requiresCalibration = value)) }
            }
            ToggleRow("Require configuration health", safety.requiresConfigurationHealth) { value ->
                viewModel.edit { it.copy(safety = it.safety.copy(requiresConfigurationHealth = value)) }
            }
            ToggleRow("Require valid current monitoring", safety.requiresCurrentMonitoring) { value ->
                viewModel.edit { it.copy(safety = it.safety.copy(requiresCurrentMonitoring = value)) }
            }
            ToggleRow("Require explicit successful neutral recovery", safety.requiresExplicitNeutralRecovery) { value ->
                viewModel.edit { it.copy(safety = it.safety.copy(requiresExplicitNeutralRecovery = value)) }
            }
            ToggleRow("Publish subsystem telemetry", safety.telemetryEnabled) { value ->
                viewModel.edit { it.copy(safety = it.safety.copy(telemetryEnabled = value)) }
            }
        }
    }
}
