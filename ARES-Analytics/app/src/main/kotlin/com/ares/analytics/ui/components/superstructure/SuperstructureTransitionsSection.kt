package com.ares.analytics.ui.components.superstructure

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.SubsystemValueType
import com.areslib.superstructure.*
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.components.core.AresDoubleField
import com.ares.analytics.ui.components.core.AresNullableDoubleField
import com.ares.analytics.viewmodel.superstructure.*
import kotlin.math.*

@Composable
fun SuperstructureTransitionsSection(
    state: SuperstructureStudioState,
    draft: SuperstructureDocument,
    viewModel: SuperstructureStudioViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SuperstructureCoordinationPresetBar(viewModel = viewModel)
        // Visual Transition Graph Canvas
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AresSurface,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("VISUAL TRANSITION STATE GRAPH", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("Drag nodes to arrange. Click a node to inspect its setpoints.", color = AresTextTertiary, fontSize = 10.sp)
                    }

                    var addTransitionOpen by remember { mutableStateOf(false) }
                    var srcState by remember { mutableStateOf(draft.initialStateId) }
                    var tgtState by remember { mutableStateOf(draft.states.firstOrNull { it.stateId != draft.initialStateId }?.stateId ?: draft.initialStateId) }
                    var actionKey by remember { mutableStateOf(state.parameterlessActions.firstOrNull()?.key.orEmpty()) }
                    var triggerKind by remember { mutableStateOf(TransitionTriggerKind.ACTION_REQUEST) }
                    var sensorField by remember { mutableStateOf(state.sourceFields.firstOrNull()) }
                    var delaySeconds by remember { mutableStateOf(0.5) }

                    if (!addTransitionOpen) {
                        Button(
                            onClick = { addTransitionOpen = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add Transition", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                StudioDropdown("From: $srcState", draft.states.map { it.stateId to it.displayName }, { srcState = it }, Modifier.width(130.dp))
                                StudioDropdown("To: $tgtState", draft.states.map { it.stateId to it.displayName }, { tgtState = it }, Modifier.width(130.dp))
                                StudioDropdown(
                                    label = "Trigger: ${triggerKind.name.lowercase().replace('_', ' ')}",
                                    options = TransitionTriggerKind.entries.map { it.name to it.name.lowercase().replace('_', ' ') },
                                    onSelect = { triggerKind = TransitionTriggerKind.valueOf(it) },
                                    modifier = Modifier.width(175.dp),
                                )
                                when (triggerKind) {
                                    TransitionTriggerKind.ACTION_REQUEST -> StudioDropdown(
                                        label = state.parameterlessActions.firstOrNull { it.key == actionKey }?.displayName ?: "Choose action",
                                        options = state.parameterlessActions.map { it.key to it.displayName },
                                        onSelect = { actionKey = it }, modifier = Modifier.width(180.dp),
                                    )
                                    TransitionTriggerKind.SENSOR_CONDITION_AUTO -> StudioDropdown(
                                        label = sensorField?.label ?: "Choose sensor",
                                        options = state.sourceFields.map { it.reference.subsystemUid + ":" + it.reference.fieldUid to it.label },
                                        onSelect = { packed -> sensorField = state.sourceFields.first { packed == it.reference.subsystemUid + ":" + it.reference.fieldUid } },
                                        modifier = Modifier.width(190.dp),
                                    )
                                    TransitionTriggerKind.TIME_ELAPSED -> CompactDecimalField("Wait (s)", delaySeconds, { delaySeconds = it }, Modifier.width(110.dp))
                                }
                                Button(
                                    onClick = {
                                        when (triggerKind) {
                                            TransitionTriggerKind.ACTION_REQUEST -> viewModel.addActionTransition(srcState, tgtState, actionKey)
                                            TransitionTriggerKind.SENSOR_CONDITION_AUTO -> sensorField?.let { viewModel.addSensorTransition(srcState, tgtState, it) }
                                            TransitionTriggerKind.TIME_ELAPSED -> viewModel.addTimedTransition(srcState, tgtState, delaySeconds)
                                        }
                                        addTransitionOpen = false
                                    },
                                    enabled = srcState != tgtState && when (triggerKind) {
                                        TransitionTriggerKind.ACTION_REQUEST -> actionKey.isNotBlank()
                                        TransitionTriggerKind.SENSOR_CONDITION_AUTO -> sensorField != null
                                        TransitionTriggerKind.TIME_ELAPSED -> delaySeconds > 0.0
                                    },
                                colors = ButtonDefaults.buttonColors(containerColor = AresGreen, contentColor = AresOnAccent),
                                modifier = Modifier.height(36.dp),
                                ) { Text("Add Route", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                OutlinedButton(onClick = { addTransitionOpen = false }, modifier = Modifier.height(36.dp)) { Text("Cancel", fontSize = 11.sp) }
                            }
                        }
                    }
                }

                StateflowGraphCanvas(
                    state = state,
                    draft = draft,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                )

                if (draft.transitions.isEmpty()) {
                    Text("No routes yet. Add a named command, sensor condition, or timed route above.", color = AresTextTertiary, fontSize = 10.sp)
                } else {
                    draft.transitions.sortedWith(compareBy<StateTransitionEdge> { it.sourceStateId }.thenBy { it.priority }).forEach { edge ->
                        TransitionEditor(edge, state, draft, viewModel)
                    }
                }
            }
        }

        // Collision Guards & Interlocks and Lookup Tables (Side-by-Side 2 columns)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Left (50%): Interlock Guards
            Surface(
                modifier = Modifier.weight(1f),
                color = AresSurface,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, AresBorder),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, null, tint = AresGold, modifier = Modifier.size(14.dp))
                            Text("COLLISION INTERLOCKS (${draft.interlocks.size})", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        if (state.targetFields.size >= 2) {
                            OutlinedButton(
                                onClick = {
                                    val src = state.sourceFields.firstOrNull()
                                    val tgt = state.targetFields.firstOrNull()
                                    if (src != null && tgt != null) {
                                        viewModel.addInterlock(src, tgt)
                                    }
                                },
                                modifier = Modifier.height(26.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(10.dp))
                                Spacer(Modifier.width(2.dp))
                                Text("Add Guard", fontSize = 10.sp)
                            }
                        }
                    }
                    if (draft.interlocks.isEmpty()) {
                        Text("No collision interlocks configured. Mechanisms move freely without positional guards.", color = AresTextTertiary, fontSize = 10.sp)
                    } else {
                        draft.interlocks.forEach { guard -> InterlockEditor(guard, state, viewModel) }
                    }
                }
            }

            // Right (50%): Lookup Tables (LUTs)
            Surface(
                modifier = Modifier.weight(1f),
                color = AresSurface,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, AresBorder),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.TableChart, null, tint = AresCyan, modifier = Modifier.size(14.dp))
                            Text("LOOKUP TABLES / CURVES (${draft.luts.size})", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = viewModel::addLut,
                            modifier = Modifier.height(26.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(10.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("Add LUT", fontSize = 10.sp)
                        }
                    }
                    if (draft.luts.isEmpty()) {
                        Text("No lookup tables configured. Use when output speeds depend on distance (e.g. shooter RPM).", color = AresTextTertiary, fontSize = 10.sp)
                    } else {
                        draft.luts.forEach { lut -> LutEditor(lut, viewModel) }
                    }
                }
            }
        }

        HealthFallbackEditor(state, draft, viewModel)
    }
}

@Composable
private fun TransitionEditor(
    edge: StateTransitionEdge,
    state: SuperstructureStudioState,
    draft: SuperstructureDocument,
    viewModel: SuperstructureStudioViewModel,
) {
    Surface(color = AresSurfaceElevated, shape = RoundedCornerShape(7.dp), border = BorderStroke(1.dp, AresBorder)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(edge.transitionId, color = AresTextPrimary, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                StudioDropdown("From: ${edge.sourceStateId}", draft.states.map { it.stateId to it.displayName }, { viewModel.updateTransition(edge.copy(sourceStateId = it)) }, Modifier.width(140.dp))
                StudioDropdown("To: ${edge.targetStateId}", draft.states.map { it.stateId to it.displayName }, { viewModel.updateTransition(edge.copy(targetStateId = it)) }, Modifier.width(140.dp))
                IconButton(onClick = { viewModel.removeTransition(edge.transitionId) }, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.Delete, "Delete transition", tint = AresError, modifier = Modifier.size(14.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StudioDropdown(
                    "Trigger: ${edge.triggerKind.name.lowercase().replace('_', ' ')}",
                    TransitionTriggerKind.entries.map { it.name to it.name.lowercase().replace('_', ' ') },
                    { raw ->
                        val kind = TransitionTriggerKind.valueOf(raw)
                        viewModel.updateTransition(
                            edge.copy(
                                triggerKind = kind,
                                actionKey = if (kind == TransitionTriggerKind.ACTION_REQUEST) state.parameterlessActions.firstOrNull()?.key else null,
                                timeoutSeconds = if (kind == TransitionTriggerKind.TIME_ELAPSED) edge.timeoutSeconds ?: 0.5 else edge.timeoutSeconds,
                            )
                        )
                    }, Modifier.weight(1f),
                )
                if (edge.triggerKind == TransitionTriggerKind.ACTION_REQUEST) {
                    StudioDropdown(
                        "Action: ${state.parameterlessActions.firstOrNull { it.key == edge.actionKey }?.displayName ?: "Choose"}",
                        state.parameterlessActions.map { it.key to it.displayName },
                        { viewModel.updateTransition(edge.copy(actionKey = it)) }, Modifier.weight(1f),
                    )
                } else {
                    CompactIntField("Priority (lower first)", edge.priority, { viewModel.updateTransition(edge.copy(priority = it)) }, Modifier.weight(1f))
                }
                CompactLongField("Debounce (ms)", edge.debounceMs, { viewModel.updateTransition(edge.copy(debounceMs = it)) }, Modifier.weight(1f))
            }
            if (edge.triggerKind == TransitionTriggerKind.TIME_ELAPSED) {
                NullableDecimalField("Time in source state (seconds)", edge.timeoutSeconds, { viewModel.updateTransition(edge.copy(timeoutSeconds = it)) }, Modifier.fillMaxWidth())
            } else if (edge.triggerKind == TransitionTriggerKind.ACTION_REQUEST) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NullableDecimalField("Request deadline (seconds, optional)", edge.timeoutSeconds, { viewModel.updateTransition(edge.copy(timeoutSeconds = it)) }, Modifier.weight(1f))
                    StudioDropdown(
                        "If deadline expires: ${edge.timeoutTargetStateId ?: "Reject and stay"}",
                        listOf("" to "Reject and stay") + draft.states.map { it.stateId to it.displayName },
                        { viewModel.updateTransition(edge.copy(timeoutTargetStateId = it.ifBlank { null })) }, Modifier.weight(1f),
                    )
                }
            }

            Text("GUARDS · every condition below must be true", color = AresTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            edge.guards.forEach { guard -> GuardEditor(edge, guard, state, viewModel) }
            val guardable = state.sourceFields.filter { option -> edge.guards.none { it.source == option.reference } }
            if (guardable.isNotEmpty()) {
                StudioDropdown(
                    label = "+ Add cached sensor or status guard",
                    options = guardable.map { it.reference.subsystemUid + ":" + it.reference.fieldUid to it.label },
                    onSelect = { packed -> viewModel.addGuard(edge.transitionId, guardable.first { packed == it.reference.subsystemUid + ":" + it.reference.fieldUid }) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun GuardEditor(
    edge: StateTransitionEdge,
    guard: TransitionGuard,
    state: SuperstructureStudioState,
    viewModel: SuperstructureStudioViewModel,
) {
    val option = state.sourceFields.firstOrNull { it.reference == guard.source }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(option?.label ?: "${guard.source.subsystemUid}.${guard.source.fieldUid}", color = AresTextPrimary, fontSize = 10.sp, modifier = Modifier.weight(1.4f))
        StudioDropdown(
            label = guard.comparison.name.lowercase().replace('_', ' '),
            options = InterlockComparison.entries.map { it.name to it.name.lowercase().replace('_', ' ') },
            onSelect = { selectedComparison ->
                viewModel.updateTransition(
                    edge.copy(guards = edge.guards.map { existing ->
                        if (existing.guardId == guard.guardId) guard.copy(comparison = InterlockComparison.valueOf(selectedComparison)) else existing
                    })
                )
            },
            modifier = Modifier.weight(1f),
        )
        when (option?.field?.type) {
            SubsystemValueType.BOOLEAN -> {
                Switch(
                    checked = guard.expectedBooleanValue ?: true,
                    onCheckedChange = { value -> viewModel.updateTransition(edge.copy(guards = edge.guards.map { if (it.guardId == guard.guardId) guard.copy(expectedBooleanValue = value) else it })) },
                )
            }
            SubsystemValueType.STRING -> OutlinedTextField(
                value = guard.expectedStringValue.orEmpty(),
                onValueChange = { value -> viewModel.updateTransition(edge.copy(guards = edge.guards.map { if (it.guardId == guard.guardId) guard.copy(expectedStringValue = value) else it })) },
                label = { Text("Expected") }, modifier = Modifier.weight(1f), singleLine = true,
            )
            else -> CompactDecimalField(
                "Threshold", guard.expectedDoubleValue ?: 0.0,
                { value -> viewModel.updateTransition(edge.copy(guards = edge.guards.map { if (it.guardId == guard.guardId) guard.copy(expectedDoubleValue = value) else it })) },
                Modifier.weight(1f),
            )
        }
        NullableLongField(
            "Max age ms", guard.maxStalenessMs,
            { value -> viewModel.updateTransition(edge.copy(guards = edge.guards.map { if (it.guardId == guard.guardId) guard.copy(maxStalenessMs = value) else it })) },
            Modifier.weight(0.8f),
        )
        IconButton(onClick = { viewModel.removeGuard(edge.transitionId, guard.guardId) }, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.Delete, "Remove guard", tint = AresError, modifier = Modifier.size(13.dp))
        }
    }
}

@Composable
private fun InterlockEditor(rule: SuperstructureInterlockRule, state: SuperstructureStudioState, viewModel: SuperstructureStudioViewModel) {
    val numericSources = state.sourceFields.filter { it.field.type in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT) }
    val numericTargets = state.targetFields.filter { it.field.type in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT) }
    Surface(color = AresSurfaceElevated, shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, AresBorder)) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(rule.description, { viewModel.updateInterlock(rule.copy(description = it)) }, label = { Text("Safety rule explanation") }, modifier = Modifier.weight(1f), singleLine = true)
                IconButton(onClick = { viewModel.removeInterlock(rule.ruleId) }) { Icon(Icons.Default.Delete, "Delete interlock", tint = AresError) }
            }
            StudioDropdown(
                "Observe: ${numericSources.firstOrNull { it.reference == rule.primary }?.label ?: "Choose"}",
                numericSources.map { it.reference.subsystemUid + ":" + it.reference.fieldUid to it.label },
                { packed -> viewModel.updateInterlock(rule.copy(primary = numericSources.first { packed == it.reference.subsystemUid + ":" + it.reference.fieldUid }.reference)) }, Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StudioDropdown(rule.conditionComparison.name.lowercase().replace('_', ' '), InterlockComparison.entries.map { it.name to it.name.lowercase().replace('_', ' ') }, { viewModel.updateInterlock(rule.copy(conditionComparison = InterlockComparison.valueOf(it))) }, Modifier.weight(1f))
                CompactDecimalField("Threshold", rule.conditionThreshold, { viewModel.updateInterlock(rule.copy(conditionThreshold = it)) }, Modifier.weight(1f))
            }
            StudioDropdown(
                "Constrain: ${numericTargets.firstOrNull { it.reference == rule.constrained }?.label ?: "Choose"}",
                numericTargets.map { it.reference.subsystemUid + ":" + it.reference.fieldUid to it.label },
                { packed -> viewModel.updateInterlock(rule.copy(constrained = numericTargets.first { packed == it.reference.subsystemUid + ":" + it.reference.fieldUid }.reference)) }, Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NullableDecimalField("Minimum allowed", rule.clampMinimum, { viewModel.updateInterlock(rule.copy(clampMinimum = it)) }, Modifier.weight(1f))
                NullableDecimalField("Maximum allowed", rule.clampMaximum, { viewModel.updateInterlock(rule.copy(clampMaximum = it)) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LutEditor(lut: SuperstructureDynamicLut, viewModel: SuperstructureStudioViewModel) {
    Surface(color = AresSurfaceElevated, shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, AresBorder)) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(lut.displayName, { viewModel.updateLut(lut.copy(displayName = it)) }, label = { Text("Curve name") }, modifier = Modifier.weight(1f), singleLine = true)
                IconButton(onClick = { viewModel.removeLut(lut.lutId) }) { Icon(Icons.Default.Delete, "Delete lookup table", tint = AresError) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(lut.inputUnit, { viewModel.updateLut(lut.copy(inputUnit = it)) }, label = { Text("Input unit") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(lut.outputUnit, { viewModel.updateLut(lut.copy(outputUnit = it)) }, label = { Text("Output unit") }, modifier = Modifier.weight(1f), singleLine = true)
                StudioDropdown(lut.interpolation.name.lowercase().replace('_', ' '), LutInterpolationMethod.entries.map { it.name to it.name.lowercase().replace('_', ' ') }, { viewModel.updateLut(lut.copy(interpolation = LutInterpolationMethod.valueOf(it))) }, Modifier.weight(1f))
            }
            lut.controlPoints.forEachIndexed { index, point ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CompactDecimalField("Input", point.inputX, { value -> viewModel.updateLut(lut.copy(controlPoints = lut.controlPoints.mapIndexed { i, existing -> if (i == index) existing.copy(inputX = value) else existing })) }, Modifier.weight(1f))
                    CompactDecimalField("Output", point.outputY, { value -> viewModel.updateLut(lut.copy(controlPoints = lut.controlPoints.mapIndexed { i, existing -> if (i == index) existing.copy(outputY = value) else existing })) }, Modifier.weight(1f))
                    IconButton(onClick = { viewModel.updateLut(lut.copy(controlPoints = lut.controlPoints.filterIndexed { i, _ -> i != index })) }, enabled = lut.controlPoints.size > 2, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Delete, "Remove curve point", tint = AresError, modifier = Modifier.size(13.dp))
                    }
                }
            }
            OutlinedButton(onClick = {
                val last = lut.controlPoints.lastOrNull() ?: LutControlPoint(0.0, 0.0)
                viewModel.updateLut(lut.copy(controlPoints = lut.controlPoints + LutControlPoint(last.inputX + 1.0, last.outputY)))
            }, modifier = Modifier.fillMaxWidth()) { Text("+ Add curve point") }
        }
    }
}

@Composable
private fun HealthFallbackEditor(state: SuperstructureStudioState, draft: SuperstructureDocument, viewModel: SuperstructureStudioViewModel) {
    Surface(Modifier.fillMaxWidth(), color = AresSurface, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, AresBorder)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("SENSOR-HEALTH FALLBACKS", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("Move to a reviewed safe posture if a cached input is stale, invalid, or not control-ready.", color = AresTextTertiary, fontSize = 10.sp)
                }
                val available = state.sourceFields.filter { option -> draft.healthFallbacks.none { it.source == option.reference } }
                if (available.isNotEmpty()) {
                    StudioDropdown(
                        "+ Add monitored input",
                        available.map { it.reference.subsystemUid + ":" + it.reference.fieldUid to it.label },
                        { packed -> viewModel.addHealthFallback(available.first { packed == it.reference.subsystemUid + ":" + it.reference.fieldUid }) },
                        Modifier.width(220.dp),
                    )
                }
            }
            draft.healthFallbacks.forEach { policy ->
                Surface(color = AresSurfaceElevated, shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, AresBorder)) {
                    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(state.sourceFields.firstOrNull { it.reference == policy.source }?.label ?: "${policy.source.subsystemUid}.${policy.source.fieldUid}", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Text("Latch until legal recovery", color = AresTextSecondary, fontSize = 10.sp)
                            Switch(policy.latchFault, { viewModel.updateHealthFallback(policy.copy(latchFault = it)) })
                            IconButton(onClick = { viewModel.removeHealthFallback(policy.policyId) }) { Icon(Icons.Default.Delete, "Delete health fallback", tint = AresError) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StudioDropdown("Safe posture: ${policy.fallbackStateId}", draft.states.map { it.stateId to it.displayName }, { viewModel.updateHealthFallback(policy.copy(fallbackStateId = it)) }, Modifier.weight(1f))
                            OutlinedTextField(policy.description, { viewModel.updateHealthFallback(policy.copy(description = it)) }, label = { Text("Student-readable reason") }, modifier = Modifier.weight(2f), singleLine = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactDecimalField(label: String, value: Double, onValue: (Double) -> Unit, modifier: Modifier = Modifier) {
    AresDoubleField(label, value, modifier, onValueChange = onValue)
}

@Composable
private fun NullableDecimalField(label: String, value: Double?, onValue: (Double?) -> Unit, modifier: Modifier = Modifier) {
    AresNullableDoubleField(label, value, modifier, onValueChange = onValue)
}

@Composable
private fun CompactIntField(label: String, value: Int, onValue: (Int) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(text, { next -> text = next; next.toIntOrNull()?.let(onValue) }, label = { Text(label) }, isError = text.toIntOrNull() == null, modifier = modifier, singleLine = true)
}

@Composable
private fun CompactLongField(label: String, value: Long, onValue: (Long) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(text, { next -> text = next; next.toLongOrNull()?.let(onValue) }, label = { Text(label) }, isError = text.toLongOrNull() == null, modifier = modifier, singleLine = true)
}

@Composable
private fun NullableLongField(label: String, value: Long?, onValue: (Long?) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(text, { next -> text = next; if (next.isBlank()) onValue(null) else next.toLongOrNull()?.let { onValue(it) } }, label = { Text(label) }, isError = text.isNotBlank() && text.toLongOrNull() == null, modifier = modifier, singleLine = true)
}
