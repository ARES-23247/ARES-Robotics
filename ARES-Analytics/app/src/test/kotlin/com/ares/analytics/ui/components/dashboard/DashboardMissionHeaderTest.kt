package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DashboardMissionHeaderTest {

    private val baseWorkspace = WorkspaceConfig(
        id = "test-ws",
        robotId = "Marvin",
        robotName = "Marvin XIX",
        teamId = "23247",
        seasonId = "2025",
        league = League.FTC,
        projectPath = "/test/project"
    )

    @Test
    fun `source type correctly identifies local simulator truth`() {
        val snapshot = DashboardMissionSnapshot(
            workspace = baseWorkspace,
            isConnected = true,
            isLocalSimulator = true,
            isSimulatorRunning = true,
            isReplayActive = false,
            primarySessionId = null
        )

        assertEquals(DashboardDataSourceType.SIMULATION_TRUTH, snapshot.sourceType)
        assertEquals("SIM TRUTH", snapshot.sourceType.badge)
    }

    @Test
    fun `source type correctly identifies FTC live hardware`() {
        val snapshot = DashboardMissionSnapshot(
            workspace = baseWorkspace.copy(league = League.FTC),
            isConnected = true,
            isLocalSimulator = false,
            isSimulatorRunning = false,
            isReplayActive = false,
            primarySessionId = null
        )

        assertEquals(DashboardDataSourceType.LIVE_ROBOT_FTC, snapshot.sourceType)
        assertEquals("HARDWARE", snapshot.sourceType.badge)
    }

    @Test
    fun `source type correctly identifies FRC live hardware`() {
        val snapshot = DashboardMissionSnapshot(
            workspace = baseWorkspace.copy(league = League.FRC),
            isConnected = true,
            isLocalSimulator = false,
            isSimulatorRunning = false,
            isReplayActive = false,
            primarySessionId = null
        )

        assertEquals(DashboardDataSourceType.LIVE_ROBOT_FRC, snapshot.sourceType)
        assertEquals("HARDWARE", snapshot.sourceType.badge)
    }

    @Test
    fun `source type correctly identifies XRP live hardware`() {
        val snapshot = DashboardMissionSnapshot(
            workspace = baseWorkspace.copy(league = League.XRP),
            isConnected = true,
            isLocalSimulator = false,
            isSimulatorRunning = false,
            isReplayActive = false,
            primarySessionId = null
        )

        assertEquals(DashboardDataSourceType.LIVE_ROBOT_XRP, snapshot.sourceType)
        assertEquals("HARDWARE", snapshot.sourceType.badge)
    }

    @Test
    fun `source type correctly identifies historical replay`() {
        val snapshot = DashboardMissionSnapshot(
            workspace = baseWorkspace,
            isConnected = false,
            isLocalSimulator = false,
            isSimulatorRunning = false,
            isReplayActive = true,
            primarySessionId = "session-12345"
        )

        assertEquals(DashboardDataSourceType.HISTORICAL_REPLAY, snapshot.sourceType)
        assertEquals("REPLAY", snapshot.sourceType.badge)
    }

    @Test
    fun `source type correctly identifies offline state`() {
        val snapshot = DashboardMissionSnapshot(
            workspace = baseWorkspace,
            isConnected = false,
            isLocalSimulator = false,
            isSimulatorRunning = false,
            isReplayActive = false,
            primarySessionId = null
        )

        assertEquals(DashboardDataSourceType.NO_ACTIVE_SOURCE, snapshot.sourceType)
        assertEquals("OFFLINE", snapshot.sourceType.badge)
    }

    @Test
    fun `freshness reflects update latency`() {
        val freshSnapshot = DashboardMissionSnapshot(
            workspace = baseWorkspace,
            isConnected = true,
            isLocalSimulator = true,
            isSimulatorRunning = true,
            isReplayActive = false,
            primarySessionId = null,
            lastUpdateAgeMs = 50L
        )
        assertEquals(TelemetryFreshness.FRESH, freshSnapshot.freshness)

        val staleSnapshot = DashboardMissionSnapshot(
            workspace = baseWorkspace,
            isConnected = true,
            isLocalSimulator = true,
            isSimulatorRunning = true,
            isReplayActive = false,
            primarySessionId = null,
            lastUpdateAgeMs = 1200L
        )
        assertEquals(TelemetryFreshness.STALE, staleSnapshot.freshness)

        val veryStaleSnapshot = staleSnapshot.copy(lastUpdateAgeMs = 5_000L)
        assertEquals(TelemetryFreshness.STALE, veryStaleSnapshot.freshness)

        val connectedWithoutEvidence = freshSnapshot.copy(lastUpdateAgeMs = -1L)
        assertEquals(TelemetryFreshness.INACTIVE, connectedWithoutEvidence.freshness)

        val offlineSnapshot = DashboardMissionSnapshot(
            workspace = baseWorkspace,
            isConnected = false,
            isLocalSimulator = false,
            isSimulatorRunning = false,
            isReplayActive = false,
            primarySessionId = null,
            lastUpdateAgeMs = -1L
        )
        assertEquals(TelemetryFreshness.INACTIVE, offlineSnapshot.freshness)
    }

    @Test
    fun `health summary describes conditions in plain language`() {
        // Nominal connected
        val nominal = DashboardMissionSnapshot(
            workspace = baseWorkspace,
            isConnected = true,
            isLocalSimulator = true,
            isSimulatorRunning = true,
            isReplayActive = false,
            primarySessionId = null,
            loopTimeMs = 20.0,
            batteryVoltage = 12.6,
            brownoutCount = 0,
            lastUpdateAgeMs = 50L
        )
        assertTrue(nominal.healthSummary.contains("All systems nominal"))

        // Low battery
        val lowBattery = DashboardMissionSnapshot(
            workspace = baseWorkspace,
            isConnected = true,
            isLocalSimulator = false,
            isSimulatorRunning = false,
            isReplayActive = false,
            primarySessionId = null,
            batteryVoltage = 11.2,
            lastUpdateAgeMs = 50L
        )
        assertTrue(lowBattery.healthSummary.contains("Low battery voltage"))

        val nominalXrpBattery = nominal.copy(
            workspace = baseWorkspace.copy(league = League.XRP),
            batteryVoltage = 6.0,
            xrpBrownoutThresholdVolts = 4.3,
        )
        assertTrue(nominalXrpBattery.healthSummary.contains("All systems nominal"))

        val lowXrpBattery = nominalXrpBattery.copy(batteryVoltage = 4.5)
        assertTrue(lowXrpBattery.healthSummary.contains("Low battery voltage"))

        // Brownout
        val brownout = DashboardMissionSnapshot(
            workspace = baseWorkspace,
            isConnected = true,
            isLocalSimulator = false,
            isSimulatorRunning = false,
            isReplayActive = false,
            primarySessionId = null,
            brownoutCount = 2,
            lastUpdateAgeMs = 50L
        )
        assertTrue(brownout.healthSummary.contains("brownout events detected"))

        // Offline guidance
        val offline = DashboardMissionSnapshot(
            workspace = baseWorkspace,
            isConnected = false,
            isLocalSimulator = false,
            isSimulatorRunning = false,
            isReplayActive = false,
            primarySessionId = null
        )
        assertTrue(offline.healthSummary.contains("practice safely in the Local Simulator"))

        val waitingForEvidence = nominal.copy(loopTimeMs = null, batteryVoltage = null, lastUpdateAgeMs = -1L)
        assertTrue(waitingForEvidence.healthSummary.contains("no telemetry evidence"))

        val incompleteEvidence = nominal.copy(loopTimeMs = null)
        assertTrue(incompleteEvidence.healthSummary.contains("evidence is incomplete"))

        val overruns = nominal.copy(loopOverruns = 3)
        assertTrue(overruns.healthSummary.contains("3 control-loop overruns"))
        assertFalse(overruns.healthSummary.contains("All systems nominal"))
    }

    @Test
    fun `prioritizes critical alerts over routine notices`() {
        val routineAlert = AlertRecord(
            alertId = "1",
            sessionId = "s1",
            ruleKey = "motor_temperature_warm",
            triggerTimestampMs = 1000L,
            peakValue = 45.0
        )
        val criticalAlert = AlertRecord(
            alertId = "2",
            sessionId = "s1",
            ruleKey = "brownout_detected",
            triggerTimestampMs = 1500L,
            peakValue = 1.0
        )

        val snapshot = DashboardMissionSnapshot(
            workspace = baseWorkspace,
            isConnected = true,
            isLocalSimulator = false,
            isSimulatorRunning = false,
            isReplayActive = false,
            primarySessionId = null,
            activeAlerts = listOf(routineAlert, criticalAlert)
        )

        val topAlert = snapshot.highestPriorityAlert
        assertNotNull(topAlert)
        assertEquals("brownout_detected", topAlert.ruleKey)
    }
}
