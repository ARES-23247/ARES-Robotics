package com.ares.analytics.di

import com.ares.analytics.service.*
import com.ares.analytics.service.log.*
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.components.dashboard.DashboardWidgetRegistry
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Enforces that import cancellation cleanup joins before any dependent resource is closed. */
internal suspend fun awaitAutoImportBeforeClosingDependencies(
    stopAutoImport: suspend () -> Unit,
    closeDependencies: suspend () -> Unit
) {
    stopAutoImport()
    closeDependencies()
}

/**
 * Centralized service registry that lazy-initializes all application services
 * in correct dependency order. Replaces the 20 separate `remember {}` blocks
 * that previously lived in Main.kt.
 *
 * The application owner must call [disposeAndJoin] from a background coroutine before exit.
 */
class ServiceRegistry {
    private val autoImportConfig = AtomicReference<WorkspaceConfig?>(null)
    private val autoImportTransitionMutex = Mutex()
    private val disposed = AtomicBoolean(false)

    // ── Tier 0: No dependencies ──────────────────────────────────────────────
    val databaseService by lazy { DatabaseService() }
    val environmentService by lazy { EnvironmentService() }
    val processManagerService by lazy { ProcessManagerService() }
    val adbService by lazy { AdbService() }
    val targetScannerService by lazy { TargetScannerService() }
    val keybindingParserService by lazy { KeybindingParserService() }
    val eventApiService by lazy { EventApiService() }
    val layoutPreferenceService by lazy { LayoutPreferenceService(widgetCatalog = DashboardWidgetRegistry) }
    val updateCheckerService by lazy { UpdateCheckerService() }
    val windowsUpdateService by lazy {
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
            followRedirects = true
            engine { requestTimeout = 60_000 }
        }
        WindowsUpdateService(
            downloadClient = KtorWindowsUpdateDownloadClient(client),
            trustedSignerThumbprints = com.ares.analytics.BuildConfig.WINDOWS_UPDATE_SIGNER_THUMBPRINTS
                .split(',')
                .filter(String::isNotBlank)
                .toSet(),
        )
    }
    val learningProgressService by lazy { LearningProgressService() }
    val academyPracticePackService by lazy { com.ares.analytics.service.AcademyPracticePackService() }
    val importArchiveService by lazy { ImportArchiveService() }
    val robotProjectTemplateService by lazy { com.ares.analytics.service.project.RobotProjectTemplateService() }
    val projectIdeLauncher by lazy { com.ares.analytics.service.project.ProjectIdeLauncher() }
    val managedToolchainService by lazy { ManagedToolchainService() }
    val projectVersionControlService by lazy { com.ares.analytics.service.versioncontrol.ProjectVersionControlService() }
    val projectArchiveExporter by lazy { com.ares.analytics.service.versioncontrol.ProjectArchiveExporter() }
    val hardwareSetupService by lazy { com.ares.analytics.service.hardware.HardwareSetupService() }
    val integrationSettingsService by lazy { com.ares.analytics.service.integration.IntegrationSettingsService() }
    val tuningProposalInbox by lazy { com.ares.analytics.service.tuning.TuningProposalInbox() }
    val drivebaseProjectRepository by lazy { com.ares.analytics.service.drivebase.DrivebaseProjectRepository() }
    val tuningProfileRepository by lazy { com.ares.analytics.service.tuning.TuningProfileRepository() }
    val guidedTuningExperimentRepository by lazy { com.ares.analytics.service.tuning.GuidedTuningExperimentRepository() }
    val projectSession by lazy {
        com.ares.analytics.service.project.ProjectSession(
            drivebaseRepository = drivebaseProjectRepository,
            tuningRepository = tuningProfileRepository,
        )
    }
    val projectExecutionCoordinator by lazy {
        com.ares.analytics.service.project.ProjectExecutionCoordinator(
            projectSession,
            com.ares.analytics.service.project.ProcessManagerProjectGateway(processManagerService),
        )
    }
    val projectGenerator by lazy {
        com.ares.analytics.service.project.SessionProjectGenerator(
            projectSession,
            projectExecutionCoordinator,
            processManagerService,
        )
    }

    // ── Tier 1: Depend on Tier 0 ─────────────────────────────────────────────
    val nt4ClientService by lazy { Nt4ClientService(databaseService) }
    val logParserService by lazy { LogParserService(databaseService, summaryEngineService) }
    val parquetExporterService by lazy { ParquetExporterService(databaseService) }
    val replayEngineService by lazy { ReplayEngineService(databaseService, nt4ClientService) }
    val sysIdService by lazy { SysIdService(databaseService) }
    val autoTunerService by lazy { AutoTunerService(nt4ClientService, sysIdService, tuningProposalInbox) }
    val calibrationService by lazy { CalibrationService(databaseService) }
    val oauthService by lazy { OAuthService(environmentService) }
    val exportService by lazy { ExportService(databaseService) }
    val advancedAnalyticsService by lazy { AdvancedAnalyticsService(databaseService) }
    val notificationIntegrationService by lazy {
        com.ares.analytics.service.integration.NotificationIntegrationService(
            databaseService,
            integrationSettingsService,
            googleDriveService,
        )
    }
    val integrationCenterService by lazy {
        com.ares.analytics.service.integration.IntegrationCenterService(
            settingsService = integrationSettingsService,
            store = databaseService.integrations,
            eventRecorder = databaseService.integrationEvents,
            reloadIntegrations = notificationIntegrationService::reload,
            configurationErrors = notificationIntegrationService::configurationErrors,
        )
    }
    val engineeringNotebookDraftService by lazy {
        com.ares.analytics.service.integration.EngineeringNotebookDraftService(
            databaseService = databaseService,
            aiProvider = com.ares.analytics.service.integration.JsonStructuredDraftProvider(
                providerId = "gemini.configured",
                model = com.ares.analytics.shared.DEFAULT_GEMINI_MODEL,
                requestJson = syncEngineService::requestNotebookDraftJson,
            ),
        )
    }

    // ── Tier 2: Depend on Tier 0 + Tier 1 ────────────────────────────────────
    val academyPracticeWorkflowService by lazy {
        com.ares.analytics.service.AcademyPracticeWorkflowService(
            academyPracticePackService,
            databaseService,
            logParserService,
        )
    }
    val alertEngineService by lazy { AlertEngineService(databaseService, nt4ClientService) }
    val driverAnalysisService by lazy { DriverAnalysisService(databaseService, sysIdService) }
    val diagnosticCoachService by lazy { DiagnosticCoachService(databaseService) }
    val runComparisonService by lazy { RunComparisonService(databaseService) }
    val guidedTuningExperimentEvaluator by lazy {
        com.ares.analytics.service.tuning.GuidedTuningExperimentEvaluator(runComparisonService)
    }
    val guidedRunAnalysisService by lazy {
        GuidedRunAnalysisService(
            databaseService = databaseService,
            importArchiveService = importArchiveService,
            advancedAnalyticsService = advancedAnalyticsService,
            diagnosticCoachService = diagnosticCoachService,
            driverAnalysisService = driverAnalysisService,
        )
    }
    val robotProjectReadinessService by lazy {
        RobotProjectReadinessService(
            databaseService = databaseService,
            projectSession = projectSession,
            hardwareSetupService = hardwareSetupService,
        )
    }
    private val automaticNotebookDraftService by lazy {
        com.ares.analytics.service.integration.EngineeringNotebookDraftService(databaseService)
    }
    val summaryEngineService by lazy {
        SummaryEngineService(databaseService, sysIdService, driverAnalysisService) { summary, alerts ->
            automaticNotebookDraftService.createSessionDraft(
                summary = summary,
                alerts = alerts,
                authorId = null,
                useAi = false,
            )
        }
    }
    val hootDecoderService by lazy { HootDecoderService(databaseService, summaryEngineService, sysIdService) }
    val autoImportService by lazy {
        AutoImportService(
            logParserService = logParserService,
            hootDecoderService = hootDecoderService,
            adbConnected = adbService.connected,
            configProvider = autoImportConfig::get
        )
    }
    val robotLogIngestionService by lazy {
        RobotLogIngestionService(databaseService, logParserService, autoImportService)
    }
    val manualLogImportService by lazy {
        ManualLogImportService(databaseService, logParserService, hootDecoderService, autoImportService)
    }
    val googleDriveService by lazy { GoogleDriveService(oauthService, environmentService) }
    val syncEngineService by lazy { SyncEngineService(databaseService, parquetExporterService, environmentService, summaryEngineService, googleDriveService) }
    val phoenixDiagnosticsService by lazy { PhoenixDiagnosticsService(nt4ClientService) }
    val dashboardHealthService by lazy {
        DashboardHealthService(nt4ClientService.telemetryStore, databaseService.metrics, nt4ClientService, replayEngineService)
    }

    /**
     * Opens DuckDB before [MainScreen] resolves database-backed services.
     *
     * The caller owns dispatcher selection. Desktop startup invokes this on [Dispatchers.IO] so
     * a cold native-library load, WAL recovery, or a large database cannot block the AWT event
     * thread before Compose creates a visible window.
     */
    internal fun prepareForMainScreen() {
        databaseService
        runBlocking { notificationIntegrationService.start() }
    }

    /** Applies a workspace transition only after the prior scanner generation has joined. */
    suspend fun transitionAutoImport(
        config: WorkspaceConfig?,
        onImportSuccess: () -> Unit
    ) = withContext(NonCancellable) {
        autoImportTransitionMutex.withLock {
            if (disposed.get()) return@withLock
            autoImportService.stop()
            autoImportConfig.set(config)
            if (config != null) autoImportService.start(onImportSuccess)
        }
    }

    /** Tears down services that hold coroutine scopes or background jobs, in dependency order. */
    internal suspend fun disposeAndJoin() {
        var telemetryPersisted = true
        if (lazyFieldInitialized(::updateCheckerService)) {
            updateCheckerService.dispose()
        }
        if (lazyFieldInitialized(::windowsUpdateService)) {
            windowsUpdateService.close()
        }
        if (lazyFieldInitialized(::integrationCenterService)) {
            integrationCenterService.close()
        }
        if (lazyFieldInitialized(::targetScannerService)) {
            targetScannerService.stopScanning()
        }
        // AutoImport can be inside a database transaction or filesystem staging move. Its scanner
        // must finish cancellation cleanup before NT4/process dependencies and DuckDB are closed.
        awaitAutoImportBeforeClosingDependencies(
            stopAutoImport = {
                autoImportTransitionMutex.withLock {
                    disposed.set(true)
                    autoImportConfig.set(null)
                    if (lazyFieldInitialized(::autoImportService)) autoImportService.stop()
                }
            },
            closeDependencies = {
                // Terminal disposal cancels and joins the WebSocket client job, then rejects
                // Compose-driven restarts while later process shutdown updates UI state.
                if (lazyFieldInitialized(::nt4ClientService)) {
                    telemetryPersisted = nt4ClientService.disposeAndJoin()
                }
                if (lazyFieldInitialized(::processManagerService)) {
                    processManagerService.shutdown()
                }
                if (lazyFieldInitialized(::adbService)) {
                    adbService.shutdownAndJoin()
                }
                  if (lazyFieldInitialized(::projectVersionControlService)) {
                      projectVersionControlService.closeAndJoin()
                  }
                if (lazyFieldInitialized(::replayEngineService)) {
                    replayEngineService.disposeAndJoin()
                }
                if (lazyFieldInitialized(::alertEngineService)) {
                    alertEngineService.dispose()
                }
                if (lazyFieldInitialized(::phoenixDiagnosticsService)) {
                    phoenixDiagnosticsService.dispose()
                }
                if (lazyFieldInitialized(::dashboardHealthService)) {
                    dashboardHealthService.dispose()
                }
                if (lazyFieldInitialized(::oauthService)) {
                    oauthService.dispose()
                }
                if (lazyFieldInitialized(::syncEngineService)) {
                    syncEngineService.close()
                }
                if (lazyFieldInitialized(::notificationIntegrationService)) {
                    notificationIntegrationService.closeAndJoin()
                }
                if (lazyFieldInitialized(::robotLogIngestionService)) {
                    robotLogIngestionService.close()
                }
                if (lazyFieldInitialized(::googleDriveService)) {
                    googleDriveService.dispose()
                }
                if (lazyFieldInitialized(::eventApiService)) {
                    eventApiService.close()
                }
                if (telemetryPersisted && lazyFieldInitialized(::databaseService)) {
                    databaseService.closeAndJoin()
                }
                if (lazyFieldInitialized(::gamepadService)) {
                    gamepadService.dispose()
                }
            }
        )
        if (!telemetryPersisted) {
            throw java.io.IOException(
                "Shutdown aborted before closing DuckDB because pending telemetry could not be persisted"
            )
        }
    }

    // ── Global Keyboard Drive State ──────────────────────────────────────────
    val keyboardDriveState by lazy { KeyboardDriveState() }

    // ── Gamepad Service ──────────────────────────────────────────────────────
    val gamepadService by lazy {
        GamepadService().apply { start() }
    }

    private fun lazyFieldInitialized(prop: kotlin.reflect.KProperty0<*>): Boolean {
        return try {
            (prop.getDelegate() as? Lazy<*>)?.isInitialized() == true
        } catch (t: Throwable) {
            true // Fallback to true so we dispose the service rather than leaking resources/jobs
        }
    }
}

class KeyboardDriveState {
    var enabled by androidx.compose.runtime.mutableStateOf(false)
    var useGamepad by androidx.compose.runtime.mutableStateOf(false)

    // Left Stick (W/A/S/D)
    var isWPressed by androidx.compose.runtime.mutableStateOf(false)
    var isSPressed by androidx.compose.runtime.mutableStateOf(false)
    var isAPressed by androidx.compose.runtime.mutableStateOf(false)
    var isDPressed by androidx.compose.runtime.mutableStateOf(false)

    // Right Stick (Arrow Keys)
    var isUpPressed by androidx.compose.runtime.mutableStateOf(false)
    var isDownPressed by androidx.compose.runtime.mutableStateOf(false)
    var isLeftPressed by androidx.compose.runtime.mutableStateOf(false)
    var isRightPressed by androidx.compose.runtime.mutableStateOf(false)

    // Face Buttons (J, L, U, I)
    var isJPressed by androidx.compose.runtime.mutableStateOf(false)
    var isLPressed by androidx.compose.runtime.mutableStateOf(false)
    var isUPressed by androidx.compose.runtime.mutableStateOf(false)
    var isIPressed by androidx.compose.runtime.mutableStateOf(false)

    // Bumpers (Q, E)
    var isQPressed by androidx.compose.runtime.mutableStateOf(false)
    var isEPressed by androidx.compose.runtime.mutableStateOf(false)

    // Trigger (Shift)
    var isShiftPressed by androidx.compose.runtime.mutableStateOf(false)

    fun releaseAll() {
        isWPressed = false
        isSPressed = false
        isAPressed = false
        isDPressed = false
        isUpPressed = false
        isDownPressed = false
        isLeftPressed = false
        isRightPressed = false
        isJPressed = false
        isLPressed = false
        isUPressed = false
        isIPressed = false
        isQPressed = false
        isEPressed = false
        isShiftPressed = false
    }

    fun disarm() {
        enabled = false
        useGamepad = false
        releaseAll()
    }
}
