package com.ares.analytics.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ares.analytics.domain.navigation.MatchStrategySubTab
import com.ares.analytics.domain.navigation.NavigationTarget
import com.ares.analytics.domain.navigation.PitOperationsSubTab
import com.ares.analytics.domain.pit.BatteryFleetAnalyzer
import com.ares.analytics.domain.strategy.OprAndSynergyAnalyzer
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.DeployExecutionState
import com.ares.analytics.service.EventScoutingStore
import com.ares.analytics.service.LearningProgressService
import com.ares.analytics.service.MatchInfo
import com.ares.analytics.service.UpdateCheckerService
import com.ares.analytics.service.integration.EngineeringNotebookCaptureService
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.components.CommandPalette
import com.ares.analytics.ui.components.CommandPaletteBatteryItem
import com.ares.analytics.ui.components.CommandPaletteNotebookItem
import com.ares.analytics.ui.components.CommandPaletteProjectItem
import com.ares.analytics.ui.components.CommandPaletteRunItem
import com.ares.analytics.ui.components.CommandPaletteStrategyTeamItem
import com.ares.analytics.ui.components.LearningCoachDrawer
import com.ares.analytics.ui.components.buildCommandPaletteBatteryItems
import com.ares.analytics.ui.components.buildCommandPaletteMatchItems
import com.ares.analytics.ui.components.buildCommandPaletteNotebookItems
import com.ares.analytics.ui.components.buildCommandPaletteRunItems
import com.ares.analytics.ui.components.buildCommandPaletteStrategyTeamItems
import com.ares.analytics.ui.components.core.AresDialog
import com.ares.analytics.ui.components.core.AresDialogVariant
import com.ares.analytics.ui.components.core.OneClickDeployDialog
import com.ares.analytics.ui.components.core.TargetSelection
import com.ares.analytics.ui.components.layout.UpdateNotificationBanner
import com.ares.analytics.ui.theme.AresTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MainScreenOverlayState(
    val activeNavigation: NavigationTarget,
    val coachDrawerOpen: Boolean,
    val activeCoachLessonId: String?,
    val commandPaletteOpen: Boolean,
    val developerMode: Boolean,
    val workspacePendingDeletion: Pair<String, String>?,
    val deployDialogOpen: Boolean,
    val deployAwaitingConfirmation: Boolean,
    val deployExecutionState: DeployExecutionState,
    val projectPath: String,
    val league: League,
    val updateState: UpdateCheckerService.UpdateState,
    val showUpdateBanner: Boolean,
    val recentTargets: List<NavigationTarget> = emptyList(),
    val matches: List<MatchInfo> = emptyList(),
)

private data class CommandPaletteIndexedEntities(
    val runs: List<CommandPaletteRunItem> = emptyList(),
    val notes: List<CommandPaletteNotebookItem> = emptyList(),
    val batteries: List<CommandPaletteBatteryItem> = emptyList(),
    val strategyTeams: List<CommandPaletteStrategyTeamItem> = emptyList(),
)

internal data class MainScreenOverlayActions(
    val navigate: (NavigationTarget) -> Unit,
    val dismissCoach: () -> Unit,
    val openAcademyLesson: (String) -> Unit,
    val selectTarget: (TargetSelection) -> Unit,
    val startSimulator: () -> Unit,
    val stopSimulator: () -> Unit,
    val dismissCommandPalette: () -> Unit,
    val openGlossaryTerm: (String) -> Unit,
    val openRunReplay: (String) -> Unit = {},
    val openNotebookEntry: (String) -> Unit = {},
    val openProjectItem: (CommandPaletteProjectItem) -> Unit = {},
    val openPitTab: (PitOperationsSubTab) -> Unit = {},
    val openStrategyTab: (MatchStrategySubTab) -> Unit = {},
    val openScoutedTeam: (String) -> Unit = {},
    val openBatteryPack: (String) -> Unit = {},
    val openScheduledMatch: (Int) -> Unit = {},
    val confirmWorkspaceDeletion: (String) -> Unit,
    val dismissWorkspaceDeletion: () -> Unit,
    val confirmDeploy: () -> Unit,
    val dismissDeploy: () -> Unit,
    val cancelDeploy: () -> Unit,
    val dismissUpdate: () -> Unit,
)

/** Global overlays layered above routed workspace content. */
@Composable
internal fun BoxScope.MainScreenOverlays(
    state: MainScreenOverlayState,
    actions: MainScreenOverlayActions,
    learningProgressService: LearningProgressService,
    workspace: WorkspaceConfig? = null,
    databaseService: DatabaseService? = null,
    notebookCaptureService: EngineeringNotebookCaptureService? = null,
    eventScoutingStore: EventScoutingStore? = null,
) {
    AnimatedVisibility(
        visible = state.coachDrawerOpen &&
            state.activeNavigation != NavigationTarget.ACADEMY &&
            state.activeCoachLessonId != null,
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .padding(vertical = 8.dp),
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
    ) {
        LearningCoachDrawer(
            progressService = learningProgressService,
            onOpenAcademy = actions.openAcademyLesson,
            onOpenScreen = actions.navigate,
            onSelectLocalSimulator = { actions.selectTarget(TargetSelection.LOCAL_SIM) },
            onStartSimulator = actions.startSimulator,
            onOpenDashboard = { actions.navigate(NavigationTarget.DASHBOARD) },
            onStopSimulator = actions.stopSimulator,
            onDismiss = actions.dismissCoach,
        )
    }

    if (state.commandPaletteOpen) {
        val indexedEntities by produceState(
            initialValue = CommandPaletteIndexedEntities(),
            workspace?.id,
            workspace?.teamId,
            workspace?.seasonId,
            workspace?.robotId,
            databaseService,
            notebookCaptureService,
            eventScoutingStore,
        ) {
            value = withContext(Dispatchers.IO) {
                val ws = workspace
                val db = databaseService
                val canQueryWorkspace = ws != null &&
                    db != null &&
                    ws.teamId.isNotBlank() &&
                    ws.seasonId.isNotBlank() &&
                    ws.robotId.isNotBlank()
                val sessions = if (canQueryWorkspace) {
                    runCatching {
                        db.getSessionsForWorkspace(
                            ws.teamId,
                            ws.seasonId,
                            ws.robotId,
                        )
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                val runs = buildCommandPaletteRunItems(sessions)
                val batteries = if (canQueryWorkspace) {
                    runCatching {
                        val specs = db.listBatterySpecsForWorkspace(
                            ws.teamId,
                            ws.seasonId,
                            ws.robotId,
                        )
                        val checkins = db.listBatteryCheckinsForWorkspace(
                            ws.teamId,
                            ws.seasonId,
                            ws.robotId,
                        )
                        val sessionIds = sessions.mapTo(HashSet(), Session::sessionId)
                        val summaries = if (sessionIds.isEmpty()) emptyList()
                        else db.getAllSessionSummaries().filter { it.sessionId in sessionIds }
                        buildCommandPaletteBatteryItems(
                            BatteryFleetAnalyzer.analyzeFleet(
                                league = ws.league,
                                specs = specs,
                                checkins = checkins,
                                sessions = sessions,
                                summaries = summaries,
                            )
                        )
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                val notes = if (workspace != null && notebookCaptureService != null) {
                    runCatching {
                        buildCommandPaletteNotebookItems(notebookCaptureService.listWorkspaceEntries(workspace, limit = 150))
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                val strategyTeams = if (workspace != null && eventScoutingStore != null) {
                    runCatching {
                        val bundle = eventScoutingStore.loadBundle(workspace)
                        val opr = OprAndSynergyAnalyzer.solveOprMetrics(workspace.league, bundle.matchResults)
                        val stand = OprAndSynergyAnalyzer.summarizeStandObservations(bundle.standObservations)
                        buildCommandPaletteStrategyTeamItems(opr, bundle.pitProfiles, stand)
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                CommandPaletteIndexedEntities(runs = runs, notes = notes, batteries = batteries, strategyTeams = strategyTeams)
            }
        }
        val matchItems = remember(state.matches, workspace?.teamId) {
            buildCommandPaletteMatchItems(state.matches, workspace?.teamId.orEmpty())
        }
        CommandPalette(
            developerMode = state.developerMode,
            projectPath = state.projectPath,
            recentTargets = state.recentTargets,
            strategyTeams = indexedEntities.strategyTeams,
            batteryItems = indexedEntities.batteries,
            matchItems = matchItems,
            runItems = indexedEntities.runs,
            notebookItems = indexedEntities.notes,
            onDismiss = actions.dismissCommandPalette,
            onNavigate = actions.navigate,
            onOpenGlossaryTerm = actions.openGlossaryTerm,
            onOpenRun = actions.openRunReplay,
            onOpenNotebookEntry = actions.openNotebookEntry,
            onOpenProjectItem = actions.openProjectItem,
            onOpenPitTab = actions.openPitTab,
            onOpenStrategyTab = actions.openStrategyTab,
            onOpenScoutedTeam = actions.openScoutedTeam,
            onOpenBattery = actions.openBatteryPack,
            onOpenScheduledMatch = actions.openScheduledMatch,
        )
    }

    WorkspaceDeletionDialog(
        pendingWorkspace = state.workspacePendingDeletion,
        onConfirm = actions.confirmWorkspaceDeletion,
        onDismiss = actions.dismissWorkspaceDeletion,
    )

    if (state.deployDialogOpen) {
        OneClickDeployDialog(
            state = if (state.deployAwaitingConfirmation) {
                DeployExecutionState(
                    projectPath = state.projectPath,
                    league = state.league,
                )
            } else {
                state.deployExecutionState
            },
            projectPath = state.projectPath,
            league = state.league,
            onConfirm = actions.confirmDeploy,
            onDismiss = actions.dismissDeploy,
            onCancel = actions.cancelDeploy,
        )
    }

    val availableUpdate = state.updateState as? UpdateCheckerService.UpdateState.UpdateAvailable
    if (availableUpdate != null && state.showUpdateBanner) {
        UpdateNotificationBanner(
            updateState = availableUpdate,
            onDismiss = actions.dismissUpdate,
        )
    }
}

@Composable
internal fun WorkspaceDeletionDialog(
    pendingWorkspace: Pair<String, String>?,
    onConfirm: (workspaceId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val (workspaceId, displayName) = pendingWorkspace ?: return
    AresDialog(
        title = "Remove this workspace?",
        onDismiss = onDismiss,
        icon = Icons.Default.Delete,
        variant = AresDialogVariant.DESTRUCTIVE,
        confirmText = "Remove workspace",
        onConfirm = { onConfirm(workspaceId) },
        dismissText = "Keep workspace",
    ) {
        Text(
            "ARES will remove the saved workspace settings for $displayName. " +
                "Your robot project files and imported run data will not be deleted.",
            color = AresTextSecondary,
        )
    }
}
