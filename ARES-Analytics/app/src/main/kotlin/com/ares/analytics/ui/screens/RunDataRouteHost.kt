package com.ares.analytics.ui.screens

import androidx.compose.runtime.Composable
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.AiDiagnosticsService
import com.ares.analytics.service.SyncEngineService
import com.ares.analytics.service.tuning.GuidedTuningExperimentSeed
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.components.NavigationTarget
import com.ares.analytics.viewmodel.CloudViewModel
import com.ares.analytics.viewmodel.ImportCenterViewModel
import com.ares.analytics.viewmodel.runanalysis.GuidedRunAnalysisViewModel

/** Dependencies intentionally limited to run import, persistence, cloud sync, and guided analysis. */
internal data class RunDataFeatureScope(
    val cloud: CloudViewModel,
    val imports: ImportCenterViewModel,
    val guidedAnalysis: GuidedRunAnalysisViewModel,
    val database: DatabaseService,
    val sync: SyncEngineService,
    val aiDiagnostics: AiDiagnosticsService,
)

internal data class RunDataRouteActions(
    val navigate: (NavigationTarget) -> Unit,
    val reloadRuns: () -> Unit,
    val openDashboardReplay: (String, Long?) -> Unit,
    val beginTuningExperiment: (GuidedTuningExperimentSeed) -> Unit,
    val openAcademyLesson: (String) -> Unit,
)

/** Renders the run-data route family and returns false for routes owned by another feature. */
@Composable
internal fun RunDataRouteHost(
    route: NavigationTarget,
    scope: RunDataFeatureScope,
    workspace: WorkspaceConfig,
    reloadTrigger: Int,
    actions: RunDataRouteActions,
): Boolean = when (route) {
    NavigationTarget.CLOUD -> {
        CloudScreen(
            viewModel = scope.cloud,
            teamId = workspace.teamId,
            seasonId = workspace.seasonId,
            robotId = workspace.robotId,
        )
        true
    }

    NavigationTarget.IMPORT_CENTER -> {
        ImportCenterScreen(
            viewModel = scope.imports,
            projectPath = workspace.projectPath.orEmpty(),
            onOpenRunHistory = {
                actions.reloadRuns()
                actions.navigate(NavigationTarget.RUN_HISTORY)
            },
            onOpenGuidedAnalysis = {
                actions.reloadRuns()
                actions.navigate(NavigationTarget.GUIDED_RUN_ANALYSIS)
            },
            onOpenHelp = { actions.openAcademyLesson("compare-run-evidence") },
        )
        true
    }

    NavigationTarget.GUIDED_RUN_ANALYSIS -> {
        GuidedRunAnalysisScreen(
            viewModel = scope.guidedAnalysis,
            onOpenImports = { actions.navigate(NavigationTarget.IMPORT_CENTER) },
            onOpenDashboardReplay = actions.openDashboardReplay,
            onOpenTuning = { actions.navigate(NavigationTarget.TUNING) },
            onCreateTuningExperiment = actions.beginTuningExperiment,
            onOpenAcademy = { actions.openAcademyLesson("compare-run-evidence") },
            onOpenRunHistory = { actions.navigate(NavigationTarget.RUN_HISTORY) },
        )
        true
    }

    NavigationTarget.RUN_HISTORY -> {
        RunHistoryScreen(
            databaseService = scope.database,
            aiDiagnosticsService = scope.aiDiagnostics,
            workspace = workspace,
            reloadTrigger = reloadTrigger,
            onOpenImports = { actions.navigate(NavigationTarget.IMPORT_CENTER) },
            onOpenHelp = { actions.navigate(NavigationTarget.ACADEMY) },
        )
        true
    }

    NavigationTarget.DATABASE_VIEWER -> {
        DatabaseViewerScreen(databaseService = scope.database)
        true
    }

    else -> false
}
