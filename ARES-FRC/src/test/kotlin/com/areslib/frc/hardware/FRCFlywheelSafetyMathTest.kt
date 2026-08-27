package com.areslib.frc.hardware

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FRCFlywheelSafetyMathTest {
    @Test
    fun `all four flywheel observations must be finite and mutually aligned`() {
        assertTrue(flywheelVelocitySnapshotValid(4_000.0, 3_990.0, 4_010.0, 4_000.0))
        assertFalse(flywheelVelocitySnapshotValid(4_000.0, 0.0, 4_000.0, 4_000.0))
        assertFalse(flywheelVelocitySnapshotValid(4_000.0, 3_749.0, 4_000.0, 4_000.0))
        assertFalse(flywheelVelocitySnapshotValid(4_000.0, Double.NaN, 4_000.0, 4_000.0))
    }

    @Test
    fun `readiness checks every motor against target rather than only their average`() {
        assertTrue(flywheelVelocitySnapshotReadyForTarget(4_000.0, 150.0, 4_010.0, 3_990.0, 4_020.0, 4_000.0))
        assertFalse(flywheelVelocitySnapshotReadyForTarget(4_000.0, 150.0, 4_198.0, 4_049.0, 4_049.0, 4_049.0))
        assertFalse(flywheelVelocitySnapshotReadyForTarget(4_000.0, 150.0, 4_000.0, 3_850.0, 4_000.0, 4_000.0))
    }
}
