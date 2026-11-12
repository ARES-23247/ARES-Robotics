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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.GamepadState
import com.ares.analytics.service.drivebase.toCanonicalDrivebase
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.components.controls.ControlsEditorPanel
import com.ares.analytics.ui.components.robotstudio.DEFAULT_HIERARCHY_WIDTH_DP
import com.ares.analytics.ui.components.robotstudio.DEFAULT_INSPECTOR_WIDTH_DP
import com.ares.analytics.ui.components.robotstudio.MAX_HIERARCHY_WIDTH_DP
import com.ares.analytics.ui.components.robotstudio.MAX_INSPECTOR_WIDTH_DP
import com.ares.analytics.ui.components.robotstudio.MIN_HIERARCHY_WIDTH_DP
import com.ares.analytics.ui.components.robotstudio.MIN_INSPECTOR_WIDTH_DP
import com.ares.analytics.ui.components.robotstudio.RobotContextInspector
import com.ares.analytics.ui.components.robotstudio.RobotHierarchyTree
import com.ares.analytics.ui.components.robotstudio.RobotProjectSpecModal
import com.ares.analytics.ui.components.robotstudio.RobotStudioActionToast
import com.ares.analytics.ui.components.robotstudio.RobotStudioDirtySummaryBar
import com.ares.analytics.ui.components.robotstudio.RobotStudioSelection
import com.ares.analytics.ui.components.robotstudio.RobotStudioVerticalSplitter
import com.ares.analytics.ui.components.robotstudio.SubsystemTreeItem
import com.ares.analytics.ui.components.robotstudio.buildRobotProjectSpecSnapshot
import com.ares.analytics.ui.components.robotstudio.clampSplitPaneWidthDp
import com.ares.analytics.ui.components.robotstudio.collectDirtyRobotStudioStages
import com.ares.analytics.ui.components.robotstudio.robotStudioKeyboardShortcuts
import com.ares.analytics.ui.components.robotstudio.robotStudioPanePresentation
import com.ares.analytics.ui.components.robotstudio.robotStudioPersistedRevision
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresRed
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresThemeSettings
import com.ares.analytics.viewmodel.PathPlannerIntent
import com.ares.analytics.viewmodel.PathPlannerViewModel
import com.ares.analytics.viewmodel.SubsystemGeneratorViewModel
import com.ares.analytics.viewmodel.SubsystemProblemSeverity
import com.ares.analytics.viewmodel.controls.ControlsEditorState
import com.ares.analytics.viewmodel.controls.ControlsEditorViewModel
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderIntent
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderViewModel
import com.ares.analytics.viewmodel.hardware.HardwareSetupViewModel
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.ares.analytics.viewmodel.project.ProjectIdentityViewModel
import com.ares.analytics.viewmodel.project.projectIdentityChanges
import com.ares.analytics.viewmodel.project.validateProjectIdentityDraft
import com.ares.analytics.viewmodel.robotstudio.RobotStudioStageStatus
import com.ares.analytics.viewmodel.robotstudio.RobotStudioState
import com.ares.analytics.viewmodel.robotstudio.RobotStudioViewModel
import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioViewModel
import com.areslib.project.AresProjectAuthoringModel
import com.areslib.subsystem.SubsystemSchema
import com.areslib.subsystem.SubsystemTemplate
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    onChooseStandaloneExport: () -> File?,
    onExportStandaloneProject: suspend (File) -> String,
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
                SubsystemSchema.validate(subsystem).isNotEmpty()
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

    var showProjectSpecModal by remember { mutableStateOf(false) }

    val identityDirty = remember(identityState.loading, identityState.currentDocument, identityState.draft, config.league) {
        if (identityState.loading || identityState.currentDocument == null) return@remember false
        val validated = validateProjectIdentityDraft(config.league, identityState.draft).document ?: return@remember true
        projectIdentityChanges(identityState.currentDocument, validated).isNotEmpty()
    }
    val dirtyStages = remember(
        identityDirty,
        drivebaseState.dirty,
        subsystemState.dirty,
        superstructureState.dirty,
        controlsState.dirty,
        pathPlannerState.routineDirty,
    ) {
        collectDirtyRobotStudioStages(
            identityDirty = identityDirty,
            drivebaseDirty = drivebaseState.dirty,
            subsystemDirty = subsystemState.dirty,
            superstructureDirty = superstructureState.dirty,
            controlsDirty = controlsState.dirty,
            routineDirty = pathPlannerState.routineDirty,
        )
    }

    val handleSaveAll: () -> String? = {
        val savedStages = mutableListOf<String>()
        if (subsystemState.dirty && subsystemState.canSave) {
            subsystemViewModel.save()
            savedStages += "Subsystems"
        }
        if (controlsState.dirty && controlsState.canSave) {
            controlsViewModel.save()
            savedStages += "Controls"
        }
        if (pathPlannerState.routineDirty) {
            pathPlannerViewModel.onIntent(PathPlannerIntent.SaveRoutine(config.projectPath))
            savedStages += "Autonomous"
        }
        val reviewNeeded = when {
            drivebaseState.dirty -> {
                selection = RobotStudioSelection.Drivetrain
                drivebaseViewModel.onIntent(DrivebaseBuilderIntent.ReviewSave)
                "Drivetrain"
            }
            superstructureState.dirty -> {
                selection = RobotStudioSelection.Superstructure
                superstructureViewModel.reviewSave()
                "Superstructure"
            }
            identityDirty -> {
                selection = RobotStudioSelection.Identity
                projectIdentityViewModel.review()
                "Identity"
            }
            else -> null
        }
        when {
            savedStages.isNotEmpty() && reviewNeeded != null ->
                "Saved ${savedStages.joinToString(", ")}. Opened reviewed save confirmation for $reviewNeeded."
            savedStages.isNotEmpty() ->
                "Saved ${savedStages.joinToString(", ")}."
            reviewNeeded != null ->
                "Opened reviewed save confirmation for $reviewNeeded."
            dirtyStages.isNotEmpty() ->
                "Resolve validation errors in ${dirtyStages.joinToString(", ")} before saving."
            else -> null
        }
    }

    RobotStudioWorkspace(
        state = state,
        subsystems = subsystemTreeItems,
        selection = selection,
        onSelect = { newSelection ->
            if (newSelection is RobotStudioSelection.Subsystem && newSelection.documentId.isNotBlank()) {
                subsystemViewModel.selectDocument(newSelection.documentId)
                selection = RobotStudioSelection.Subsystem(subsystemViewModel.state.value.selectedDocumentId.orEmpty())
            } else {
                selection = newSelection
            }
        },
        onAddSubsystem = {
            subsystemViewModel.newSubsystem(SubsystemTemplate.SIMPLE_ACTUATOR)
            selection = RobotStudioSelection.Subsystem(subsystemViewModel.state.value.selectedDocumentId.orEmpty())
        },
        onOpenInIde = onOpenInIde,
        onChooseStandaloneExport = onChooseStandaloneExport,
        onExportStandaloneProject = onExportStandaloneProject,
        onOpenProjectSpec = { showProjectSpecModal = true },
        dirtyStages = dirtyStages,
        onSaveAll = handleSaveAll,
    ) { selected ->
        when (selected) {
            RobotStudioSelection.Identity -> ProjectIdentityScreen(
                viewModel = projectIdentityViewModel,
                config = config,
                onBackToStudio = null,
                onOpenProjectSpec = { showProjectSpecModal = true },
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
                onNavigateToSelection = { newSelection ->
                    if (newSelection is RobotStudioSelection.Subsystem) {
                        val targetId = newSelection.documentId.ifBlank {
                            subsystemTreeItems.firstOrNull()?.documentId.orEmpty()
                        }
                        if (targetId.isNotBlank()) {
                            subsystemViewModel.selectDocument(targetId)
                        }
                        selection = RobotStudioSelection.Subsystem(targetId)
                    } else {
                        selection = newSelection
                    }
                },
            )
        }
    }

    if (showProjectSpecModal) {
        val currentDrivetrain = runCatching {
            (drivebaseState.saved ?: drivebaseState.draft).toCanonicalDrivebase()
        }.getOrNull()
        RobotProjectSpecModal(
            isOpen = true,
            snapshot = buildRobotProjectSpecSnapshot(
                identityState = identityState,
                drivetrain = currentDrivetrain,
                subsystems = subsystemState.documents,
                superstructures = superstructureState.documents,
                controlSchemes = controlsState.schemes,
                routines = pathPlannerState.availableRoutines,
                hardwareCount = hardwareState.snapshot?.items?.size ?: 0,
                hardwareReviewStatus = hardwareState.snapshot?.reviewStatus?.name,
            ),
            onDismiss = { showProjectSpecModal = false },
        )
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
    onChooseStandaloneExport: (() -> File?)? = null,
    onExportStandaloneProject: (suspend (File) -> String)? = null,
    onOpenProjectSpec: (() -> Unit)? = null,
    dirtyStages: List<String> = emptyList(),
    onSaveAll: (() -> String?)? = null,
    modifier: Modifier = Modifier,
    centerContent: @Composable (RobotStudioSelection) -> Unit,
) {
    var leftCollapsedState by remember { mutableStateOf(false) }
    var rightCollapsedState by remember { mutableStateOf(false) }
    var leftWidthDp by remember { mutableStateOf(DEFAULT_HIERARCHY_WIDTH_DP) }
    var rightWidthDp by remember { mutableStateOf(DEFAULT_INSPECTOR_WIDTH_DP) }
    var ideMessage by remember(state.projectPath) { mutableStateOf<String?>(null) }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(AresBackground)
            .robotStudioKeyboardShortcuts(
                onToggleHierarchy = { leftCollapsedState = !leftCollapsedState },
                onToggleInspector = { rightCollapsedState = !rightCollapsedState },
                onSaveAll = {
                    val result = onSaveAll?.invoke()
                    if (result != null) ideMessage = result
                },
            ),
    ) {
        val presentation = robotStudioPanePresentation(maxWidth.value, AresThemeSettings.largeTextMode)

        LaunchedEffect(presentation) {
            leftCollapsedState = presentation.collapseTree
            rightCollapsedState = presentation.collapseInspector
        }

        Column(Modifier.fillMaxSize()) {
            if (onOpenProjectSpec != null || onOpenInIde != null || (onChooseStandaloneExport != null && onExportStandaloneProject != null)) {
                var exportInProgress by remember(state.projectPath) { mutableStateOf(false) }
                val actionScope = rememberCoroutineScope()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        when (state.authoringModel) {
                            AresProjectAuthoringModel.GUI_OWNED -> "GUI-owned standalone repository"
                            AresProjectAuthoringModel.CODE_FIRST -> "Code-first standalone repository"
                            AresProjectAuthoringModel.HYBRID -> "Hybrid Studio + team code repository"
                        },
                        color = AresTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    onOpenProjectSpec?.let { openSpec ->
                        OutlinedButton(onClick = openSpec) { Text("Robot Spec & DSL") }
                    }
                    onOpenInIde?.let { open ->
                        OutlinedButton(onClick = { ideMessage = open() }) { Text("Open in IDE") }
                    }
                    val chooseExport = onChooseStandaloneExport
                    val export = onExportStandaloneProject
                    if (chooseExport != null && export != null) {
                        OutlinedButton(
                            enabled = !exportInProgress,
                            onClick = {
                                val destination = chooseExport()
                                if (destination != null) {
                                    actionScope.launch {
                                        exportInProgress = true
                                        try {
                                            ideMessage = export(destination)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            ideMessage = failure.message ?: "Standalone archive export failed."
                                        } finally {
                                            exportInProgress = false
                                        }
                                    }
                                }
                            },
                        ) {
                            Text(if (exportInProgress) "Exporting…" else "Export standalone archive")
                        }
                    }
                }
            }
            if (dirtyStages.isNotEmpty() && onSaveAll != null) {
                RobotStudioDirtySummaryBar(
                    dirtyStages = dirtyStages,
                    onSaveAll = {
                        val result = onSaveAll.invoke()
                        if (result != null) ideMessage = result
                    },
                )
            }
            ideMessage?.let { message ->
                RobotStudioActionToast(
                    message = message,
                    onDismiss = { ideMessage = null },
                )
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
                    isCollapsed = leftCollapsedState,
                    onToggleCollapse = { leftCollapsedState = !leftCollapsedState },
                    expandedWidth = leftWidthDp.dp,
                )
                if (!leftCollapsedState) {
                    RobotStudioVerticalSplitter(
                        onDragDeltaDp = { delta ->
                            leftWidthDp = clampSplitPaneWidthDp(
                                currentDp = leftWidthDp,
                                deltaDp = delta,
                                minDp = MIN_HIERARCHY_WIDTH_DP,
                                maxDp = MAX_HIERARCHY_WIDTH_DP,
                            )
                        },
                    )
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    centerContent(selection)
                }
                if (!rightCollapsedState) {
                    RobotStudioVerticalSplitter(
                        onDragDeltaDp = { delta ->
                            rightWidthDp = clampSplitPaneWidthDp(
                                currentDp = rightWidthDp,
                                deltaDp = -delta,
                                minDp = MIN_INSPECTOR_WIDTH_DP,
                                maxDp = MAX_INSPECTOR_WIDTH_DP,
                            )
                        },
                    )
                }
                RobotContextInspector(
                    selection = selection,
                    state = state,
                    isCollapsed = rightCollapsedState,
                    onToggleCollapse = { rightCollapsedState = !rightCollapsedState },
                    expandedWidth = rightWidthDp.dp,
                )
            }
        }
    }
}
