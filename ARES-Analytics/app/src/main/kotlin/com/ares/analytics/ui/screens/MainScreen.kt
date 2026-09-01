package com.ares.analytics.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.di.ServiceRegistry
import com.ares.analytics.service.BuildExecutionPhase
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
import com.ares.analytics.ui.components.WorkspaceSelector
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
import com.ares.analytics.viewmodel.tuning.GuidedTuningExperimentViewModel
import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioViewModel
import com.ares.analytics.viewmodel.integrationcenter.IntegrationCenterViewModel
import kotlinx.coroutines.launch

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
 * @see com.ares.analytics.service.ProjectBuildService
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
            scope = scope,
            beforeWorkspaceChange = services::releaseWorkspaceOwnership,
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
    var requestedCheckpointId by remember { mutableStateOf<String?>(null) }
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

    val workspaceScope = rememberWorkspaceCoroutineScope(currentConfig.id)
    val workspaceModels = rememberWorkspaceViewModelGraph(
        services = services,
        config = currentConfig,
        workspaceScope = workspaceScope,
        mainViewModel = mainViewModel,
        activeNavigation = activeNav,
    )
    val dashboardViewModel = workspaceModels.dashboard
    val pathPlannerViewModel = workspaceModels.pathPlanner
    val fieldEditorViewModel = workspaceModels.fieldEditor
    val sysIdViewModel = workspaceModels.sysId
    val tuningViewModel = workspaceModels.tuning
    val profileViewModel = workspaceModels.profile
    val cloudViewModel = workspaceModels.cloud
    val importCenterViewModel = workspaceModels.importCenter
    val controlsEditorViewModel = workspaceModels.controlsEditor
    val subsystemGeneratorViewModel = workspaceModels.subsystemGenerator
    val drivebaseBuilderViewModel = workspaceModels.drivebaseBuilder
    val superstructureStudioViewModel = workspaceModels.superstructureStudio
    val projectBackupViewModel = workspaceModels.projectBackup
    val integrationCenterViewModel = workspaceModels.integrationCenter
    val hardwareSetupViewModel = workspaceModels.hardwareSetup
    val robotStudioViewModel = workspaceModels.robotStudio
    val projectIdentityViewModel = workspaceModels.projectIdentity
    val guidedRunAnalysisViewModel = workspaceModels.guidedRunAnalysis
    val guidedTuningExperimentViewModel = workspaceModels.guidedTuningExperiment
    // These view models share the keyed workspace scope and are cancelled together before a new
    // workspace can observe or mutate the prior project's state.
    val dashboardShellState by dashboardViewModel.shellState.collectAsState()
    var dashboardMissionSnapshot by remember(currentConfig.id) {
        mutableStateOf<DashboardMissionSnapshot?>(null)
    }
    val robotStudioShellState by robotStudioViewModel.shellState.collectAsState()
    val primarySessionId = dashboardShellState.primarySessionId
    val compareSessionId = dashboardShellState.compareSessionId
    val isConnected by services.nt4ClientService.isConnected.collectAsState()
    val processState by services.projectBuildService.processState.collectAsState()
    val deployExecutionState by services.robotDeploymentService.state.collectAsState()
    val adbConnected by services.adbService.connected.collectAsState()
    val simulatorState by services.simulatorProcessService.state.collectAsState()
    val isSimRunning = simulatorState.running
    val activeSimulationProjectPath = simulatorState.projectPath
    val activeSimulationLeague = simulatorState.league
    val isBuildRunning = processState.buildRunning
    val buildExecutionState = processState.buildExecution
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
    val dashboardFeatureServices = remember(services) {
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
    val workspaceRouteFeatureScope = remember(
        dashboardViewModel,
        pathPlannerViewModel,
        fieldEditorViewModel,
        cloudViewModel,
        importCenterViewModel,
        guidedRunAnalysisViewModel,
        academyFeatureScope,
        tuningViewModel,
        sysIdViewModel,
        guidedTuningExperimentViewModel,
        robotStudioViewModel,
        drivebaseBuilderViewModel,
        subsystemGeneratorViewModel,
        superstructureStudioViewModel,
        controlsEditorViewModel,
        hardwareSetupViewModel,
        projectIdentityViewModel,
        profileViewModel,
        projectBackupViewModel,
        integrationCenterViewModel,
        dashboardFeatureServices,
        services,
    ) {
        WorkspaceRouteFeatureScope(
            dashboard = dashboardViewModel,
            dashboardServices = dashboardFeatureServices,
            pathPlanner = pathPlannerViewModel,
            fieldEditor = fieldEditorViewModel,
            runData = RunDataFeatureScope(
                cloud = cloudViewModel,
                imports = importCenterViewModel,
                guidedAnalysis = guidedRunAnalysisViewModel,
                database = services.databaseService,
                sync = services.syncEngineService,
                aiDiagnostics = services.aiDiagnosticsService,
            ),
            academy = academyFeatureScope,
            learningProgress = services.learningProgressService,
            academyPracticeWorkflow = services.academyPracticeWorkflowService,
            tuning = tuningViewModel,
            sysId = sysIdViewModel,
            tuningExperiment = guidedTuningExperimentViewModel,
            authoring = RobotAuthoringFeatureScope(
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
            workspaceServices = WorkspaceServicesFeatureScope(
                profile = profileViewModel,
                projectBackup = projectBackupViewModel,
                integrations = integrationCenterViewModel,
                toolchains = services.managedToolchainService,
                sync = services.syncEngineService,
                oauth = services.oauthService,
            ),
            nt4 = services.nt4ClientService,
            simulator = services.simulatorProcessService,
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
                                services.projectBuildService.killActiveBuild()
                                services.robotDeploymentService.cancel()
                                services.simulatorProcessService.stop()
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
                        WorkspaceSelector(
                            current = currentConfig,
                            workspaces = mainState.workspaces,
                            compact = compactShell,
                            onSelect = { mainViewModel.onIntent(MainIntent.SelectWorkspace(it)) },
                            onRemove = { workspace ->
                                val displayName = workspace.robotName.ifBlank {
                                    "${workspace.robotId} (Team ${workspace.teamId})"
                                }
                                workspacePendingDeletion = workspace.id to displayName
                            },
                            onCreate = {
                                requestedProjectSetupMode = ProjectSetupMode.CREATE_NEW
                                mainViewModel.onIntent(MainIntent.AddNewWorkspace)
                            },
                            onExploreDemo = {
                                requestedProjectSetupMode = ProjectSetupMode.EXPLORE_DEMO
                                mainViewModel.onIntent(MainIntent.AddNewWorkspace)
                            },
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
                                services.projectBuildService.killActiveBuild()
                                services.robotDeploymentService.cancel()
                                services.simulatorProcessService.stop()
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

                    if (
                        !learningProgress.firstMissionNudgeDismissed &&
                        "start-simulator" !in learningProgress.startedLessonIds &&
                        activeNav != NavigationTarget.ACADEMY
                    ) {
                        FirstMissionNudge(
                            onStart = {
                                requestedLessonId = "start-simulator"
                                requestedCheckpointId = null
                                mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.ACADEMY))
                            },
                            onDismiss = { scope.launch { services.learningProgressService.dismissFirstMissionNudge() } },
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        WorkspaceRouteHost(
                            state = WorkspaceRouteState(
                                route = activeNav,
                                workspace = currentConfig,
                                matches = matches,
                                runsReloadTrigger = runsIndexReloadTrigger,
                                primarySessionId = primarySessionId,
                                targetSelection = targetSelection,
                                simulatorRunning = isSimRunning,
                                localSimulatorOnline = isLocalSimOnline,
                                nt4Connected = isNt4Connected,
                                simulatorLaunchPreparationRunning = pendingSimulatorLaunch,
                                simulatorLaunchRequiresVerification = simulatorLaunchRequiresVerification,
                                canLaunchSimulator = simulatorLaunchRequestEnabled,
                                simulatorLaunchDisabledReason = simulatorLaunchDisabledReason,
                                hardwareStudioInitialTab = hardwareStudioInitialTab,
                                requestedLessonId = requestedLessonId,
                                requestedCheckpointId = requestedCheckpointId,
                                requestedGlossaryTerm = requestedGlossaryTerm,
                            ),
                            scope = workspaceRouteFeatureScope,
                            actions = WorkspaceRouteActions(
                                navigate = { mainViewModel.onIntent(MainIntent.SetActiveNav(it)) },
                                saveWorkspace = { mainViewModel.onIntent(MainIntent.SaveConfig(it)) },
                                reloadRuns = { mainViewModel.onIntent(MainIntent.TriggerRunsIndexReload) },
                                associateSessionWithMatch = { sessionId, match, allianceColor ->
                                    workspaceScope.launch {
                                        val opponents = if (allianceColor == "red") {
                                            match.blueAlliance
                                        } else {
                                            match.redAlliance
                                        }
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
                                updateMissionSnapshot = { dashboardMissionSnapshot = it },
                                requestSimulatorLaunch = requestSimulatorLaunch,
                                startSimulator = startSimulatorProcess,
                                selectTarget = { targetSelection = it },
                                openCoach = { coachDrawerOpen = true },
                                createProject = {
                                    requestedProjectSetupMode = ProjectSetupMode.CREATE_NEW
                                    mainViewModel.onIntent(MainIntent.AddNewWorkspace)
                                },
                                openAcademyLesson = {
                                    requestedCheckpointId = null
                                    requestedLessonId = it
                                    mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.ACADEMY))
                                },
                                openAcademyCheckpoint = { lessonId, checkpointId ->
                                    requestedLessonId = lessonId
                                    requestedCheckpointId = checkpointId
                                    mainViewModel.onIntent(MainIntent.SetActiveNav(NavigationTarget.ACADEMY))
                                },
                                executeProjectCommand = executeProjectCommand,
                                openInIde = {
                                    services.projectIdeLauncher.open(
                                        currentConfig.projectPath,
                                        currentConfig.league,
                                    ).message
                                },
                            ),
                        )
                    }
                }
                // Collapsible Terminal drawer overlay
                TerminalDrawer(
                    projectBuildService = services.projectBuildService,
                    robotDeploymentService = services.robotDeploymentService,
                    adbService = services.adbService,
                    simulatorProcessService = services.simulatorProcessService,
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
                onStopSimulator = services.simulatorProcessService::stop,
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

        WorkspaceDeletionDialog(
            pendingWorkspace = workspacePendingDeletion,
            onConfirm = { workspaceId ->
                mainViewModel.onIntent(MainIntent.DeleteWorkspace(workspaceId))
                workspacePendingDeletion = null
            },
            onDismiss = { workspacePendingDeletion = null },
        )

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
                onCancel = { services.robotDeploymentService.cancel() },
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
