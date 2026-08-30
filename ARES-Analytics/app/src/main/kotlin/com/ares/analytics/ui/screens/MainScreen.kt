package com.ares.analytics.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import com.ares.analytics.di.ServiceRegistry
import com.ares.analytics.service.AutoImportService
import com.ares.analytics.service.BuildExecutionPhase
import com.ares.analytics.service.MatchInfo
import com.ares.analytics.service.UpdateCheckerService
import com.ares.analytics.service.isLoopbackDriveControlHost
import com.ares.analytics.service.project.ProjectExecutionCommand
import com.ares.analytics.shared.*
import com.ares.analytics.shared.models.*
import com.ares.analytics.ui.components.CommandPalette
import com.ares.analytics.ui.components.LearningCoachDrawer
import com.ares.analytics.ui.components.NavigationTarget
import com.ares.analytics.ui.components.QuickNavigationMenu
import com.ares.analytics.ui.components.SectionNavigationBar
import com.ares.analytics.ui.components.Sidebar
import com.ares.analytics.ui.components.core.TargetSelection
import com.ares.analytics.ui.input.DesktopDriveInputPublisher
import com.ares.analytics.ui.input.DesktopDriveKeyDispatcher
import com.ares.analytics.ui.components.core.ExecutionToolbar
import com.ares.analytics.ui.components.core.OneClickDeployDialog
import com.ares.analytics.ui.components.dashboard.DashboardCommandBar
import com.ares.analytics.ui.components.dashboard.DashboardMissionHeader
import com.ares.analytics.ui.components.dashboard.DashboardMissionSnapshot
import com.ares.analytics.ui.components.dashboard.DashboardAnalysisWidgetServices
import com.ares.analytics.ui.components.dashboard.DashboardLiveWidgetServices
import com.ares.analytics.ui.components.dashboard.DashboardReplayWidgetServices
import com.ares.analytics.ui.components.dashboard.DashboardWidgetServices
import com.ares.analytics.ui.components.dashboard.LocalSimulatorLaunchRequest
import com.ares.analytics.ui.components.dashboard.DashboardWidgetRegistry
import com.ares.analytics.ui.components.dashboard.localSimulatorLaunchRequest
import com.ares.analytics.ui.components.terminal.TerminalDrawer
import com.ares.analytics.ui.help.LearningCatalog
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.*
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderViewModel
import com.ares.analytics.viewmodel.hardware.HardwareSetupViewModel
import com.ares.analytics.viewmodel.project.ProjectIdentityViewModel
import com.ares.analytics.viewmodel.robotstudio.RobotStudioRuntimeEvidence
import com.ares.analytics.viewmodel.robotstudio.RobotStudioViewModel
import com.ares.analytics.viewmodel.runanalysis.GuidedRunAnalysisViewModel
import com.ares.analytics.viewmodel.tuning.GuidedExperimentProposal
import com.ares.analytics.viewmodel.tuning.GuidedTuningExperimentIntent
import com.ares.analytics.viewmodel.tuning.GuidedTuningExperimentViewModel
import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioViewModel
import com.ares.analytics.viewmodel.integrationcenter.IntegrationCenterViewModel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Root UI frame container and screen routing shell for the ARES Robotics Studio desktop application.
 *
 * Manages navigation sidebar targets ([NavigationTarget]), execution toolbars (Gradle build/deploy, ADB logcat, Sim launcher),
 * terminal output drawers, and global keyboard shortcuts (`Ctrl+Shift+B`, `Ctrl+Shift+R`).
 *
 * @param services Primary dependency container [ServiceRegistry].
 * @param currentConfig Active workspace configuration state.
 * @param onUpdateConfig Callback for saving modified workspace settings.
 *
 * @see NavigationTarget
 * @see com.ares.analytics.service.ProcessManagerService
 */
@Composable
fun MainScreen(services: ServiceRegistry) {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val mainViewModel = remember {
        MainViewModel(
            environmentService = services.environmentService,
            eventApiService = services.eventApiService,
            keybindingParserService = services.keybindingParserService,
            scope = scope
        )
    }
    val mainState by mainViewModel.state.collectAsState()
    val config = mainState.config
    val activeNav = mainState.activeNav
    val matches = mainState.matches
    val runsIndexReloadTrigger = mainState.runsIndexReloadTrigger
    val diagnosticsResponse = mainState.diagnosticsResponse
    val isTerminalOpen = mainState.isTerminalOpen
    val showUpdateBanner = mainState.showUpdateBanner
    val updateState by services.updateCheckerService.updateState.collectAsState()
    var commandPaletteOpen by remember { mutableStateOf(false) }
    var workspacePendingDeletion by remember { mutableStateOf<Pair<String, String>?>(null) }
    var requestedLessonId by remember { mutableStateOf<String?>(null) }
    var requestedGlossaryTerm by remember { mutableStateOf<String?>(null) }
    var coachDrawerOpen by remember { mutableStateOf(false) }
    var requestedProjectSetupMode by remember { mutableStateOf<ProjectSetupMode?>(null) }
    val learningProgress by services.learningProgressService.progress.collectAsState()
    val activeCoachLessonId = learningProgress.activeLessonId

    LaunchedEffect(activeCoachLessonId) {
        if (activeCoachLessonId == null) coachDrawerOpen = false
    }

    // Trigger update check on startup
    LaunchedEffect(Unit) {
        services.updateCheckerService.checkForUpdates()
    }
    val autoImportService = services.autoImportService

    LaunchedEffect(config) {
        services.transitionAutoImport(config) {
            if (config != null) {
                mainViewModel.onIntent(MainIntent.TriggerRunsIndexReload)
            }
        }
    }
    val currentConfig = config

    LaunchedEffect(
        currentConfig?.colorblindMode,
        currentConfig?.highContrastMode,
        currentConfig?.touchOptimizedMode,
        currentConfig?.largeTextMode
    ) {
        if (currentConfig != null) {
            AresThemeSettings.colorblindMode = currentConfig.colorblindMode
            AresThemeSettings.highContrastMode = currentConfig.highContrastMode
            AresThemeSettings.touchOptimizedMode = currentConfig.touchOptimizedMode
            AresThemeSettings.largeTextMode = currentConfig.largeTextMode
        }
    }

    val isNt4Connected by services.nt4ClientService.isConnected.collectAsState()

    if (currentConfig == null) {
        val onboardingViewModel = remember {
            OnboardingViewModel(
                environmentService = services.environmentService,
                syncEngineService = services.syncEngineService,
                googleDriveService = services.googleDriveService,
                projectTemplateService = services.robotProjectTemplateService,
                managedToolchainService = services.managedToolchainService,
                projectHistoryInitializer = NewProjectHistoryInitializer { stagedProjectPath ->
                    services.projectVersionControlService.initializeNewProject(stagedProjectPath)
                },
                scope = scope,
            ) { loaded ->
                mainViewModel.onIntent(MainIntent.SaveConfig(loaded))
                mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.ROBOT_STUDIO))
            }
        }
        LaunchedEffect(onboardingViewModel, requestedProjectSetupMode) {
            requestedProjectSetupMode?.let { mode ->
                onboardingViewModel.handleIntent(OnboardingIntent.SetProjectSetupMode(mode))
                requestedProjectSetupMode = null
            }
        }
        val showCancel = mainState.workspaces.isNotEmpty()
        OnboardingScreen(
            viewModel = onboardingViewModel,
            oauthService = services.oauthService,
            onCancel = if (showCancel) {
                {
                    requestedProjectSetupMode = null
                    mainViewModel.onIntent(MainIntent.CancelAddNewWorkspace)
                }
            } else null
        )
        return
    }

    // Instantiate ViewModels
    val dashboardViewModel = remember(currentConfig.id) {
        DashboardViewModel(
            databaseService = services.databaseService,
            nt4ClientService = services.nt4ClientService,
            alertEngineService = services.alertEngineService,
            syncEngineService = services.syncEngineService,
            hootDecoderService = services.hootDecoderService,
            logParserService = services.logParserService,
            layoutPreferenceService = services.layoutPreferenceService,
            widgetCatalog = DashboardWidgetRegistry,
            scope = scope
        )
    }
    val pathPlannerViewModel = remember(currentConfig.id) {
        PathPlannerViewModel(
            scope = scope,
            nt4ClientService = services.nt4ClientService,
            projectGenerator = services.projectGenerator,
            checkpointRecorder = services.projectVersionControlService,
            projectSession = services.projectSession,
        )
    }
    val fieldEditorViewModel = remember(currentConfig.id) {
        FieldEditorViewModel(
            scope = scope,
            nt4ClientService = services.nt4ClientService,
            projectSession = services.projectSession,
        )
    }
    val sysIdViewModel = remember(currentConfig.id) {
        SysIdViewModel(
            databaseService = services.databaseService,
            sysIdService = services.sysIdService,
            driverAnalysisService = services.driverAnalysisService,
            autoTunerService = services.autoTunerService,
            nt4ClientService = services.nt4ClientService,
            scope = scope,
            tuningProposalInbox = services.tuningProposalInbox
        )
    }
    val tuningViewModel = remember(currentConfig.id) {
        TuningViewModel(
            nt4ClientService = services.nt4ClientService,
            scope = scope,
            repository = services.tuningProfileRepository,
            proposalInbox = services.tuningProposalInbox,
            checkpointRecorder = services.projectVersionControlService,
            projectSession = services.projectSession,
            targetPlatform = when (currentConfig.league) {
                League.FTC -> com.areslib.controls.ControllerInputPlatform.FTC
                League.FRC -> com.areslib.controls.ControllerInputPlatform.FRC
            },
        )
    }
    LaunchedEffect(currentConfig.league) {
        sysIdViewModel.onIntent(SysIdIntent.ConfigurePlatform(currentConfig.league == League.FTC))
    }
    LaunchedEffect(activeNav) {
        if (activeNav != NavigationTarget.TUNING) {
            sysIdViewModel.onIntent(SysIdIntent.DisarmCalibration("Left the Tuning screen"))
        }
    }
    val profileViewModel = remember(currentConfig.id) {
        ProfileViewModel(
            oauthService = services.oauthService,
            googleDriveService = services.googleDriveService,
            syncEngineService = services.syncEngineService,
            scope = scope
        )
    }
    val cloudViewModel = remember(currentConfig.id) {
        com.ares.analytics.viewmodel.CloudViewModel(
            databaseService = services.databaseService,
            syncEngineService = services.syncEngineService,
            oauthService = services.oauthService,
            nt4ClientService = services.nt4ClientService,
            robotLogIngestionService = services.robotLogIngestionService,
            workspaceConfig = currentConfig,
            scope = scope
        )
    }
    val importCenterViewModel = remember(currentConfig.id, currentConfig.projectPath) {
        ImportCenterViewModel(
            archiveService = services.importArchiveService,
            manualLogImportService = services.manualLogImportService,
            workspace = currentConfig,
            scope = scope,
            onImportCompleted = {
                mainViewModel.onIntent(MainIntent.TriggerRunsIndexReload)
            },
        )
    }
    DisposableEffect(importCenterViewModel) {
        onDispose { importCenterViewModel.dispose() }
    }
    val controlsEditorViewModel = remember(currentConfig.projectPath, currentConfig.league) {
        com.ares.analytics.viewmodel.controls.ControlsEditorViewModel(
            projectPath = currentConfig.projectPath,
            league = currentConfig.league,
            projectGenerator = services.projectGenerator,
            checkpointRecorder = services.projectVersionControlService,
            designAssistant = com.ares.analytics.service.ControlsDesignAssistant { current, context, request ->
                services.syncEngineService.requestControlsDesignProposal(current, context, request)
            },
            projectSession = services.projectSession,
        )
    }
    DisposableEffect(controlsEditorViewModel) {
        onDispose { controlsEditorViewModel.close() }
    }
    LaunchedEffect(autoImportService, importCenterViewModel) {
        autoImportService.importNotifications.collect {
            importCenterViewModel.onIntent(ImportCenterIntent.Refresh)
        }
    }
    DisposableEffect(cloudViewModel) {
        onDispose {
            // CloudViewModel owns its own HttpClient; close it on screen exit to avoid
            // leaking the CIO engine + connection pool across navigations.
            cloudViewModel.dispose()
        }
    }
    val subsystemGeneratorViewModel = remember(currentConfig.projectPath, currentConfig.league) {
        SubsystemGeneratorViewModel(
            projectPath = currentConfig.projectPath,
            league = currentConfig.league,
            projectGenerator = services.projectGenerator,
            checkpointRecorder = services.projectVersionControlService,
            designAssistant = com.ares.analytics.service.SubsystemDesignAssistant { current, request ->
                services.syncEngineService.requestSubsystemDesignProposal(current, request)
            },
            projectSession = services.projectSession,
        )
    }
    DisposableEffect(subsystemGeneratorViewModel) {
        onDispose { subsystemGeneratorViewModel.close() }
    }
    val drivebaseBuilderViewModel = remember(currentConfig.projectPath, currentConfig.robotId, currentConfig.league) {
        DrivebaseBuilderViewModel(
            projectPath = currentConfig.projectPath,
            projectId = currentConfig.robotId,
            league = currentConfig.league,
            scope = scope,
            repository = services.drivebaseProjectRepository,
            checkpointRecorder = services.projectVersionControlService,
            projectSession = services.projectSession,
            designAssistant = com.ares.analytics.service.DrivebaseDesignAssistant { current, request ->
                services.syncEngineService.requestDrivebaseDesignProposal(current, request)
            },
        )
    }
    val superstructureStudioViewModel = remember(currentConfig.projectPath) {
        SuperstructureStudioViewModel(
            projectPath = currentConfig.projectPath,
            scope = scope,
            checkpointRecorder = services.projectVersionControlService,
            targetPlatform = when (currentConfig.league) {
                League.FTC -> com.areslib.controls.ControllerInputPlatform.FTC
                League.FRC -> com.areslib.controls.ControllerInputPlatform.FRC
            },
            projectSession = services.projectSession,
        )
    }
    val projectBackupViewModel = remember(currentConfig.projectPath) {
        ProjectBackupViewModel(
            service = services.projectVersionControlService,
            archiveExporter = services.projectArchiveExporter,
            scope = scope,
        )
    }
    val integrationCenterViewModel = remember {
        IntegrationCenterViewModel(services.integrationCenterService, scope)
    }
    val hardwareSetupViewModel = remember(currentConfig.projectPath, currentConfig.league) {
        HardwareSetupViewModel(
            projectPath = currentConfig.projectPath,
            league = currentConfig.league,
            service = services.hardwareSetupService,
            scope = scope,
        )
    }
    val robotStudioViewModel = remember(currentConfig.id) {
        RobotStudioViewModel(
            readinessService = services.robotProjectReadinessService,
            scope = scope,
        )
    }
    val projectIdentityViewModel = remember(currentConfig.id) {
        ProjectIdentityViewModel(scope = scope, projectSession = services.projectSession)
    }
    val guidedRunAnalysisViewModel = remember(currentConfig.id) {
        GuidedRunAnalysisViewModel(
            service = services.guidedRunAnalysisService,
            comparisonService = services.runComparisonService,
            scope = scope,
        )
    }
    LaunchedEffect(currentConfig.projectPath) {
        tuningViewModel.onIntent(TuningIntent.LoadConstants(currentConfig.projectPath))
    }
    val guidedTuningExperimentViewModel = remember(currentConfig.id) {
        GuidedTuningExperimentViewModel(
            workspace = currentConfig,
            scope = scope,
            runRepository = services.guidedRunAnalysisService,
            repository = services.guidedTuningExperimentRepository,
            evaluator = services.guidedTuningExperimentEvaluator,
            tuningState = { tuningViewModel.state.value },
            stageProposal = { proposal: GuidedExperimentProposal ->
                tuningViewModel.onIntent(TuningIntent.UpdateTypedConstant(proposal.key, proposal.value))
                tuningViewModel.onIntent(
                    TuningIntent.SetProposalProvenance(
                        key = proposal.key,
                        source = proposal.provenance.source,
                        note = proposal.provenance.note,
                        evidencePath = proposal.provenance.evidencePath,
                        evidenceSha256 = proposal.provenance.evidenceSha256,
                    )
                )
            },
            removeProposal = { key ->
                services.processManagerService.killActiveSim()
                tuningViewModel.onIntent(TuningIntent.RemoveProposal(key))
            },
        )
    }
    // This ViewModel owns no independent scope or hardware/service resource. Its jobs run in the
    // screen's Compose scope and are cancelled automatically when MainScreen leaves composition.
    val dashboardShellState by dashboardViewModel.shellState.collectAsState()
    var dashboardMissionSnapshot by remember(currentConfig.id) {
        mutableStateOf<DashboardMissionSnapshot?>(null)
    }
    val robotStudioShellState by robotStudioViewModel.shellState.collectAsState()
    val primarySessionId = dashboardShellState.primarySessionId
    val compareSessionId = dashboardShellState.compareSessionId
    val isConnected by services.nt4ClientService.isConnected.collectAsState()
    val processState by services.processManagerService.processState.collectAsState()
    val adbConnected = processState.adbConnected
    val isSimRunning = processState.simulatorRunning
    val activeSimulationProjectPath = processState.activeSimulationProjectPath
    val activeSimulationLeague = processState.activeSimulationLeague
    val isBuildRunning = processState.buildRunning
    val buildExecutionState = processState.buildExecution
    val deployExecutionState = processState.deployExecution
    var deployDialogOpen by remember { mutableStateOf(false) }
    var deployAwaitingConfirmation by remember { mutableStateOf(false) }
    var hardwareStudioInitialTab by remember { mutableStateOf(HardwareStudioTab.DRIVETRAIN) }
    var targetSelection by remember { mutableStateOf(TargetSelection.LIVE_ROBOT) }
    var liveRobotIp by remember(currentConfig.nt4Host) {
        mutableStateOf(currentConfig.nt4Host ?: "192.168.43.1")
    }
    LaunchedEffect(activeNav, targetSelection, isNt4Connected) {
        if (activeNav != NavigationTarget.DASHBOARD || !isNt4Connected) {
            services.keyboardDriveState.disarm()
        } else {
            services.keyboardDriveState.releaseAll()
        }
    }
    val isLiveRobotOnline by services.targetScannerService.isLiveRobotOnline.collectAsState()
    val isLocalSimOnline by services.targetScannerService.isLocalSimOnline.collectAsState()
    val localSimulatorControlAuthorized =
        activeNav == NavigationTarget.DASHBOARD &&
            targetSelection == TargetSelection.LOCAL_SIM &&
            isNt4Connected &&
            isLoopbackDriveControlHost(services.nt4ClientService.serverIp)
    DesktopDriveInputPublisher(
        nt4ClientService = services.nt4ClientService,
        keyboardState = services.keyboardDriveState,
        gamepadState = services.gamepadService.gamepad1State,
        connected = localSimulatorControlAuthorized,
        controlSurfaceActive = localSimulatorControlAuthorized,
        league = currentConfig.league,
    )
    val unmanagedSimulatorOnline = isLocalSimOnline && !isSimRunning
    val simulationProduct = robotStudioShellState.simulationProduct
    val simulatorLaunchEnabled = robotStudioShellState.canRunSimulation && simulationProduct != null && !unmanagedSimulatorOnline
    var pendingSimulatorLaunch by remember(currentConfig.id) { mutableStateOf(false) }
    val simulatorLaunchRequest = localSimulatorLaunchRequest(
        canRunSimulation = simulatorLaunchEnabled,
        canRunBuild = robotStudioShellState.canRunBuild,
        isBuildRunning = isBuildRunning,
        isSimulatorRunning = isSimRunning,
        isSimulatorOnline = isLocalSimOnline,
        isLaunchPending = pendingSimulatorLaunch,
    )
    val simulatorLaunchRequiresVerification = simulatorLaunchRequest == LocalSimulatorLaunchRequest.VERIFY_THEN_START
    val simulatorLaunchRequestEnabled = simulatorLaunchRequest != LocalSimulatorLaunchRequest.NONE
    val simulatorLaunchDisabledReason = if (unmanagedSimulatorOnline) {
        "A simulator is already online on port 5810. Use it from Dashboard, or stop it from the process that launched it."
    } else {
        robotStudioShellState.simulationDisabledReason
    }
    val executeProjectCommand: (ProjectExecutionCommand) -> Boolean = { command ->
        val decision = services.projectExecutionCoordinator.execute(currentConfig, command)
        if (decision.accepted) {
            mainViewModel.onIntent(MainIntent.SetTerminalOpen(true))
        } else {
            println("[ProjectSession] Rejected $command: ${decision.message}")
        }
        decision.accepted
    }
    val startSimulatorProcess: () -> Unit = {
        if (simulatorLaunchEnabled && !isSimRunning && !isLocalSimOnline) {
            if (executeProjectCommand(ProjectExecutionCommand.SIMULATE)) {
                targetSelection = TargetSelection.LOCAL_SIM
            }
        }
    }
    val requestSimulatorLaunch: () -> Unit = {
        when (simulatorLaunchRequest) {
            LocalSimulatorLaunchRequest.START_SIMULATOR -> startSimulatorProcess()
            LocalSimulatorLaunchRequest.VERIFY_THEN_START -> {
                pendingSimulatorLaunch = true
                targetSelection = TargetSelection.LOCAL_SIM
                executeProjectCommand(ProjectExecutionCommand.VERIFY_AND_BUILD)
            }
            LocalSimulatorLaunchRequest.NONE -> Unit
        }
    }
    DesktopDriveKeyDispatcher(
        state = services.keyboardDriveState,
        controlSurfaceActive = localSimulatorControlAuthorized,
    )

    LaunchedEffect(currentConfig, runsIndexReloadTrigger) {
        robotStudioViewModel.load(currentConfig)
        guidedRunAnalysisViewModel.load(currentConfig)
    }
    LaunchedEffect(
        buildExecutionState,
        deployExecutionState,
        isSimRunning,
        activeSimulationProjectPath,
        activeSimulationLeague,
        isLocalSimOnline,
        isNt4Connected,
    ) {
        robotStudioViewModel.updateRuntime(
            RobotStudioRuntimeEvidence(
                build = buildExecutionState,
                deploy = deployExecutionState,
                simulatorRunning = isSimRunning,
                simulatorProjectPath = activeSimulationProjectPath,
                simulatorLeague = activeSimulationLeague,
                localSimulatorOnline = isLocalSimOnline,
                nt4Connected = isNt4Connected,
            )
        )
    }
    LaunchedEffect(
        pendingSimulatorLaunch,
        buildExecutionState.phase,
        simulatorLaunchEnabled,
        isSimRunning,
        isLocalSimOnline,
    ) {
        if (!pendingSimulatorLaunch) return@LaunchedEffect
        when (buildExecutionState.phase) {
            BuildExecutionPhase.FAILED,
            BuildExecutionPhase.CANCELED -> pendingSimulatorLaunch = false
            BuildExecutionPhase.SUCCEEDED -> {
                if (simulatorLaunchEnabled && !isSimRunning && !isLocalSimOnline) {
                    pendingSimulatorLaunch = false
                    startSimulatorProcess()
                }
            }
            else -> Unit
        }
    }
    LaunchedEffect(buildExecutionState.requestId, buildExecutionState.phase) {
        if (
            buildExecutionState.requestId > 0L && buildExecutionState.phase in setOf(
                BuildExecutionPhase.SUCCEEDED,
                BuildExecutionPhase.FAILED,
                BuildExecutionPhase.CANCELED,
            )
        ) {
            robotStudioViewModel.refresh()
        }
    }
    val academyFeatureScope = remember(
        subsystemGeneratorViewModel,
        controlsEditorViewModel,
        tuningViewModel,
        superstructureStudioViewModel,
        pathPlannerViewModel,
        guidedRunAnalysisViewModel,
        robotStudioViewModel,
    ) {
        AcademyRuntimeFeatureScope(
            subsystem = subsystemGeneratorViewModel,
            controls = controlsEditorViewModel,
            tuning = tuningViewModel,
            superstructure = superstructureStudioViewModel,
            autonomous = pathPlannerViewModel,
            runAnalysis = guidedRunAnalysisViewModel,
            graduation = robotStudioViewModel,
        )
    }
    val academyEnvironment = AcademyRuntimeEnvironment(
        isLocalSimulatorSelected = targetSelection == TargetSelection.LOCAL_SIM,
        isSimulatorRunning = isSimRunning,
        isLocalSimulatorOnline = isLocalSimOnline,
        isNt4Connected = isNt4Connected,
    )

    // An active lesson continues observing evidence while its drawer is closed, without making
    // the entire shell subscribe to every authoring feature.
    if (activeCoachLessonId != null && activeNav != NavigationTarget.ACADEMY) {
        AcademyRuntimeHost(academyFeatureScope, academyEnvironment) { runtime ->
            LaunchedEffect(activeCoachLessonId, runtime) {
                services.learningProgressService.observeRuntime(runtime)
            }
        }
    }

    LaunchedEffect(liveRobotIp) {
        services.targetScannerService.startScanning(liveRobotIp)
    }

    // Auto-switch based on Most Recently Booted / Online status
    LaunchedEffect(isLocalSimOnline, isSimRunning, isLiveRobotOnline) {
        if (isLocalSimOnline || isSimRunning) {
            targetSelection = TargetSelection.LOCAL_SIM
        } else if (isLiveRobotOnline) {
            targetSelection = TargetSelection.LIVE_ROBOT
        }
    }

    // Start NT4 connection once config is resolved or target/simulator status changes
    LaunchedEffect(currentConfig, targetSelection, liveRobotIp, isSimRunning) {
        println(
            "[MainScreen LaunchedEffect] RUNNING: workspace=${currentConfig.id}, " +
                "targetSelection=$targetSelection, isSimRunning=$isSimRunning",
        )
        focusRequester.requestFocus()
        val host = if (targetSelection == TargetSelection.LOCAL_SIM) {
            "127.0.0.1"
        } else {
            liveRobotIp
        }
        println("[MainScreen LaunchedEffect] Computed host=$host")
        services.nt4ClientService.start(
            host = host,
            teamId = currentConfig.teamId,
            seasonId = currentConfig.seasonId,
            robotId = currentConfig.robotId
        )
        services.phoenixDiagnosticsService.start(host = host)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                val isCtrl = keyEvent.isCtrlPressed
                if (keyEvent.type == KeyEventType.KeyDown && isCtrl) {
                    when (keyEvent.key) {
                        Key.B -> {
                            if (robotStudioShellState.canRunBuild && !isBuildRunning) {
                                executeProjectCommand(ProjectExecutionCommand.VERIFY_AND_BUILD)
                            }
                            true
                        }
                        Key.D -> {
                            requestSimulatorLaunch()
                            true
                        }
                        Key.K -> {
                            if (keyEvent.isShiftPressed) {
                                services.processManagerService.killActiveBuild()
                                services.processManagerService.killActiveSim()
                            } else {
                                commandPaletteOpen = true
                            }
                            true
                        }
                        else -> false
                    }
                } else if (keyEvent.key == Key.Escape && keyEvent.type == KeyEventType.KeyDown) {
                    when {
                        commandPaletteOpen -> { commandPaletteOpen = false; true }
                        isTerminalOpen -> { mainViewModel.onIntent(MainIntent.SetTerminalOpen(false)); true }
                        else -> false
                    }
                } else false
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(AresBackground)
        ) {
            // ── Sidebar ──────────────────────────────────────────────────────
            Sidebar(
                activeTarget = activeNav,
                isConnected = isConnected,
                adbConnected = adbConnected,
                isSimRunning = isSimRunning,
                league = currentConfig.league,
                onNavigate = {
                    if (it == NavigationTarget.ACADEMY) { requestedLessonId = null; requestedGlossaryTerm = null }
                    mainViewModel.onIntent(MainIntent.SetActiveNav(it))
                },
                onOpenCommandPalette = { commandPaletteOpen = true },
                onToggleTerminal = { mainViewModel.onIntent(MainIntent.SetTerminalOpen(!isTerminalOpen)) }
            )

            // ── Content Area ─────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(10.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    // Top header bar with run config info
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val compactShell = maxWidth < 1450.dp
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(if (compactShell) 6.dp else 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                        // Dropdown Selector for active Workspace/Robot configuration
                        var dropdownExpanded by remember { mutableStateOf(false) }
                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { dropdownExpanded = true }
                                    .background(AresSurface)
                                    .border(1.dp, AresBorder, RoundedCornerShape(6.dp))
                                    .padding(horizontal = if (compactShell) 8.dp else 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val badgeBg = if (currentConfig.league == League.FTC) AresGold else AresCyan
                                Text(
                                    text = currentConfig.league.name,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AresBackground,
                                    modifier = Modifier
                                        .background(badgeBg, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )

                                Text(
                                    text = if (compactShell) currentConfig.robotId else "${currentConfig.robotId} (Team ${currentConfig.teamId})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = AresTextPrimary
                                )

                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = AresTextSecondary
                                )
                            }

                            DropdownMenu(
                                expanded = dropdownExpanded,
                                onDismissRequest = { dropdownExpanded = false },
                                modifier = Modifier.background(AresSurfaceElevated).border(1.dp, AresBorder)
                            ) {
                                Text(
                                    "MY ROBOTS",
                                    color = AresTextTertiary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                                mainState.workspaces.forEach { workspace ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.width(220.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "${workspace.robotId} (Team ${workspace.teamId})",
                                                        fontWeight = if (workspace.id == currentConfig.id) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (workspace.id == currentConfig.id) AresCyan else AresTextPrimary
                                                    )
                                                    Text(
                                                        text = "${workspace.league.name} • Season ${workspace.seasonId}",
                                                        fontSize = 11.sp,
                                                        color = AresTextSecondary
                                                    )
                                                }

                                                IconButton(
                                                    onClick = {
                                                        val displayName = workspace.robotName.ifBlank {
                                                            "${workspace.robotId} (Team ${workspace.teamId})"
                                                        }
                                                        workspacePendingDeletion = workspace.id to displayName
                                                        dropdownExpanded = false
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Delete,
                                                        contentDescription = "Remove workspace",
                                                        tint = AresError.copy(alpha = 0.8f),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            mainViewModel.onIntent(MainIntent.SelectWorkspace(workspace.id))
                                            dropdownExpanded = false
                                        }
                                    )
                                }

                                HorizontalDivider(color = AresBorder, modifier = Modifier.padding(vertical = 4.dp))

                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = null,
                                                tint = AresCyan,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text("Create or open a robot...", color = AresCyan, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    onClick = {
                                        requestedProjectSetupMode = ProjectSetupMode.CREATE_NEW
                                        mainViewModel.onIntent(MainIntent.AddNewWorkspace)
                                        dropdownExpanded = false
                                    }
                                )
                                HorizontalDivider(color = AresBorder, modifier = Modifier.padding(vertical = 4.dp))
                                Text(
                                    "EXAMPLES",
                                    color = AresTextTertiary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("Explore the demo robot", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                                            Text(
                                                "Create one editable, simulation-first FTC example",
                                                color = AresTextSecondary,
                                                fontSize = 11.sp,
                                            )
                                        }
                                    },
                                    onClick = {
                                        requestedProjectSetupMode = ProjectSetupMode.EXPLORE_DEMO
                                        mainViewModel.onIntent(MainIntent.AddNewWorkspace)
                                        dropdownExpanded = false
                                    },
                                )
                            }
                        }

                        val missionSnapshot = dashboardMissionSnapshot
                        if (activeNav == NavigationTarget.DASHBOARD && missionSnapshot != null) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                DashboardMissionHeader(
                                    snapshot = missionSnapshot,
                                    onNavigate = { mainViewModel.onIntent(MainIntent.SetActiveNav(it)) },
                                    modifier = Modifier.weight(1f),
                                )
                                if (dashboardShellState.hasLayout) {
                                    DashboardCommandBar(
                                        profileName = dashboardShellState.currentRoleProfile,
                                        availableProfiles = dashboardShellState.availableProfiles,
                                        isEditing = dashboardShellState.isLayoutEditing,
                                        onSelectProfile = { dashboardViewModel.onIntent(DashboardIntent.ChangeProfile(it)) },
                                        onSaveLayoutAs = { dashboardViewModel.onIntent(DashboardIntent.SaveLayoutAs(it)) },
                                        onDeleteProfile = { dashboardViewModel.onIntent(DashboardIntent.DeleteLayout(it)) },
                                        onToggleEditing = { dashboardViewModel.onIntent(DashboardIntent.SetLayoutEditing(!dashboardShellState.isLayoutEditing)) },
                                        onAddWidget = { dashboardViewModel.onIntent(DashboardIntent.SetPickerOpen(true)) },
                                        onResetLayout = { dashboardViewModel.onIntent(DashboardIntent.ResetProfile) },
                                        modifier = Modifier.widthIn(min = 145.dp, max = 250.dp),
                                    )
                                }
                            }
                        } else {
                            SectionNavigationBar(
                                activeTarget = activeNav,
                                onNavigate = { mainViewModel.onIntent(MainIntent.SetActiveNav(it)) },
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
                            onTargetChanged = { targetSelection = it },
                            onTargetIpChanged = { ip ->
                                if (targetSelection == TargetSelection.LIVE_ROBOT) {
                                    liveRobotIp = ip
                                }
                            },
                            onRunBuild = {
                                if (robotStudioShellState.canRunBuild) {
                                    executeProjectCommand(ProjectExecutionCommand.VERIFY_AND_BUILD)
                                }
                            },
                            onRunSim = requestSimulatorLaunch,
                            onStopAll = {
                                pendingSimulatorLaunch = false
                                services.processManagerService.killActiveBuild()
                                services.processManagerService.killActiveSim()
                            },
                            compact = compactShell,
                        )

                            QuickNavigationMenu(
                                onNavigate = { destination ->
                                    if (destination == NavigationTarget.ACADEMY) { requestedLessonId = null; requestedGlossaryTerm = null }
                                    mainViewModel.onIntent(MainIntent.SetActiveNav(destination))
                                },
                                compact = compactShell,
                            )

                            if (activeNav != NavigationTarget.ACADEMY && activeCoachLessonId != null) {
                                if (compactShell) {
                                    IconButton(onClick = { coachDrawerOpen = true }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.School, "Open Robot Academy coach", tint = AresCyan, modifier = Modifier.size(18.dp))
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { coachDrawerOpen = true },
                                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 7.dp),
                                    ) {
                                        Icon(Icons.Default.School, contentDescription = null, modifier = Modifier.size(17.dp))
                                        Spacer(Modifier.width(5.dp))
                                        Text("Coach", fontSize = 12.sp)
                                    }
                                }
                            } else if (activeNav != NavigationTarget.ACADEMY) LearningCatalog.lessonFor(activeNav)?.let { lesson ->
                                IconButton(
                                    onClick = {
                                        requestedLessonId = lesson.id
                                        mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.ACADEMY))
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "Help for ${activeNav.label}", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }

                    // ── Screen Router ────────────────────────────────────────
                    Box(modifier = Modifier.weight(1f)) {
                        when (activeNav) {
                            NavigationTarget.DASHBOARD -> {
                                val dashboardServices = remember(services) {
                                    DashboardFeatureServices(
                                        widgets = DashboardWidgetServices(
                                            live = DashboardLiveWidgetServices(
                                                nt4ClientService = services.nt4ClientService,
                                                alertEngineService = services.alertEngineService,
                                                dashboardHealthService = services.dashboardHealthService,
                                                keyboardDriveState = services.keyboardDriveState,
                                                gamepadService = services.gamepadService,
                                            ),
                                            analysis = DashboardAnalysisWidgetServices(
                                                databaseService = services.databaseService,
                                                advancedAnalyticsService = services.advancedAnalyticsService,
                                                syncEngineService = services.syncEngineService,
                                                driverAnalysisService = services.driverAnalysisService,
                                                diagnosticCoachService = services.diagnosticCoachService,
                                            ),
                                            replay = DashboardReplayWidgetServices(
                                                replayEngineService = services.replayEngineService,
                                            ),
                                        ),
                                        processManager = services.processManagerService,
                                        tuningProfiles = services.tuningProfileRepository,
                                    )
                                }
                                DashboardScreen(
                                    viewModel = dashboardViewModel,
                                    services = dashboardServices,
                                    currentConfig = currentConfig,
                                    isLocalSimulatorSelected = targetSelection == TargetSelection.LOCAL_SIM,
                                    isSimulatorLaunchPreparationRunning = pendingSimulatorLaunch,
                                    simulatorLaunchRequiresVerification = simulatorLaunchRequiresVerification,
                                    canLaunchSimulator = simulatorLaunchRequestEnabled && !isSimRunning,
                                    simulatorLaunchDisabledReason = simulatorLaunchDisabledReason,
                                    onLaunchSimulator = requestSimulatorLaunch,
                                    matches = matches,
                                    onForensicsCompleted = { mainViewModel.onIntent(MainIntent.SetDiagnosticsResponse(it)) },
                                    onSelectMatch = { match, allianceColor ->
                                        if (primarySessionId != null) {
                                            scope.launch {
                                                val opponents = if (allianceColor == "red") match.blueAlliance else match.redAlliance
                                                services.databaseService.associateSessionWithMatch(
                                                    sessionId = primarySessionId,
                                                    matchNumber = match.matchNumber,
                                                    allianceColor = allianceColor,
                                                    opponentTeams = opponents
                                                )
                                                mainViewModel.onIntent(MainIntent.TriggerRunsIndexReload)
                                            }
                                        }
                                    },
                                    reloadTrigger = runsIndexReloadTrigger,
                                    onImportSuccess = { mainViewModel.onIntent(MainIntent.TriggerRunsIndexReload) },
                                    onNavigate = { mainViewModel.onIntent(MainIntent.SetActiveNav(it)) },
                                    onOpenKeybindings = { mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.CONTROLS)) },
                                    onOpenRunHistory = { mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.RUN_HISTORY)) },
                                    onOpenHelp = { mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.ACADEMY)) },
                                    onMissionSnapshotChanged = { dashboardMissionSnapshot = it },
                                )
                            }
                            NavigationTarget.PATH_PLANNER -> PathPlannerScreen(
                                viewModel = pathPlannerViewModel,
                                league = currentConfig.league,
                                projectPath = currentConfig.projectPath,
                                robotDimensions = com.ares.analytics.viewmodel.pathing.RobotDimensions(
                                    lengthMeters = currentConfig.robotLengthMeters
                                        ?: com.ares.analytics.viewmodel.pathing.RobotDimensions
                                            .defaultFor(currentConfig.league).lengthMeters,
                                    widthMeters = currentConfig.robotWidthMeters
                                        ?: com.ares.analytics.viewmodel.pathing.RobotDimensions
                                            .defaultFor(currentConfig.league).widthMeters
                                ),
                                onProjectPathChanged = { selectedPath ->
                                    mainViewModel.onIntent(
                                        MainIntent.SaveConfig(currentConfig.copy(projectPath = selectedPath))
                                    )
                                },
                                onRobotDimensionsChanged = { dimensions ->
                                    mainViewModel.onIntent(
                                        MainIntent.SaveConfig(
                                            currentConfig.copy(
                                                robotLengthMeters = dimensions.lengthMeters,
                                                robotWidthMeters = dimensions.widthMeters
                                            )
                                        )
                                    )
                                }
                            )
                            NavigationTarget.CLOUD,
                            NavigationTarget.IMPORT_CENTER,
                            NavigationTarget.GUIDED_RUN_ANALYSIS,
                            NavigationTarget.RUN_HISTORY,
                            NavigationTarget.DATABASE_VIEWER -> RunDataRouteHost(
                                route = activeNav,
                                scope = RunDataFeatureScope(
                                    cloud = cloudViewModel,
                                    imports = importCenterViewModel,
                                    guidedAnalysis = guidedRunAnalysisViewModel,
                                    database = services.databaseService,
                                    sync = services.syncEngineService,
                                ),
                                workspace = currentConfig,
                                reloadTrigger = mainState.runsIndexReloadTrigger,
                                actions = RunDataRouteActions(
                                    navigate = { mainViewModel.onIntent(MainIntent.SetActiveNav(it)) },
                                    reloadRuns = { mainViewModel.onIntent(MainIntent.TriggerRunsIndexReload) },
                                    openDashboardReplay = { sessionId, timestampMs ->
                                        dashboardViewModel.onIntent(
                                            DashboardIntent.SelectPrimarySession(sessionId, timestampMs),
                                        )
                                        mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.DASHBOARD))
                                    },
                                    beginTuningExperiment = { seed ->
                                        guidedTuningExperimentViewModel.onIntent(GuidedTuningExperimentIntent.Begin(seed))
                                        mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.TUNING))
                                    },
                                    openAcademyLesson = { lessonId ->
                                        requestedLessonId = lessonId
                                        mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.ACADEMY))
                                    },
                                ),
                            )
                            NavigationTarget.FIELD_EDITOR -> FieldEditorScreen(
                                viewModel = fieldEditorViewModel,
                                league = currentConfig.league,
                                projectPath = currentConfig.projectPath
                            )
                            NavigationTarget.ACADEMY -> AcademyRuntimeHost(
                                academyFeatureScope,
                                academyEnvironment,
                            ) { runtime ->
                                AcademyScreen(
                                    progressService = services.learningProgressService,
                                    onOpenScreen = { destination ->
                                        coachDrawerOpen = true
                                        mainViewModel.onIntent(MainIntent.SetActiveNav(destination))
                                    },
                                    onStartSimulator = {
                                        coachDrawerOpen = true
                                        startSimulatorProcess()
                                    },
                                    onCreatePracticeProject = {
                                        requestedProjectSetupMode = ProjectSetupMode.CREATE_NEW
                                        mainViewModel.onIntent(MainIntent.AddNewWorkspace)
                                    },
                                    onInstallAndImportPracticeRuns = {
                                        val result = services.academyPracticeWorkflowService.installAndImport(
                                            projectRoot = File(currentConfig.projectPath),
                                            identity = com.ares.analytics.service.AcademyPracticeIdentity(
                                                teamId = currentConfig.teamId,
                                                seasonId = currentConfig.seasonId,
                                                robotId = currentConfig.robotId,
                                            ),
                                        )
                                        mainViewModel.onIntent(MainIntent.TriggerRunsIndexReload)
                                        result
                                    },
                                    onOpenImports = {
                                        mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.IMPORT_CENTER))
                                    },
                                    onOpenRunReview = {
                                        mainViewModel.onIntent(MainIntent.TriggerRunsIndexReload)
                                        mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.GUIDED_RUN_ANALYSIS))
                                    },
                                    projectPath = currentConfig.projectPath.orEmpty(),
                                    projectLabel = listOf(currentConfig.robotName, currentConfig.teamId)
                                        .filter(String::isNotBlank)
                                        .joinToString(" · "),
                                    initialLessonId = requestedLessonId,
                                    initialGlossaryTerm = requestedGlossaryTerm,
                                    runtime = runtime,
                                )
                            }
                            NavigationTarget.KDOC_VIEWER -> KDocViewerScreen()
                            NavigationTarget.PIT_DIAGNOSTICS -> HardwareSelfTestWizard(nt4ClientService = services.nt4ClientService)
                            NavigationTarget.MATCH_STRATEGY -> MatchStrategyScreen()
                            NavigationTarget.TUNING -> TuningScreen(
                                viewModel = tuningViewModel,
                                sysIdViewModel = sysIdViewModel,
                                experimentViewModel = guidedTuningExperimentViewModel,
                                projectPath = currentConfig.projectPath,
                                canLaunchSimulator = simulatorLaunchRequestEnabled,
                                canApplyCandidateToSimulator =
                                    targetSelection == TargetSelection.LOCAL_SIM &&
                                        isLocalSimOnline &&
                                        isNt4Connected &&
                                        isLoopbackDriveControlHost(services.nt4ClientService.serverIp),
                                simulatorStatus = when {
                                    isSimRunning -> "managed simulator running"
                                    isLocalSimOnline -> "simulator online"
                                    else -> simulatorLaunchDisabledReason
                                },
                                onLaunchSimulator = requestSimulatorLaunch,
                                onApplyCandidateToSimulator = {
                                    val experiment = guidedTuningExperimentViewModel.state.value.experiment
                                    if (
                                        experiment != null &&
                                        targetSelection == TargetSelection.LOCAL_SIM &&
                                        isLocalSimOnline &&
                                        isNt4Connected &&
                                        isLoopbackDriveControlHost(services.nt4ClientService.serverIp)
                                    ) {
                                        tuningViewModel.onIntent(TuningIntent.PushToRobot(experiment.change.key))
                                    }
                                },
                                onOpenDashboard = {
                                    targetSelection = TargetSelection.LOCAL_SIM
                                    mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.DASHBOARD))
                                },
                                onStopSimulator = { services.processManagerService.killActiveSim() },
                                onOpenGuidedRunReview = {
                                    mainViewModel.onIntent(MainIntent.TriggerRunsIndexReload)
                                    mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.GUIDED_RUN_ANALYSIS))
                                },
                                onOpenReplay = { sessionId, timestampMs ->
                                    dashboardViewModel.onIntent(DashboardIntent.SelectPrimarySession(sessionId, timestampMs))
                                    mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.DASHBOARD))
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
                                route = activeNav,
                                scope = RobotAuthoringFeatureScope(
                                    robotStudio = robotStudioViewModel,
                                    drivebase = drivebaseBuilderViewModel,
                                    subsystem = subsystemGeneratorViewModel,
                                    superstructure = superstructureStudioViewModel,
                                    pathPlanner = pathPlannerViewModel,
                                    controls = controlsEditorViewModel,
                                    hardwareSetup = hardwareSetupViewModel,
                                    projectIdentity = projectIdentityViewModel,
                                    gamepads = services.gamepadService,
                                ),
                                config = currentConfig,
                                hardwareStudioInitialTab = hardwareStudioInitialTab,
                                actions = RobotAuthoringRouteActions(
                                    navigate = { mainViewModel.onIntent(MainIntent.SetActiveNav(it)) },
                                    runVerification = {
                                        executeProjectCommand(ProjectExecutionCommand.VERIFY_AND_BUILD)
                                    },
                                    openInIde = {
                                        services.projectIdeLauncher.open(currentConfig.projectPath, currentConfig.league).message
                                    },
                                    createStandaloneProject = {
                                        requestedProjectSetupMode = ProjectSetupMode.CREATE_NEW
                                        mainViewModel.onIntent(MainIntent.AddNewWorkspace)
                                    },
                                    refreshRobotStudio = robotStudioViewModel::refresh,
                                ),
                            )
                            NavigationTarget.PROFILE,
                            NavigationTarget.PROJECT_BACKUP,
                            NavigationTarget.INTEGRATIONS,
                            NavigationTarget.ADMIN -> WorkspaceServicesRouteHost(
                                route = activeNav,
                                scope = WorkspaceServicesFeatureScope(
                                    profile = profileViewModel,
                                    projectBackup = projectBackupViewModel,
                                    integrations = integrationCenterViewModel,
                                    toolchains = services.managedToolchainService,
                                    sync = services.syncEngineService,
                                    oauth = services.oauthService,
                                ),
                                workspace = currentConfig,
                                saveWorkspace = { mainViewModel.onIntent(MainIntent.SaveConfig(it)) },
                            )
                        }
                    }
                }

                // Collapsible Terminal drawer overlay
                TerminalDrawer(
                    processManagerService = services.processManagerService,
                    projectPath = currentConfig.projectPath,
                    league = currentConfig.league,
                    isOpen = isTerminalOpen,
                    onClose = { mainViewModel.onIntent(MainIntent.SetTerminalOpen(false)) },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )

                // Critical Emergency Fault Alert Overlay (Pop-up Banner for Motor Stalls, Brownouts, Disconnects)
                Box(modifier = Modifier.align(Alignment.TopCenter)) {
                    com.ares.analytics.ui.components.dashboard.CriticalAlertOverlay(
                        alertEngineService = services.alertEngineService
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = coachDrawerOpen && activeNav != NavigationTarget.ACADEMY && activeCoachLessonId != null,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp),
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            LearningCoachDrawer(
                progressService = services.learningProgressService,
                onOpenAcademy = { lessonId ->
                    coachDrawerOpen = false
                    requestedLessonId = lessonId
                    mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.ACADEMY))
                },
                onOpenScreen = { destination ->
                    mainViewModel.onIntent(MainIntent.SetActiveNav(destination))
                },
                onSelectLocalSimulator = { targetSelection = TargetSelection.LOCAL_SIM },
                onStartSimulator = {
                    startSimulatorProcess()
                },
                onOpenDashboard = {
                    mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.DASHBOARD))
                },
                onStopSimulator = { services.processManagerService.killActiveSim() },
                onDismiss = { coachDrawerOpen = false },
            )
        }

        if (commandPaletteOpen) {
            CommandPalette(
                developerMode = currentConfig.developerMode,
                onDismiss = { commandPaletteOpen = false },
                onNavigate = {
                    if (it == NavigationTarget.ACADEMY) { requestedLessonId = null; requestedGlossaryTerm = null }
                    mainViewModel.onIntent(MainIntent.SetActiveNav(it))
                },
                onOpenGlossaryTerm = { termName ->
                    requestedGlossaryTerm = termName
                    requestedLessonId = null
                    mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.ACADEMY))
                }
            )
        }

        workspacePendingDeletion?.let { (workspaceId, displayName) ->
            AlertDialog(
                onDismissRequest = { workspacePendingDeletion = null },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = AresError
                    )
                },
                title = { Text("Remove this workspace?") },
                text = {
                    Text(
                        "ARES will remove the saved workspace settings for $displayName. " +
                            "Your robot project files and imported run data will not be deleted."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            mainViewModel.onIntent(MainIntent.DeleteWorkspace(workspaceId))
                            workspacePendingDeletion = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AresError, contentColor = AresOnAccent)
                    ) {
                        Text("Remove workspace")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { workspacePendingDeletion = null }) {
                        Text("Keep workspace")
                    }
                }
            )
        }

        if (deployDialogOpen) {
            OneClickDeployDialog(
                state = if (deployAwaitingConfirmation) {
                    com.ares.analytics.service.DeployExecutionState(
                        projectPath = currentConfig.projectPath,
                        league = currentConfig.league,
                    )
                } else {
                    deployExecutionState
                },
                projectPath = currentConfig.projectPath,
                league = currentConfig.league,
                onConfirm = {
                    deployAwaitingConfirmation = false
                    executeProjectCommand(ProjectExecutionCommand.DEPLOY)
                },
                onDismiss = {
                    deployDialogOpen = false
                    deployAwaitingConfirmation = false
                },
                onCancel = { services.processManagerService.killActiveBuild() },
            )
        }

        // ── Update Notification Banner ──────────────────────────────────────────
        val currentUpdateState = updateState
        if (currentUpdateState is UpdateCheckerService.UpdateState.UpdateAvailable && showUpdateBanner) {
            com.ares.analytics.ui.components.layout.UpdateNotificationBanner(
                updateState = currentUpdateState,
                onDismiss = { mainViewModel.onIntent(MainIntent.SetShowUpdateBanner(false)) }
            )
        }
    }
}
