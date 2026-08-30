package com.ares.analytics.ui.screens

import androidx.compose.runtime.Composable
import com.ares.analytics.service.AcademyPracticeIdentity
import com.ares.analytics.service.AcademyPracticeWorkflowService
import com.ares.analytics.service.LearningProgressService
import com.ares.analytics.service.MatchInfo
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.SimulatorProcessService
import com.ares.analytics.service.isLoopbackDriveControlHost
import com.ares.analytics.service.project.ProjectExecutionCommand
import com.ares.analytics.shared.models.ForensicsResponse
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.components.NavigationTarget
import com.ares.analytics.ui.components.core.TargetSelection
import com.ares.analytics.ui.components.dashboard.DashboardMissionSnapshot
import com.ares.analytics.viewmodel.DashboardIntent
import com.ares.analytics.viewmodel.DashboardViewModel
import com.ares.analytics.viewmodel.FieldEditorViewModel
import com.ares.analytics.viewmodel.PathPlannerViewModel
import com.ares.analytics.viewmodel.SysIdViewModel
import com.ares.analytics.viewmodel.TuningIntent
import com.ares.analytics.viewmodel.TuningViewModel
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.ares.analytics.viewmodel.tuning.GuidedTuningExperimentIntent
import com.ares.analytics.viewmodel.tuning.GuidedTuningExperimentViewModel
import java.io.File

internal data class WorkspaceRouteFeatureScope(
    val dashboard: DashboardViewModel,
    val dashboardServices: DashboardFeatureServices,
    val pathPlanner: PathPlannerViewModel,
    val fieldEditor: FieldEditorViewModel,
    val runData: RunDataFeatureScope,
    val academy: AcademyRuntimeFeatureScope,
    val learningProgress: LearningProgressService,
    val academyPracticeWorkflow: AcademyPracticeWorkflowService,
    val tuning: TuningViewModel,
    val sysId: SysIdViewModel,
    val tuningExperiment: GuidedTuningExperimentViewModel,
    val authoring: RobotAuthoringFeatureScope,
    val workspaceServices: WorkspaceServicesFeatureScope,
    val nt4: Nt4ClientService,
    val simulator: SimulatorProcessService,
)

internal data class WorkspaceRouteState(
    val route: NavigationTarget,
    val workspace: WorkspaceConfig,
    val matches: List<MatchInfo>,
    val runsReloadTrigger: Int,
    val primarySessionId: String?,
    val targetSelection: TargetSelection,
    val simulatorRunning: Boolean,
    val localSimulatorOnline: Boolean,
    val nt4Connected: Boolean,
    val simulatorLaunchPreparationRunning: Boolean,
    val simulatorLaunchRequiresVerification: Boolean,
    val canLaunchSimulator: Boolean,
    val simulatorLaunchDisabledReason: String,
    val hardwareStudioInitialTab: HardwareStudioTab,
    val requestedLessonId: String?,
    val requestedGlossaryTerm: String?,
)

internal data class WorkspaceRouteActions(
    val navigate: (NavigationTarget) -> Unit,
    val saveWorkspace: (WorkspaceConfig) -> Unit,
    val reloadRuns: () -> Unit,
    val associateSessionWithMatch: (String, MatchInfo, String) -> Unit,
    val setDiagnosticsResponse: (ForensicsResponse) -> Unit,
    val updateMissionSnapshot: (DashboardMissionSnapshot) -> Unit,
    val requestSimulatorLaunch: () -> Unit,
    val startSimulator: () -> Unit,
    val selectTarget: (TargetSelection) -> Unit,
    val openCoach: () -> Unit,
    val createProject: () -> Unit,
    val openAcademyLesson: (String) -> Unit,
    val executeProjectCommand: (ProjectExecutionCommand) -> Boolean,
    val openInIde: () -> String,
)

/** Renders the selected workspace route from explicit feature capabilities and shell actions. */
@Composable
internal fun WorkspaceRouteHost(
    state: WorkspaceRouteState,
    scope: WorkspaceRouteFeatureScope,
    actions: WorkspaceRouteActions,
) {
    val workspace = state.workspace
    when (state.route) {
        NavigationTarget.DASHBOARD -> DashboardScreen(
            viewModel = scope.dashboard,
            services = scope.dashboardServices,
            currentConfig = workspace,
            isLocalSimulatorSelected = state.targetSelection == TargetSelection.LOCAL_SIM,
            isSimulatorLaunchPreparationRunning = state.simulatorLaunchPreparationRunning,
            simulatorLaunchRequiresVerification = state.simulatorLaunchRequiresVerification,
            canLaunchSimulator = state.canLaunchSimulator && !state.simulatorRunning,
            simulatorLaunchDisabledReason = state.simulatorLaunchDisabledReason,
            onLaunchSimulator = actions.requestSimulatorLaunch,
            matches = state.matches,
            onForensicsCompleted = actions.setDiagnosticsResponse,
            onSelectMatch = { match, allianceColor ->
                state.primarySessionId?.let { actions.associateSessionWithMatch(it, match, allianceColor) }
            },
            reloadTrigger = state.runsReloadTrigger,
            onImportSuccess = actions.reloadRuns,
            onNavigate = actions.navigate,
            onOpenKeybindings = { actions.navigate(NavigationTarget.CONTROLS) },
            onOpenRunHistory = { actions.navigate(NavigationTarget.RUN_HISTORY) },
            onOpenHelp = { actions.navigate(NavigationTarget.ACADEMY) },
            onMissionSnapshotChanged = actions.updateMissionSnapshot,
        )

        NavigationTarget.PATH_PLANNER -> PathPlannerScreen(
            viewModel = scope.pathPlanner,
            league = workspace.league,
            projectPath = workspace.projectPath,
            robotDimensions = RobotDimensions(
                lengthMeters = workspace.robotLengthMeters ?: RobotDimensions.defaultFor(workspace.league).lengthMeters,
                widthMeters = workspace.robotWidthMeters ?: RobotDimensions.defaultFor(workspace.league).widthMeters,
            ),
            onProjectPathChanged = { actions.saveWorkspace(workspace.copy(projectPath = it)) },
            onRobotDimensionsChanged = {
                actions.saveWorkspace(
                    workspace.copy(robotLengthMeters = it.lengthMeters, robotWidthMeters = it.widthMeters),
                )
            },
        )

        NavigationTarget.CLOUD,
        NavigationTarget.IMPORT_CENTER,
        NavigationTarget.GUIDED_RUN_ANALYSIS,
        NavigationTarget.RUN_HISTORY,
        NavigationTarget.DATABASE_VIEWER -> RunDataRouteHost(
            route = state.route,
            scope = scope.runData,
            workspace = workspace,
            reloadTrigger = state.runsReloadTrigger,
            actions = RunDataRouteActions(
                navigate = actions.navigate,
                reloadRuns = actions.reloadRuns,
                openDashboardReplay = { sessionId, timestampMs ->
                    scope.dashboard.onIntent(DashboardIntent.SelectPrimarySession(sessionId, timestampMs))
                    actions.navigate(NavigationTarget.DASHBOARD)
                },
                beginTuningExperiment = { seed ->
                    scope.tuningExperiment.onIntent(GuidedTuningExperimentIntent.Begin(seed))
                    actions.navigate(NavigationTarget.TUNING)
                },
                openAcademyLesson = actions.openAcademyLesson,
            ),
        )

        NavigationTarget.FIELD_EDITOR -> FieldEditorScreen(
            viewModel = scope.fieldEditor,
            league = workspace.league,
            projectPath = workspace.projectPath,
        )

        NavigationTarget.ACADEMY -> AcademyRuntimeHost(
            scope.academy,
            AcademyRuntimeEnvironment(
                isLocalSimulatorSelected = state.targetSelection == TargetSelection.LOCAL_SIM,
                isSimulatorRunning = state.simulatorRunning,
                isLocalSimulatorOnline = state.localSimulatorOnline,
                isNt4Connected = state.nt4Connected,
            ),
        ) { runtime ->
            AcademyScreen(
                progressService = scope.learningProgress,
                onOpenScreen = {
                    actions.openCoach()
                    actions.navigate(it)
                },
                onStartSimulator = {
                    actions.openCoach()
                    actions.startSimulator()
                },
                onCreatePracticeProject = actions.createProject,
                onInstallAndImportPracticeRuns = {
                    scope.academyPracticeWorkflow.installAndImport(
                        projectRoot = File(workspace.projectPath),
                        identity = AcademyPracticeIdentity(
                            teamId = workspace.teamId,
                            seasonId = workspace.seasonId,
                            robotId = workspace.robotId,
                        ),
                    ).also { actions.reloadRuns() }
                },
                onOpenImports = { actions.navigate(NavigationTarget.IMPORT_CENTER) },
                onOpenRunReview = {
                    actions.reloadRuns()
                    actions.navigate(NavigationTarget.GUIDED_RUN_ANALYSIS)
                },
                projectPath = workspace.projectPath,
                projectLabel = listOf(workspace.robotName, workspace.teamId)
                    .filter(String::isNotBlank)
                    .joinToString(" · "),
                initialLessonId = state.requestedLessonId,
                initialGlossaryTerm = state.requestedGlossaryTerm,
                runtime = runtime,
            )
        }

        NavigationTarget.KDOC_VIEWER -> KDocViewerScreen()
        NavigationTarget.PIT_DIAGNOSTICS -> HardwareSelfTestWizard(nt4ClientService = scope.nt4)
        NavigationTarget.MATCH_STRATEGY -> MatchStrategyScreen()
        NavigationTarget.TUNING -> TuningScreen(
            viewModel = scope.tuning,
            sysIdViewModel = scope.sysId,
            experimentViewModel = scope.tuningExperiment,
            projectPath = workspace.projectPath,
            canLaunchSimulator = state.canLaunchSimulator,
            canApplyCandidateToSimulator =
                state.targetSelection == TargetSelection.LOCAL_SIM &&
                    state.localSimulatorOnline &&
                    state.nt4Connected &&
                    isLoopbackDriveControlHost(scope.nt4.serverIp),
            simulatorStatus = when {
                state.simulatorRunning -> "managed simulator running"
                state.localSimulatorOnline -> "simulator online"
                else -> state.simulatorLaunchDisabledReason
            },
            onLaunchSimulator = actions.requestSimulatorLaunch,
            onApplyCandidateToSimulator = {
                scope.tuningExperiment.state.value.experiment?.let { experiment ->
                    if (
                        state.targetSelection == TargetSelection.LOCAL_SIM &&
                        state.localSimulatorOnline &&
                        state.nt4Connected &&
                        isLoopbackDriveControlHost(scope.nt4.serverIp)
                    ) {
                        scope.tuning.onIntent(TuningIntent.PushToRobot(experiment.change.key))
                    }
                }
            },
            onOpenDashboard = {
                actions.selectTarget(TargetSelection.LOCAL_SIM)
                actions.navigate(NavigationTarget.DASHBOARD)
            },
            onStopSimulator = scope.simulator::stop,
            onOpenGuidedRunReview = {
                actions.reloadRuns()
                actions.navigate(NavigationTarget.GUIDED_RUN_ANALYSIS)
            },
            onOpenReplay = { sessionId, timestampMs ->
                scope.dashboard.onIntent(DashboardIntent.SelectPrimarySession(sessionId, timestampMs))
                actions.navigate(NavigationTarget.DASHBOARD)
            },
        )

        NavigationTarget.ROBOT_STUDIO,
        NavigationTarget.CONTROLS,
        NavigationTarget.SUPERSTRUCTURE_STUDIO,
        NavigationTarget.HARDWARE_STUDIO,
        NavigationTarget.HARDWARE_SETUP,
        NavigationTarget.DRIVEBASE_BUILDER,
        NavigationTarget.SUBSYSTEM_GEN,
        NavigationTarget.PROJECT_IDENTITY -> RobotAuthoringRouteHost(
            route = state.route,
            scope = scope.authoring,
            config = workspace,
            hardwareStudioInitialTab = state.hardwareStudioInitialTab,
            actions = RobotAuthoringRouteActions(
                navigate = actions.navigate,
                runVerification = { actions.executeProjectCommand(ProjectExecutionCommand.VERIFY_AND_BUILD) },
                openInIde = actions.openInIde,
                createStandaloneProject = actions.createProject,
                refreshRobotStudio = scope.authoring.robotStudio::refresh,
            ),
        )

        NavigationTarget.PROFILE,
        NavigationTarget.PROJECT_BACKUP,
        NavigationTarget.INTEGRATIONS,
        NavigationTarget.ADMIN -> WorkspaceServicesRouteHost(
            route = state.route,
            scope = scope.workspaceServices,
            workspace = workspace,
            saveWorkspace = actions.saveWorkspace,
        )
    }
}
