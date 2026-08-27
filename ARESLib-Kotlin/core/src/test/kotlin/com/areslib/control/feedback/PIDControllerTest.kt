package com.areslib.control.feedback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PIDControllerTest {
    @Test
    fun `reset clears the derivative filter so stale rate state cannot leak into the next segment`() {
        val pid = PIDController(p = 0.0, i = 0.0, d = 10.0)
        pid.setSetpoint(1.0)

        // Establish a nonzero filtered derivative from a moving measurement.
        for (step in 1..25) {
            pid.calculate(step.toDouble() / 25.0, dtSeconds = 0.02)
        }

        pid.reset()
        // First post-reset step with a flat measurement: with a properly cleared EMA the
        // D contribution is exactly zero. Before the fix, the stale filtered derivative
        // blended through for ~20 more loops.
        val output = pid.calculate(1.0, dtSeconds = 0.02)

        assertEquals(0.0, output, 1e-9, "post-reset D term must be zero, not a stale EMA blend")
    }

    @Test
    fun `derivative filter still smooths within a segment`() {
        val pid = PIDController(p = 0.0, i = 0.0, d = 10.0)
        pid.setSetpoint(0.0)

        pid.calculate(0.0, dtSeconds = 0.02)
        // Derivative-on-measurement: a rising measurement produces a negative D contribution.
        val output = pid.calculate(0.02, dtSeconds = 0.02)

        assertTrue(output < 0.0, "a positive measurement slope must produce a negative D term, got $output")
    }
}
