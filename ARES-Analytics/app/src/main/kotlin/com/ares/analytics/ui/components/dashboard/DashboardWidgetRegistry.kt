package com.ares.analytics.ui.components.dashboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.ares.analytics.service.DashboardLayoutConfig
import com.ares.analytics.service.MatchInfo
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.service.WidgetConfig
import com.ares.analytics.service.dashboard.DashboardWidgetCapability
import com.ares.analytics.service.dashboard.DashboardWidgetCatalog
import com.ares.analytics.service.dashboard.DashboardWidgetCategory
import com.ares.analytics.service.dashboard.DashboardWidgetPropertyKind
import com.ares.analytics.service.dashboard.DashboardWidgetPropertySpec
import com.ares.analytics.service.dashboard.DashboardWidgetServiceGroup
import com.ares.analytics.service.dashboard.DashboardWidgetSpec
import com.ares.analytics.service.dashboard.DashboardWidgetType
import com.ares.analytics.service.dashboard.allows
import com.ares.analytics.shared.models.ForensicsResponse
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.viewmodel.DashboardState
import com.areslib.tuning.TuningParameterDeclaration

typealias DashboardWidgetRenderer =
    @Composable (widget: WidgetConfig, context: DashboardWidgetRenderContext, modifier: Modifier) -> Unit

data class DashboardWidgetRenderContext(
    private val services: DashboardWidgetServices,
    private val declaredCapabilities: Set<DashboardWidgetCapability> = emptySet(),
    val workspace: WorkspaceConfig,
    val isRobotLinkConnected: Boolean,
    val dashboardState: DashboardState,
    val layout: DashboardLayoutConfig,
    val replayFrame: ReplayFrame?,
    val replaySessionStartMs: Long,
    val matches: List<MatchInfo>,
    val tuningDeclarations: List<TuningParameterDeclaration>,
    val reloadTrigger: Int,
    val onForensicsCompleted: (ForensicsResponse) -> Unit,
    val onSelectMatch: (MatchInfo, String) -> Unit,
    val onSelectPrimarySession: (String?) -> Unit,
    val onSelectCompareSession: (String?) -> Unit,
    val onOpenKeybindings: () -> Unit,
    val onUpdateProperties: (WidgetConfig, Map<String, String>) -> Unit,
) {
    val liveServices: DashboardLiveWidgetServices
        get() = requireServiceGroup(DashboardWidgetServiceGroup.LIVE, services.live)

    val analysisServices: DashboardAnalysisWidgetServices
        get() = requireServiceGroup(DashboardWidgetServiceGroup.ANALYSIS, services.analysis)

    val replayServices: DashboardReplayWidgetServices
        get() = requireServiceGroup(DashboardWidgetServiceGroup.REPLAY, services.replay)

    internal fun forDefinition(definition: DashboardWidgetDefinition): DashboardWidgetRenderContext =
        copy(declaredCapabilities = definition.capabilities)

    private fun <T> requireServiceGroup(group: DashboardWidgetServiceGroup, services: T): T {
        require(declaredCapabilities.allows(group)) {
            "Dashboard widget service access to ${group.name.lowercase()} was not declared"
        }
        return services
    }
}
data class DashboardWidgetDefinition(
    override val type: DashboardWidgetType,
    override val displayName: String,
    override val description: String,
    val icon: ImageVector,
    override val category: DashboardWidgetCategory,
    override val recommended: Boolean,
    override val defaultRowSpan: Int,
    override val defaultColSpan: Int,
    override val minimumRowSpan: Int,
    override val minimumColSpan: Int,
    override val capabilities: Set<DashboardWidgetCapability>,
    override val properties: List<DashboardWidgetPropertySpec>,
    val renderer: DashboardWidgetRenderer,
) : DashboardWidgetSpec

/**
 * The single registration point for dashboard widgets.
 *
 * Picker metadata, add-to-layout sizing, capability declarations, configuration schemas, and
 * Compose renderers all originate here. Persisted layouts continue to store only the stable
 * [DashboardWidgetType.serializedName].
 */
object DashboardWidgetRegistry : DashboardWidgetCatalog {
    private val live = setOf(DashboardWidgetCapability.LIVE_TELEMETRY)
    private val data = setOf(DashboardWidgetCapability.DATABASE)
    private val liveReplay = setOf(DashboardWidgetCapability.LIVE_TELEMETRY, DashboardWidgetCapability.REPLAY)
    private val dataReplay = setOf(DashboardWidgetCapability.DATABASE, DashboardWidgetCapability.REPLAY)

    val definitions: List<DashboardWidgetDefinition> = listOf(
        definition("driver_station", "Driver Station", "Select and run FTC OpModes from the dashboard.", Icons.Default.SportsEsports, DashboardWidgetCategory.LIVE, 3, 3, recommended = true, capabilities = live + DashboardWidgetCapability.ROBOT_CONTROL) { _, context, modifier ->
            FtcDriverStationWidget(context.liveServices.nt4ClientService, modifier)
        },
        definition("autonomous_selector", "Autonomous Selector", "Arm one of the generated routines compiled into the robot.", Icons.Default.Route, DashboardWidgetCategory.LIVE, 3, 5, recommended = true, capabilities = live + DashboardWidgetCapability.ROBOT_CONTROL) { _, context, modifier ->
            AutonomousSelectorCard(context.liveServices.nt4ClientService, modifier)
        },
        definition(
            "field_viewer", "Field 2D Viewer", "Live robot pose and trajectory on the game field.",
            Icons.Default.Map, DashboardWidgetCategory.LIVE, 6, 6, recommended = true,
            capabilities = liveReplay + DashboardWidgetCapability.DATABASE,
            properties = listOf(
                DashboardWidgetPropertySpec("rotation", "View rotation", DashboardWidgetPropertyKind.DECIMAL, "0", "degrees"),
                DashboardWidgetPropertySpec("show_tracer", "Show trajectory trace", DashboardWidgetPropertyKind.BOOLEAN, "false"),
            ),
        ) { widget, context, modifier ->
            FieldViewerCard(
                nt4ClientService = context.liveServices.nt4ClientService,
                currentFrame = context.replayFrame,
                databaseService = context.analysisServices.databaseService,
                replayStartTimestampMs = context.replaySessionStartMs,
                liveTransportConnected = context.isRobotLinkConnected,
                league = context.workspace.league,
                projectPath = context.workspace.projectPath,
                properties = widget.properties,
                onPropertiesChanged = { context.onUpdateProperties(widget, it) },
                modifier = modifier,
            )
        },
        definition(
            "telemetry_chart", "Live Telemetry Chart", "Searchable, scrolling multi-channel signal scope.",
            Icons.AutoMirrored.Filled.ShowChart, DashboardWidgetCategory.LIVE, 6, 6, recommended = true,
            capabilities = liveReplay + DashboardWidgetCapability.DATABASE,
            properties = listOf(
                DashboardWidgetPropertySpec("selectedKeys", "Selected topics", DashboardWidgetPropertyKind.TEXT),
                DashboardWidgetPropertySpec("windowSec", "Time window", DashboardWidgetPropertyKind.CHOICE, "30", "seconds", listOf("10", "30", "60", "120")),
            ),
        ) { widget, context, modifier ->
            TelemetryChartPanel(
                nt4ClientService = context.liveServices.nt4ClientService,
                databaseService = context.analysisServices.databaseService,
                currentFrame = context.replayFrame,
                properties = widget.properties,
                onPropertiesChanged = { context.onUpdateProperties(widget, it) },
                modifier = modifier,
            )
        },
        definition(
            "single_signal", "Single Signal", "One live or replay telemetry value with optional limits and a compact bar.",
            Icons.Default.MonitorHeart, DashboardWidgetCategory.LIVE, 3, 4, recommended = true, capabilities = liveReplay,
            properties = listOf(
                DashboardWidgetPropertySpec("topic", "Telemetry topic", DashboardWidgetPropertyKind.TOPIC, "Robot/LoopTimeMs"),
                DashboardWidgetPropertySpec("label", "Display label", DashboardWidgetPropertyKind.TEXT, "Loop time"),
                DashboardWidgetPropertySpec("unit", "Display unit", DashboardWidgetPropertyKind.TEXT, "ms"),
                DashboardWidgetPropertySpec("displayMode", "Display mode", DashboardWidgetPropertyKind.CHOICE, "value", choices = listOf("value", "bar")),
                DashboardWidgetPropertySpec("minimum", "Bar minimum", DashboardWidgetPropertyKind.DECIMAL, "0"),
                DashboardWidgetPropertySpec("maximum", "Bar maximum", DashboardWidgetPropertyKind.DECIMAL, "40"),
                DashboardWidgetPropertySpec("warningLow", "Warn below", DashboardWidgetPropertyKind.DECIMAL),
                DashboardWidgetPropertySpec("warningHigh", "Warn above", DashboardWidgetPropertyKind.DECIMAL, "25"),
            ),
        ) { widget, context, modifier ->
            SingleSignalWidget(
                nt4ClientService = context.liveServices.nt4ClientService,
                replayFrame = context.replayFrame,
                properties = widget.properties,
                onPropertiesChanged = { context.onUpdateProperties(widget, it) },
                modifier = modifier,
            )
        },
        definition("joystick_visualizer", "Gamepad Monitor", "Controller sticks, triggers, buttons, and command shaping.", Icons.Default.Gamepad, DashboardWidgetCategory.LIVE, 4, 6, capabilities = liveReplay + DashboardWidgetCapability.ROBOT_CONTROL) { _, context, modifier ->
            JoystickVisualizer(
                currentFrame = context.replayFrame,
                nt4ClientService = context.liveServices.nt4ClientService.takeIf { context.replayFrame == null },
                keyboardDriveState = context.liveServices.keyboardDriveState.takeIf { context.replayFrame == null },
                gamepadService = context.liveServices.gamepadService.takeIf { context.replayFrame == null },
                onOpenKeybindings = context.onOpenKeybindings,
                modifier = modifier,
            )
        },
        definition("mecanum_visualizer", "Mecanum Visualizer", "Wheel velocity, current, and traction-force vectors.", Icons.Default.Settings, DashboardWidgetCategory.LIVE, 4, 6, capabilities = liveReplay) { _, context, modifier ->
            MecanumVisualizer(context.replayFrame, context.liveServices.nt4ClientService.takeIf { context.replayFrame == null }, modifier)
        },
        definition("swerve_animator", "Swerve Visualizer", "Target and measured module vectors.", Icons.Default.DirectionsCar, DashboardWidgetCategory.LIVE, 4, 6, capabilities = liveReplay) { _, context, modifier ->
            SwerveModuleVisualizer(context.liveServices.nt4ClientService, context.replayFrame, modifier)
        },
        definition("mechanism_visualizer", "Linkage Animator", "Arm, slide, and mechanism motion rendering.", Icons.Default.Build, DashboardWidgetCategory.LIVE, 4, 6, capabilities = liveReplay) { _, context, modifier ->
            MechanismVisualizer(context.replayFrame, context.liveServices.nt4ClientService.takeIf { context.replayFrame == null }, modifier)
        },
        definition(
            "camera_stream", "Camera Stream", "Limelight, PhotonVision, or WPILib MJPEG feed.",
            Icons.Default.Videocam, DashboardWidgetCategory.LIVE, 6, 6, capabilities = live + DashboardWidgetCapability.CAMERA,
            properties = listOf(DashboardWidgetPropertySpec("streamUrl", "Camera stream URL", DashboardWidgetPropertyKind.TEXT)),
        ) { widget, context, modifier ->
            CameraStreamCard(widget.properties, { context.onUpdateProperties(widget, it) }, modifier)
        },
        definition("indicator_lights", "Indicator Lights", "Live GoBilda PWM indicator-light state.", Icons.Default.Lightbulb, DashboardWidgetCategory.LIVE, 3, 4, capabilities = liveReplay) { _, context, modifier ->
            IndicatorLightsCard(context.liveServices.nt4ClientService, context.replayFrame, modifier)
        },

        definition("advanced_analytics", "Advanced Analytics", "Regressions, driver score, heatmap, correlations, and tuning confidence.", Icons.Default.Insights, DashboardWidgetCategory.ANALYSIS, 5, 6, recommended = true, capabilities = dataReplay) { _, context, modifier ->
            AdvancedAnalyticsCard(context.analysisServices.advancedAnalyticsService, context.dashboardState.primarySessionId, context.dashboardState.compareSessionId, modifier)
        },
        definition("statistics_panel", "Signal Statistics", "Distributions, descriptive statistics, and error forensics.", Icons.Default.Analytics, DashboardWidgetCategory.ANALYSIS, 4, 5, capabilities = dataReplay) { _, context, modifier ->
            StatisticsPanel(context.analysisServices.databaseService, context.dashboardState.primarySessionId, modifier)
        },
        definition("trends_card", "Battery Trends", "Multi-session degradation and regression trends.", Icons.AutoMirrored.Filled.TrendingDown, DashboardWidgetCategory.ANALYSIS, 4, 5, capabilities = data) { _, context, modifier ->
            TrendsCard(context.analysisServices.databaseService, modifier)
        },
        definition("session_summary", "Session Summary", "Headline metrics for the selected recording.", Icons.Default.Summarize, DashboardWidgetCategory.ANALYSIS, 3, 3, capabilities = dataReplay) { _, context, modifier ->
            SessionSummaryCard(context.analysisServices.databaseService, context.dashboardState.primarySessionId, modifier)
        },
        definition("ai_coach", "AI Forensics Coach", "Evidence-backed pit diagnostics and repair guidance.", Icons.Default.Psychology, DashboardWidgetCategory.ANALYSIS, 5, 6, capabilities = dataReplay + DashboardWidgetCapability.CLOUD) { _, context, modifier ->
            AiCoachPanel(context.analysisServices.databaseService, context.analysisServices.aiDiagnosticsService, context.dashboardState.primarySessionId, context.onForensicsCompleted, modifier)
        },
        definition("driver_motion_review", "Driver Motion Review", "Practice prompts from timestamp-synchronized chassis motion; never a driver score.", Icons.Default.SportsEsports, DashboardWidgetCategory.ANALYSIS, 5, 6, capabilities = dataReplay) { _, context, modifier ->
            DriverMotionReviewWidget(context.analysisServices.driverAnalysisService, context.dashboardState.primarySessionId, modifier)
        },
        definition("pit_evidence_checklist", "Pit Evidence Checklist", "Observed telemetry thresholds, possible causes, and verification steps without pretending to diagnose.", Icons.AutoMirrored.Filled.FactCheck, DashboardWidgetCategory.DIAGNOSTICS, 5, 6, capabilities = dataReplay) { _, context, modifier ->
            DiagnosticChecklistWidget(context.analysisServices.diagnosticCoachService, context.dashboardState.primarySessionId, modifier)
        },
        definition("vision_quality", "Vision & EKF Quality", "AprilTag acceptance, latency, and estimator quality.", Icons.Default.Camera, DashboardWidgetCategory.ANALYSIS, 3, 4, capabilities = dataReplay) { _, context, modifier ->
            VisionQualityCard(context.analysisServices.databaseService, context.dashboardState.primarySessionId, modifier)
        },
        definition("motor_health", "Motor Health", "Current draw, thermal risk, and stall warnings.", Icons.Default.ElectricBolt, DashboardWidgetCategory.ANALYSIS, 3, 4, capabilities = dataReplay) { _, context, modifier ->
            MotorHealthCard(context.analysisServices.databaseService, context.dashboardState.primarySessionId, modifier)
        },

        definition("system_health", "Dashboard & Robot Health", "Ingest, query, cache, reconnect, loop, and battery health.", Icons.Default.Memory, DashboardWidgetCategory.DIAGNOSTICS, 3, 4, recommended = true, capabilities = liveReplay) { _, context, modifier ->
            SystemHealthCard(context.liveServices.nt4ClientService, context.liveServices.dashboardHealthService, context.replayFrame, modifier)
        },
        definition("alerts", "Live Alerts", "Battery, motor, communications, and sensor warnings.", Icons.Default.Warning, DashboardWidgetCategory.DIAGNOSTICS, 3, 4, recommended = true, capabilities = liveReplay + DashboardWidgetCapability.DATABASE) { _, context, modifier ->
            AlertPanel(context.liveServices.alertEngineService, modifier)
        },
        definition("battery_health", "Battery Diagnostics", "Voltage, state of charge, and brownout risk.", Icons.Default.BatteryChargingFull, DashboardWidgetCategory.DIAGNOSTICS, 3, 4, capabilities = dataReplay) { _, context, modifier ->
            BatteryHealthCard(context.analysisServices.databaseService, context.dashboardState.primarySessionId, modifier)
        },
        definition("power_distribution", "Power Distribution", "Current draw by PDP or PDH channel.", Icons.Default.ElectricBolt, DashboardWidgetCategory.DIAGNOSTICS, 3, 4, capabilities = live) { _, context, modifier ->
            PowerDistributionCard(context.liveServices.nt4ClientService, modifier)
        },
        definition("brownout_protection", "Brownout Protection", "Battery-sag scaling and active protection state.", Icons.Default.BatteryAlert, DashboardWidgetCategory.DIAGNOSTICS, 3, 3, capabilities = live) { _, context, modifier ->
            BrownoutProtectionCard(context.liveServices.nt4ClientService, modifier)
        },
        definition("imu_visualizer", "IMU Visualizer", "Roll, pitch, yaw, and attitude health.", Icons.Default.CompassCalibration, DashboardWidgetCategory.DIAGNOSTICS, 4, 5, capabilities = live) { _, context, modifier ->
            IMUVisualizerCard(context.liveServices.nt4ClientService, modifier)
        },
        definition("ekf_telemetry", "EKF Diagnostics", "Estimator drift, innovation, and covariance.", Icons.Default.QueryStats, DashboardWidgetCategory.DIAGNOSTICS, 4, 5, capabilities = liveReplay) { _, context, modifier ->
            EKFTelemetryCard(context.liveServices.nt4ClientService, modifier)
        },
        definition("control_profiler", "Control Loop Profiler", "Target-versus-actual mechanism error and timing.", Icons.Default.Speed, DashboardWidgetCategory.DIAGNOSTICS, 4, 5, capabilities = live) { _, context, modifier ->
            ControlLoopProfilerCard(context.liveServices.nt4ClientService, modifier)
        },
        definition("profiling_diagnostics", "Profiling Diagnostics", "Maximum and average loop/subsystem timings.", Icons.Default.HourglassEmpty, DashboardWidgetCategory.DIAGNOSTICS, 4, 5, capabilities = live) { _, context, modifier ->
            ProfilingDiagnosticsCard(context.liveServices.nt4ClientService, modifier)
        },
        definition("hardware_topology", "Hardware Topology", "Interactive CAN and REV hardware map tree with live telemetry.", Icons.Default.Hub, DashboardWidgetCategory.DIAGNOSTICS, 3, 3, recommended = true, capabilities = liveReplay + DashboardWidgetCapability.DATABASE) { _, context, modifier ->
            HardwareTopologyCard(
                context.liveServices.nt4ClientService,
                context.analysisServices.databaseService,
                context.dashboardState.primarySessionId,
                modifier,
            )
        },
        definition("subsystem_health", "Subsystem Health", "Configuration, feedback, homing, calibration, current, and latched-fault status for every mechanism.", Icons.Default.HealthAndSafety, DashboardWidgetCategory.DIAGNOSTICS, 4, 5, recommended = true, capabilities = live) { _, context, modifier ->
            SubsystemHealthCard(context.liveServices.nt4ClientService, modifier)
        },
        definition("state_tracker", "Subsystem State Tracker", "Current subsystem state-machine states.", Icons.Default.AccountTree, DashboardWidgetCategory.DIAGNOSTICS, 4, 5, capabilities = live) { _, context, modifier ->
            StateMachineTrackerCard(context.liveServices.nt4ClientService, modifier)
        },

        definition("runs_index", "Recorded Sessions", "Practice runs, match logs, comparisons, and tags.", Icons.Default.History, DashboardWidgetCategory.REPLAY, 4, 9, recommended = true, capabilities = dataReplay) { _, context, modifier ->
            RunsIndex(
                databaseService = context.analysisServices.databaseService,
                workspace = context.workspace,
                primarySessionId = context.dashboardState.primarySessionId,
                compareSessionId = context.dashboardState.compareSessionId,
                onSelectPrimary = context.onSelectPrimarySession,
                onSelectCompare = context.onSelectCompareSession,
                modifier = modifier,
                reloadTrigger = context.reloadTrigger,
            )
        },
        definition("pose_viewer", "Robot Pose Tracker", "Numeric EKF, odometry, and vision pose values.", Icons.Default.MyLocation, DashboardWidgetCategory.REPLAY, 6, 6, capabilities = liveReplay) { _, context, modifier ->
            PoseViewerCard(context.liveServices.nt4ClientService, context.replayFrame, modifier)
        },
        definition("match_schedule", "Match Schedule", "TBA/TOA schedule and match association.", Icons.Default.CalendarMonth, DashboardWidgetCategory.REPLAY, 4, 9, capabilities = data) { _, context, modifier ->
            MatchScheduleCard(context.matches, context.workspace.teamId, context.onSelectMatch, modifier)
        },

        definition(
            "console_viewer", "Robot Console", "Live logs with search and severity filtering.",
            Icons.Default.Terminal, DashboardWidgetCategory.DEVELOPER, 6, 9, capabilities = liveReplay + DashboardWidgetCapability.DATABASE,
            properties = listOf(DashboardWidgetPropertySpec("sessionId", "Pinned replay session", DashboardWidgetPropertyKind.TEXT)),
        ) { widget, context, modifier ->
            ConsoleViewer(
                context.liveServices.nt4ClientService,
                context.analysisServices.databaseService,
                context.replayServices.replayEngineService,
                widget,
                modifier,
            )
        },
        definition("tuning_card", "Live Tuning", "Update exposed robot variables over NT4.", Icons.Default.Tune, DashboardWidgetCategory.DEVELOPER, 3, 3, capabilities = live + DashboardWidgetCapability.ROBOT_CONTROL) { _, context, modifier ->
            TuningCard(context.liveServices.nt4ClientService, modifier, context.tuningDeclarations)
        },
        definition("path_tuning", "Path Tuning", "Cross-track and along-track controller error.", Icons.Default.Timeline, DashboardWidgetCategory.DEVELOPER, 4, 5, capabilities = liveReplay) { _, context, modifier ->
            PathTuningVisualizer(context.liveServices.nt4ClientService, modifier)
        },
    ).also { definitions ->
        require(definitions.isNotEmpty()) { "Dashboard widget registry cannot be empty" }
    }

    private val byType = definitions.associateBy { it.type }.also { indexed ->
        require(indexed.size == definitions.size) { "Dashboard widget registry contains duplicate types" }
    }

    override val specs: List<DashboardWidgetSpec> = definitions

    override fun find(type: DashboardWidgetType): DashboardWidgetDefinition? = byType[type]

    init {
        requireValid()
    }

    private fun definition(
        type: String,
        displayName: String,
        description: String,
        icon: ImageVector,
        category: DashboardWidgetCategory,
        defaultRowSpan: Int,
        defaultColSpan: Int,
        minimumRowSpan: Int = 1,
        minimumColSpan: Int = 1,
        recommended: Boolean = false,
        capabilities: Set<DashboardWidgetCapability> = emptySet(),
        properties: List<DashboardWidgetPropertySpec> = emptyList(),
        renderer: DashboardWidgetRenderer,
    ) = DashboardWidgetDefinition(
        type = DashboardWidgetType.of(type),
        displayName = displayName,
        description = description,
        icon = icon,
        category = category,
        recommended = recommended,
        defaultRowSpan = defaultRowSpan,
        defaultColSpan = defaultColSpan,
        minimumRowSpan = minimumRowSpan,
        minimumColSpan = minimumColSpan,
        capabilities = capabilities,
        properties = properties,
        renderer = renderer,
    )
}
