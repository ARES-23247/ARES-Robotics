@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.*
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.learning.AcademyLearningAssignment
import com.ares.analytics.domain.learning.AcademyRuntimeSnapshot
import com.ares.analytics.domain.learning.LearningCatalog
import com.ares.analytics.domain.learning.LearningCheckpoint
import com.ares.analytics.domain.learning.LearningCheckpointEvidence
import com.ares.analytics.domain.learning.LearningLab
import com.ares.analytics.domain.learning.LearningLabGuide
import com.ares.analytics.ui.components.core.*
import com.ares.analytics.ui.components.dashboard.EkfSensorFusionLabCard
import com.ares.analytics.ui.components.pathplanner.MotionProfileLabCard
import com.ares.analytics.ui.components.superstructure.*
import com.ares.analytics.ui.help.DeveloperReferenceCatalog
import com.ares.analytics.ui.help.GlossaryCatalog
import com.ares.analytics.ui.help.GlossaryTerm
import com.ares.analytics.ui.help.toAcademyAutonomousSnapshot
import com.ares.analytics.ui.help.toAcademyControlsSnapshot
import com.ares.analytics.ui.help.toAcademyGraduationSnapshot
import com.ares.analytics.ui.help.toAcademyRunAnalysisSnapshot
import com.ares.analytics.ui.help.toAcademySubsystemSnapshot
import com.ares.analytics.ui.help.toAcademySuperstructureSnapshot
import com.ares.analytics.ui.help.toAcademyTuningSnapshot
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.ui.util.DesktopFileChoosers
import com.ares.analytics.viewmodel.PathPlannerViewModel
import com.ares.analytics.viewmodel.SubsystemGeneratorViewModel
import com.ares.analytics.viewmodel.TuningViewModel
import com.ares.analytics.viewmodel.controls.ControlsEditorViewModel
import com.ares.analytics.viewmodel.robotstudio.RobotStudioViewModel
import com.ares.analytics.viewmodel.runanalysis.GuidedRunAnalysisViewModel
import com.ares.analytics.viewmodel.superstructure.*
import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioViewModel
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.toKotlinDsl
import java.io.File

@Composable
fun SuperstructureStudioScreen(
    viewModel: SuperstructureStudioViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    var showSpecSummaryModal by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }

    // This editor is intentionally retained while students move between Robot Studio stages so
    // an unfinished coordinator draft survives navigation. A clean editor, however, must refresh
    // on entry: drivetrain, subsystem, and controls editors may have written new catalog entries
    // while this view model was off screen. Never require a novice to discover the reload icon just
    // to make a newly-created mechanism or action appear here.
    LaunchedEffect(viewModel) {
        if (shouldRefreshSuperstructureCatalogsOnEntry(viewModel.state.value)) {
            viewModel.reload()
        }
    }

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
                                "COORDINATOR",
                                color = AresCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Text(
                            state.draft?.displayName ?: "No Coordinator",
                            color = AresTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                steps = {
                    if (state.draft != null) {
                        SuperstructureStudioStep.entries.forEach { step ->
                            val selected = step == state.step
                            val label = when (step) {
                                SuperstructureStudioStep.POSTURES -> "1. Postures & Setpoints"
                                SuperstructureStudioStep.TRANSITIONS -> "2. Transitions & Interlocks"
                                SuperstructureStudioStep.REVIEW -> "3. Verification & Save"
                            }
                            FilterChip(
                                selected = selected,
                                modifier = Modifier.height(headerControlHeight),
                                onClick = { viewModel.selectStep(step) },
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
                        onClick = { createOpen = true },
                        modifier = Modifier.height(headerControlHeight),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Default.Add, "Create coordinator", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New", fontSize = 11.sp)
                    }
                    if (state.draft != null) {
                        OutlinedButton(
                            onClick = { showSpecSummaryModal = true },
                            modifier = Modifier.height(headerControlHeight),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        ) {
                            Icon(Icons.Default.TableChart, "Open coordinator specification", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Spec", fontSize = 11.sp)
                        }
                        IconButton(
                            onClick = { viewModel.reload() },
                            modifier = Modifier.size(headerControlHeight),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reload coordinator", modifier = Modifier.size(18.dp), tint = AresTextSecondary)
                        }
                        Button(
                            onClick = viewModel::reviewSave,
                            enabled = state.canSave,
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                            modifier = Modifier.height(headerControlHeight),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        ) {
                            Text("Review & save", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                },
            )

            state.error?.let { AresStatusBanner(it, AresError) }
            if (state.status.isNotBlank()) AresStatusBanner(state.status, AresGreen)

            val draft = state.draft
            if (draft == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("No Superstructure Coordinator Configured", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("A superstructure coordinates multiple mechanisms (Intake, Arm, Shooter) through synchronized state presets.", color = AresTextSecondary, fontSize = 11.sp)
                        Button(
                            onClick = { createOpen = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                        ) {
                            Text("Create Superstructure Coordinator", fontWeight = FontWeight.Bold)
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
                    when (state.step) {
                        SuperstructureStudioStep.POSTURES -> SuperstructurePostureMatrix(state, draft, viewModel)
                        SuperstructureStudioStep.TRANSITIONS -> SuperstructureTransitionsSection(state, draft, viewModel)
                        SuperstructureStudioStep.REVIEW -> SuperstructureVerificationSection(state, draft, viewModel)
                    }
                }
            }
        }

        // Create Dialog Modal
        if (createOpen) {
            var newId by remember { mutableStateOf("") }
            var newName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { createOpen = false },
                title = { Text("Create Superstructure Coordinator", color = AresTextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(newId, { newId = it }, label = { Text("Stable ID (e.g. main-superstructure)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(newName, { newName = it }, label = { Text("Display Name (e.g. Main Superstructure)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newId.isNotBlank()) {
                                viewModel.create(newId, newName)
                                createOpen = false
                            }
                        },
                        enabled = newId.isNotBlank(),
                    ) { Text("Create") }
                },
                dismissButton = {
                    TextButton(onClick = { createOpen = false }) { Text("Cancel") }
                }
            )
        }

        if (state.pendingSelectionId != null) {
            AlertDialog(
                onDismissRequest = viewModel::cancelDiscard,
                title = { Text("Discard Unsaved Changes?", color = AresTextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "You have unsaved changes in the current coordinator. Discarding will revert to the last saved configuration or cancel new draft creation.",
                        color = AresTextSecondary,
                        fontSize = 12.sp,
                    )
                },
                confirmButton = {
                    Button(
                        onClick = viewModel::confirmDiscard,
                        colors = ButtonDefaults.buttonColors(containerColor = AresError, contentColor = AresOnAccent),
                    ) {
                        Text("Discard", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::cancelDiscard) {
                        Text("Cancel")
                    }
                },
            )
        }

        val currentDraft = state.draft
        if (currentDraft != null) {
            AresSpecSummaryModal(
                isOpen = showSpecSummaryModal,
                title = "${currentDraft.displayName} Superstructure Specification",
                subtitle = "Coordinator · .ares/superstructures/${currentDraft.superstructureId}.aressuperstructure",
                sections = generateSuperstructureSpecSections(state, currentDraft),
                onDismiss = { showSpecSummaryModal = false },
                kotlinDslGenerator = { currentDraft.toKotlinDsl() },
            )
        }
    }
}

internal fun shouldRefreshSuperstructureCatalogsOnEntry(state: SuperstructureStudioState): Boolean =
    !state.loading && !state.dirty

private fun generateSuperstructureSpecSections(
    state: SuperstructureStudioState,
    draft: SuperstructureDocument,
): List<AresSpecSection> = listOf(
    AresSpecSection(
        title = "Postures & Setpoints (${draft.states.size})",
        rows = draft.states.map { st ->
            AresSpecRow(
                id = st.stateId,
                primaryLabel = st.displayName.ifBlank { st.stateId },
                secondaryLabel = "id: ${st.stateId}",
                badge = if (st.stateId == draft.initialStateId) "INITIAL" else if (st.stateId == draft.faultStateId) "FAULT" else null,
                columns = listOf(
                    "Targets" to "${st.subsystemTargets.size} configured",
                    "Entry Actions" to (st.onEntryActionKeys.joinToString().ifBlank { "None" }),
                )
            )
        }
    ),
    AresSpecSection(
        title = "Transitions & Interlocks",
        rows = listOf(
            AresSpecRow(
                id = "transitions",
                primaryLabel = "Active Transitions",
                columns = listOf("Count" to "${draft.transitions.size} routes")
            ),
            AresSpecRow(
                id = "interlocks",
                primaryLabel = "Collision Guards",
                columns = listOf("Count" to "${draft.interlocks.size} guards")
            ),
            AresSpecRow(
                id = "luts",
                primaryLabel = "Lookup Curves",
                columns = listOf("Count" to "${draft.luts.size} tables")
            ),
        )
    )
)
