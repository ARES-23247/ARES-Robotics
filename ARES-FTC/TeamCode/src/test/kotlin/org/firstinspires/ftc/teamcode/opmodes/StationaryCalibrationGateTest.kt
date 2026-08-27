package org.firstinspires.ftc.teamcode.opmodes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationaryCalibrationGateTest {
    @Test
    fun `requires neutral measured stationary dwell and resets on any motion`() {
        val gate = StationaryCalibrationGate(dwellMs = 500L)

        assertFalse(gate.update(1_000L, true, 0.0, 0.0))
        assertFalse(gate.update(1_499L, true, 0.0, 0.0))
        assertTrue(gate.update(1_500L, true, 0.0, 0.0))
        assertFalse(gate.update(1_501L, true, 0.031, 0.0))
        assertFalse(gate.update(2_000L, true, 0.0, 0.0))
        assertFalse(gate.update(2_499L, false, 0.0, 0.0))
        assertFalse(gate.update(3_000L, true, 0.0, 0.0))
        assertTrue(gate.update(3_500L, true, 0.0, 0.0))
    }

    @Test
    fun `nonfinite motion and receiver clock rollback fail closed`() {
        val gate = StationaryCalibrationGate(dwellMs = 100L)

        assertFalse(gate.update(500L, true, 0.0, 0.0))
        assertFalse(gate.update(600L, true, Double.NaN, 0.0))
        assertFalse(gate.update(700L, true, 0.0, 0.0))
        assertFalse(gate.update(650L, true, 0.0, 0.0))
        assertTrue(gate.update(750L, true, 0.0, 0.0))
    }
}
