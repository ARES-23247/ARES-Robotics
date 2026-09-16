package com.ares.analytics.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.ares.analytics.di.ServiceRegistry
import com.ares.analytics.domain.navigation.NavigationTarget
import com.ares.analytics.service.project.ProjectExecutionCommand
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.components.core.TargetSelection
import com.ares.analytics.ui.components.dashboard.DashboardAnalysisWidgetServices
import com.ares.analytics.ui.components.dashboard.DashboardLiveWidgetServices
import com.ares.analytics.ui.components.dashboard.DashboardMissionSnapshot
import com.ares.analytics.ui.components.dashboard.DashboardReplayWidgetServices
import com.ares.analytics.ui.components.dashboard.DashboardWidgetServices
import com.ares.analytics.viewmodel.MainIntent
import com.ares.analytics.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun rememberAcademyFeatureScope(
    models: WorkspaceViewModelGraph,
): AcademyRuntimeFeatureScope = remember(
    models.subsystemGenerator,
    models.controlsEditor,
    models.tuning,
    models.superstructureStudio,
    models.pathPlanner,
    models.guidedRunAnalysis,
    models.robotStudio,
) {
    AcademyRuntimeFeatureScope(
        subsystem = models.subsystemGenerator,
        controls = models.controlsEditor,
        tuning = models.tuning,
        superstructure = models.superstructureStudio,
        autonomous = models.pathPlanner,
        runAnalysis = models.guidedRunAnalysis,
        graduation = models.robotStudio,
    )
}

@Composable
internal fun rememberDashboardFeatureServices(
    services: ServiceRegistry,
): DashboardFeatureServices = remember(services) {
    DashboardFeatureServices(
        widgets = DashboardWidgetServices(
            live = DashboardLiveWidgetServices(
                nt4ClientService = services.nt4ClientService,
                xrpLinkService = services.xrpLinkService,
                alertEngineService = services.alertEngineService,
                dashboardHealthService = services.dashboardHealthService,
                keyboardDriveState = services.keyboardDriveState,
                gamepadService = services.gamepadService,
            ),
            analysis = DashboardAnalysisWidgetServices(
                databaseService = services.databaseService,
                advancedAnalyticsService = services.advancedAnalyticsService,
                aiDiagnosticsService = services.aiDiagnosticsService,
                driverAnalysisService = services.driverAnalysisService,
                diagnosticCoachService = services.diagnosticCoachService,
            ),
            replay = DashboardReplayWidgetServices(
                replayEngineService = services.replayEngineService,
            ),
        ),
        simulator = services.simulatorProcessService,
        tuningProfiles = services.tuningProfileRepository,
    )
}

@Composable
internal fun rememberWorkspaceRouteFeatureScope(
    models: WorkspaceViewModelGraph,
    services: ServiceRegistry,
    dashboardFeatureServices: DashboardFeatureServices,
    academyFeatureScope: AcademyRuntimeFeatureScope,
): WorkspaceRouteFeatureScope = remember(
    models.dashboard,
    models.pathPlanner,
    models.fieldEditor,
    models.cloud,
    models.importCenter,
    models.guidedRunAnalysis,
    academyFeatureScope,
    models.tuning,
    models.sysId,
    models.guidedTuningExperiment,
    models.robotStudio,
    models.drivebaseBuilder,
    models.subsystemGenerator,
    models.superstructureStudio,
    models.controlsEditor,
    models.hardwareSetup,
    models.projectIdentity,
    models.profile,
    models.projectBackup,
    models.integrationCenter,
    dashboardFeatureServices,
    services,
) {
    WorkspaceRouteFeatureScope(
        dashboard = models.dashboard,
        dashboardServices = dashboardFeatureServices,
        pathPlanner = models.pathPlanner,
        fieldEditor = models.fieldEditor,
        runData = RunDataFeatureScope(
            cloud = models.cloud,
            imports = models.importCenter,
            guidedAnalysis = models.guidedRunAnalysis,
            database = services.databaseService,
            sync = services.syncEngineService,
            aiDiagnostics = services.aiDiagnosticsService,
        ),
        academy = academyFeatureScope,
        learningProgress = services.learningProgressService,
        academyPracticeWorkflow = services.academyPracticeWorkflowService,
        tuning = models.tuning,
        sysId = models.sysId,
        tuningExperiment = models.guidedTuningExperiment,
        authoring = RobotAuthoringFeatureScope(
            robotStudio = models.robotStudio,
            drivebase = models.drivebaseBuilder,
            subsystem = models.subsystemGenerator,
            superstructure = models.superstructureStudio,
            pathPlanner = models.pathPlanner,
            controls = models.controlsEditor,
            hardwareSetup = models.hardwareSetup,
            projectIdentity = models.projectIdentity,
            gamepads = services.gamepadService,
        ),
        workspaceServices = WorkspaceServicesFeatureScope(
            profile = models.profile,
            projectBackup = models.projectBackup,
            integrations = models.integrationCenter,
            toolchains = services.managedToolchainService,
            sync = services.syncEngineService,
            oauth = services.oauthService,
        ),
        nt4 = services.nt4ClientService,
        simulator = services.simulatorProcessService,
    )
}

internal fun createWorkspaceRouteActions(
    mainViewModel: MainViewModel,
    workspaceScope: CoroutineScope,
    services: ServiceRegistry,
    currentConfig: WorkspaceConfig,
    updateMissionSnapshot: (DashboardMissionSnapshot) -> Unit,
    requestSimulatorLaunch: () -> Unit,
    startSimulator: () -> Unit,
    selectTarget: (TargetSelection) -> Unit,
    openCoach: () -> Unit,
    createProject: () -> Unit,
    openAcademyLesson: (String) -> Unit,
    openAcademyCheckpoint: (String, String) -> Unit,
    executeProjectCommand: (ProjectExecutionCommand) -> Boolean,
): WorkspaceRouteActions = WorkspaceRouteActions(
    navigate = { mainViewModel.onIntent(MainIntent.SetActiveNav(it)) },
    saveWorkspace = { mainViewModel.onIntent(MainIntent.SaveConfig(it)) },
    reloadRuns = { mainViewModel.onIntent(MainIntent.TriggerRunsIndexReload) },
    associateSessionWithMatch = { sessionId, match, allianceColor ->
        workspaceScope.launch {
            val opponents = if (allianceColor == "red") match.blueAlliance else match.redAlliance
            services.databaseService.associateSessionWithMatch(
                sessionId = sessionId,
                matchNumber = match.matchNumber,
                allianceColor = allianceColor,
                opponentTeams = opponents,
            )
            mainViewModel.onIntent(MainIntent.TriggerRunsIndexReload)
        }
    },
    setDiagnosticsResponse = {
        mainViewModel.onIntent(MainIntent.SetDiagnosticsResponse(it))
    },
    updateMissionSnapshot = updateMissionSnapshot,
    requestSimulatorLaunch = requestSimulatorLaunch,
    startSimulator = startSimulator,
    selectTarget = selectTarget,
    openCoach = openCoach,
    createProject = createProject,
    chooseStandaloneExport = { chooseStandaloneArchiveDestination(currentConfig) },
    exportStandaloneProject = { destination ->
        exportStandaloneArchive(currentConfig, services.projectArchiveExporter, destination)
    },
    openAcademyLesson = openAcademyLesson,
    openAcademyCheckpoint = openAcademyCheckpoint,
    executeProjectCommand = executeProjectCommand,
    openInIde = {
        services.projectIdeLauncher.open(
            currentConfig.projectPath,
            currentConfig.league,
        ).message
    },
)
