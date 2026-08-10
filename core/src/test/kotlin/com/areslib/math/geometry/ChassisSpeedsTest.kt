package com.areslib.math.geometry

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ChassisSpeedsTest {

    @Test
    fun `fromFieldRelativeSpeeds maps directly at zero heading`() {
        val heading = Rotation2d(0.0) // cos=1, sin=0
        val speeds = ChassisSpeeds.fromFieldRelativeSpeeds(1.0, 0.5, 0.1, heading)
        assertEquals(1.0, speeds.vxMetersPerSecond, 0.001)
        assertEquals(0.5, speeds.vyMetersPerSecond, 0.001)
        assertEquals(0.1, speeds.omegaRadiansPerSecond, 0.001)
    }

    @Test
    fun `fromFieldRelativeSpeeds rotates by inverse at 90 degrees`() {
        val heading = Rotation2d(Math.PI / 2) // cos=0, sin=1
        val speeds = ChassisSpeeds.fromFieldRelativeSpeeds(1.0, 0.0, 0.1, heading)
        // If we command X on the field, and we are facing 90 deg (left), our robot-centric Y should be -1.0
        assertEquals(0.0, speeds.vxMetersPerSecond, 0.001)
        assertEquals(-1.0, speeds.vyMetersPerSecond, 0.001)
    }

    @Test
    fun `discretize uses exact inverse SE2 translation Jacobian`() {
        val speeds = ChassisSpeeds.discretize(
            vxMetersPerSecond = 1.0,
            vyMetersPerSecond = 0.0,
            omegaRadiansPerSecond = Math.PI,
            dtSeconds = 1.0
        )

        assertEquals(0.0, speeds.vxMetersPerSecond, 1e-9)
        assertEquals(-Math.PI / 2.0, speeds.vyMetersPerSecond, 1e-9)
        assertEquals(Math.PI, speeds.omegaRadiansPerSecond, 1e-9)
    }

    @Test
    fun `discretize returns safe zero for invalid timestep`() {
        val speeds = ChassisSpeeds.discretize(1.0, 2.0, 3.0, Double.NaN)
        assertEquals(0.0, speeds.vxMetersPerSecond)
        assertEquals(0.0, speeds.vyMetersPerSecond)
        assertEquals(0.0, speeds.omegaRadiansPerSecond)
    }
}
