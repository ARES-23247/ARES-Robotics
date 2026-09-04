package com.ares.analytics.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.ares.analytics.di.ServiceRegistry
import com.ares.analytics.service.ControlsDesignAssistant
import com.ares.analytics.service.DrivebaseDesignAssistant
import com.ares.analytics.service.SubsystemDesignAssistant
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.components.NavigationTarget
import com.ares.analytics.ui.components.dashboard.DashboardWidgetRegistry
import com.ares.analytics.viewmodel.CloudViewModel
import com.ares.analytics.viewmodel.DashboardViewModel
import com.ares.analytics.viewmodel.FieldEditorViewModel
import com.ares.analytics.viewmodel.ImportCenterIntent
import com.ares.analytics.viewmodel.ImportCenterViewModel
import com.ares.analytics.viewmodel.MainIntent
import com.ares.analytics.viewmodel.MainViewModel
import com.ares.analytics.viewmodel.PathPlannerViewModel
import com.ares.analytics.viewmodel.ProfileViewModel
import com.ares.analytics.viewmodel.ProjectBackupViewModel
import com.ares.analytics.viewmodel.SubsystemGeneratorViewModel
import com.ares.analytics.viewmodel.SysIdIntent
import com.ares.analytics.viewmodel.SysIdViewModel
import com.ares.analytics.viewmodel.TuningIntent
import com.ares.analytics.viewmodel.TuningViewModel
import com.ares.analytics.viewmodel.controls.ControlsEditorViewModel
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderViewModel
import com.ares.analytics.viewmodel.hardware.HardwareSetupViewModel
import com.ares.analytics.viewmodel.integrationcenter.IntegrationCenterViewModel
import com.ares.analytics.viewmodel.project.ProjectIdentityViewModel
import com.ares.analytics.viewmodel.robotstudio.RobotStudioViewModel
import com.ares.analytics.viewmodel.runanalysis.GuidedRunAnalysisViewModel
import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioViewModel
import com.ares.analytics.viewmodel.tuning.GuidedExperimentProposal
import com.ares.analytics.viewmodel.tuning.GuidedTuningExperimentViewModel
import com.areslib.controls.ControllerInputPlatform
import kotlinx.coroutines.CoroutineScope

/** Every mutable screen model owned by one canonical workspace and its cancellable scope. */
internal data class WorkspaceViewModelGraph(
    val dashboard: DashboardViewModel,
    val pathPlanner: PathPlannerViewModel,
    val fieldEditor: FieldEditorViewModel,
    val sysId: SysIdViewModel,
    val tuning: TuningViewModel,
    val profile: ProfileViewModel,
    val cloud: CloudViewModel,
    val importCenter: ImportCenterViewModel,
    val controlsEditor: ControlsEditorViewModel,
    val subsystemGenerator: SubsystemGeneratorViewModel,
    val drivebaseBuilder: DrivebaseBuilderViewModel,
    val superstructureStudio: SuperstructureStudioViewModel,
    val projectBackup: ProjectBackupViewModel,
    val integrationCenter: IntegrationCenterViewModel,
    val hardwareSetup: HardwareSetupViewModel,
    val robotStudio: RobotStudioViewModel,
    val projectIdentity: ProjectIdentityViewModel,
    val guidedRunAnalysis: GuidedRunAnalysisViewModel,
    val guidedTuningExperiment: GuidedTuningExperimentViewModel,
)

@Composable
internal fun rememberWorkspaceViewModelGraph(
    services: ServiceRegistry,
    config: WorkspaceConfig,
    workspaceScope: CoroutineScope,
    mainViewModel: MainViewModel,
    activeNavigation: NavigationTarget,
): WorkspaceViewModelGraph {
    val dashboard = remember(config.id) {
        DashboardViewModel(
            services.databaseService,
            services.nt4ClientService,
            services.alertEngineService,
            services.syncEngineService,
            services.hootDecoderService,
            services.logParserService,
            services.layoutPreferenceService,
            DashboardWidgetRegistry,
            workspaceScope,
        )
    }
    val pathPlanner = remember(config.id) {
        PathPlannerViewModel(
            scope = workspaceScope,
            nt4ClientService = services.nt4ClientService,
            projectGenerator = services.projectGenerator,
            checkpointRecorder = services.projectVersionControlService,
            projectSession = services.projectSession,
        )
    }
    val fieldEditor = remember(config.id) {
        FieldEditorViewModel(
            scope = workspaceScope,
            nt4ClientService = services.nt4ClientService,
            projectSession = services.projectSession,
        )
    }
    val sysId = remember(config.id) {
        SysIdViewModel(
            services.databaseService,
            services.sysIdService,
            services.driverAnalysisService,
            services.autoTunerService,
            services.nt4ClientService,
            workspaceScope,
            services.tuningProposalInbox,
        )
    }
    val platform = when (config.league) {
        League.FTC -> ControllerInputPlatform.FTC
        League.FRC -> ControllerInputPlatform.FRC
        League.XRP -> ControllerInputPlatform.XRP
    }
    val tuning = remember(config.id) {
        TuningViewModel(
            nt4ClientService = services.nt4ClientService,
            scope = workspaceScope,
            repository = services.tuningProfileRepository,
            proposalInbox = services.tuningProposalInbox,
            checkpointRecorder = services.projectVersionControlService,
            projectSession = services.projectSession,
            targetPlatform = platform,
        )
    }
    val profile = remember(config.id) {
        ProfileViewModel(
            services.oauthService,
            services.googleDriveService,
            services.syncEngineService,
            workspaceScope,
        )
    }
    val cloud = remember(config.id) {
        CloudViewModel(
            services.databaseService,
            services.syncEngineService,
            services.oauthService,
            services.nt4ClientService,
            services.robotLogIngestionService,
            config,
            workspaceScope,
        )
    }
    val importCenter = remember(config.id, config.projectPath) {
        ImportCenterViewModel(
            services.importArchiveService,
            services.manualLogImportService,
            config,
            workspaceScope,
        ) { mainViewModel.onIntent(MainIntent.TriggerRunsIndexReload) }
    }
    val controlsEditor = remember(config.id, config.projectPath, config.league) {
        ControlsEditorViewModel(
            projectPath = config.projectPath,
            league = config.league,
            projectGenerator = services.projectGenerator,
            checkpointRecorder = services.projectVersionControlService,
            designAssistant = ControlsDesignAssistant { current, context, request ->
                services.robotDesignAssistantService.requestControlsDesignProposal(current, context, request)
            },
            projectSession = services.projectSession,
        )
    }
    val subsystemGenerator = remember(config.id, config.projectPath, config.league) {
        SubsystemGeneratorViewModel(
            projectPath = config.projectPath,
            league = config.league,
            projectGenerator = services.projectGenerator,
            checkpointRecorder = services.projectVersionControlService,
            designAssistant = SubsystemDesignAssistant { current, request ->
                services.robotDesignAssistantService.requestSubsystemDesignProposal(current, request)
            },
            projectSession = services.projectSession,
        )
    }
    val drivebaseBuilder = remember(config.id, config.projectPath, config.robotId, config.league) {
        DrivebaseBuilderViewModel(
            projectPath = config.projectPath,
            projectId = config.robotId,
            league = config.league,
            scope = workspaceScope,
            repository = services.drivebaseProjectRepository,
            checkpointRecorder = services.projectVersionControlService,
            projectSession = services.projectSession,
            designAssistant = DrivebaseDesignAssistant { current, request ->
                services.robotDesignAssistantService.requestDrivebaseDesignProposal(current, request)
            },
        )
    }
    val superstructureStudio = remember(config.id, config.projectPath, config.league) {
        SuperstructureStudioViewModel(
            projectPath = config.projectPath,
            scope = workspaceScope,
            checkpointRecorder = services.projectVersionControlService,
            targetPlatform = platform,
            projectSession = services.projectSession,
        )
    }
    val projectBackup = remember(config.id, config.projectPath) {
        ProjectBackupViewModel(
            services.projectVersionControlService,
            services.projectRemoteBackupService,
            services.projectRecoveryService,
            services.githubAuthenticationService,
            services.projectBackupAutoSyncService,
            services.projectArchiveExporter,
            workspaceScope,
        )
    }
    val integrationCenter = remember(config.id) {
        IntegrationCenterViewModel(services.integrationCenterService, workspaceScope)
    }
    val hardwareSetup = remember(config.id, config.projectPath, config.league) {
        HardwareSetupViewModel(config.projectPath, config.league, services.hardwareSetupService, workspaceScope)
    }
    val robotStudio = remember(config.id) {
        RobotStudioViewModel(services.robotProjectReadinessService, workspaceScope)
    }
    val projectIdentity = remember(config.id) {
        ProjectIdentityViewModel(scope = workspaceScope, projectSession = services.projectSession)
    }
    val guidedRunAnalysis = remember(config.id) {
        GuidedRunAnalysisViewModel(
            service = services.guidedRunAnalysisService,
            comparisonService = services.runComparisonService,
            scope = workspaceScope,
        )
    }
    val guidedTuningExperiment = remember(config.id) {
        GuidedTuningExperimentViewModel(
            config,
            workspaceScope,
            services.guidedRunAnalysisService,
            services.guidedTuningExperimentRepository,
            services.guidedTuningExperimentEvaluator,
            tuningState = { tuning.state.value },
            stageProposal = { proposal: GuidedExperimentProposal ->
                tuning.onIntent(TuningIntent.UpdateTypedConstant(proposal.key, proposal.value))
                tuning.onIntent(
                    TuningIntent.SetProposalProvenance(
                        proposal.key,
                        proposal.provenance.source,
                        proposal.provenance.note,
                        proposal.provenance.evidencePath,
                        proposal.provenance.evidenceSha256,
                    ),
                )
            },
            removeProposal = { key ->
                services.simulatorProcessService.stop()
                tuning.onIntent(TuningIntent.RemoveProposal(key))
            },
        )
    }

    LaunchedEffect(config.league) {
        sysId.onIntent(SysIdIntent.ConfigurePlatform(config.league == League.FTC))
    }
    LaunchedEffect(activeNavigation) {
        if (activeNavigation != NavigationTarget.TUNING) {
            sysId.onIntent(SysIdIntent.DisarmCalibration("Left the Tuning screen"))
        }
    }
    LaunchedEffect(config.projectPath) {
        tuning.onIntent(TuningIntent.LoadConstants(config.projectPath))
    }
    LaunchedEffect(services.autoImportService, importCenter) {
        services.autoImportService.importNotifications.collect {
            importCenter.onIntent(ImportCenterIntent.Refresh)
        }
    }
    DisposableEffect(importCenter) { onDispose(importCenter::dispose) }
    DisposableEffect(controlsEditor) { onDispose(controlsEditor::close) }
    DisposableEffect(subsystemGenerator) { onDispose(subsystemGenerator::close) }
    DisposableEffect(cloud) { onDispose(cloud::dispose) }

    return WorkspaceViewModelGraph(
        dashboard,
        pathPlanner,
        fieldEditor,
        sysId,
        tuning,
        profile,
        cloud,
        importCenter,
        controlsEditor,
        subsystemGenerator,
        drivebaseBuilder,
        superstructureStudio,
        projectBackup,
        integrationCenter,
        hardwareSetup,
        robotStudio,
        projectIdentity,
        guidedRunAnalysis,
        guidedTuningExperiment,
    )
}
