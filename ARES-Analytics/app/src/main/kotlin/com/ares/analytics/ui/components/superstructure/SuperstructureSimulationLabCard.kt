package com.ares.analytics.ui.components.superstructure

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.viewmodel.superstructure.PreviewPortCondition
import com.ares.analytics.viewmodel.superstructure.SuperstructurePreviewPort
import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioState
import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioViewModel
import com.areslib.subsystem.SubsystemValueType
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.TransitionTriggerKind
import java.util.Locale

fun extractSuperstructureActionTriggers(draft: SuperstructureDocument): List<String> =
    draft.transitions
        .asSequence()
        .filter { it.triggerKind == TransitionTriggerKind.ACTION_REQUEST }
        .mapNotNull { it.actionKey?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
        .toList()

fun formatPreviewPortValue(port: SuperstructurePreviewPort): String {
    val raw = when (port.type) {
        SubsystemValueType.DOUBLE -> port.numericValue?.let { String.format(Locale.US, "%.3f", it) } ?: "NaN"
        SubsystemValueType.INT -> port.numericValue?.toInt()?.toString() ?: "0"
        SubsystemValueType.BOOLEAN -> port.booleanValue?.toString() ?: "false"
        SubsystemValueType.STRING -> port.stringValue?.let { "\"$it\"" } ?: "\"\""
    }
    val unitSuffix = port.unit?.trim()?.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
    return "$raw$unitSuffix"
}

fun previewPortConditionDisplayName(condition: PreviewPortCondition): String = when (condition) {
    PreviewPortCondition.HEALTHY -> "Healthy"
    PreviewPortCondition.STALE -> "Stale Age"
    PreviewPortCondition.INVALID -> "Invalid Sample"
    PreviewPortCondition.UNCONFIGURED -> "Unconfigured"
    PreviewPortCondition.UNHOMED_OR_UNCALIBRATED -> "Unhomed"
    PreviewPortCondition.CURRENT_INVALID -> "Current Fault"
    PreviewPortCondition.OUTPUT_FAULT -> "Output Fault"
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SuperstructureSimulationLabCard(
    state: SuperstructureStudioState,
    draft: SuperstructureDocument,
    viewModel: SuperstructureStudioViewModel,
    modifier: Modifier = Modifier,
) {
    val preview = state.preview
    val actionTriggers = remember(draft) { extractSuperstructureActionTriggers(draft) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AresSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, AresBorder),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "DETERMINISTIC SIMULATION & FAULT INJECTION LAB",
                        color = AresTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Execute the production SuperstructureRuntime against cached sensor/target ports, action triggers, and injected port faults.",
                        color = AresTextTertiary,
                        fontSize = 11.sp,
                    )
                }
                Button(
                    onClick = viewModel::startPreview,
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        if (preview == null) Icons.Default.PlayArrow else Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (preview == null) "Start Simulation" else "Reset Simulation",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            if (preview == null) {
                Surface(
                    color = AresSurfaceElevated,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, AresBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Click \"Start Simulation\" to initialize the deterministic coordinator at state '${draft.initialStateId}' (t = 0 ms) and test transitions, interlocks, and health fallbacks.",
                        color = AresTextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            } else {
                // Transport & Clock Controls
                Surface(
                    color = AresSurfaceElevated,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (preview.isFaulted) AresError else AresBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text("ACTIVE POSTURE", color = AresTextTertiary, fontSize = 9.sp)
                                    Text(
                                        preview.currentStateId,
                                        color = AresCyan,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                Column {
                                    Text("PREVIOUS", color = AresTextTertiary, fontSize = 9.sp)
                                    Text(
                                        preview.previousStateId,
                                        color = AresTextSecondary,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                Column {
                                    Text("SIM CLOCK / STATE AGE", color = AresTextTertiary, fontSize = 9.sp)
                                    Text(
                                        "t=${preview.nowMs} ms (age ${preview.stateAgeMs} ms · seq #${preview.transitionSequence})",
                                        color = AresTextPrimary,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }

                            val statusColor = when {
                                preview.isFaulted -> AresError
                                preview.isEnabled -> AresGreen
                                else -> AresAmber
                            }
                            Surface(
                                color = statusColor.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(1.dp, statusColor),
                            ) {
                                Text(
                                    when {
                                        preview.isFaulted -> "FAULTED (${preview.faultReason ?: "Unknown"})"
                                        preview.isEnabled -> "ROBOT ENABLED"
                                        else -> "ROBOT DISABLED"
                                    },
                                    color = statusColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }

                        HorizontalDivider(color = AresBorder)

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = preview.isEnabled,
                                onClick = { viewModel.setPreviewEnabled(!preview.isEnabled) },
                                modifier = Modifier.height(30.dp),
                                label = {
                                    Text(
                                        if (preview.isEnabled) "Enabled (Click to Disable)" else "Disabled (Click to Enable)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AresGreen,
                                    selectedLabelColor = AresOnAccent,
                                    containerColor = AresSurface,
                                    labelColor = AresAmber,
                                ),
                            )

                            listOf(20L to "+20 ms (1 loop)", 100L to "+100 ms", 500L to "+500 ms", 1000L to "+1.0 s").forEach { (deltaMs, label) ->
                                OutlinedButton(
                                    onClick = { viewModel.advancePreview(deltaMs) },
                                    modifier = Modifier.height(30.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                ) {
                                    Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }

                        preview.candidateTransitionId?.let { candidate ->
                            Text(
                                "Pending debounced transition candidate: $candidate",
                                color = AresGold,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        preview.lastRejectionReason?.let { rejection ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = AresAmber, modifier = Modifier.size(14.dp))
                                Text("Last transition rejection: $rejection", color = AresAmber, fontSize = 11.sp)
                            }
                        }
                        preview.lastLifecycleError?.let { err ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = AresError, modifier = Modifier.size(14.dp))
                                Text("Lifecycle error: $err", color = AresError, fontSize = 11.sp)
                            }
                        }
                        if (preview.lifecycleActions.isNotEmpty()) {
                            Text(
                                "Executed lifecycle actions: ${preview.lifecycleActions.joinToString(" → ")}",
                                color = AresCyan,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }

                // Action Request Triggers
                if (actionTriggers.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "DISPATCH ACTION REQUESTS",
                            color = AresTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            actionTriggers.forEach { actionKey ->
                                OutlinedButton(
                                    onClick = { viewModel.requestPreviewAction(actionKey) },
                                    modifier = Modifier.height(30.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(13.dp), tint = AresCyan)
                                    Spacer(Modifier.width(4.dp))
                                    Text(actionKey, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }

                // Cached Ports & Fault Condition Injector
                if (preview.ports.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "CACHED PORTS & FAULT CONDITION INJECTOR (${preview.ports.size})",
                            color = AresTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        preview.ports.forEach { port ->
                            PreviewPortControlCard(
                                port = port,
                                viewModel = viewModel,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PreviewPortControlCard(
    port: SuperstructurePreviewPort,
    viewModel: SuperstructureStudioViewModel,
) {
    val isHealthy = port.condition == PreviewPortCondition.HEALTHY
    val borderColor = if (isHealthy) AresBorder else AresAmber

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AresSurfaceElevated,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(port.label, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(
                        "${port.type.name} · age ${port.ageMs} ms · health 0x${port.healthBits.toString(16).uppercase()}",
                        color = AresTextTertiary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    formatPreviewPortValue(port),
                    color = AresCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Value Editor Row
            when (port.type) {
                SubsystemValueType.DOUBLE, SubsystemValueType.INT -> {
                    var textValue by remember(port.reference, port.numericValue) {
                        mutableStateOf(port.numericValue?.toString().orEmpty())
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedTextField(
                            value = textValue,
                            onValueChange = { textValue = it },
                            label = { Text("Port value", fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.width(130.dp),
                        )
                        OutlinedButton(
                            onClick = {
                                textValue.trim().toDoubleOrNull()?.let { num ->
                                    viewModel.setPreviewNumeric(port.reference, num)
                                }
                            },
                            modifier = Modifier.height(30.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text("Set", fontSize = 11.sp)
                        }
                        listOf(0.0 to "0", 1.0 to "1", 10.0 to "10", 50.0 to "50").forEach { (preset, label) ->
                            OutlinedButton(
                                onClick = { viewModel.setPreviewNumeric(port.reference, preset) },
                                modifier = Modifier.height(30.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text(label, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                SubsystemValueType.BOOLEAN -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(false, true).forEach { boolVal ->
                            val selected = port.booleanValue == boolVal
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.setPreviewBoolean(port.reference, boolVal) },
                                modifier = Modifier.height(28.dp),
                                label = { Text(boolVal.toString(), fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                            )
                        }
                    }
                }
                SubsystemValueType.STRING -> {
                    var strVal by remember(port.reference, port.stringValue) {
                        mutableStateOf(port.stringValue.orEmpty())
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = strVal,
                            onValueChange = { strVal = it },
                            label = { Text("String value", fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.width(160.dp),
                        )
                        OutlinedButton(
                            onClick = { viewModel.setPreviewString(port.reference, strVal) },
                            modifier = Modifier.height(30.dp),
                        ) {
                            Text("Set", fontSize = 11.sp)
                        }
                    }
                }
            }

            // Fault Condition Selector Chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PreviewPortCondition.entries.forEach { condition ->
                    val selected = port.condition == condition
                    val chipColor = if (condition == PreviewPortCondition.HEALTHY) AresGreen else AresAmber
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.injectPreview(port.reference, condition) },
                        modifier = Modifier.height(26.dp),
                        label = {
                            Text(
                                previewPortConditionDisplayName(condition),
                                fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = chipColor,
                            selectedLabelColor = AresOnAccent,
                            containerColor = AresSurface,
                            labelColor = AresTextSecondary,
                        ),
                    )
                }
            }
        }
    }
}
