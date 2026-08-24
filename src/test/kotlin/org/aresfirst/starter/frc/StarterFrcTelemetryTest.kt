package org.aresfirst.starter.frc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StarterFrcTelemetryTest {
    @Test
    fun `starter telemetry strips transport-only leading slash`() {
        assertEquals("Drive/Pose_X", canonicalStarterFrcTelemetryTopic("/Drive/Pose_X"))
        assertEquals(
            "ARES/Starter/PhysicalHardwareReady",
            canonicalStarterFrcTelemetryTopic("ARES/Starter/PhysicalHardwareReady"),
        )
    }
}
