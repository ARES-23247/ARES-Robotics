package com.ares.analytics.ui.screens

import androidx.compose.runtime.Composable
import com.ares.analytics.service.ManagedToolchainService
import com.ares.analytics.service.OAuthService
import com.ares.analytics.service.SyncEngineService
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.shared.models.IntegrationWorkspaceIdentity
import com.ares.analytics.ui.components.NavigationTarget
import com.ares.analytics.viewmodel.ProfileViewModel
import com.ares.analytics.viewmodel.ProjectBackupViewModel
import com.ares.analytics.viewmodel.integrationcenter.IntegrationCenterViewModel

/** Dependencies intentionally limited to workspace preferences, backup, and external integrations. */
internal data class WorkspaceServicesFeatureScope(
    val profile: ProfileViewModel,
    val projectBackup: ProjectBackupViewModel,
    val integrations: IntegrationCenterViewModel,
    val toolchains: ManagedToolchainService,
    val sync: SyncEngineService,
    val oauth: OAuthService,
)

@Composable
internal fun WorkspaceServicesRouteHost(
    route: NavigationTarget,
    scope: WorkspaceServicesFeatureScope,
    workspace: WorkspaceConfig,
    saveWorkspace: (WorkspaceConfig) -> Unit,
): Boolean = when (route) {
    NavigationTarget.PROFILE -> {
        ProfileScreen(
            viewModel = scope.profile,
            managedToolchainService = scope.toolchains,
            config = workspace,
            onConfigChanged = saveWorkspace,
        )
        true
    }

    NavigationTarget.PROJECT_BACKUP -> {
        ProjectBackupScreen(
            viewModel = scope.projectBackup,
            projectPath = workspace.projectPath,
        )
        true
    }

    NavigationTarget.INTEGRATIONS -> {
        IntegrationCenterScreen(
            viewModel = scope.integrations,
            workspace = IntegrationWorkspaceIdentity(
                teamId = workspace.teamId,
                seasonId = workspace.seasonId,
                robotId = workspace.robotId,
            ),
        )
        true
    }

    NavigationTarget.ADMIN -> {
        AdminScreen(
            syncEngineService = scope.sync,
            oauthService = scope.oauth,
            config = workspace,
        )
        true
    }

    else -> false
}
