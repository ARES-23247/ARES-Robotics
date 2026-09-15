package com.areslib.frc.drivetrain

import com.areslib.state.DriveState
import com.ctre.phoenix6.swerve.SwerveRequest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SwerveCtreZeroGcTest {
    @Test
    fun `scaled periodic writer reuses mutable CTRE requests`() {
        var observed: SwerveRequest? = null
        val writer = SwerveCtreSpeedRequestWriter { request -> observed = request }
        val state = DriveState(
            xVelocityMetersPerSecond = 2.0,
            yVelocityMetersPerSecond = -1.0,
            angularVelocityRadiansPerSecond = 0.5,
            isFieldCentric = false,
        )

        val bytes = measureAllocationWindows(writesPerWindow = 20_000) { writer.write(state, 0.75) }
        assertTrue(observed is SwerveRequest.ApplyRobotSpeeds)
        assertSteadyStateAllocationWindows(bytes)
    }
}
