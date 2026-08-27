package com.areslib.frc

import edu.wpi.first.networktables.NetworkTableInstance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FRCTelemetryTest {
    @Test
    fun `FRC telemetry preserves canonical slash-free topic identity`() {
        val instance = NetworkTableInstance.create()
        val telemetry = FRCTelemetry(instance = instance, startDataLog = false)
        try {
            telemetry.putNumber("/Robot/LoopTimeMs", 20.0)
            telemetry.putString("Drive/Pose_Source", "EKF")
            telemetry.update()

            assertEquals(20.0, instance.getEntry("Robot/LoopTimeMs").getDouble(Double.NaN), 1e-12)
            assertEquals("EKF", instance.getEntry("Drive/Pose_Source").getString(""))
            val names = instance.topicInfo.map { it.name }
            assertTrue("Robot/LoopTimeMs" in names)
            assertTrue("Drive/Pose_Source" in names)
            assertTrue(names.none { it.startsWith("/SmartDashboard/") })
        } finally {
            telemetry.close()
            instance.close()
        }
    }
}
