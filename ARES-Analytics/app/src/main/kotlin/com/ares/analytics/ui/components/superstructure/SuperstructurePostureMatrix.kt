package com.ares.analytics.ui.components.superstructure

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.areslib.subsystem.SubsystemValueType
import com.areslib.superstructure.*
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.components.core.AresNullableDoubleField
import com.ares.analytics.viewmodel.superstructure.*

@Composable
fun SuperstructurePostureMatrix(
    state: SuperstructureStudioState,
    draft: SuperstructureDocument,
    viewModel: SuperstructureStudioViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Coordinator Overview Header Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AresSurface,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("COORDINATOR IDENTITY & DEFAULT POSTURES", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft.displayName,
                        onValueChange = { viewModel.updateMetadata(it, draft.description) },
                        label = { Text("Coordinator Name") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    StudioDropdown(
                        label = "Initial: ${draft.states.firstOrNull { it.stateId == draft.initialStateId }?.displayName ?: draft.initialStateId}",
                        options = draft.states.map { it.stateId to it.displayName },
                        onSelect = viewModel::setInitialState,
                        modifier = Modifier.weight(1f),
                    )
                    StudioDropdown(
                        label = "Fault: ${draft.states.firstOrNull { it.stateId == draft.faultStateId }?.displayName ?: draft.faultStateId}",
                        options = draft.states.map { it.stateId to it.displayName },
                        onSelect = viewModel::setFaultState,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StudioDropdown(
                        label = "Disabled: ${draft.states.firstOrNull { it.stateId == draft.disabledStateId }?.displayName ?: draft.disabledStateId}",
                        options = draft.states.map { it.stateId to it.displayName },
                        onSelect = viewModel::setDisabledState,
                        modifier = Modifier.weight(1f),
                    )
                    StudioDropdown(
                        label = "When disabled: ${draft.disabledPolicy.name.lowercase().replace('_', ' ')}",
                        options = SuperstructureDisabledPolicy.entries.map { it.name to it.name.lowercase().replace('_', ' ') },
                        onSelect = { viewModel.setDisabledPolicy(SuperstructureDisabledPolicy.valueOf(it)) },
                        modifier = Modifier.weight(2f),
                    )
                }
            }
        }

        // Posture Presets Matrix
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AresSurface,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("SYNCHRONIZED POSTURES & TARGET SETPOINTS", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("Each posture defines immutable targets across all mechanism subsystems.", color = AresTextTertiary, fontSize = 10.sp)
                    }
                    var addOpen by remember { mutableStateOf(false) }
                    var newId by remember { mutableStateOf("") }
                    var newName by remember { mutableStateOf("") }
                    if (!addOpen) {
                        Button(
                            onClick = { addOpen = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add Posture", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newId,
                                onValueChange = { newId = it },
                                label = { Text("ID (e.g. score_high)", fontSize = 10.sp) },
                                modifier = Modifier.width(140.dp).height(48.dp),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = newName,
                                onValueChange = { newName = it },
                                label = { Text("Name (e.g. Score High)", fontSize = 10.sp) },
                                modifier = Modifier.width(160.dp).height(48.dp),
                                singleLine = true,
                            )
                            Button(
                                onClick = {
                                    if (newId.isNotBlank()) {
                                        viewModel.addState(newId.trim(), newName.trim())
                                        newId = ""
                                        newName = ""
                                        addOpen = false
                                    }
                                },
                                enabled = newId.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = AresGreen, contentColor = AresOnAccent),
                                modifier = Modifier.height(36.dp),
                            ) {
                                Text("Create", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(onClick = { addOpen = false }, modifier = Modifier.height(36.dp)) {
                                Text("Cancel", fontSize = 11.sp)
                            }
                        }
                    }
                }

                // Posture Preset Cards List
                draft.states.forEach { preset ->
                    val isSelected = preset.stateId == state.selectedStateId
                    val isInitial = preset.stateId == draft.initialStateId
                    val isFault = preset.stateId == draft.faultStateId

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) AresCyan else AresBorder,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { viewModel.selectState(preset.stateId) },
                        color = if (isSelected) AresCyan.copy(alpha = 0.08f) else AresSurfaceElevated,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        preset.displayName.ifBlank { preset.stateId },
                                        color = AresTextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                    )
                                    Text(
                                        "id: ${preset.stateId}",
                                        color = AresCyan,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                    )
                                    if (isInitial) {
                                        Surface(color = AresGreen.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                                            Text("INITIAL", color = AresGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                        }
                                    }
                                    if (isFault) {
                                        Surface(color = AresError.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                                            Text("FAULT", color = AresError, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                        }
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${preset.subsystemTargets.size} targets",
                                        color = AresTextSecondary,
                                        fontSize = 10.sp,
                                    )
                                    if (!isInitial && !isFault && draft.states.size > 2) {
                                        IconButton(
                                            onClick = {
                                                viewModel.selectState(preset.stateId)
                                                viewModel.removeSelectedState()
                                            },
                                            modifier = Modifier.size(24.dp),
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = AresError, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }

                            // Subsystem Setpoints inline badges
                            if (preset.subsystemTargets.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    preset.subsystemTargets.take(6).forEach { target ->
                                        val label = state.targetFields.firstOrNull { it.reference == target.target }?.label ?: target.target.fieldUid
                                        val valStr = target.constantDoubleValue?.toString()
                                            ?: target.constantBooleanValue?.toString()
                                            ?: target.constantStringValue
                                            ?: target.lutId
                                            ?: "-"
                                        Surface(
                                            color = AresSurface,
                                            shape = RoundedCornerShape(4.dp),
                                            border = BorderStroke(1.dp, AresBorder),
                                        ) {
                                            Text(
                                                "$label: $valStr",
                                                color = AresTextSecondary,
                                                fontSize = 10.sp,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        state.selectedStateId
            ?.let { id -> draft.states.firstOrNull { it.stateId == id } }
            ?.let { selected -> SelectedPostureEditor(state, draft, selected, viewModel) }
    }
}

@Composable
private fun SelectedPostureEditor(
    state: SuperstructureStudioState,
    draft: SuperstructureDocument,
    selected: SuperstructureStatePreset,
    viewModel: SuperstructureStudioViewModel,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AresSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, AresBorder),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("EDIT ${selected.displayName.uppercase()}", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = selected.displayName,
                    onValueChange = { viewModel.updateSelectedStateDetails(it, selected.description) },
                    label = { Text("Posture name") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = selected.description,
                    onValueChange = { viewModel.updateSelectedStateDetails(selected.displayName, it) },
                    label = { Text("What this posture is for") },
                    modifier = Modifier.weight(2f),
                    singleLine = true,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                DecimalEditor(
                    label = "Leave after (seconds, optional)",
                    value = selected.timeoutSeconds,
                    onValue = { viewModel.updateSelectedStateTimeout(it, selected.timeoutTargetStateId) },
                    modifier = Modifier.weight(1f),
                )
                StudioDropdown(
                    label = "Timeout destination: ${selected.timeoutTargetStateId ?: "None"}",
                    options = listOf("" to "None") + draft.states.map { it.stateId to it.displayName },
                    onSelect = { viewModel.updateSelectedStateTimeout(selected.timeoutSeconds, it.ifBlank { null }) },
                    modifier = Modifier.weight(1f),
                )
            }

            LifecycleActionsEditor("Run once when entering", true, selected.onEntryActionKeys, state, viewModel)
            LifecycleActionsEditor("Run once when leaving", false, selected.onExitActionKeys, state, viewModel)

            HorizontalDivider(color = AresBorder)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("MECHANISM TARGETS", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("Choose what every subsystem should do in this posture.", color = AresTextTertiary, fontSize = 10.sp)
                }
                val missing = state.targetFields.filter { candidate -> selected.subsystemTargets.none { it.target == candidate.reference } }
                if (missing.isNotEmpty()) {
                    StudioDropdown(
                        label = "+ Add target",
                        options = missing.map { it.reference.subsystemUid + ":" + it.reference.fieldUid to it.label },
                        onSelect = { packed ->
                            val option = missing.first { packed == it.reference.subsystemUid + ":" + it.reference.fieldUid }
                            viewModel.addTarget(option.reference)
                        },
                        modifier = Modifier.width(220.dp),
                        openAbove = true,
                    )
                }
            }
            selected.subsystemTargets.forEach { target ->
                val field = state.targetFields.firstOrNull { it.reference == target.target }
                TargetEditor(target, field, draft, state, viewModel)
            }
        }
    }
}

@Composable
private fun LifecycleActionsEditor(
    title: String,
    onEntry: Boolean,
    selectedKeys: List<String>,
    state: SuperstructureStudioState,
    viewModel: SuperstructureStudioViewModel,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = AresTextPrimary, fontSize = 11.sp, modifier = Modifier.width(160.dp))
        selectedKeys.forEach { key ->
            AssistChip(
                onClick = { viewModel.removeSelectedStateLifecycleAction(onEntry, key) },
                label = { Text("$key  ×", fontSize = 10.sp) },
            )
        }
        val available = state.parameterlessActions.filter { it.key !in selectedKeys }
        if (available.isNotEmpty()) {
            StudioDropdown(
                label = "+ Named action",
                options = available.map { it.key to it.displayName },
                onSelect = { viewModel.addSelectedStateLifecycleAction(onEntry, it) },
                modifier = Modifier.width(200.dp),
            )
        }
    }
}

@Composable
private fun TargetEditor(
    target: SuperstructureSubsystemTarget,
    field: SuperstructureFieldOption?,
    draft: SuperstructureDocument,
    state: SuperstructureStudioState,
    viewModel: SuperstructureStudioViewModel,
) {
    Surface(color = AresSurfaceElevated, shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, AresBorder)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(field?.label ?: "${target.target.subsystemUid}.${target.target.fieldUid}", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.weight(1f))
                StudioDropdown(
                    label = target.targetMode.name.lowercase().replace('_', ' '),
                    options = SuperstructureTargetMode.entries.map { it.name to it.name.lowercase().replace('_', ' ') },
                    onSelect = { mode ->
                        val selectedMode = SuperstructureTargetMode.valueOf(mode)
                        viewModel.updateSelectedTarget(
                            target.copy(
                                targetMode = selectedMode,
                                lutId = if (selectedMode == SuperstructureTargetMode.DYNAMIC_LUT) draft.luts.firstOrNull()?.lutId else null,
                                source = if (selectedMode != SuperstructureTargetMode.CONSTANT) state.sourceFields.firstOrNull()?.reference else null,
                            )
                        )
                    },
                    modifier = Modifier.width(150.dp),
                )
                IconButton(onClick = { viewModel.removeTarget(target.target) }) {
                    Icon(Icons.Default.Delete, "Remove target", tint = AresError)
                }
            }
            when (target.targetMode) {
                SuperstructureTargetMode.CONSTANT -> when (field?.field?.type) {
                    SubsystemValueType.BOOLEAN -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = target.constantBooleanValue ?: false,
                            onCheckedChange = { viewModel.updateSelectedTarget(target.copy(constantBooleanValue = it)) },
                        )
                        Spacer(Modifier.width(8.dp)); Text(if (target.constantBooleanValue == true) "On" else "Off", color = AresTextSecondary)
                    }
                    SubsystemValueType.STRING -> OutlinedTextField(
                        value = target.constantStringValue.orEmpty(),
                        onValueChange = { viewModel.updateSelectedTarget(target.copy(constantStringValue = it)) },
                        label = { Text("Target value") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    else -> DecimalEditor(
                        label = "Target value${field?.field?.unit?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}",
                        value = target.constantDoubleValue,
                        onValue = { viewModel.updateSelectedTarget(target.copy(constantDoubleValue = it)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SuperstructureTargetMode.DYNAMIC_LUT -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudioDropdown("Curve: ${target.lutId ?: "Choose"}", draft.luts.map { it.lutId to it.displayName }, { viewModel.updateSelectedTarget(target.copy(lutId = it)) }, Modifier.weight(1f))
                    SourceFieldDropdown(target.source, state.sourceFields, { viewModel.updateSelectedTarget(target.copy(source = it)) }, Modifier.weight(1f))
                }
                SuperstructureTargetMode.PASS_THROUGH -> SourceFieldDropdown(target.source, state.sourceFields, { viewModel.updateSelectedTarget(target.copy(source = it)) }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SourceFieldDropdown(
    selected: SuperstructureFieldReference?,
    fields: List<SuperstructureFieldOption>,
    onSelect: (SuperstructureFieldReference) -> Unit,
    modifier: Modifier,
) {
    StudioDropdown(
        label = "Source: ${fields.firstOrNull { it.reference == selected }?.label ?: "Choose"}",
        options = fields.map { it.reference.subsystemUid + ":" + it.reference.fieldUid to it.label },
        onSelect = { packed -> onSelect(fields.first { packed == it.reference.subsystemUid + ":" + it.reference.fieldUid }.reference) },
        modifier = modifier,
    )
}

@Composable
private fun DecimalEditor(label: String, value: Double?, onValue: (Double?) -> Unit, modifier: Modifier = Modifier) {
    AresNullableDoubleField(label, value, modifier, onValueChange = onValue)
}

@Composable
fun StudioDropdown(
    label: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    openAbove: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(6.dp),
        ) {
            Text(label, color = AresTextPrimary, fontSize = 11.sp, maxLines = 1)
        }
        val verticalOffset = if (openAbove) {
            -(48 * (options.size.coerceAtMost(6) + 1)).dp
        } else {
            0.dp
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, verticalOffset),
        ) {
            options.forEach { (key, display) ->
                DropdownMenuItem(
                    text = { Text(display, fontSize = 11.sp) },
                    onClick = {
                        expanded = false
                        onSelect(key)
                    }
                )
            }
        }
    }
}
