package com.ares.analytics.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.GamepadState
import com.ares.analytics.shared.WorkspaceConfig
import com.ares.analytics.ui.components.controls.ControlsEditorPanel
import com.ares.analytics.ui.components.robotstudio.RobotContextInspector
import com.ares.analytics.ui.components.robotstudio.RobotHierarchyTree
import com.ares.analytics.ui.components.robotstudio.RobotStudioSelection
import com.ares.analytics.ui.components.robotstudio.SubsystemTreeItem
import com.ares.analytics.ui.components.robotstudio.robotStudioPanePresentation
import com.ares.analytics.ui.components.robotstudio.robotStudioPersistedRevision
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresRed
import com.ares.analytics.ui.theme.AresThemeSettings
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.viewmodel.PathPlannerViewModel
import com.ares.analytics.viewmodel.SubsystemGeneratorViewModel
import com.ares.analytics.viewmodel.SubsystemProblemSeverity
import com.ares.analytics.viewmodel.controls.ControlsEditorState
import com.ares.analytics.viewmodel.controls.ControlsEditorViewModel
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderViewModel
import com.ares.analytics.viewmodel.hardware.HardwareSetupViewModel
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.ares.analytics.viewmodel.project.ProjectIdentityViewModel
import com.ares.analytics.viewmodel.robotstudio.RobotStudioStageStatus
import com.ares.analytics.viewmodel.robotstudio.RobotStudioState
import com.ares.analytics.viewmodel.robotstudio.RobotStudioViewModel
import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioViewModel
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.validateSubsystemDocument
import com.areslib.project.AresProjectAuthoringModel
import kotlinx.coroutines.delay

/**
 * Unified Robot Studio workspace. The hierarchy and context inspector wrap the real canonical
 * editors; they never maintain a second copy of robot configuration state.
 */
@Composable
fun RobotStudioScreen(
    viewModel: RobotStudioViewModel,
    drivebaseViewModel: DrivebaseBuilderViewModel,
    subsystemViewModel: SubsystemGeneratorViewModel,
    superstructureViewModel: SuperstructureStudioViewModel,
    pathPlannerViewModel: PathPlannerViewModel,
    controlsViewModel: ControlsEditorViewModel,
    controlsState: ControlsEditorState,
    gamepad1State: GamepadState,
    gamepad2State: GamepadState,
    hardwareSetupViewModel: HardwareSetupViewModel,
    projectIdentityViewModel: ProjectIdentityViewModel,
    config: WorkspaceConfig,
    onOpenPitDiagnostics: () -> Unit,
    onRunVerification: () -> Unit,
    onOpenInIde: () -> String,
    onCreateStandaloneProject: () -> Unit,
    initialSelection: RobotStudioSelection = RobotStudioSelection.Identity,
) {
    val state by viewModel.state.collectAsState()
    val drivebaseState by drivebaseViewModel.state.collectAsState()
    val subsystemState by subsystemViewModel.state.collectAsState()
    val superstructureState by superstructureViewModel.state.collectAsState()
    val pathPlannerState by pathPlannerViewModel.state.collectAsState()
    val hardwareState by hardwareSetupViewModel.state.collectAsState()
    val identityState by projectIdentityViewModel.state.collectAsState()

    var selection by remember(initialSelection) { mutableStateOf(initialSelection) }

    // Editors live for the whole workspace so a student's draft survives navigation. When the
    // controls editor has no draft of its own, entering it should nevertheless reload canonical
    // catalogs written by the drivetrain/subsystem/superstructure editors. This keeps a newly
    // generated action discoverable without teaching students to press a manual refresh button.
    LaunchedEffect(selection) {
        if (
            selection == RobotStudioSelection.Controls &&
            !controlsViewModel.state.value.dirty &&
            !controlsViewModel.state.value.draftHasUnappliedChanges
        ) {
            controlsViewModel.reload()
        }
    }

    val subsystemTreeItems = remember(
        subsystemState.documents,
        subsystemState.draft,
        subsystemState.dirty,
        subsystemState.problems,
    ) {
        val draftId = subsystemState.draft?.document?.documentId
        val items = subsystemState.documents.map { subsystem ->
            val isDraft = subsystem.documentId == draftId && subsystemState.dirty
            val hasErrors = if (subsystem.documentId == draftId) {
                subsystemState.problems.any { it.severity == SubsystemProblemSeverity.ERROR }
            } else {
                validateSubsystemDocument(subsystem).isNotEmpty()
            }
            SubsystemTreeItem(
                documentId = subsystem.documentId,
                displayName = subsystem.displayName,
                isDraft = isDraft,
                status = when {
                    hasErrors -> RobotStudioStageStatus.INVALID
                    isDraft -> RobotStudioStageStatus.NEEDS_ACTION
                    else -> RobotStudioStageStatus.READY
                },
            )
        }
        val unsavedDraft = subsystemState.draft?.document
        if (unsavedDraft != null && items.none { it.documentId == unsavedDraft.documentId }) {
            items +
                SubsystemTreeItem(
                    documentId = unsavedDraft.documentId,
                    displayName = unsavedDraft.displayName,
                    isDraft = subsystemState.dirty,
                    status = when {
                        subsystemState.problems.any { it.severity == SubsystemProblemSeverity.ERROR } ->
                            RobotStudioStageStatus.INVALID
                        subsystemState.dirty -> RobotStudioStageStatus.NEEDS_ACTION
                        else -> RobotStudioStageStatus.READY
                    },
                )
        } else {
            items
        }
    }

    // Refresh readiness only after a canonical save settles. Draft edits deliberately do not
    // claim project-wide readiness and do not cause repeated filesystem inspections.
    val persistedRevision = remember(
        identityState,
        drivebaseState,
        subsystemState,
        superstructureState,
        controlsState,
        pathPlannerState,
        hardwareState,
    ) {
        robotStudioPersistedRevision(
            loading = identityState.loading || drivebaseState.loading || superstructureState.loading ||
                pathPlannerState.projectLoading || hardwareState.loading,
            hasUnsavedChanges = drivebaseState.dirty ||
                subsystemState.dirty || superstructureState.dirty || controlsState.dirty ||
                pathPlannerState.routineDirty,
            fingerprints = listOf(
                identityState.currentContentHash,
                drivebaseState.saved?.hashCode(),
                subsystemState.documents.hashCode(),
                superstructureState.savedContentHash,
                controlsState.profiles.hashCode(),
                controlsState.schemes.hashCode(),
                controlsState.generatedContentHash,
                pathPlannerState.availableRoutines.hashCode(),
                hardwareState.snapshot?.hashCode(),
            ),
        )
    }
    var lastPersistedRevision by remember(config.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(persistedRevision) {
        val revision = persistedRevision ?: return@LaunchedEffect
        val previous = lastPersistedRevision
        lastPersistedRevision = revision
        if (previous != null && previous != revision) {
            delay(150)
            viewModel.refresh()
        }
    }

    RobotStudioWorkspace(
        state = state,
        subsystems = subsystemTreeItems,
        selection = selection,
        onSelect = { newSelection ->
            selection = newSelection
            if (newSelection is RobotStudioSelection.Subsystem && newSelection.documentId.isNotBlank()) {
                subsystemViewModel.selectDocument(newSelection.documentId)
            }
        },
        onAddSubsystem = {
            subsystemViewModel.newSubsystem(SubsystemTemplate.SIMPLE_ACTUATOR)
            selection = RobotStudioSelection.Subsystem(subsystemViewModel.state.value.selectedDocumentId.orEmpty())
        },
        onOpenInIde = onOpenInIde,
        onCreateStandaloneProject = onCreateStandaloneProject,
    ) { selected ->
        when (selected) {
            RobotStudioSelection.Identity -> ProjectIdentityScreen(
                viewModel = projectIdentityViewModel,
                config = config,
                onBackToStudio = null,
            )
            RobotStudioSelection.Drivetrain -> DrivebaseBuilderScreen(
                viewModel = drivebaseViewModel,
                onContinueToSubsystems = {
                    selection = RobotStudioSelection.Subsystem(subsystemTreeItems.firstOrNull()?.documentId.orEmpty())
                },
                onBackToStudio = null,
            )
            is RobotStudioSelection.Subsystem -> SubsystemGeneratorScreen(
                viewModel = subsystemViewModel,
                onContinueToPortMap = { selection = RobotStudioSelection.PortMap },
                onBackToDrivetrain = { selection = RobotStudioSelection.Drivetrain },
            )
            RobotStudioSelection.Superstructure -> SuperstructureStudioScreen(superstructureViewModel)
            RobotStudioSelection.Autonomous -> PathPlannerScreen(
                viewModel = pathPlannerViewModel,
                league = config.league,
                projectPath = config.projectPath,
                robotDimensions = RobotDimensions(
                    lengthMeters = config.robotLengthMeters ?: RobotDimensions.defaultFor(config.league).lengthMeters,
                    widthMeters = config.robotWidthMeters ?: RobotDimensions.defaultFor(config.league).widthMeters,
                ),
            )
            RobotStudioSelection.Controls -> ControlsEditorPanel(
                state = controlsState,
                viewModel = controlsViewModel,
                gamepad1State = gamepad1State,
                gamepad2State = gamepad2State,
                modifier = Modifier.fillMaxSize(),
            )
            RobotStudioSelection.PortMap -> HardwareSetupScreen(
                viewModel = hardwareSetupViewModel,
                onOpenDrivebase = { selection = RobotStudioSelection.Drivetrain },
                onOpenSubsystems = {
                    selection = RobotStudioSelection.Subsystem(subsystemTreeItems.firstOrNull()?.documentId.orEmpty())
                },
                onOpenPitDiagnostics = onOpenPitDiagnostics,
                onBackToStudio = null,
            )
            RobotStudioSelection.Verification -> RobotVerificationReportScreen(
                report = state.verificationReport,
                isRunning = state.buildStage?.status == RobotStudioStageStatus.RUNNING,
                onRunVerification = onRunVerification,
            )
        }
    }
}

/** Shared by production and screenshot regression tests so the test covers the actual shell. */
@Composable
internal fun RobotStudioWorkspace(
    state: RobotStudioState,
    subsystems: List<SubsystemTreeItem>,
    selection: RobotStudioSelection,
    onSelect: (RobotStudioSelection) -> Unit,
    onAddSubsystem: () -> Unit,
    onOpenInIde: (() -> String)? = null,
    onCreateStandaloneProject: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    centerContent: @Composable (RobotStudioSelection) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(AresBackground)) {
        val presentation = robotStudioPanePresentation(maxWidth.value, AresThemeSettings.largeTextMode)
        var leftCollapsed by remember { mutableStateOf(presentation.collapseTree) }
        var rightCollapsed by remember { mutableStateOf(presentation.collapseInspector) }

        LaunchedEffect(presentation) {
            leftCollapsed = presentation.collapseTree
            rightCollapsed = presentation.collapseInspector
        }

        Column(Modifier.fillMaxSize()) {
            if (onOpenInIde != null || onCreateStandaloneProject != null) {
                var ideMessage by remember(state.projectPath) { mutableStateOf<String?>(null) }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        when (state.authoringModel) {
                            AresProjectAuthoringModel.GUI_OWNED -> "GUI-owned standalone repository"
                            AresProjectAuthoringModel.CODE_FIRST -> "Code-first Kotlin repository"
                            AresProjectAuthoringModel.HYBRID -> "Hybrid Studio + Kotlin repository"
                        },
                        color = AresTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    onOpenInIde?.let { open ->
                        OutlinedButton(onClick = { ideMessage = open() }) { Text("Open in IDE") }
                    }
                    onCreateStandaloneProject?.let { create ->
                        OutlinedButton(onClick = create) { Text("Export standalone repository") }
                    }
                }
                ideMessage?.let { message ->
                    Text(
                        message,
                        color = AresTextPrimary,
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            state.error?.let { error ->
                Surface(color = AresRed.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Robot Studio readiness is unavailable: $error",
                        color = AresTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            Row(Modifier.fillMaxSize()) {
                RobotHierarchyTree(
                    state = state,
                    subsystems = subsystems,
                    selected = selection,
                    onSelect = onSelect,
                    onAddSubsystem = onAddSubsystem,
                    isCollapsed = leftCollapsed,
                    onToggleCollapse = { leftCollapsed = !leftCollapsed },
                )
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    centerContent(selection)
                }
                RobotContextInspector(
                    selection = selection,
                    state = state,
                    isCollapsed = rightCollapsed,
                    onToggleCollapse = { rightCollapsed = !rightCollapsed },
                )
            }
        }
    }
}
