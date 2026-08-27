package com.ares.analytics.ui.components.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardTelemetryParsingTest {
    @Test
    fun `motor current topics preserve channel identity`() {
        assertEquals("fl", parsePowerCurrentTopic("Hardware/Motors/fl/CurrentAmps")?.channelName)
        assertEquals("rr", parsePowerCurrentTopic("/Hardware/Motors/rr/Current")?.channelName)
        assertEquals("rl", parsePowerCurrentTopic("Drive/MotorCurrent_rl")?.channelName)
        assertEquals("7", parsePowerCurrentTopic("PDH/Channel7_Current")?.channelName)
    }

    @Test
    fun `total current is distinct and unrelated current settings are rejected`() {
        val total = parsePowerCurrentTopic("Robot/TotalCurrentAmps")
        assertTrue(total?.isTotal == true)
        assertNull(total?.channelName)
        assertNull(parsePowerCurrentTopic("Tuning/Drive/CurrentLimit"))
        assertNull(parsePowerCurrentTopic("Intake/TargetCurrentAmps"))
    }

    @Test
    fun `brownout telemetry begins unknown and never invents a healthy state`() {
        val initial = BrownoutTelemetryState()
        assertNull(initial.powerScale)
        assertNull(initial.stateOfCharge)
        assertNull(initial.brownoutState)
        assertNull(initial.tripCount)

        val withoutStringEvidence = initial.acceptBrownoutTelemetry(
            key = "Robot/BrownoutState",
            value = 0.0,
            stringValue = null,
        )
        assertNull(withoutStringEvidence.brownoutState)

        val warning = withoutStringEvidence.acceptBrownoutTelemetry(
            key = "Robot/BrownoutState",
            value = 0.0,
            stringValue = "warning",
        )
        assertEquals("WARNING", warning.brownoutState)
    }

    @Test
    fun `pose viewer recognizes truth estimate odometry vision and packed frames`() {
        assertTrue(isPoseViewerTelemetryTopic("ARES/TruePose/0"))
        assertTrue(isPoseViewerTelemetryTopic("ARES/EstimatedPose/1"))
        assertTrue(isPoseViewerTelemetryTopic("ARES/SimulatorPoseFrame/9"))
        assertTrue(isPoseViewerTelemetryTopic("Vision/HasTarget"))
        assertFalse(isPoseViewerTelemetryTopic("Hardware/Motors/fl/CurrentAmps"))
    }
}
