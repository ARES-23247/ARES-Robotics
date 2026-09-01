package com.ares.analytics.ui.components.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.AresGenerationPhase
import com.ares.analytics.service.GamepadState
import com.ares.analytics.ui.components.core.AresInspectorDrawer
import com.ares.analytics.ui.components.core.AresSpecRow
import com.ares.analytics.ui.components.core.AresSpecSection
import com.ares.analytics.ui.components.core.AresSpecSummaryModal
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresRed
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.viewmodel.controls.ControlsEditorState
import com.ares.analytics.viewmodel.controls.ControlsEditorViewModel
import com.ares.analytics.viewmodel.controls.ControlsProblemSeverity
import com.ares.analytics.viewmodel.controls.momentaryOutputParameter
import com.areslib.catalog.CapabilityParameterDescriptor
import com.areslib.catalog.CapabilityParameterType
import com.areslib.controls.ControlBindingDocument
import com.areslib.controls.ControlEvent
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControlTargetKind
import com.areslib.controls.ControlThresholdDirection
import com.areslib.controls.ControlTimingDocument
import com.areslib.controls.ControllerControlDocument
import com.areslib.controls.ControllerControlTypeDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerSurfaceDocument
import com.areslib.controls.RoutineInvocationPolicy

@Composable
fun ControlsEditorPanel(
    state: ControlsEditorState,
    viewModel: ControlsEditorViewModel,
    gamepad1State: GamepadState,
    gamepad2State: GamepadState,
    modifier: Modifier = Modifier,
) {
    val liveState = if (state.selectedControllerSlot == "operator") gamepad2State else gamepad1State
    var showKeymapSummary by remember { mutableStateOf(false) }
    var showAiAssistantDrawer by remember { mutableStateOf(false) }

    state.aiProposal?.let { review ->
        AlertDialog(
            onDismissRequest = viewModel::dismissAiProposal,
            title = { Text("Review Gemini's binding proposal") },
            text = {
                Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(review.proposal.summary, color = AresTextPrimary, fontWeight = FontWeight.Bold)
                    review.proposal.explanations.forEach { Text("• $it", color = AresTextSecondary, fontSize = 11.sp) }
                    HorizontalDivider(color = AresBorder)
                    review.changes.forEach { Text(it, color = AresTextPrimary, fontSize = 11.sp) }
                    review.problems.forEach { ProblemBanner(it.message, it.severity) }
                }
            },
            confirmButton = { Button(viewModel::applyAiProposal, enabled = review.canApply) { Text("Apply to form") } },
            dismissButton = { OutlinedButton(viewModel::dismissAiProposal) { Text("Keep current bindings") } },
        )
    }
    LaunchedEffect(liveState.rawButtons, liveState.rawAxes, state.learning) {
        if (state.learning != null) viewModel.observeDesktopInput(liveState)
    }

    val boundActionLabels = remember(state.selectedScheme?.bindings) {
        val map = mutableMapOf<String, MutableList<String>>()
        state.selectedScheme?.bindings.orEmpty().forEach { binding ->
            binding.source.controlIds.forEach { controlId ->
                map.getOrPut(controlId) { mutableListOf() }.add(binding.displayName)
            }
        }
        map
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProjectHeader(
                state = state,
                viewModel = viewModel,
                onOpenKeymapSummary = { showKeymapSummary = true },
                onOpenAiAssistant = { showAiAssistantDrawer = true },
            )
            if (state.loadError != null) {
                ProblemBanner(state.loadError, ControlsProblemSeverity.ERROR)
                return@Column
            }
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    Modifier.weight(1.45f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SchemeToolbar(state, viewModel)
                    SurfaceTabs(state.surface, viewModel::showSurface)
                    state.selectedProfile?.let { profile ->
                        val isEditingChord = state.draftBinding?.source?.kind == ControlSourceKind.CHORD
                        val chord = state.draftBinding?.takeIf { isEditingChord }
                            ?.source?.controlIds.orEmpty().toSet()
                        val bound = state.selectedScheme?.bindings.orEmpty()
                            .flatMapTo(linkedSetOf()) { it.source.controlIds }
                        ControllerCanvas(
                            profile = profile,
                            surface = state.surface,
                            selectedControlId = state.selectedControlId,
                            chordControlIds = chord,
                            boundControlIds = bound,
                            targetPlatform = state.targetPlatform,
                            liveState = liveState,
                            onControlSelected = { viewModel.selectControl(it, appendToChord = isEditingChord) },
                            boundActionLabels = boundActionLabels,
                        )
                        SelectedControlCard(state, viewModel, liveState)
                        AccessibleControlList(state, viewModel, liveState)
                    }
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ControlRecipesCard(state, viewModel)
                    CapabilityCoverageCard(state, viewModel)
                    BindingList(state, viewModel)
                    BindingLearningTraceCard(state)
                    ProblemsCard(state)
                }
            }
        }

        // Slide-out Property Inspector for bindings
        state.draftBinding?.let { binding ->
            AresInspectorDrawer(
                isOpen = true,
                title = if (state.selectedBindingId == null) "New Control Binding" else "Edit Control Binding",
                categoryBadge = binding.event.name,
                stableId = binding.bindingId,
                icon = Icons.Default.Tune,
                onDismiss = viewModel::discardDraft,
                allowBackgroundInteraction = true,
                doneButtonText = if (state.selectedBindingId == null) "Add Binding" else "Apply Changes",
                onDone = viewModel::applyDraft,
                onDelete = state.selectedBindingId?.let { idToDelete ->
                    {
                        viewModel.discardDraft()
                        viewModel.deleteBinding(idToDelete)
                    }
                },
                deleteButtonText = "Delete Binding",
            ) {
                BindingInspectorBody(state, viewModel, binding)
            }
        }

        // Slide-out AI Controls Assistant Drawer
        AresInspectorDrawer(
            isOpen = showAiAssistantDrawer,
            title = "AI Controls Assistant",
            categoryBadge = "GEMINI",
            icon = Icons.Default.AutoAwesome,
            onDismiss = { showAiAssistantDrawer = false },
            width = 520.dp,
            doneButtonText = "Close",
            onDone = { showAiAssistantDrawer = false },
        ) {
            ControlsAiAssistantContent(state, viewModel)
        }

        // At-a-Glance Keymap Summary Modal
        AresSpecSummaryModal(
            isOpen = showKeymapSummary,
            title = "Controls & TeleOp Keymap Summary",
            subtitle = "Complete physical control to robot action mapping for ${state.league.name} project",
            sections = generateKeymapSpecSections(state, onEditBinding = { bindingId ->
                showKeymapSummary = false
                viewModel.editBinding(bindingId)
            }),
            onDismiss = { showKeymapSummary = false },
            rawMarkdownGenerator = { generateKeymapMarkdown(state) },
        )
    }
}

@Composable
private fun CapabilityCoverageCard(state: ControlsEditorState, viewModel: ControlsEditorViewModel) {
    val coverage = state.coverage
    var expanded by remember(state.selectedSchemeId) { mutableStateOf(coverage.missingSafetyActions.isNotEmpty()) }
    Column(cardModifier(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
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
        ControlTargetKind.ROUTINE -> "routine ${binding.target.key} · ${binding.target.routinePolicy.friendlyName()}"
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
        event = "${binding.source.kind.friendlyName()} · ${binding.event.friendlyName()}",
        target = target,
        runtimePath = runtimePath,
        hasBlockingProblem = state.problems.any {
            it.severity == ControlsProblemSeverity.ERROR && (it.bindingId == null || it.bindingId == binding.bindingId)
        },
    )
}

@Composable
private fun BindingLearningTraceCard(state: ControlsEditorState) {
    val trace = bindingLearningTrace(state) ?: return
    Column(cardModifier(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

@Composable
private fun ControlsAiAssistantContent(state: ControlsEditorState, viewModel: ControlsEditorViewModel) {
    var request by remember(state.selectedSchemeId) { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            color = AresSurface,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Describe your driver and operator control scheme.",
                    color = AresTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                Text(
                    "Gemini can suggest bindings from your project's catalog actions, routines, and gamepad inputs. Nothing is applied until you review and confirm.",
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }

        OutlinedTextField(
            value = request,
            onValueChange = { request = it.take(4_000) },
            label = { Text("What should these controls do?") },
            placeholder = { Text("e.g. Right trigger runs intake while held, left bumper raises elevator to High Basket...") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            enabled = !state.aiProposalInProgress,
        )

        Button(
            onClick = { viewModel.requestAiProposal(request) },
            enabled = request.isNotBlank() && !state.aiProposalInProgress && state.selectedScheme != null,
            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (state.aiProposalInProgress) "Preparing proposal…" else "Ask Gemini for binding suggestions")
        }

        state.aiProposalError?.let { Text(it, color = AresError, fontSize = 11.sp) }

        Surface(
            color = AresBackground.copy(alpha = 0.5f),
            shape = RoundedCornerShape(6.dp),
        ) {
            Text(
                "Configure Gemini in Profile → Gemini assistance. Review source, event, timing, target, and arguments before applying.",
                color = AresTextTertiary,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@Composable
private fun ProjectHeader(
    state: ControlsEditorState,
    viewModel: ControlsEditorViewModel,
    onOpenKeymapSummary: () -> Unit,
    onOpenAiAssistant: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Visual controls editor", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                "${state.league.name} project • ${state.projectPath} • offline authoring",
                color = AresTextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "Desktop learning writes DESKTOP_GLFW only. ${state.targetPlatform.name} mappings require separate verification.",
                color = AresGold,
                fontSize = 11.sp,
            )
            state.projectMetadata?.let { metadata ->
                Text(
                    "${metadata.coordinateConvention.name} | robot ${metadata.robotLengthMeters} x ${metadata.robotWidthMeters} m",
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onOpenAiAssistant,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AresCyan),
                border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.6f)),
            ) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp), tint = AresCyan)
                Spacer(Modifier.width(5.dp))
                Text("AI Assistant")
            }
            OutlinedButton(onClick = onOpenKeymapSummary) {
                Icon(Icons.Default.TableChart, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Keymap Summary")
            }
            OutlinedButton(onClick = viewModel::reload) {
                Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Reload")
            }
            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) {
                Icon(Icons.Default.Save, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Save")
            }
            Button(
                onClick = viewModel::saveAndGenerate,
                enabled = state.canGenerate,
                colors = ButtonDefaults.buttonColors(containerColor = AresGreen, contentColor = AresOnAccent),
            ) {
                Icon(Icons.Default.Save, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp))
                Text(if (state.generationPhase == AresGenerationPhase.RUNNING) "Generating..." else "Save & Generate")
            }
        }
    }
    state.status?.let { Text(it, color = AresTextSecondary, fontSize = 11.sp) }
    state.generationMessage?.let { message ->
        val color = when (state.generationPhase) {
            AresGenerationPhase.FAILED -> AresError
            AresGenerationPhase.SUCCEEDED -> AresGreen
            else -> AresTextSecondary
        }
        Text(message, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
    state.generatedContentHash?.let { hash ->
        Text("Generated content SHA-256: $hash", color = AresTextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SchemeToolbar(state: ControlsEditorState, viewModel: ControlsEditorViewModel) {
    Column(cardModifier(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            SelectionMenu(
                label = "Scheme",
                selected = state.selectedScheme?.name ?: "No scheme",
                choices = state.schemes.map { it.documentId to it.name },
                modifier = Modifier.weight(1f),
                onSelect = viewModel::selectScheme,
            )
            SelectionMenu(
                label = "Controller",
                selected = state.selectedController?.displayName ?: "No controller",
                choices = state.selectedScheme?.controllers.orEmpty().map { it.slot to it.displayName },
                modifier = Modifier.weight(1f),
                onSelect = viewModel::selectController,
            )
            SelectionMenu(
                label = "Profile",
                selected = state.selectedProfile?.displayName ?: "No profile",
                choices = state.profiles.map { it.documentId to it.displayName },
                modifier = Modifier.weight(1.3f),
                onSelect = viewModel::assignProfile,
            )
        }
    }
}

@Composable
private fun SurfaceTabs(surface: ControllerSurfaceDocument, onSurface: (ControllerSurfaceDocument) -> Unit) {
    PrimaryTabRow(selectedTabIndex = surface.ordinal) {
        ControllerSurfaceDocument.entries.forEach { candidate ->
            Tab(
                selected = surface == candidate,
                onClick = { onSurface(candidate) },
                text = { Text(candidate.name.lowercase().replaceFirstChar(Char::uppercase)) },
            )
        }
    }
}

@Composable
private fun SelectedControlCard(
    state: ControlsEditorState,
    viewModel: ControlsEditorViewModel,
    liveState: GamepadState,
) {
    val control = state.selectedControl ?: return
    val assignedBindings = state.selectedScheme?.bindings.orEmpty().filter { control.controlId in it.source.controlIds }
    val targetMapping = control.mappings.firstOrNull { it.platform == state.targetPlatform }
    var showHardwareSetup by remember(control.controlId) { mutableStateOf(false) }
    Column(cardModifier(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Text(control.displayName, color = AresCyan, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    if (control.isActive(liveState)) "LIVE INPUT ACTIVE" else control.type.name,
                    color = if (control.isActive(liveState)) AresCyan else AresTextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = viewModel::createBinding,
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Text(" Add action")
                }
            }
        }
        HorizontalDivider(color = AresBorder)
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    if (assignedBindings.isEmpty()) "No action assigned yet" else
                        "${assignedBindings.size} assigned action${if (assignedBindings.size == 1) "" else "s"}",
                    color = if (assignedBindings.isEmpty()) AresTextSecondary else AresGreen,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
                Text(
                    if (targetMapping == null) {
                        "${state.targetPlatform.studentLabel()} input is not configured"
                    } else {
                        "Ready for ${state.targetPlatform.studentLabel()} generated code"
                    },
                    color = if (targetMapping == null) AresGold else AresTextSecondary,
                    fontSize = 11.sp,
                )
            }
            OutlinedButton(onClick = { showHardwareSetup = !showHardwareSetup }) {
                Text(if (showHardwareSetup) "Hide hardware setup" else "Hardware setup", fontSize = 11.sp)
            }
        }
        if (assignedBindings.isNotEmpty()) {
            assignedBindings.take(3).forEach { binding ->
                Text("• ${binding.displayName}", color = AresTextPrimary, fontSize = 11.sp)
            }
        }
        if (showHardwareSetup) {
            HorizontalDivider(color = AresBorder)
            Text("Advanced hardware mapping", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(
                "Configure how this physical input maps to raw Driver Station / controller hardware.",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )
            HardwareMappingRow(control, state.targetPlatform, liveState, viewModel)
        }
    }
}

@Composable
private fun HardwareMappingRow(
    control: ControllerControlDocument,
    platform: ControllerInputPlatform,
    liveState: GamepadState,
    viewModel: ControlsEditorViewModel,
) {
    val mapping = control.mappings.firstOrNull { it.platform == platform }
    val isLearning = control.controlId == control.controlId && false // learning managed in viewmodel
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Column {
            Text(
                "${platform.studentLabel()}: ${mapping?.buttonIndex?.let { "button $it" } ?: mapping?.axisIndex?.let { "axis $it" } ?: "unmapped"}",
                color = AresTextPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { viewModel.beginDesktopLearning(liveState) }) {
                Text("Detect live press", fontSize = 11.sp)
            }
        }
    }
}

private fun ControllerInputPlatform.studentLabel() = when (this) {
    ControllerInputPlatform.FTC -> "FTC"
    ControllerInputPlatform.FRC -> "FRC"
    ControllerInputPlatform.DESKTOP_GLFW -> "Desktop simulator"
}

@Composable
private fun AccessibleControlList(
    state: ControlsEditorState,
    viewModel: ControlsEditorViewModel,
    liveState: GamepadState,
) {
    val profile = state.selectedProfile ?: return
    Column(cardModifier(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Accessible control list", color = AresTextPrimary, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = state.search,
            onValueChange = viewModel::setSearch,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Find a control or binding") },
        )
        profile.controls.filter { control ->
            state.search.isBlank() || control.displayName.contains(state.search, true) ||
                control.controlId.contains(state.search, true) ||
                state.selectedScheme?.bindings.orEmpty().any { binding ->
                    control.controlId in binding.source.controlIds && binding.displayName.contains(state.search, true)
                }
        }.forEach { control ->
            Row(
                Modifier.fillMaxWidth().clickable { viewModel.selectControl(control.controlId) }
                    .background(Color.Black.copy(alpha = .18f), RoundedCornerShape(6.dp)).padding(8.dp),
                Arrangement.SpaceBetween,
                Alignment.CenterVertically,
            ) {
                Text(control.displayName, color = if (control.isActive(liveState)) AresCyan else AresTextPrimary)
                Text(
                    "${control.surface.name.lowercase()} • ${control.type.name.lowercase()}",
                    color = AresTextSecondary,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun BindingList(state: ControlsEditorState, viewModel: ControlsEditorViewModel) {
    Column(cardModifier(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Bindings", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        val bindings = state.selectedScheme?.bindings.orEmpty()
        if (bindings.isEmpty()) {
            Text("Select a control, then add its first binding.", color = AresTextSecondary, fontSize = 11.sp)
        }
        bindings.forEach { binding ->
            val hasProblem = state.problems.any { it.bindingId == binding.bindingId }
            Row(
                Modifier.fillMaxWidth().clickable { viewModel.editBinding(binding.bindingId) }
                    .background(Color.Black.copy(alpha = .22f), RoundedCornerShape(7.dp))
                    .border(1.dp, if (hasProblem) AresGold else AresBorder, RoundedCornerShape(7.dp))
                    .padding(9.dp),
                Arrangement.SpaceBetween,
                Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(binding.displayName, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(
                        "${binding.source.controlIds.joinToString(" + ")} • ${binding.event} → ${binding.target.key}",
                        color = AresTextSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                IconButton(onClick = { viewModel.deleteBinding(binding.bindingId) }) {
                    Icon(Icons.Default.Delete, "Delete binding", tint = AresRed, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

@Composable
private fun BindingInspectorBody(
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
private fun ProblemsCard(state: ControlsEditorState) {
    if (state.problems.isEmpty()) return
    Column(cardModifier(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Validation problems", color = AresTextPrimary, fontWeight = FontWeight.Bold)
        state.problems.forEach { ProblemBanner(it.message, it.severity) }
    }
}

@Composable
private fun ProblemBanner(message: String, severity: ControlsProblemSeverity) {
    val color = when (severity) {
        ControlsProblemSeverity.ERROR -> AresError
        ControlsProblemSeverity.WARNING -> AresGold
        ControlsProblemSeverity.INFO -> AresCyan
    }
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = .12f), RoundedCornerShape(6.dp))
            .border(1.dp, color, RoundedCornerShape(6.dp)).padding(8.dp),
    ) {
        Text(message, color = color, fontSize = 11.sp)
    }
}

internal fun hasAdvancedBindingSettings(binding: ControlBindingDocument): Boolean =
    binding.timing != ControlTimingDocument() || binding.suppressConstituentBindings

internal fun advancedBindingSummary(binding: ControlBindingDocument): String {
    val active = buildList {
        if (binding.timing.pressDebounceSeconds > 0.0 || binding.timing.releaseDebounceSeconds > 0.0) add("debounce")
        if (binding.timing.holdAfterSeconds != null) add("hold")
        if (binding.timing.repeatAfterSeconds != null || binding.timing.repeatEverySeconds != null) add("repeat")
        if (binding.timing.cooldownSeconds > 0.0) add("cooldown")
        if (binding.timing.maximumActiveSeconds != null) add("maximum active time")
        if (binding.suppressConstituentBindings) add("chord suppression")
    }
    return if (active.isEmpty()) "Using safe defaults — no custom timing" else "Configured: ${active.joinToString()}"
}

@Composable
private fun SelectionMenu(
    label: String,
    selected: String,
    choices: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), enabled = choices.isNotEmpty()) {
            Column(Modifier.fillMaxWidth()) {
                Text(label, color = AresTextSecondary, fontSize = 9.sp)
                Text(selected, maxLines = 1, fontSize = 11.sp)
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { (key, display) ->
                DropdownMenuItem(
                    text = { Text(display, fontSize = 11.sp) },
                    onClick = { expanded = false; onSelect(key) },
                )
            }
        }
    }
}

@Composable
private fun NumberEditor(label: String, value: Double, onValue: (Double) -> Unit) =
    NullableNumberEditor(label, value) { it?.let(onValue) }

@Composable
private fun NullableNumberEditor(label: String, value: Double?, onValue: (Double?) -> Unit) {
    var raw by remember(label, value) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(
        value = raw,
        onValueChange = { text -> raw = text; onValue(text.toDoubleOrNull()) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
    )
}

private fun cardModifier() = Modifier.fillMaxWidth()
    .background(AresSurfaceElevated, RoundedCornerShape(10.dp))
    .border(1.dp, AresBorder, RoundedCornerShape(10.dp))
    .padding(12.dp)

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

private fun generateKeymapSpecSections(
    state: ControlsEditorState,
    onEditBinding: (String) -> Unit,
): List<AresSpecSection> {
    val bindings = state.selectedScheme?.bindings.orEmpty()
    val driverBindings = bindings.filter { it.source.controllerSlot == "driver" }
    val operatorBindings = bindings.filter { it.source.controllerSlot == "operator" }
    val otherBindings = bindings.filter { it.source.controllerSlot != "driver" && it.source.controllerSlot != "operator" }

    val driverRows = driverBindings.map { binding ->
        AresSpecRow(
            id = binding.bindingId,
            primaryLabel = binding.displayName,
            secondaryLabel = binding.source.controlIds.joinToString(" + "),
            badge = binding.event.name,
            columns = listOf(
                "Event" to binding.event.friendlyName(),
                "Target" to binding.target.key,
                "Kind" to binding.target.kind.name,
                "Enabled" to if (binding.enabled) "YES" else "NO",
            ),
            onEditClick = { onEditBinding(binding.bindingId) },
        )
    }

    val operatorRows = operatorBindings.map { binding ->
        AresSpecRow(
            id = binding.bindingId,
            primaryLabel = binding.displayName,
            secondaryLabel = binding.source.controlIds.joinToString(" + "),
            badge = binding.event.name,
            columns = listOf(
                "Event" to binding.event.friendlyName(),
                "Target" to binding.target.key,
                "Kind" to binding.target.kind.name,
                "Enabled" to if (binding.enabled) "YES" else "NO",
            ),
            onEditClick = { onEditBinding(binding.bindingId) },
        )
    }

    val sections = mutableListOf(
        AresSpecSection("Driver (Gamepad 1)", null, driverRows, "No bindings configured on Gamepad 1 (Driver)."),
        AresSpecSection("Operator (Gamepad 2)", null, operatorRows, "No bindings configured on Gamepad 2 (Operator)."),
    )
    if (otherBindings.isNotEmpty()) {
        val otherRows = otherBindings.map { binding ->
            AresSpecRow(
                id = binding.bindingId,
                primaryLabel = binding.displayName,
                secondaryLabel = "${binding.source.controllerSlot}: ${binding.source.controlIds.joinToString(" + ")}",
                badge = binding.event.name,
                columns = listOf(
                    "Event" to binding.event.friendlyName(),
                    "Target" to binding.target.key,
                    "Kind" to binding.target.kind.name,
                ),
                onEditClick = { onEditBinding(binding.bindingId) },
            )
        }
        sections.add(AresSpecSection("Other Controllers", null, otherRows))
    }
    return sections
}

private fun generateKeymapMarkdown(state: ControlsEditorState): String = buildString {
    appendLine("# ARES TeleOp & Controls Keymap Spec")
    appendLine("Project: ${state.projectPath}")
    appendLine("League: ${state.league.name}")
    appendLine("Target Platform: ${state.targetPlatform.name}")
    appendLine()
    state.selectedScheme?.bindings.orEmpty().groupBy { it.source.controllerSlot }.forEach { (slot, bindings) ->
        appendLine("## Controller: ${slot.replaceFirstChar(Char::uppercase)}")
        appendLine("| Control | Event | Target Action | Kind | Enabled |")
        appendLine("|---|---|---|---|---|")
        bindings.forEach { b ->
            appendLine("| ${b.source.controlIds.joinToString(" + ")} | ${b.event.friendlyName()} | ${b.target.key} | ${b.target.kind.name} | ${if (b.enabled) "Yes" else "No"} |")
        }
        appendLine()
    }
}
