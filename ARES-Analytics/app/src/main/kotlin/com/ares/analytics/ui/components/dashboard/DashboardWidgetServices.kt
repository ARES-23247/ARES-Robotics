package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.di.KeyboardDriveState
import com.ares.analytics.service.AdvancedAnalyticsService
import com.ares.analytics.service.AlertEngineService
import com.ares.analytics.service.DashboardHealthService
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.DiagnosticCoachService
import com.ares.analytics.service.DriverAnalysisService
import com.ares.analytics.service.GamepadService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.ReplayEngineService
import com.ares.analytics.service.SyncEngineService

/** Live robot/simulator dependencies available to dashboard widget renderers. */
data class DashboardLiveWidgetServices(
    val nt4ClientService: Nt4ClientService,
    val alertEngineService: AlertEngineService,
    val dashboardHealthService: DashboardHealthService,
    val keyboardDriveState: KeyboardDriveState,
    val gamepadService: GamepadService,
)
/** Database and analysis dependencies available to dashboard widget renderers. */
data class DashboardAnalysisWidgetServices(
    val databaseService: DatabaseService,
    val advancedAnalyticsService: AdvancedAnalyticsService,
    val syncEngineService: SyncEngineService,
    val driverAnalysisService: DriverAnalysisService,
    val diagnosticCoachService: DiagnosticCoachService,
)

/** Replay dependency kept separate so live-only widgets do not acquire replay internals. */
data class DashboardReplayWidgetServices(
    val replayEngineService: ReplayEngineService,
)

data class DashboardWidgetServices(
    val live: DashboardLiveWidgetServices,
    val analysis: DashboardAnalysisWidgetServices,
    val replay: DashboardReplayWidgetServices,
)
