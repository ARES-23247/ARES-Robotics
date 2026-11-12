package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.service.hardware.SubsystemHealthStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticReplayCardsTest {

    @Test
    fun `extractReplayPowerDistribution parses motor channels and explicit total current`() {
        val frame = ReplayFrame(
            timestampMs = 1_000L,
            values = mapOf(
                "Hardware/Motors/DriveFL/CurrentAmps" to 18.5,
                "PDH/Channel3_Current" to 11.0,
                "Robot/TotalCurrentAmps" to 32.0,
            ),
            playheadMs = 1_000L,
        )
        val snap = extractReplayPowerDistribution(frame)
        assertEquals(18.5, snap.channelDraws["DriveFL"])
        assertEquals(11.0, snap.channelDraws["3"])
        assertEquals(32.0, snap.displayedTotalAmps)
    }

    @Test
    fun `extractReplayBrownoutTelemetry reconstructs scale charge state and trips`() {
        val frame = ReplayFrame(
            timestampMs = 2_500L,
            values = mapOf(
                "Robot/BrownoutPowerScale" to 0.72,
                "Robot/StateOfCharge" to 64.5,
                "Diagnostics/Power/BrownoutCount" to 2.0,
                "Robot/BrownoutState" to 0.0,
            ),
            stringValues = mapOf("Robot/BrownoutState" to "WARNING"),
            playheadMs = 2_500L,
        )
        val state = extractReplayBrownoutTelemetry(frame)
        assertEquals(0.72, state.powerScale)
        assertEquals(64.5, state.stateOfCharge)
        assertEquals("WARNING", state.brownoutState)
        assertEquals(2, state.tripCount)
    }

    @Test
    fun `extractReplayImuAttitude and control loop values parse clean snapshot keys`() {
        val frame = ReplayFrame(
            timestampMs = 3_000L,
            values = mapOf(
                "IMU/Roll" to 0.05,
                "IMU/Pitch" to -0.02,
                "ARES/EstimatedPose/2" to 1.57,
                "Arm/TargetPosition" to 1.20,
                "Arm/ActualPosition" to 1.15,
            ),
            playheadMs = 3_000L,
        )
        val attitude = extractReplayImuAttitude(frame)
        assertEquals(0.05, attitude.rollRad)
        assertEquals(-0.02, attitude.pitchRad)
        assertEquals(1.57, attitude.yawRad)

        val loop = extractReplayControlLoopValues(frame, "Select Motor")
        assertEquals(listOf("Arm"), loop.availableMotors)
        assertEquals(1.20, loop.targetValue)
        assertEquals(1.15, loop.actualValue)
    }

    @Test
    fun `extractReplayProfilingValues and state tracker decode active components`() {
        val frame = ReplayFrame(
            timestampMs = 4_000L,
            values = mapOf(
                "Profiling/Drivetrain_ms" to 2.4,
                "Robot/LoopTimeMs" to 6.8,
                "Superstructure/CurrentState" to 0.0,
            ),
            stringValues = mapOf("Superstructure/CurrentState" to "SCORING_HIGH"),
            playheadMs = 4_000L,
        )
        val prof = extractReplayProfilingValues(frame)
        assertEquals(2.4, prof["Drivetrain"])
        assertEquals(6.8, prof["Total"])

        val states = extractReplaySubsystemStates(frame)
        assertEquals("SCORING_HIGH", states["Superstructure"])
    }

    @Test
    fun `extractReplaySubsystemHealth marks healthy fresh snapshot and flags stale playhead gap`() {
        val healthyValues = mapOf(
            "Subsystems/Elevator/TelemetryHeartbeat" to 42.0,
            "Subsystems/Elevator/FeedbackValid" to 1.0,
            "Subsystems/Elevator/ConfigurationHealthy" to 1.0,
            "Subsystems/Elevator/Homed" to 1.0,
            "Subsystems/Elevator/HomingFaultLatched" to 0.0,
            "Subsystems/Elevator/Calibrated" to 1.0,
            "Subsystems/Elevator/CurrentReadingValid" to 1.0,
            "Subsystems/Elevator/OutputFaultLatched" to 0.0,
            "Subsystems/Elevator/PositionMeters" to 0.65,
        )
        val fresh = extractReplaySubsystemHealth(
            ReplayFrame(timestampMs = 5_000L, values = healthyValues, playheadMs = 5_200L),
        )
        assertEquals(1, fresh.size)
        assertEquals(SubsystemHealthStatus.HEALTHY, fresh.single().status)
        assertEquals(0.65, fresh.single().measurements["PositionMeters"])

        val stale = extractReplaySubsystemHealth(
            ReplayFrame(timestampMs = 5_000L, values = healthyValues, playheadMs = 7_500L),
        )
        assertEquals(SubsystemHealthStatus.STALE, stale.single().status)
        assertTrue(stale.single().issues.any { "heartbeat" in it })
    }

    @Test
    fun `crosshair coordinator isolates ownership and interpolates chart points accurately`() {
        val coordinator = DashboardCrosshairCoordinator()
        assertNull(coordinator.hover.value)

        coordinator.updateHover("chart_1", 1_500L)
        assertEquals(CrosshairHoverState(1_500L, "chart_1"), coordinator.hover.value)

        // Exiting a different chart must not clear chart_1's active hover
        coordinator.clearHover("chart_2")
        assertNotNull(coordinator.hover.value)

        coordinator.clearHover("chart_1")
        assertNull(coordinator.hover.value)

        val pts = listOf(TelemetryPoint(1_000L, 10.0), TelemetryPoint(2_000L, 20.0))
        assertEquals(15.0, interpolateChartPointAt(pts, 1_500L)!!, 1e-6)
    }
}
