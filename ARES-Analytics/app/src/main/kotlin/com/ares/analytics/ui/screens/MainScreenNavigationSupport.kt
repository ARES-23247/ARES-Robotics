package com.ares.analytics.ui.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.ares.analytics.di.ServiceRegistry
import com.ares.analytics.domain.navigation.MatchStrategySubTab
import com.ares.analytics.domain.navigation.NavigationTarget
import com.ares.analytics.domain.navigation.PitDeepLinkRequest
import com.ares.analytics.domain.navigation.PitOperationsSubTab
import com.ares.analytics.domain.navigation.StrategyDeepLinkRequest
import com.ares.analytics.service.project.ProjectExecutionCommand
import com.ares.analytics.ui.components.CommandPaletteProjectCategory
import com.ares.analytics.ui.components.CommandPaletteProjectItem
import com.ares.analytics.ui.components.core.TargetSelection
import com.ares.analytics.viewmodel.DashboardIntent
import com.ares.analytics.viewmodel.MainIntent
import com.ares.analytics.viewmodel.MainViewModel
import com.ares.analytics.viewmodel.PathPlannerIntent

/**
 * Handles top-level Studio keyboard shortcuts (`Ctrl+K`, `Ctrl+Shift+K`, `Ctrl+B`, `Ctrl+D`,
 * `Alt+Left` / `Alt+Right` navigation history, and `Escape` overlay dismissal).
 */
internal fun handleMainScreenPreviewKeyEvent(
    keyEvent: KeyEvent,
    activeNav: NavigationTarget,
    canRunBuild: Boolean,
    isBuildRunning: Boolean,
    commandPaletteOpen: Boolean,
    coachDrawerOpen: Boolean,
    hasWorkspacePendingDeletion: Boolean,
    deployDialogOpen: Boolean,
    isTerminalOpen: Boolean,
    executeProjectCommand: (ProjectExecutionCommand) -> Boolean,
    requestSimulatorLaunch: () -> Unit,
    onStopAll: () -> Unit,
    onOpenCommandPalette: () -> Unit,
    onDismissCommandPalette: () -> Unit,
    onDismissCoach: () -> Unit,
    onDismissWorkspaceDeletion: () -> Unit,
    onDismissDeploy: () -> Unit,
    onCloseTerminal: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateForward: () -> Unit,
): Boolean {
    if (keyEvent.type != KeyEventType.KeyDown) return false
    val isCtrl = keyEvent.isCtrlPressed
    if (!isCtrl && keyEvent.isAltPressed) {
        return when (keyEvent.key) {
            Key.DirectionLeft -> {
                onNavigateBack()
                true
            }
            Key.DirectionRight -> {
                onNavigateForward()
                true
            }
            else -> false
        }
    }
    if (isCtrl) {
        return when (keyEvent.key) {
            Key.B -> if (activeNav == NavigationTarget.ROBOT_STUDIO && !keyEvent.isShiftPressed) {
                false
            } else {
                if (canRunBuild && !isBuildRunning) {
                    executeProjectCommand(ProjectExecutionCommand.VERIFY_AND_BUILD)
                }
                true
            }
            Key.D -> {
                requestSimulatorLaunch()
                true
            }
            Key.K -> {
                if (keyEvent.isShiftPressed) onStopAll() else onOpenCommandPalette()
                true
            }
            else -> false
        }
    }
    if (keyEvent.key == Key.Escape) {
        return when {
            commandPaletteOpen -> {
                onDismissCommandPalette()
                true
            }
            coachDrawerOpen -> {
                onDismissCoach()
                true
            }
            hasWorkspacePendingDeletion -> {
                onDismissWorkspaceDeletion()
                true
            }
            deployDialogOpen -> {
                onDismissDeploy()
                true
            }
            isTerminalOpen -> {
                onCloseTerminal()
                true
            }
            else -> false
        }
    }
    return false
}

/** Dispatches deep jumps from Command Palette project items to their exact entity & editor screen. */
internal fun dispatchProjectItemDeepJump(
    item: CommandPaletteProjectItem,
    projectPath: String,
    workspaceModels: WorkspaceViewModelGraph,
    navigate: (NavigationTarget, String?) -> Unit,
) {
    when (item.category) {
        CommandPaletteProjectCategory.SUBSYSTEM -> {
            item.entityId?.let(workspaceModels.subsystemGenerator::selectDocument)
            navigate(NavigationTarget.SUBSYSTEM_GEN, item.label.removePrefix("Subsystem: ").trim())
        }
        CommandPaletteProjectCategory.SUPERSTRUCTURE -> {
            item.entityId?.let { workspaceModels.superstructureStudio.select(it, force = false) }
            navigate(NavigationTarget.SUPERSTRUCTURE_STUDIO, item.label.removePrefix("Superstructure: ").trim())
        }
        CommandPaletteProjectCategory.ROUTINE -> {
            item.entityId?.let {
                workspaceModels.pathPlanner.onIntent(
                    PathPlannerIntent.LoadRoutine(projectPath.takeIf(String::isNotBlank), it)
                )
            }
            navigate(NavigationTarget.PATH_PLANNER, item.label.removePrefix("Routine: ").trim())
        }
        CommandPaletteProjectCategory.STAGE -> navigate(item.target, null)
    }
}

/** Wires overlay and Command Palette deep-link callbacks for [MainScreen]. */
internal fun createMainScreenOverlayActions(
    mainViewModel: MainViewModel,
    services: ServiceRegistry,
    workspaceModels: WorkspaceViewModelGraph,
    projectPath: String,
    startSimulatorProcess: () -> Unit,
    executeProjectCommand: (ProjectExecutionCommand) -> Boolean,
    selectTarget: (TargetSelection) -> Unit,
    dismissCoach: () -> Unit,
    dismissCommandPalette: () -> Unit,
    clearAcademyRequests: () -> Unit,
    requestLesson: (String?) -> Unit,
    requestGlossary: (String?) -> Unit,
    clearWorkspacePendingDeletion: () -> Unit,
    clearDeployAwaitingConfirmation: () -> Unit,
    dismissDeployDialog: () -> Unit,
    requestPitDeepLink: (PitOperationsSubTab, String?, Int?) -> Unit,
    requestStrategyDeepLink: (MatchStrategySubTab, String?, Int?) -> Unit = { _, _, _ -> },
): MainScreenOverlayActions = MainScreenOverlayActions(
    navigate = { destination ->
        if (destination == NavigationTarget.ACADEMY) {
            clearAcademyRequests()
        }
        mainViewModel.onIntent(MainIntent.SetActiveNav(destination))
    },
    dismissCoach = dismissCoach,
    openAcademyLesson = { lessonId ->
        dismissCoach()
        requestLesson(lessonId)
        mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.ACADEMY))
    },
    selectTarget = selectTarget,
    startSimulator = startSimulatorProcess,
    stopSimulator = services.simulatorProcessService::stop,
    dismissCommandPalette = dismissCommandPalette,
    openGlossaryTerm = { termName ->
        requestGlossary(termName)
        requestLesson(null)
        mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.ACADEMY))
    },
    openRunReplay = { sessionId ->
        workspaceModels.dashboard.onIntent(DashboardIntent.SelectPrimarySession(sessionId, null))
        mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.DASHBOARD))
    },
    openNotebookEntry = { entryId ->
        workspaceModels.engineeringNotebook.openAndSelectEntry(entryId)
        mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.ENGINEERING_NOTEBOOK))
    },
    openProjectItem = { item ->
        dispatchProjectItemDeepJump(
            item = item,
            projectPath = projectPath,
            workspaceModels = workspaceModels,
            navigate = { target, subLabel ->
                mainViewModel.onIntent(MainIntent.SetActiveNav(target, subLabel))
            },
        )
    },
    openPitTab = { tab ->
        requestPitDeepLink(tab, null, null)
        mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.PIT_DIAGNOSTICS, tab.label))
    },
    openStrategyTab = { tab ->
        requestStrategyDeepLink(tab, null, null)
        mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.MATCH_STRATEGY, tab.label))
    },
    openScoutedTeam = { team ->
        requestStrategyDeepLink(MatchStrategySubTab.OPR_SCOUTING, team, null)
        mainViewModel.onIntent(
            MainIntent.SetActiveNav(
                NavigationTarget.MATCH_STRATEGY,
                "${MatchStrategySubTab.OPR_SCOUTING.label} · Team #$team",
            ),
        )
    },
    openBatteryPack = { batteryId ->
        requestPitDeepLink(PitOperationsSubTab.BATTERY_FLEET, batteryId, null)
        mainViewModel.onIntent(
            MainIntent.SetActiveNav(
                NavigationTarget.PIT_DIAGNOSTICS,
                "${PitOperationsSubTab.BATTERY_FLEET.label} · $batteryId",
            )
        )
    },
    openScheduledMatch = { matchNumber ->
        requestPitDeepLink(PitOperationsSubTab.MATCH_QUEUE, null, matchNumber)
        mainViewModel.onIntent(
            MainIntent.SetActiveNav(
                NavigationTarget.PIT_DIAGNOSTICS,
                "${PitOperationsSubTab.MATCH_QUEUE.label} · Match #$matchNumber",
            )
        )
    },
    confirmWorkspaceDeletion = { workspaceId ->
        mainViewModel.onIntent(MainIntent.DeleteWorkspace(workspaceId))
        clearWorkspacePendingDeletion()
    },
    dismissWorkspaceDeletion = clearWorkspacePendingDeletion,
    confirmDeploy = {
        clearDeployAwaitingConfirmation()
        executeProjectCommand(ProjectExecutionCommand.DEPLOY)
    },
    dismissDeploy = dismissDeployDialog,
    cancelDeploy = services.robotDeploymentService::cancel,
    dismissUpdate = { mainViewModel.onIntent(MainIntent.SetShowUpdateBanner(false)) },
)

internal fun nextPitDeepLink(
    previous: PitDeepLinkRequest,
    tab: PitOperationsSubTab,
    batteryId: String? = null,
    matchNumber: Int? = null,
): PitDeepLinkRequest = PitDeepLinkRequest(
    tab = tab,
    highlightedBatteryId = batteryId,
    highlightedMatchNumber = matchNumber,
    nonce = previous.nonce + 1,
)

internal fun nextStrategyDeepLink(
    previous: StrategyDeepLinkRequest,
    tab: MatchStrategySubTab,
    teamNumber: String? = null,
    matchNumber: Int? = null,
): StrategyDeepLinkRequest = StrategyDeepLinkRequest(
    tab = tab,
    highlightedTeamNumber = teamNumber,
    highlightedMatchNumber = matchNumber,
    nonce = previous.nonce + 1,
)
