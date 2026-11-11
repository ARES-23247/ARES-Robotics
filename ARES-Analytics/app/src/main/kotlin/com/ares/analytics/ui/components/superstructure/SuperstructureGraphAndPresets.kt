package com.ares.analytics.ui.components.superstructure

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.viewmodel.superstructure.*
import com.ares.analytics.viewmodel.superstructure.SuperstructureCoordinationPreset
import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioState
import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioViewModel
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.TransitionTriggerKind
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Composable
fun SuperstructureCoordinationPresetBar(
    viewModel: SuperstructureStudioViewModel,
    modifier: Modifier = Modifier,
) {
    var configureClearance by remember { mutableStateOf(false) }
    val state by viewModel.state.collectAsState()
    if (configureClearance) {
        ClearanceInterlockDialog(state, onDismiss = { configureClearance = false }) { config ->
            viewModel.applyCoordinationPreset(SuperstructureCoordinationPreset.RAISE_BEFORE_EXTEND_INTERLOCK, config)
            configureClearance = false
        }
    }
    Surface(
        color = AresSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "MULTI-MECHANISM COORDINATION PRESETS",
                    color = AresCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Scaffold state transitions. Configure and review physical clearance before adding a collision guard:",
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SuperstructureCoordinationPreset.entries.forEach { preset ->
                    Surface(
                        color = AresSurfaceElevated,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, AresBorder),
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                if (preset == SuperstructureCoordinationPreset.RAISE_BEFORE_EXTEND_INTERLOCK) configureClearance = true
                                else viewModel.applyCoordinationPreset(preset)
                            },
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    preset.title,
                                    color = AresTextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                )
                                Text(
                                    if (preset == SuperstructureCoordinationPreset.RAISE_BEFORE_EXTEND_INTERLOCK) "CONFIGURE" else "ADD",
                                    color = AresCyan,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(preset.subtitle, color = AresTextSecondary, fontSize = 10.sp)
                            Text(
                                preset.badge,
                                color = AresGold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClearanceInterlockDialog(
    state: SuperstructureStudioState,
    onDismiss: () -> Unit,
    onApply: (ClearanceInterlockConfiguration) -> Unit,
) {
    var source by remember { mutableStateOf<SuperstructureFieldOption?>(null) }
    var target by remember { mutableStateOf<SuperstructureFieldOption?>(null) }
    var threshold by remember { mutableStateOf("") }
    var minimum by remember { mutableStateOf("") }
    var maximum by remember { mutableStateOf("") }
    val config = source?.let { selectedSource -> target?.let { selectedTarget ->
        ClearanceInterlockConfiguration(selectedSource.reference, selectedTarget.reference,
            threshold.toDoubleOrNull() ?: Double.NaN, minimum.toDoubleOrNull() ?: Double.NaN,
            maximum.toDoubleOrNull() ?: Double.NaN)
    } }
    val error = if (config == null) "Choose both fields and review the values." else clearanceInterlockError(state, config)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review raise-before-extend guard") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ClearanceFieldPicker("Measured clearance", source, state.sourceFields.filter {
                    it.field.role == com.areslib.subsystem.SubsystemFieldRole.MEASUREMENT &&
                        it.field.type in setOf(com.areslib.subsystem.SubsystemValueType.DOUBLE, com.areslib.subsystem.SubsystemValueType.INT) &&
                        !it.field.unit.isNullOrBlank()
                }) { source = it; target = null }
                ClearanceFieldPicker("Constrained target", target, state.targetFields.filter {
                    it.subsystem.uid != source?.subsystem?.uid &&
                        it.field.type == com.areslib.subsystem.SubsystemValueType.DOUBLE &&
                        !it.field.unit.isNullOrBlank()
                }) { target = it }
                OutlinedTextField(threshold, { threshold = it }, label = { Text("Clearance threshold (${source?.field?.unit ?: "select measurement"})") })
                OutlinedTextField(minimum, { minimum = it }, label = { Text("Safe minimum (${target?.field?.unit ?: "select target"})") })
                OutlinedTextField(maximum, { maximum = it }, label = { Text("Safe maximum (${target?.field?.unit ?: "select target"})") })
                Text("Below the measured clearance, the target is restricted to this range. Review it against the mechanism geometry before saving.")
                if (error != null) Text(error, color = AresGold)
            }
        },
        confirmButton = { TextButton(enabled = config != null && error == null, onClick = { config?.let(onApply) }) { Text("Add reviewed guard") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ClearanceFieldPicker(label: String, selected: SuperstructureFieldOption?, options: List<SuperstructureFieldOption>, onSelect: (SuperstructureFieldOption) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label: ${selected?.label ?: "Choose field"}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text("${option.label} [${option.field.unit}]") }, onClick = { onSelect(option); expanded = false }) }
        }
    }
}
