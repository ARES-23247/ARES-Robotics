package com.ares.analytics.ui.components.controls

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.components.core.AresDoubleField
import com.ares.analytics.ui.components.core.AresNullableDoubleField
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.viewmodel.controls.ControlsEditorState
import com.ares.analytics.viewmodel.controls.ControlsEditorViewModel
import com.ares.analytics.viewmodel.controls.momentaryOutputParameter
import com.areslib.catalog.CapabilityParameterDescriptor
import com.areslib.catalog.CapabilityParameterType
import com.areslib.controls.ControlBindingDocument
import com.areslib.controls.ControlEvent
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControlTargetKind
import com.areslib.controls.ControlThresholdDirection
import com.areslib.controls.ControllerControlTypeDocument
import com.areslib.controls.RoutineInvocationPolicy

@Composable
internal fun ControlsBindingInspector(
    state: ControlsEditorState,
    viewModel: ControlsEditorViewModel,
    binding: ControlBindingDocument,
) {
    var advancedExpanded by remember(binding.bindingId) {
        mutableStateOf(hasAdvancedBindingSettings(binding))
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = binding.displayName,
            onValueChange = { value -> viewModel.updateDraft { it.copy(displayName = value) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Binding name") },
            singleLine = true,
        )
        val sourceKinds = if (state.selectedControl?.type == ControllerControlTypeDocument.AXIS) {
            listOf(ControlSourceKind.AXIS_THRESHOLD, ControlSourceKind.AXIS_VALUE, ControlSourceKind.AXIS_ZONE)
        } else {
            listOf(ControlSourceKind.BUTTON, ControlSourceKind.CHORD)
        }.let { allowed -> (allowed + binding.source.kind).distinct() }
        SelectionMenu(
            "Input type", binding.source.kind.friendlyName(),
            sourceKinds.map { it.name to it.friendlyName() },
            Modifier.fillMaxWidth(),
        ) { viewModel.setSourceKind(ControlSourceKind.valueOf(it)) }
        if (binding.source.kind == ControlSourceKind.CHORD) {
            Text("Chord: ${binding.source.controlIds.joinToString(" + ").ifBlank { "select two controls" }}", color = AresGold, fontSize = 11.sp)
            Text("Click controls on the diagram to add or remove chord members.", color = AresTextSecondary, fontSize = 10.sp)
            NumberEditor("Chord window (s)", binding.source.chordWindowSeconds) { value ->
                viewModel.updateDraft { it.copy(source = it.source.copy(chordWindowSeconds = value)) }
            }
        }
        AnalogSourceFields(binding, viewModel)
        val events = allowedEvents(binding.source.kind)
        SelectionMenu(
            "Event", binding.event.friendlyName(), events.map { it.name to it.friendlyName() }, Modifier.fillMaxWidth(),
        ) { selected -> viewModel.updateDraft { it.copy(event = ControlEvent.valueOf(selected)) } }
        TargetFields(state, binding, viewModel)
        val momentaryAction = state.selectedAction
        val momentaryParameter = momentaryAction?.let(::momentaryOutputParameter)
        if (momentaryAction != null && momentaryParameter != null && binding.source.kind == ControlSourceKind.BUTTON) {
            Surface(
                color = AresCyan.copy(alpha = 0.08f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.45f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Safe momentary motor output", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text(
                        "A motor keeps its last requested output after a button is released. Use Held to run and Release at 0 ${momentaryParameter.unit.orEmpty().ifBlank { "output" }} to stop.",
                        color = AresTextSecondary,
                        fontSize = 10.sp,
                    )
                    if (state.selectedBindingId == null) {
                        Button(onClick = viewModel::addSafeMomentaryPair, modifier = Modifier.fillMaxWidth()) {
                            Text("Add safe hold + release pair")
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(binding.enabled, { value -> viewModel.updateDraft { it.copy(enabled = value) } })
                Text(" Enabled", color = AresTextPrimary, fontSize = 11.sp)
            }
        }
        HorizontalDivider(color = AresBorder)
        Row(
            Modifier.fillMaxWidth().clickable { advancedExpanded = !advancedExpanded }.padding(vertical = 2.dp),
            Arrangement.SpaceBetween,
            Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Advanced timing & safety", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    advancedBindingSummary(binding),
                    color = if (hasAdvancedBindingSettings(binding)) AresGold else AresTextSecondary,
                    fontSize = 10.sp,
                )
            }
            Icon(
                if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                if (advancedExpanded) "Hide advanced settings" else "Show advanced settings",
                tint = AresTextSecondary,
            )
        }
        if (advancedExpanded) {
            TimingFields(binding, viewModel)
            if (binding.source.kind == ControlSourceKind.CHORD) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(binding.suppressConstituentBindings, { value ->
                        viewModel.updateDraft { it.copy(suppressConstituentBindings = value) }
                    })
                    Text(" Suppress individual chord-button actions", color = AresTextPrimary, fontSize = 11.sp)
                }
                Text(
                    "Recommended for chords so one press does not trigger both the chord and its individual buttons.",
                    color = AresTextSecondary,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun AnalogSourceFields(binding: ControlBindingDocument, viewModel: ControlsEditorViewModel) {
    when (binding.source.kind) {
        ControlSourceKind.AXIS_THRESHOLD -> {
            NumberEditor("Press threshold", binding.source.pressThreshold ?: .65) { value ->
                viewModel.updateDraft { it.copy(source = it.source.copy(pressThreshold = value)) }
            }
            NumberEditor("Release threshold", binding.source.releaseThreshold ?: .50) { value ->
                viewModel.updateDraft { it.copy(source = it.source.copy(releaseThreshold = value)) }
            }
            SelectionMenu(
                "Direction", binding.source.thresholdDirection.name.lowercase(),
                ControlThresholdDirection.entries.map { it.name to it.name.lowercase() }, Modifier.fillMaxWidth(),
            ) { selected ->
                viewModel.updateDraft { it.copy(source = it.source.copy(thresholdDirection = ControlThresholdDirection.valueOf(selected))) }
            }
        }
        ControlSourceKind.AXIS_ZONE -> {
            NumberEditor("Zone minimum", binding.source.zoneMinimum ?: -.25) { value ->
                viewModel.updateDraft { it.copy(source = it.source.copy(zoneMinimum = value)) }
            }
            NumberEditor("Zone maximum", binding.source.zoneMaximum ?: .25) { value ->
                viewModel.updateDraft { it.copy(source = it.source.copy(zoneMaximum = value)) }
            }
            NumberEditor("Zone hysteresis", binding.source.zoneHysteresis) { value ->
                viewModel.updateDraft { it.copy(source = it.source.copy(zoneHysteresis = value)) }
            }
        }
        ControlSourceKind.AXIS_VALUE -> {
            val policy = binding.analogPolicy ?: return
            NumberEditor("Change epsilon", policy.changeEpsilon) { value ->
                viewModel.updateDraft { it.copy(analogPolicy = it.analogPolicy?.copy(changeEpsilon = value)) }
            }
            NumberEditor("Re-arm neutral", policy.rearmNeutralThreshold) { value ->
                viewModel.updateDraft { it.copy(analogPolicy = it.analogPolicy?.copy(rearmNeutralThreshold = value)) }
            }
        }
        else -> Unit
    }
}

@Composable
private fun TimingFields(binding: ControlBindingDocument, viewModel: ControlsEditorViewModel) {
    NumberEditor("Press debounce (s)", binding.timing.pressDebounceSeconds) { value ->
        viewModel.updateDraft { it.copy(timing = it.timing.copy(pressDebounceSeconds = value)) }
    }
    NumberEditor("Release debounce (s)", binding.timing.releaseDebounceSeconds) { value ->
        viewModel.updateDraft { it.copy(timing = it.timing.copy(releaseDebounceSeconds = value)) }
    }
    NullableNumberEditor("Hold after (s)", binding.timing.holdAfterSeconds) { value ->
        viewModel.updateDraft { it.copy(timing = it.timing.copy(holdAfterSeconds = value)) }
    }
    NullableNumberEditor("Repeat after (s)", binding.timing.repeatAfterSeconds) { value ->
        viewModel.updateDraft { it.copy(timing = it.timing.copy(repeatAfterSeconds = value)) }
    }
    NullableNumberEditor("Repeat every (s)", binding.timing.repeatEverySeconds) { value ->
        viewModel.updateDraft { it.copy(timing = it.timing.copy(repeatEverySeconds = value)) }
    }
    NumberEditor("Cooldown (s)", binding.timing.cooldownSeconds) { value ->
        viewModel.updateDraft { it.copy(timing = it.timing.copy(cooldownSeconds = value)) }
    }
    NullableNumberEditor("Max active (s)", binding.timing.maximumActiveSeconds) { value ->
        viewModel.updateDraft { it.copy(timing = it.timing.copy(maximumActiveSeconds = value)) }
    }
}

@Composable
private fun TargetFields(state: ControlsEditorState, binding: ControlBindingDocument, viewModel: ControlsEditorViewModel) {
    HorizontalDivider(color = AresBorder)
    Text("Target", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    SelectionMenu(
        "Target type", binding.target.kind.friendlyName(),
        ControlTargetKind.entries.map { it.name to it.friendlyName() }, Modifier.fillMaxWidth()
    ) { selected ->
        val kind = ControlTargetKind.valueOf(selected)
        val key = when (kind) {
            ControlTargetKind.ACTION -> state.actions.firstOrNull()?.key.orEmpty()
            ControlTargetKind.DRIVE -> com.areslib.controls.DriveAxisKeys.VX
            else -> state.routineIds.firstOrNull().orEmpty()
        }
        viewModel.setTarget(kind, key)
    }
    when (binding.target.kind) {
        ControlTargetKind.ACTION -> {
            ActionPicker(state, binding.target.key) { viewModel.setTarget(ControlTargetKind.ACTION, it) }
            state.selectedAction?.parameters.orEmpty().forEach { parameter ->
                TargetArgumentField(parameter, binding.target.arguments[parameter.key].orEmpty()) { value ->
                    viewModel.setTargetArgument(parameter.key, value)
                }
            }
        }
        ControlTargetKind.DRIVE -> {
            SelectionMenu(
                "Drivetrain axis", binding.target.key,
                com.areslib.controls.DriveAxisKeys.ALL.sorted().map { it to driveAxisLabel(it) },
                Modifier.fillMaxWidth()
            ) { viewModel.setTarget(ControlTargetKind.DRIVE, it) }
            Text(
                "Drive bindings must use an analog stick axis with a Value event; the generated " +
                    "runtime shapes each axis and the robot applies alliance mirroring.",
                color = AresTextSecondary, fontSize = 10.sp
            )
        }
        else -> {
            SelectionMenu(
                "Reusable routine", binding.target.key.ifBlank { "Choose routine" },
                state.routineIds.map { it to it }, Modifier.fillMaxWidth()
            ) { viewModel.setTarget(binding.target.kind, it) }
            SelectionMenu(
                "Invocation", binding.target.routinePolicy.friendlyName(),
                RoutineInvocationPolicy.entries.map { it.name to it.friendlyName() }, Modifier.fillMaxWidth()
            ) { selected ->
                viewModel.updateDraft { it.copy(target = it.target.copy(routinePolicy = RoutineInvocationPolicy.valueOf(selected))) }
            }
        }
    }
}

@Composable
private fun ActionPicker(state: ControlsEditorState, selectedKey: String, onSelect: (String) -> Unit) {
    val selected = state.actions.firstOrNull { it.key == selectedKey }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val groups = actionBrowserGroups(state.actions, query)
    val matchCount = groups.sumOf { it.actions.size }

    fun openBrowser() {
        query = ""
        expanded = true
    }

    LaunchedEffect(expanded) {
        if (expanded) searchFocus.requestFocus()
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${actionCatalogSummary(state.actions)} • .ares/action-catalog.json",
            color = if (state.actions.isEmpty()) AresGold else AresTextSecondary,
            fontSize = 10.sp,
            modifier = Modifier.semantics {
                contentDescription = if (state.actions.isEmpty()) {
                    "No project actions loaded from the action catalog"
                } else {
                    "${actionCatalogSummary(state.actions)} loaded from the project action catalog"
                }
            }
        )
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = ::openBrowser,
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = if (selected == null) {
                        "Choose a project action"
                    } else {
                        "Selected action. ${actionAccessibleLabel(selected)}. Open action browser"
                    }
                }
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Catalog action", color = AresTextSecondary, fontSize = 9.sp)
                    Text(
                        selected?.displayName ?: selectedKey.ifBlank { "Choose an action" },
                        color = AresTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                    selected?.let {
                        Text(
                            "${it.category.ifBlank { "General" }} • ${it.key}",
                            color = AresTextSecondary,
                            fontSize = 9.sp,
                            maxLines = 1
                        )
                    }
                }
                Icon(Icons.Default.ArrowDropDown, "Browse all project actions")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 380.dp, max = 520.dp)
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("Choose an action", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        "All ${state.actions.size} project actions are shown until you search.",
                        color = AresTextSecondary,
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                        label = { Text("Search actions") },
                        placeholder = { Text("Try LED, light, color, or Prism") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        supportingText = {
                            Text(
                                if (query.isBlank()) "$matchCount actions available" else "$matchCount matching actions",
                                fontSize = 9.sp
                            )
                        },
                        singleLine = true
                    )
                }
                if (groups.isEmpty()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            if (state.actions.isEmpty()) "No project actions were loaded." else "No actions match “$query”.",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp
                        )
                        Text(
                            if (state.actions.isEmpty()) {
                                "Check .ares/action-catalog.json, then use Reload at the top of the editor."
                            } else {
                                "Clear the search or try a device, behavior, LED, light, color, or Prism."
                            },
                            color = AresTextSecondary,
                            fontSize = 10.sp
                        )
                        if (query.isNotBlank()) {
                            OutlinedButton(onClick = { query = "" }) { Text("Clear search", fontSize = 10.sp) }
                        }
                    }
                }
                groups.forEachIndexed { index, group ->
                    if (index > 0) HorizontalDivider(color = AresBorder)
                    Text(
                        "${group.category} (${group.actions.size})",
                        color = AresCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
                    )
                    group.actions.forEach { action ->
                        DropdownMenuItem(
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(action.displayName, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                                    if (!action.description.isNullOrBlank()) {
                                        Text(action.description, color = AresTextSecondary, fontSize = 9.sp)
                                    }
                                    Text(
                                        "Project catalog • ${action.key}",
                                        color = AresTextSecondary,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            },
                            onClick = {
                                query = ""
                                expanded = false
                                onSelect(action.key)
                            },
                            modifier = Modifier.semantics {
                                contentDescription = actionAccessibleLabel(action)
                            }
                        )
                    }
                }
            }
        }
        if (state.actions.isEmpty()) {
            Text(
                "No actions are available. Check .ares/action-catalog.json, then select Reload.",
                color = AresGold,
                fontSize = 10.sp
            )
        } else if (selected == null && selectedKey.isNotBlank()) {
            Text(
                "This binding references an action that is not in the current catalog: $selectedKey",
                color = AresGold,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun TargetArgumentField(parameter: CapabilityParameterDescriptor, value: String, onValue: (String) -> Unit) {
    if (parameter.type == CapabilityParameterType.ENUM || parameter.type == CapabilityParameterType.BOOLEAN) {
        val choices = if (parameter.type == CapabilityParameterType.BOOLEAN) listOf("true", "false") else parameter.options
        SelectionMenu(
            parameter.displayName,
            value.ifBlank { "Choose" },
            choices.map { it to it },
            Modifier.fillMaxWidth(),
            onValue
        )
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(parameter.displayName + parameter.unit?.let { " ($it)" }.orEmpty()) },
            supportingText = { Text(parameter.description, fontSize = 9.sp) },
            singleLine = true
        )
    }
}

@Composable
private fun NumberEditor(label: String, value: Double, onValue: (Double) -> Unit) =
    AresDoubleField(label, value, Modifier.fillMaxWidth(), onValueChange = onValue)

@Composable
private fun NullableNumberEditor(label: String, value: Double?, onValue: (Double?) -> Unit) {
    AresNullableDoubleField(label, value, Modifier.fillMaxWidth(), onValueChange = onValue)
}

private fun ControlSourceKind.friendlyName() = when (this) {
    ControlSourceKind.BUTTON -> "Button"
    ControlSourceKind.CHORD -> "Chord"
    ControlSourceKind.AXIS_THRESHOLD -> "Analog threshold"
    ControlSourceKind.AXIS_VALUE -> "Continuous analog"
    ControlSourceKind.AXIS_ZONE -> "Analog zone"
}

private fun ControlEvent.friendlyName() = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
private fun ControlTargetKind.friendlyName() = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

private fun driveAxisLabel(axis: String): String = when (axis) {
    com.areslib.controls.DriveAxisKeys.VX -> "vx — forward/back"
    com.areslib.controls.DriveAxisKeys.VY -> "vy — strafe left/right"
    com.areslib.controls.DriveAxisKeys.OMEGA -> "omega — rotate"
    else -> axis
}
private fun RoutineInvocationPolicy.friendlyName() = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

private fun allowedEvents(kind: ControlSourceKind): List<ControlEvent> = when (kind) {
    ControlSourceKind.BUTTON, ControlSourceKind.CHORD, ControlSourceKind.AXIS_THRESHOLD ->
        listOf(ControlEvent.PRESS, ControlEvent.RELEASE, ControlEvent.HELD, ControlEvent.HOLD, ControlEvent.REPEAT)
    ControlSourceKind.AXIS_VALUE -> listOf(ControlEvent.VALUE)
    ControlSourceKind.AXIS_ZONE -> listOf(ControlEvent.ZONE_ENTER, ControlEvent.ZONE_ACTIVE, ControlEvent.ZONE_EXIT)
}
