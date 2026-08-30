package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.di.KeyboardDriveState
import com.ares.analytics.di.ServiceRegistry
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
interface DashboardLiveWidgetServices {
    val nt4ClientService: Nt4ClientService
    val alertEngineService: AlertEngineService
    val dashboardHealthService: DashboardHealthService
    val keyboardDriveState: KeyboardDriveState
    val gamepadService: GamepadService
}
/** Database and analysis dependencies available to dashboard widget renderers. */
interface DashboardAnalysisWidgetServices {
    val databaseService: DatabaseService
    val advancedAnalyticsService: AdvancedAnalyticsService
    val syncEngineService: SyncEngineService
    val driverAnalysisService: DriverAnalysisService
    val diagnosticCoachService: DiagnosticCoachService
}

/** Replay dependency kept separate so live-only widgets do not acquire replay internals. */
interface DashboardReplayWidgetServices {
    val replayEngineService: ReplayEngineService
}

data class DashboardWidgetServices(
    val live: DashboardLiveWidgetServices,
    val analysis: DashboardAnalysisWidgetServices,
    val replay: DashboardReplayWidgetServices,
)

/**
 * Composition-root adapter. Getters intentionally preserve [ServiceRegistry]'s lazy initialization;
 * creating this adapter does not eagerly start every dashboard service.
 */
class ServiceRegistryDashboardWidgetServices(
    private val registry: ServiceRegistry,
) : DashboardLiveWidgetServices, DashboardAnalysisWidgetServices, DashboardReplayWidgetServices {
    override val nt4ClientService: Nt4ClientService get() = registry.nt4ClientService
    override val alertEngineService: AlertEngineService get() = registry.alertEngineService
    override val dashboardHealthService: DashboardHealthService get() = registry.dashboardHealthService
    override val keyboardDriveState: KeyboardDriveState get() = registry.keyboardDriveState
    override val gamepadService: GamepadService get() = registry.gamepadService
    override val databaseService: DatabaseService get() = registry.databaseService
    override val advancedAnalyticsService: AdvancedAnalyticsService get() = registry.advancedAnalyticsService
    override val syncEngineService: SyncEngineService get() = registry.syncEngineService
    override val driverAnalysisService: DriverAnalysisService get() = registry.driverAnalysisService
    override val diagnosticCoachService: DiagnosticCoachService get() = registry.diagnosticCoachService
    override val replayEngineService: ReplayEngineService get() = registry.replayEngineService

    fun grouped(): DashboardWidgetServices = DashboardWidgetServices(
        live = this,
        analysis = this,
        replay = this,
    )
}
