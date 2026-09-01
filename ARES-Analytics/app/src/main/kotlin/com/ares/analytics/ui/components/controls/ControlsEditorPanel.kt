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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.AresGenerationPhase
import com.ares.analytics.service.GamepadState
import com.ares.analytics.ui.components.core.AresEditorCard
import com.ares.analytics.ui.components.core.AresInspectorDrawer
import com.ares.analytics.ui.components.core.AresSelectionField
import com.ares.analytics.ui.components.core.AresSpecSummaryModal
import com.ares.analytics.ui.components.core.AresStatusBanner
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresRed
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.viewmodel.controls.ControlsEditorState
import com.ares.analytics.viewmodel.controls.ControlsEditorViewModel
import com.ares.analytics.viewmodel.controls.ControlsProblemSeverity
import com.areslib.controls.ControlBindingDocument
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControlTimingDocument
import com.areslib.controls.ControllerControlDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerSurfaceDocument

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
                ControlsBindingInspector(state, viewModel, binding)
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
    AresEditorCard(contentSpacing = 8.dp) {
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
private fun BindingList(state: ControlsEditorState, viewModel: ControlsEditorViewModel) {
    AresEditorCard(contentSpacing = 7.dp) {
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
private fun ProblemsCard(state: ControlsEditorState) {
    if (state.problems.isEmpty()) return
    AresEditorCard(contentSpacing = 6.dp) {
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
    AresStatusBanner(message, color)
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
internal fun SelectionMenu(
    label: String,
    selected: String,
    choices: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    AresSelectionField(label, selected, choices, modifier, onSelect = onSelect)
}
