package org.aresfirst.marvin.hardware

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FRCFlywheelSafetyMathTest {
    @Test
    fun `every motor and readiness parameter rejects invalid values`() {
        for (index in 0..3) {
            for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
                val rpm = DoubleArray(4) { 4_000.0 }
                rpm[index] = invalid
                assertFalse(flywheelVelocitySnapshotValid(rpm[0], rpm[1], rpm[2], rpm[3]))
                assertFalse(flywheelVelocitySnapshotReadyForTarget(4_000.0, 150.0, rpm[0], rpm[1], rpm[2], rpm[3]))
            }
        }
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertFalse(flywheelVelocitySnapshotReadyForTarget(invalid, 150.0, 4_000.0, 4_000.0, 4_000.0, 4_000.0))
            assertFalse(flywheelVelocitySnapshotReadyForTarget(4_000.0, invalid, 4_000.0, 4_000.0, 4_000.0, 4_000.0))
        }
        assertFalse(flywheelVelocitySnapshotReadyForTarget(4_000.0, 0.0, 4_000.0, 4_000.0, 4_000.0, 4_000.0))
        assertTrue(flywheelVelocitySnapshotValid(4_000.0, 3_750.0, 4_000.0, 4_000.0))
    }

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
