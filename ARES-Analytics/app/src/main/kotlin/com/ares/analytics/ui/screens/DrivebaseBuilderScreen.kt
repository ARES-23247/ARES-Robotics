package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.drivebase.*
import com.ares.analytics.ui.components.core.AresInspectorDrawer
import com.ares.analytics.ui.components.core.AresSpecRow
import com.ares.analytics.ui.components.core.AresSpecSection
import com.ares.analytics.ui.components.core.AresSpecSummaryModal
import com.ares.analytics.ui.components.core.AresStatusBanner
import com.ares.analytics.ui.components.core.ResponsiveBuilderHeader
import com.ares.analytics.ui.components.drivebase.*
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.drivebase.*

@Composable
fun DrivebaseBuilderScreen(
    viewModel: DrivebaseBuilderViewModel,
    onContinueToSubsystems: (() -> Unit)? = null,
    onBackToStudio: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    var showSpecSummaryModal by remember { mutableStateOf(false) }
    var showAiAssistantDrawer by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
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
                                state.draft.kind.name.uppercase(),
                                color = AresCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Text(
                            state.draft.displayName,
                            color = AresTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                steps = {
                    DrivebaseBuilderStep.entries.forEach { step ->
                        val selected = step == state.step
                        val label = when (step) {
                            DrivebaseBuilderStep.DRIVE_TYPE -> "1. Drive Type"
                            DrivebaseBuilderStep.HARDWARE -> "2. Hardware"
                            DrivebaseBuilderStep.GEOMETRY -> "3. Geometry"
                            DrivebaseBuilderStep.CONTROL -> "4. Controls"
                            DrivebaseBuilderStep.LOCALIZATION -> "5. Localization"
                            DrivebaseBuilderStep.REVIEW -> "6. Safety & Review"
                        }
                        FilterChip(
                            selected = selected,
                            modifier = Modifier.height(headerControlHeight),
                            onClick = { viewModel.onIntent(DrivebaseBuilderIntent.SelectStep(step)) },
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
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = state.advanced,
                            onCheckedChange = { viewModel.onIntent(DrivebaseBuilderIntent.SetAdvanced(it)) },
                        )
                        Text("Advanced", color = AresTextPrimary, fontSize = 10.sp)
                    }
                    OutlinedButton(
                        onClick = { showAiAssistantDrawer = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AresCyan),
                        border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.6f)),
                        modifier = Modifier.height(headerControlHeight),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Default.AutoAwesome, "Open AI drivetrain assistant", modifier = Modifier.size(16.dp), tint = AresCyan)
                        Spacer(Modifier.width(4.dp))
                        Text("AI", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { showSpecSummaryModal = true },
                        modifier = Modifier.height(headerControlHeight),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Default.TableChart, "Open drivetrain specification", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Spec", fontSize = 11.sp)
                    }
                    IconButton(
                        onClick = { viewModel.onIntent(DrivebaseBuilderIntent.Reload) },
                        modifier = Modifier.size(headerControlHeight),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload drivetrain", modifier = Modifier.size(18.dp), tint = AresTextSecondary)
                    }
                    Button(
                        onClick = { viewModel.onIntent(DrivebaseBuilderIntent.ReviewSave) },
                        enabled = state.dirty,
                        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                        modifier = Modifier.height(headerControlHeight),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    ) {
                        Text("Review & save", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                },
            )

            state.error?.let { AresStatusBanner(it, AresError) }
            if (state.status.isNotBlank()) AresStatusBanner(state.status, AresGreen)
            val blockingIssues = state.issues.filter { it.severity == DrivebaseIssueSeverity.ERROR }
            if (blockingIssues.isNotEmpty()) {
                Surface(
                    color = AresError.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, AresError.copy(alpha = 0.65f)),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            "${blockingIssues.size} item${if (blockingIssues.size == 1) "" else "s"} must be fixed before this drivetrain can be saved",
                            color = AresError,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        blockingIssues.take(3).forEach { issue ->
                            Text("• ${issue.message}", color = AresTextPrimary, fontSize = 10.sp)
                        }
                        if (blockingIssues.size > 3) {
                            Text("• ${blockingIssues.size - 3} more — open the relevant builder steps above.", color = AresTextSecondary, fontSize = 10.sp)
                        }
                    }
                }
            }

            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("Loading drivebase configuration…", color = AresTextSecondary)
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
                    when (state.step) {
                        DrivebaseBuilderStep.DRIVE_TYPE -> DriveTypeStep(state, viewModel)
                        DrivebaseBuilderStep.HARDWARE -> HardwareStep(state, viewModel)
                        DrivebaseBuilderStep.GEOMETRY -> GeometryStep(state, viewModel)
                        DrivebaseBuilderStep.CONTROL -> ControlStep(state, viewModel)
                        DrivebaseBuilderStep.LOCALIZATION -> LocalizationStep(state, viewModel)
                        DrivebaseBuilderStep.REVIEW -> SafetyAndReviewStep(state, viewModel, onContinueToSubsystems, onBackToStudio)
                    }
                }
            }
        }

        // Slide-out Hardware Device Inspector Drawer
        val selected = state.draft.hardware.firstOrNull { it.id == state.selectedHardwareId }
        if (selected != null) {
            AresInspectorDrawer(
                isOpen = true,
                title = selected.displayName,
                categoryBadge = selected.role.name,
                stableId = selected.id,
                icon = Icons.Default.Settings,
                onDismiss = { viewModel.onIntent(DrivebaseBuilderIntent.SelectHardware(null)) },
                onDone = { viewModel.onIntent(DrivebaseBuilderIntent.SelectHardware(null)) },
                onDelete = if (state.advanced || state.draft.kind in setOf(DrivebaseKind.DIFFERENTIAL, DrivebaseKind.CUSTOM)) {
                    {
                        viewModel.onIntent(DrivebaseBuilderIntent.RemoveHardware(selected.id))
                        viewModel.onIntent(DrivebaseBuilderIntent.SelectHardware(null))
                    }
                } else null,
            ) {
                HardwareEditor(
                    device = selected,
                    hardware = state.draft.hardware,
                    advanced = state.advanced,
                    onUpdate = { viewModel.onIntent(DrivebaseBuilderIntent.UpdateHardware(it)) },
                    onRemove = {
                        viewModel.onIntent(DrivebaseBuilderIntent.RemoveHardware(selected.id))
                        viewModel.onIntent(DrivebaseBuilderIntent.SelectHardware(null))
                    },
                )
            }
        }

        // Slide-out Drivetrain AI Assistant Drawer
        AresInspectorDrawer(
            isOpen = showAiAssistantDrawer,
            title = "Drivetrain AI Assistant",
            categoryBadge = "GEMINI",
            stableId = "drivebase-assistant",
            icon = Icons.Default.AutoAwesome,
            onDismiss = { showAiAssistantDrawer = false },
            onDone = { showAiAssistantDrawer = false },
        ) {
            DrivebaseAiAssistantDrawerContent(state, viewModel)
        }

        // Drivetrain AI Proposal Review Dialog
        state.aiProposal?.let { review ->
            DrivebaseAiProposalDialog(
                review = review,
                onApply = { viewModel.applyAiProposal() },
                onDismiss = { viewModel.dismissAiProposal() },
            )
        }

        AresSpecSummaryModal(
            isOpen = showSpecSummaryModal,
            title = "${state.draft.displayName} Drivetrain Specification",
            subtitle = "${state.league.name} · .ares/drivetrains/${state.draft.documentId}.aresdrivetrain",
            sections = generateDrivebaseSpecSections(state),
            onDismiss = { showSpecSummaryModal = false }
        )

        // Discard Changes Confirmation Dialog
        state.pendingDiscardAction?.let { action ->
            val title = when (action) {
                DrivebaseDiscardAction.CHANGE_KIND -> "Discard Edits & Switch Drivebase Type?"
                DrivebaseDiscardAction.RELOAD -> "Discard Edits & Reload From Disk?"
            }
            val targetName = state.pendingKind?.let { kind ->
                kind.displayName(state.league)
            } ?: "another drivebase"
            val message = when (action) {
                DrivebaseDiscardAction.CHANGE_KIND -> "You have unsaved modifications on your current drivebase. Switching to $targetName will discard your uncommitted changes. Do you want to continue?"
                DrivebaseDiscardAction.RELOAD -> "Reloading will discard all uncommitted changes made during this session."
            }
            val confirmLabel = when (action) {
                DrivebaseDiscardAction.CHANGE_KIND -> "Discard & Switch to $targetName"
                DrivebaseDiscardAction.RELOAD -> "Discard & Reload"
            }

            AlertDialog(
                onDismissRequest = { viewModel.onIntent(DrivebaseBuilderIntent.CancelDiscard) },
                title = { Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold) },
                text = { Text(message, color = AresTextSecondary, fontSize = 13.sp) },
                confirmButton = {
                    Button(
                        onClick = { viewModel.onIntent(DrivebaseBuilderIntent.ConfirmDiscard) },
                        colors = ButtonDefaults.buttonColors(containerColor = AresError, contentColor = AresOnAccent),
                    ) {
                        Text(confirmLabel, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { viewModel.onIntent(DrivebaseBuilderIntent.CancelDiscard) },
                    ) {
                        Text("Keep Editing")
                    }
                },
                containerColor = AresSurfaceElevated,
            )
        }
    }
}

@Composable
private fun DrivebaseAiAssistantDrawerContent(
    state: DrivebaseBuilderState,
    viewModel: DrivebaseBuilderViewModel,
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
                    "Describe your drivetrain requirements in plain language.",
                    color = AresTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
                Text(
                    "Gemini will generate a structured drivetrain proposal with motors, encoders, kinematics dimensions, and safety envelopes for ${state.league.name}.",
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                )
            }
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("What drivetrain should Gemini configure?") },
            modifier = Modifier.fillMaxWidth().height(100.dp),
            placeholder = { Text("e.g. 4-wheel mecanum with GoBILDA 5202 motors and Pinpoint odometry") },
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
                "Privacy: Only your prompt and current drivebase form are sent using the configured AI provider. Your logs and credentials are never transmitted.",
                color = AresTextTertiary,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
private fun DrivebaseAiProposalDialog(
    review: DrivebaseAiProposalReview,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review Gemini's Drivetrain Proposal") },
        text = {
            Column(
                Modifier.fillMaxWidth().height(420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(review.proposal.summary, color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
                review.proposal.explanations.forEach { explanation ->
                    Text("• $explanation", color = AresTextSecondary, fontSize = 12.sp)
                }
                if (review.issues.isNotEmpty()) {
                    Text("Validation Review", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                    review.issues.forEach { issue ->
                        Text(
                            "${if (issue.severity == DrivebaseIssueSeverity.ERROR) "Blocking" else "Warning"}: ${issue.message}",
                            color = if (issue.severity == DrivebaseIssueSeverity.ERROR) AresError else AresGold,
                            fontSize = 12.sp,
                        )
                    }
                }
                if (review.changes.isNotEmpty()) {
                    Text("Proposed Changes (${review.changes.size})", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                    review.changes.forEach { change ->
                        Surface(
                            color = AresSurfaceElevated,
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, AresBorder),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(change.path, color = AresCyan, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                Text("${change.before} → ${change.after}", color = AresTextSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onApply,
                enabled = review.canApply,
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) {
                Text("Apply Proposal")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = AresTextSecondary)
            }
        },
    )
}

private fun generateDrivebaseSpecSections(state: DrivebaseBuilderState): List<AresSpecSection> {
    val draft = state.draft
    return listOf(
        AresSpecSection(
            title = "Drivetrain Overview",
            rows = listOf(
                AresSpecRow(
                    id = "display_name",
                    primaryLabel = "Display Name",
                    columns = listOf("Value" to draft.displayName)
                ),
                AresSpecRow(
                    id = "kind",
                    primaryLabel = "Drivebase Kind",
                    badge = draft.kind.name,
                    columns = listOf("League" to state.league.name)
                ),
                AresSpecRow(
                    id = "document_id",
                    primaryLabel = "Canonical Document ID",
                    columns = listOf("Path" to ".ares/drivetrains/${draft.documentId}.aresdrivetrain")
                ),
            )
        ),
        AresSpecSection(
            title = "Kinematics & Geometry",
            rows = listOf(
                AresSpecRow(
                    id = "wheel_radius",
                    primaryLabel = "Wheel Radius",
                    columns = listOf("Measurement" to "${draft.geometry.wheelRadiusMeters} m")
                ),
                AresSpecRow(
                    id = "track_width",
                    primaryLabel = "Track Width",
                    columns = listOf("Measurement" to "${draft.geometry.trackWidthMeters} m")
                ),
                AresSpecRow(
                    id = "wheelbase",
                    primaryLabel = "Wheelbase",
                    columns = listOf("Measurement" to "${draft.geometry.wheelBaseMeters} m")
                ),
                AresSpecRow(
                    id = "limits",
                    primaryLabel = "Speed Envelopes",
                    columns = listOf(
                        "Max Linear" to "${draft.safety.maxLinearSpeedMetersPerSecond} m/s",
                        "Max Angular" to "${draft.safety.maxAngularSpeedRadiansPerSecond} rad/s"
                    )
                ),
            )
        ),
        AresSpecSection(
            title = "Hardware Declarations (${draft.hardware.size} devices)",
            rows = draft.hardware.map { dev ->
                AresSpecRow(
                    id = dev.id,
                    primaryLabel = dev.displayName,
                    secondaryLabel = dev.canId?.let { "CAN $it${dev.canBus?.let { bus -> " · $bus" }.orEmpty()}" }
                        ?: "hw: ${dev.hardwareName}",
                    badge = dev.role.name,
                    columns = listOf(
                        "Direction" to if (dev.inverted) "INVERTED" else "NORMAL",
                        "CAN ID" to (dev.canId?.toString() ?: "N/A"),
                        "CAN Bus" to (dev.canBus ?: "default")
                    )
                )
            }
        ),
        AresSpecSection(
            title = "Localization & Safety Rules",
            rows = listOf(
                AresSpecRow(
                    id = "localization",
                    primaryLabel = "Localization Providers",
                    columns = listOf("Configured" to draft.localization.joinToString { it.name })
                ),
                AresSpecRow(
                    id = "safety_rules",
                    primaryLabel = "Fail-Closed Safety Rules",
                    columns = listOf(
                        "Safe Neutral" to if (draft.safety.safeNeutralRequired) "REQUIRED" else "DISABLED",
                        "Config Health" to if (draft.safety.configurationHealthRequired) "REQUIRED" else "DISABLED",
                        "Neutral Recovery" to if (draft.safety.explicitNeutralRecoveryRequired) "REQUIRED" else "DISABLED",
                        "Current Monitor" to if (draft.safety.currentMonitoringRequired) "REQUIRED" else "DISABLED"
                    )
                ),
            )
        )
    )
}
