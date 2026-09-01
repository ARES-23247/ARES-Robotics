package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.areslib.superstructure.SuperstructureDocument
import com.ares.analytics.ui.components.core.*
import com.ares.analytics.ui.components.superstructure.*
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.superstructure.*

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

        val currentDraft = state.draft
        if (currentDraft != null) {
            AresSpecSummaryModal(
                isOpen = showSpecSummaryModal,
                title = "${currentDraft.displayName} Superstructure Specification",
                subtitle = "Coordinator · .ares/superstructures/${currentDraft.superstructureId}.aressuperstructure",
                sections = generateSuperstructureSpecSections(state, currentDraft),
                onDismiss = { showSpecSummaryModal = false }
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
