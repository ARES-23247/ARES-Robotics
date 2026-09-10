package org.firstinspires.ftc.teamcode.opmodes

import com.areslib.math.estimation.StationaryCalibrationGate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationaryCalibrationGateTest {
    @Test
    fun `requires neutral measured stationary dwell and resets on any motion`() {
        val gate = StationaryCalibrationGate(dwellMs = 500L)

        assertFalse(gate.update(1_000L, true, 0.0, 0.0))
        for (time in 1_010L..1_490L step 10L) assertFalse(gate.update(time, true, 0.0, 0.0))
        assertTrue(gate.update(1_500L, true, 0.0, 0.0))
        assertFalse(gate.update(1_501L, true, 0.031, 0.0))
        assertFalse(gate.update(2_000L, true, 0.0, 0.0))
        assertFalse(gate.update(2_499L, false, 0.0, 0.0))
        assertFalse(gate.update(3_000L, true, 0.0, 0.0))
        for (time in 3_010L..3_490L step 10L) assertFalse(gate.update(time, true, 0.0, 0.0))
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

    private fun StationaryCalibrationGate.update(time: Long, neutral: Boolean, linear: Double, angular: Double) =
        update(time, neutral, linear, angular, motionMeasurementsValid = true, observationTimestampMs = time)
}
