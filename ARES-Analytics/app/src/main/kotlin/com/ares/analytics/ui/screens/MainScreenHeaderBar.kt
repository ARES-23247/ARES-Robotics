package com.ares.analytics.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.learning.LearningCatalog
import com.ares.analytics.domain.navigation.NavigationTarget
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.components.QuickNavigationMenu
import com.ares.analytics.ui.components.SectionNavigationBar
import com.ares.analytics.ui.components.WorkspaceSelector
import com.ares.analytics.ui.components.core.ExecutionToolbar
import com.ares.analytics.ui.components.core.TargetSelection
import com.ares.analytics.ui.components.dashboard.DashboardCommandBar
import com.ares.analytics.ui.components.dashboard.DashboardMissionHeader
import com.ares.analytics.ui.components.dashboard.DashboardMissionSnapshot
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.viewmodel.DashboardShellState
import com.ares.analytics.viewmodel.robotstudio.RobotStudioShellState

@Composable
internal fun MainScreenHeaderBar(
    currentConfig: WorkspaceConfig,
    workspaces: List<WorkspaceConfig>,
    activeNav: NavigationTarget,
    dashboardMissionSnapshot: DashboardMissionSnapshot?,
    dashboardShellState: DashboardShellState,
    robotStudioShellState: RobotStudioShellState,
    targetSelection: TargetSelection,
    liveRobotIp: String,
    isLiveRobotOnline: Boolean,
    isLocalSimOnline: Boolean,
    isBuildRunning: Boolean,
    isSimRunning: Boolean,
    simulatorLaunchRequestEnabled: Boolean,
    simulatorLaunchDisabledReason: String?,
    activeCoachLessonId: String?,
    onSelectWorkspace: (String) -> Unit,
    onRemoveWorkspace: (WorkspaceConfig) -> Unit,
    onCreateWorkspace: () -> Unit,
    onExploreBiobuzz: () -> Unit,
    onExploreDemo: () -> Unit,
    onNavigate: (NavigationTarget) -> Unit,
    onSelectProfile: (String) -> Unit,
    onSaveLayoutAs: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onToggleEditing: (Boolean) -> Unit,
    onAddWidget: () -> Unit,
    onResetLayout: () -> Unit,
    onTargetChanged: (TargetSelection) -> Unit,
    onTargetIpChanged: (String) -> Unit,
    onRunBuild: () -> Unit,
    onRunSim: () -> Unit,
    onStopAll: () -> Unit,
    onOpenCoach: () -> Unit,
    onOpenLessonHelp: (String) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compactShell = maxWidth < 1450.dp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (compactShell) 6.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WorkspaceSelector(
                current = currentConfig,
                workspaces = workspaces,
                compact = compactShell,
                onSelect = onSelectWorkspace,
                onRemove = onRemoveWorkspace,
                onCreate = onCreateWorkspace,
                onExploreBiobuzz = onExploreBiobuzz,
                onExploreDemo = onExploreDemo,
            )

            val missionSnapshot = dashboardMissionSnapshot
            if (activeNav == NavigationTarget.DASHBOARD && missionSnapshot != null) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DashboardMissionHeader(
                        snapshot = missionSnapshot,
                        onNavigate = onNavigate,
                        modifier = Modifier.weight(1f),
                    )
                    if (dashboardShellState.hasLayout) {
                        DashboardCommandBar(
                            profileName = dashboardShellState.currentRoleProfile,
                            availableProfiles = dashboardShellState.availableProfiles,
                            isEditing = dashboardShellState.isLayoutEditing,
                            onSelectProfile = onSelectProfile,
                            onSaveLayoutAs = onSaveLayoutAs,
                            onDeleteProfile = onDeleteProfile,
                            onToggleEditing = { onToggleEditing(!dashboardShellState.isLayoutEditing) },
                            onAddWidget = onAddWidget,
                            onResetLayout = onResetLayout,
                            modifier = Modifier.widthIn(min = 145.dp, max = 250.dp),
                        )
                    }
                }
            } else {
                SectionNavigationBar(
                    activeTarget = activeNav,
                    onNavigate = onNavigate,
                    modifier = Modifier.weight(1f)
                )
            }

            ExecutionToolbar(
                projectPath = currentConfig.projectPath,
                targetSelection = targetSelection,
                targetIp = if (targetSelection == TargetSelection.LOCAL_SIM || isSimRunning) "127.0.0.1" else liveRobotIp,
                isLiveRobotOnline = isLiveRobotOnline,
                isLocalSimOnline = isLocalSimOnline,
                isBuildRunning = isBuildRunning,
                isSimRunning = isSimRunning,
                buildEnabled = robotStudioShellState.canRunBuild,
                buildDisabledReason = robotStudioShellState.buildDisabledReason,
                simulationEnabled = simulatorLaunchRequestEnabled,
                simulationDisabledReason = simulatorLaunchDisabledReason,
                onTargetChanged = onTargetChanged,
                onTargetIpChanged = onTargetIpChanged,
                onRunBuild = onRunBuild,
                onRunSim = onRunSim,
                onStopAll = onStopAll,
                compact = compactShell,
            )

            QuickNavigationMenu(
                onNavigate = onNavigate,
                compact = compactShell,
            )

            if (activeNav != NavigationTarget.ACADEMY && activeCoachLessonId != null) {
                if (compactShell) {
                    IconButton(onClick = onOpenCoach, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.School, "Open Robot Academy coach", tint = AresCyan, modifier = Modifier.size(18.dp))
                    }
                } else {
                    OutlinedButton(
                        onClick = onOpenCoach,
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 7.dp),
                    ) {
                        Icon(Icons.Default.School, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Coach", fontSize = 12.sp)
                    }
                }
            } else if (activeNav != NavigationTarget.ACADEMY) LearningCatalog.lessonFor(activeNav)?.let { lesson ->
                IconButton(
                    onClick = { onOpenLessonHelp(lesson.id) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "Help for ${activeNav.label}", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
