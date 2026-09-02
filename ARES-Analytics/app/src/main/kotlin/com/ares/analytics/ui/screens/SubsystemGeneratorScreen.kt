package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.AresGenerationPhase
import com.ares.analytics.ui.components.core.*
import com.ares.analytics.ui.components.subsystems.*
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.SubsystemBuilderStage
import com.ares.analytics.viewmodel.SubsystemGeneratorState
import com.ares.analytics.viewmodel.SubsystemGeneratorViewModel
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemImplementationKind
import com.areslib.subsystem.isAresGenerated
import com.areslib.subsystem.supportsPlatform

/** Modular visual editor for project-backed subsystem DSL documents and generated Kotlin. */
@Composable
fun SubsystemGeneratorScreen(
    viewModel: SubsystemGeneratorViewModel,
    onContinueToPortMap: (() -> Unit)? = null,
    onBackToDrivetrain: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    var showSpecSummaryModal by remember { mutableStateOf(false) }
    var showAiAssistantDrawer by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val headerControlHeight = if (AresThemeSettings.touchOptimizedMode) 48.dp else 36.dp
            ResponsiveBuilderHeader(
                identity = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = AresCyan.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.3f)),
                        ) {
                            Text(
                                "SUBSYSTEM",
                                color = AresCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Text(
                            state.draft?.document?.displayName ?: "No Subsystem",
                            color = AresTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                steps = {
                    if (state.draft != null) {
                        val activeStage = state.activeStage
                        val stages = listOf(
                            SubsystemBuilderStage.PURPOSE to "1. Purpose & Template",
                            SubsystemBuilderStage.HARDWARE to "2. Hardware & IO",
                            SubsystemBuilderStage.STATE_AND_BEHAVIOR to "3. Stateflow & Control",
                            SubsystemBuilderStage.REVIEW to "4. Tuning & Review",
                        )
                        stages.forEach { (stage, label) ->
                            val selected = when (stage) {
                                SubsystemBuilderStage.PURPOSE -> activeStage == SubsystemBuilderStage.PURPOSE
                                SubsystemBuilderStage.HARDWARE -> activeStage == SubsystemBuilderStage.HARDWARE
                                SubsystemBuilderStage.STATE_AND_BEHAVIOR -> activeStage in setOf(SubsystemBuilderStage.STATE_AND_BEHAVIOR, SubsystemBuilderStage.SAFETY)
                                SubsystemBuilderStage.REVIEW -> activeStage in setOf(SubsystemBuilderStage.TUNING, SubsystemBuilderStage.CAPABILITIES, SubsystemBuilderStage.SIMULATION_AND_TESTING, SubsystemBuilderStage.REVIEW)
                                else -> false
                            }
                            FilterChip(
                                selected = selected,
                                modifier = Modifier.height(headerControlHeight),
                                onClick = { viewModel.selectStage(stage) },
                                label = {
                                    Text(
                                        label,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AresCyan,
                                    selectedLabelColor = AresOnAccent,
                                    containerColor = AresSurfaceElevated,
                                    labelColor = AresTextPrimary,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = if (selected) AresCyan else AresBorder,
                                    selectedBorderColor = AresCyan,
                                    enabled = true,
                                    selected = selected,
                                ),
                            )
                        }
                    }
                },
                actions = {
                    OutlinedButton(
                        onClick = { showAiAssistantDrawer = true },
                        enabled = state.draft != null,
                        modifier = Modifier.height(headerControlHeight),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Default.AutoAwesome, "Open AI subsystem assistant", modifier = Modifier.size(16.dp), tint = AresCyan)
                        Spacer(Modifier.width(4.dp))
                        Text("AI Assistant", fontSize = 11.sp)
                    }
                    if (state.draft != null) {
                        IconButton(
                            onClick = viewModel::undo,
                            enabled = state.canUndo,
                            modifier = Modifier.size(headerControlHeight),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo last subsystem edit", modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = viewModel::redo,
                            enabled = state.canRedo,
                            modifier = Modifier.size(headerControlHeight),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo subsystem edit", modifier = Modifier.size(18.dp))
                        }
                        OutlinedButton(
                            onClick = { showSpecSummaryModal = true },
                            modifier = Modifier.height(headerControlHeight),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        ) {
                            Icon(Icons.Default.TableChart, "Open subsystem specification", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Spec", fontSize = 11.sp)
                        }
                        IconButton(
                            onClick = { viewModel.reload() },
                            modifier = Modifier.size(headerControlHeight),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reload subsystem", modifier = Modifier.size(18.dp), tint = AresTextSecondary)
                        }
                        OutlinedButton(
                            onClick = viewModel::requestRemoveSubsystem,
                            modifier = Modifier.height(headerControlHeight),
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                            border = BorderStroke(1.dp, AresError.copy(alpha = 0.7f)),
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = if ((state.draft?.document?.revision ?: 0) > 0) {
                                    "Remove subsystem from project"
                                } else {
                                    "Discard unsaved subsystem draft"
                                },
                                modifier = Modifier.size(18.dp),
                                tint = AresError,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Remove", color = AresError, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        val generatedByAres = state.draft?.document?.implementation?.kind?.isAresGenerated() == true
                        val generationRunning = state.generationPhase == AresGenerationPhase.RUNNING
                        Button(
                            onClick = {
                                if (generatedByAres) viewModel.generate() else viewModel.save()
                            },
                            enabled = if (generatedByAres) {
                                (state.canSave || state.canGenerate) && !generationRunning
                            } else {
                                state.canSave
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                            modifier = Modifier.height(headerControlHeight),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        ) {
                            Icon(
                                if (generatedByAres) Icons.Default.Build else Icons.Default.Save,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                when {
                                    generationRunning -> "Creating files…"
                                    !generatedByAres -> "Save subsystem"
                                    state.dirty -> "Save & create files"
                                    else -> "Create/update Kotlin"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                },
            )

            state.status?.let { AresStatusBanner(it, AresGreen) }
            state.recentRecovery?.let { recovery ->
                Surface(
                    color = AresGreen.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, AresGreen.copy(alpha = 0.7f)),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Recovery copy ready for ${recovery.displayName}",
                                color = AresTextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Restore the exact reviewed descriptor from ${recovery.recoveryPath}. Existing Kotlin remains untouched.",
                                color = AresTextSecondary,
                                fontSize = 10.sp,
                            )
                        }
                        OutlinedButton(
                            onClick = viewModel::restoreRemovedSubsystem,
                            border = BorderStroke(1.dp, AresGreen),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(headerControlHeight),
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Restore subsystem", color = AresTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = viewModel::dismissRecoveryNotice) {
                            Text("Dismiss", color = AresTextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
            state.generationMessage?.let { message ->
                val color = when (state.generationPhase) {
                    AresGenerationPhase.FAILED -> AresError
                    AresGenerationPhase.RUNNING -> AresCyan
                    AresGenerationPhase.SUCCEEDED -> AresGreen
                    AresGenerationPhase.IDLE -> AresTextSecondary
                }
                AresStatusBanner(message, color)
            }
            state.loadError?.let { AresStatusBanner(it, AresError) }

            val document = state.draft?.document
            if (document == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("No Subsystem Loaded", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("A drive-only robot does not need a mechanism. Add one when your robot has an intake, arm, lift, shooter, or sensor.", color = AresTextSecondary, fontSize = 11.sp)
                        Button(
                            onClick = { viewModel.setTemplatePickerVisible(true) },
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add a mechanism")
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (state.activeStage) {
                        SubsystemBuilderStage.PURPOSE -> SubsystemPurposeSection(state, viewModel)
                        SubsystemBuilderStage.HARDWARE -> SubsystemHardwareSection(state, viewModel)
                        SubsystemBuilderStage.STATE_AND_BEHAVIOR, SubsystemBuilderStage.SAFETY -> SubsystemStateflowSection(state, viewModel)
                        SubsystemBuilderStage.TUNING, SubsystemBuilderStage.CAPABILITIES, SubsystemBuilderStage.SIMULATION_AND_TESTING, SubsystemBuilderStage.REVIEW -> SubsystemTuningReviewSection(state, viewModel)
                    }
                }
            }
        }

        // Slide-out Hardware Device Inspector Drawer
        val doc = state.draft?.document
        if (doc != null) {
            doc.hardware.firstOrNull { it.uid == state.selectedHardwareUid }?.let { device ->
                AresInspectorDrawer(
                    isOpen = true,
                    title = device.displayName,
                    categoryBadge = device.kind.name,
                    stableId = device.hardwareId,
                    icon = Icons.Default.Settings,
                    onDismiss = { viewModel.selectHardware(null) },
                    onDone = { viewModel.selectHardware(null) },
                    onDelete = { viewModel.removeHardware(device.hardwareId) },
                    deleteButtonText = "Delete Hardware",
                ) {
                    HardwareInspectorBody(state, device, viewModel)
                }
            }

            // Slide-out State Field Inspector Drawer
            doc.stateFields.firstOrNull { it.uid == state.selectedFieldUid }?.let { field ->
                AresInspectorDrawer(
                    isOpen = true,
                    title = field.displayName,
                    categoryBadge = field.role.name,
                    stableId = field.fieldId,
                    icon = Icons.Default.Memory,
                    onDismiss = { viewModel.selectField(null) },
                    onDone = { viewModel.selectField(null) },
                    onDelete = { viewModel.removeStateField(field.fieldId) },
                    deleteButtonText = "Delete State Field",
                ) {
                    StateFieldInspectorBody(field, viewModel)
                }
            }

            // Slide-out Control Loop Inspector Drawer
            doc.controlLoops.firstOrNull { it.uid == state.selectedLoopUid }?.let { loop ->
                AresInspectorDrawer(
                    isOpen = true,
                    title = loop.displayName,
                    categoryBadge = loop.strategy.name,
                    stableId = loop.loopId,
                    icon = Icons.Default.Build,
                    onDismiss = { viewModel.selectLoop(null) },
                    onDone = { viewModel.selectLoop(null) },
                    onDelete = { viewModel.removeControlLoop(loop.loopId) },
                    deleteButtonText = "Delete Controller Rule",
                ) {
                    ControlInspectorBody(state, loop, viewModel)
                }
            }

            // Slide-out Tuning Parameter Inspector Drawer
            doc.tuningParameters.firstOrNull { it.uid == state.selectedTuningParameterUid }?.let { param ->
                AresInspectorDrawer(
                    isOpen = true,
                    title = param.displayName,
                    categoryBadge = param.type.name,
                    stableId = param.key,
                    icon = Icons.Default.Tune,
                    onDismiss = { viewModel.selectTuningParameter(null) },
                    onDone = { viewModel.selectTuningParameter(null) },
                    onDelete = { viewModel.removeTuningParameter(param.uid) },
                    deleteButtonText = "Delete Parameter",
                ) {
                    TuningParameterInspectorBody(param, viewModel)
                }
            }

            // AI Assistant Slide-Out Drawer
            AresInspectorDrawer(
                isOpen = showAiAssistantDrawer,
                title = "Subsystem AI Assistant",
                categoryBadge = "GEMINI",
                stableId = "subsystem-assistant",
                icon = Icons.Default.AutoAwesome,
                onDismiss = { showAiAssistantDrawer = false },
                onDone = { showAiAssistantDrawer = false },
            ) {
                SubsystemAiAssistantDrawerContent(state, viewModel)
            }

            // Subsystem AI Proposal Review Dialog
            state.aiProposal?.let { review ->
                SubsystemAiProposalDialog(
                    review = review,
                    onApply = { viewModel.applyAiProposal() },
                    onDismiss = { viewModel.dismissAiProposal() },
                )
            }

            // Subsystem Template Picker Modal
            if (state.showTemplatePicker) {
                SubsystemTemplatePickerDialog(
                    currentTemplate = doc.template,
                    platform = doc.platform,
                    onApplyTemplate = { tpl ->
                        viewModel.applyTemplate(tpl)
                        viewModel.setTemplatePickerVisible(false)
                    },
                    onDismiss = { viewModel.setTemplatePickerVisible(false) },
                )
            }

            // Specification Summary Modal
            AresSpecSummaryModal(
                isOpen = showSpecSummaryModal,
                title = "${doc.displayName} Subsystem Specification",
                subtitle = "Mechanism Subsystem · .ares/subsystems/${doc.documentId}.aressubsystem",
                sections = generateSubsystemSpecSections(doc),
                onDismiss = { showSpecSummaryModal = false },
            )

            state.pendingRemoval?.let { request ->
                AlertDialog(
                    onDismissRequest = viewModel::cancelRemoveSubsystem,
                    title = {
                        Text(
                            if (request.persisted) "Remove ${request.displayName} from this project?" else "Discard this unsaved draft?",
                            color = AresTextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (request.persisted) {
                                Text(
                                    "ARES will remove only the reviewed canonical descriptor ${request.canonicalPath}. A recoverable copy will be kept at ${request.recoveryPath}.",
                                    color = AresTextSecondary,
                                    fontSize = 12.sp,
                                )
                                Text(
                                    "Generated registry plumbing will be refreshed. Kotlin starter and USER-OWNED source files are never deleted by this action.",
                                    color = AresTextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            } else {
                                Text(
                                    "This subsystem has not been saved to the project. Discarding it changes no files.",
                                    color = AresTextSecondary,
                                    fontSize = 12.sp,
                                )
                            }
                            if (request.sourceFilesPreserved.isNotEmpty()) {
                                Text("Source preserved:", color = AresTextSecondary, fontSize = 11.sp)
                                request.sourceFilesPreserved.forEach { path ->
                                    Text("• $path", color = AresTextTertiary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                            if (request.discardsUnsavedChanges) {
                                Text(
                                    "Unsaved edits in the open draft will also be discarded.",
                                    color = AresGold,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = viewModel::confirmRemoveSubsystem,
                            colors = ButtonDefaults.buttonColors(containerColor = AresError, contentColor = AresOnAccent),
                        ) {
                            Text(if (request.persisted) "Remove & keep recovery copy" else "Discard draft", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = viewModel::cancelRemoveSubsystem) {
                            Text("Keep subsystem")
                        }
                    },
                    containerColor = AresSurfaceElevated,
                )
            }

            if (state.pendingStarterReplacements.isNotEmpty()) {
                AlertDialog(
                    onDismissRequest = viewModel::cancelStarterReplacement,
                    title = {
                        Text(
                            "Review generated starter replacements",
                            color = AresTextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                "These files are marked GENERATED STARTER, but their contents differ from the new proposal. ARES will replace them only after this review. USER-OWNED files can never be replaced here.",
                                color = AresTextSecondary,
                                fontSize = 12.sp,
                            )
                            state.pendingStarterReplacements.forEach { file ->
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(
                                        file.projectRelativePath,
                                        color = AresCyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    Surface(
                                        color = AresBackground,
                                        border = BorderStroke(1.dp, AresBorder),
                                        shape = RoundedCornerShape(6.dp),
                                    ) {
                                        Column(Modifier.fillMaxWidth().padding(8.dp)) {
                                            file.diff.forEach { line ->
                                                val color = when (line.kind) {
                                                    com.ares.analytics.viewmodel.SubsystemDiffLineKind.ADDED -> AresGreen
                                                    com.ares.analytics.viewmodel.SubsystemDiffLineKind.REMOVED -> AresRed
                                                    com.ares.analytics.viewmodel.SubsystemDiffLineKind.CONTEXT -> AresTextSecondary
                                                }
                                                val prefix = when (line.kind) {
                                                    com.ares.analytics.viewmodel.SubsystemDiffLineKind.ADDED -> "+ "
                                                    com.ares.analytics.viewmodel.SubsystemDiffLineKind.REMOVED -> "− "
                                                    com.ares.analytics.viewmodel.SubsystemDiffLineKind.CONTEXT -> "  "
                                                }
                                                Text(
                                                    prefix + line.text,
                                                    color = color,
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Text(
                                "Confirmation is bound to this exact proposal. If any file changes before apply, ARES will stop and require another review.",
                                color = AresGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = viewModel::confirmStarterReplacement,
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                        ) {
                            Text("Replace reviewed starters", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = viewModel::cancelStarterReplacement) {
                            Text("Keep existing files")
                        }
                    },
                    containerColor = AresSurfaceElevated,
                )
            }
        }
    }
}

@Composable
private fun SubsystemAiAssistantDrawerContent(
    state: SubsystemGeneratorState,
    viewModel: SubsystemGeneratorViewModel,
) {
    var prompt by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            color = AresSurface,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Describe your subsystem requirements in plain language.",
                    color = AresTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
                Text(
                    "Gemini will generate a structured proposal with hardware devices, state fields, control laws, and live tuning parameters.",
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                )
            }
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("What should this subsystem do?") },
            modifier = Modifier.fillMaxWidth().height(100.dp),
            placeholder = { Text("e.g. Dual-motor intake with current-based jam detection and automatic reverse") },
        )

        Button(
            onClick = {
                if (prompt.isNotBlank()) {
                    viewModel.requestAiProposal(prompt)
                }
            },
            enabled = prompt.isNotBlank() && !state.aiProposalInProgress,
            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (state.aiProposalInProgress) "Generating proposal…" else "Generate AI Proposal")
        }

        state.aiProposalError?.let { Text(it, color = AresError, fontSize = 12.sp) }

        Surface(
            color = AresBackground.copy(alpha = 0.5f),
            shape = RoundedCornerShape(6.dp),
        ) {
            Text(
                "Privacy: Only your prompt and current subsystem form are sent using the configured AI provider. Your logs and credentials are never transmitted.",
                color = AresTextTertiary,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

private fun generateSubsystemSpecSections(document: SubsystemDocument): List<AresSpecSection> = listOf(
    AresSpecSection(
        title = "Hardware Devices (${document.hardware.size})",
        rows = document.hardware.map { dev ->
            AresSpecRow(
                id = dev.hardwareId,
                primaryLabel = dev.displayName,
                secondaryLabel = "id: ${dev.hardwareId}",
                badge = dev.kind.name,
                columns = listOf(
                    "Connection" to dev.connectionLabel(document.platform),
                    "Required" to (if (dev.required) "Yes" else "No"),
                )
            )
        }
    ),
    AresSpecSection(
        title = "State Fields & Controllers (${document.stateFields.size} fields, ${document.controlLoops.size} loops)",
        rows = document.stateFields.map { fld ->
            AresSpecRow(
                id = fld.fieldId,
                primaryLabel = fld.displayName,
                secondaryLabel = "field: ${fld.fieldId}",
                badge = fld.role.name,
                columns = listOf(
                    "Type" to fld.type.name.lowercase(),
                    "Unit" to (fld.unit ?: "None"),
                )
            )
        }
    )
)
