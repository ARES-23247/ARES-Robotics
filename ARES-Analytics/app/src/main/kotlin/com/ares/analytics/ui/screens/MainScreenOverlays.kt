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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ares.analytics.domain.navigation.NavigationTarget
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.DeployExecutionState
import com.ares.analytics.service.LearningProgressService
import com.ares.analytics.service.UpdateCheckerService
import com.ares.analytics.service.integration.EngineeringNotebookCaptureService
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.components.CommandPalette
import com.ares.analytics.ui.components.CommandPaletteNotebookItem
import com.ares.analytics.ui.components.CommandPaletteRunItem
import com.ares.analytics.ui.components.LearningCoachDrawer
import com.ares.analytics.ui.components.buildCommandPaletteNotebookItems
import com.ares.analytics.ui.components.buildCommandPaletteRunItems
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
            initialValue = emptyList<CommandPaletteRunItem>() to emptyList<CommandPaletteNotebookItem>(),
            workspace?.id,
            workspace?.teamId,
            workspace?.seasonId,
            workspace?.robotId,
            databaseService,
            notebookCaptureService,
        ) {
            value = withContext(Dispatchers.IO) {
                val runs = if (
                    workspace != null &&
                    databaseService != null &&
                    workspace.teamId.isNotBlank() &&
                    workspace.seasonId.isNotBlank() &&
                    workspace.robotId.isNotBlank()
                ) {
                    runCatching {
                        buildCommandPaletteRunItems(
                            databaseService.getSessionsForWorkspace(
                                workspace.teamId,
                                workspace.seasonId,
                                workspace.robotId,
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
                runs to notes
            }
        }
        CommandPalette(
            developerMode = state.developerMode,
            projectPath = state.projectPath,
            runItems = indexedEntities.first,
            notebookItems = indexedEntities.second,
            onDismiss = actions.dismissCommandPalette,
            onNavigate = actions.navigate,
            onOpenGlossaryTerm = actions.openGlossaryTerm,
            onOpenRun = actions.openRunReplay,
            onOpenNotebookEntry = actions.openNotebookEntry,
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
