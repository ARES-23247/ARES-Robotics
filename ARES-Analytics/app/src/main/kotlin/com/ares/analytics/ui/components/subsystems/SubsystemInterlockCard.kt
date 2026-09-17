package com.ares.analytics.ui.components.subsystems

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.viewmodel.SubsystemGeneratorState
import com.ares.analytics.viewmodel.SubsystemGeneratorViewModel
import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemValueType
import com.areslib.subsystem.isAresGenerated

@Composable
fun InterlockMatrixCard(document: SubsystemDocument, state: SubsystemGeneratorState, viewModel: SubsystemGeneratorViewModel) {
    val targets = state.documents.filter {
        it.uid != document.uid && it.implementation.kind.isAresGenerated() && it.stateFields.isNotEmpty()
    }.sortedBy { it.displayName.lowercase() }
    val targetOptions = targets.map { "${it.displayName} (${it.documentId})" }
    EditorCard("Positional Interlocks (${document.interlocks.size})", Icons.Default.Lock) {
        Text("Interlocks block movement when a listed condition is true or the other subsystem's feedback is unavailable. Each actuator then uses its configured safe output.", color = AresTextSecondary, fontSize = 11.sp)
        if (document.interlocks.isEmpty()) {
            Text("No positional interlocks configured.", color = AresTextTertiary, fontSize = 10.sp)
        }
        document.interlocks.forEach { interlock ->
            val target = targets.firstOrNull { it.uid == interlock.targetSubsystemUid }
            Surface(color = AresSurface, border = BorderStroke(1.dp, AresBorder), shape = RoundedCornerShape(6.dp)) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(interlock.interlockId, color = AresCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    if (targetOptions.isNotEmpty()) {
                        DropdownSelector("Other subsystem", target?.let { "${it.displayName} (${it.documentId})" } ?: "Select a subsystem", targetOptions) { selectedName ->
                            val selected = targets.getOrNull(targetOptions.indexOf(selectedName)) ?: return@DropdownSelector
                            val field = selected.stateFields.firstOrNull() ?: return@DropdownSelector
                            viewModel.updateInterlock(interlock.interlockId) {
                                it.copy(
                                    targetSubsystemUid = selected.uid,
                                    targetFieldId = field.fieldId,
                                    comparison = if (field.type in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)) InterlockComparison.LESS_THAN else InterlockComparison.EQUALS_STATE,
                                    targetStateName = if (field.type == SubsystemValueType.BOOLEAN) "false" else null,
                                )
                            }
                        }
                    }
                    val fields = target?.stateFields.orEmpty()
                    if (fields.isNotEmpty()) {
                        DropdownSelector("Observed state value", interlock.targetFieldId, fields.map { it.fieldId }) { selected ->
                            val field = fields.firstOrNull { it.fieldId == selected } ?: return@DropdownSelector
                            viewModel.updateInterlock(interlock.interlockId) {
                                it.copy(
                                    targetFieldId = selected,
                                    comparison = if (field.type in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)) InterlockComparison.LESS_THAN else InterlockComparison.EQUALS_STATE,
                                    targetStateName = if (field.type == SubsystemValueType.BOOLEAN) "false" else null,
                                )
                            }
                        }
                        val field = fields.firstOrNull { it.fieldId == interlock.targetFieldId }
                        val numericField = field?.type in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)
                        val comparisons = if (numericField) {
                            InterlockComparison.entries
                        } else {
                            listOf(InterlockComparison.EQUALS_STATE, InterlockComparison.NOT_EQUALS_STATE)
                        }
                        EnumSelector("Block movement when", interlock.comparison, comparisons) { comparison ->
                            viewModel.updateInterlock(interlock.interlockId) { it.copy(comparison = comparison) }
                        }
                        if (numericField) {
                            DoubleInput("Threshold (${field?.unit ?: "state units"})", interlock.thresholdValue) { value ->
                                viewModel.updateInterlock(interlock.interlockId) { it.copy(thresholdValue = value) }
                            }
                        } else {
                            TextInput("Expected state", interlock.targetStateName.orEmpty()) { value ->
                                viewModel.updateInterlock(interlock.interlockId) { it.copy(targetStateName = value) }
                            }
                        }
                    }
                    TextInput("Student-facing reason", interlock.forbiddenZoneDescription) { value ->
                        viewModel.updateInterlock(interlock.interlockId) { it.copy(forbiddenZoneDescription = value) }
                    }
                    if (!document.implementation.kind.isAresGenerated()) {
                        NullableDoubleInput("Custom fallback output (implementation-owned)", interlock.safeFallbackValue) { value ->
                            viewModel.updateInterlock(interlock.interlockId) { it.copy(safeFallbackValue = value) }
                        }
                    } else if (interlock.safeFallbackValue != null) {
                        Text("This fallback override is unsupported. Use each actuator's configured safe output.", color = AresTextSecondary, fontSize = 11.sp)
                        TextButton(onClick = { viewModel.updateInterlock(interlock.interlockId) { it.copy(safeFallbackValue = null) } }) {
                            Text("Use configured safe outputs")
                        }
                    }
                    TextButton(onClick = { viewModel.removeInterlock(interlock.interlockId) }) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Remove interlock")
                    }
                }
            }
        }
        OutlinedButton(onClick = viewModel::addInterlock, enabled = targets.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("+ Add cross-mechanism interlock")
        }
        if (targets.isEmpty()) {
            FieldGuidance("Add another generated subsystem with state values before creating a cross-mechanism interlock.")
        }
    }
}
