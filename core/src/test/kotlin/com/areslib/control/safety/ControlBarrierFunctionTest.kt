package com.areslib.control.safety

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ControlBarrierFunctionTest declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
class ControlBarrierFunctionTest {

    @Test
    fun `test CBF filter allows free motion when far from physical boundaries`() {
        val cbf = ControlBarrierFunction(m = 2.0, c = 10.0, alpha = 5.0)
        val output = CBFFilteredOutput()

        // Current: x1 = 100.0, x2 = 0.0 -> safety margin is h = 100 - 10 = 90.0 (very safe)
        // hNextMin = 0.9 * 90.0 = 81.0
        // Command target: x1 = 95.0, x2 = 2.0 -> h_target = 95 - 2 * 2 - 10 = 81.0 (safe and within rate limits)
        cbf.filter(
            x1Target = 95.0,
            x2Target = 2.0,
            x1Current = 100.0,
            x2Current = 0.0,
            dtSeconds = 0.02,
            outBuffer = output
        )

        assertEquals(95.0, output.x1Filtered, "x1Target should pass through unchanged when safe")
        assertEquals(2.0, output.x2Filtered, "x2Target should pass through unchanged when safe")
    }

    @Test
    fun `test CBF filter restricts target commands when approaching or violating boundaries`() {
        val cbf = ControlBarrierFunction(m = 2.0, c = 10.0, alpha = 5.0)
        val output = CBFFilteredOutput()

        // Current: x1 = 30.0, x2 = 5.0 -> h = 30 - 10 - 10 = 10.0 (approaching)
        // Target: x1 = 15.0, x2 = 10.0 -> h_target = 15 - 20 - 10 = -15.0 (severe violation!)
        cbf.filter(
            x1Target = 15.0,
            x2Target = 10.0,
            x1Current = 30.0,
            x2Current = 5.0,
            dtSeconds = 0.02,
            outBuffer = output
        )

        // The target violates safety boundary, so it must be projected.
        val hFiltered = output.x1Filtered - 2.0 * output.x2Filtered - 10.0
        
        // Assert that the filtered target maintains safety margin >= discrete limit L
        // hFiltered = 9.0 (expected h >= 8.0)
        assertTrue(hFiltered >= 7.99, "Filtered targets must remain within safety boundary limits (expected h >= 8.0, got $hFiltered)")
        assertTrue(output.x1Filtered > 15.0, "x1 target should be increased to preserve safety")
        assertTrue(output.x2Filtered < 10.0, "x2 target should be decreased to preserve safety")
    }

    @Test
    fun `test CBF projection mathematical convergence`() {
        val cbf = ControlBarrierFunction(m = 1.0, c = 0.0, alpha = 10.0)
        val output = CBFFilteredOutput()

        // Let's command step-by-step iterations simulating a hot control loop.
        var x1 = 5.0
        var x2 = 0.0

        val x1Target = 2.0
        val x2Target = 4.0 // Violates x1 - x2 >= 0

        val dt = 0.02
        for (i in 0 until 50) {
            cbf.filter(
                x1Target = x1Target,
                x2Target = x2Target,
                x1Current = x1,
                x2Current = x2,
                dtSeconds = dt,
                outBuffer = output
            )

            // Simulate the physical system tracking the filtered targets perfectly
            x1 = output.x1Filtered
            x2 = output.x2Filtered

            assertTrue(x1 - x2 >= -1e-6, "System must remain within safe set (x1 - x2 >= 0) at step $i (x1=$x1, x2=$x2)")
        }

        // The system should have settled smoothly on the boundary line
        assertTrue(x1 >= 2.0, "State x1 should remain safely deployed")
        assertTrue(x2 <= 3.0 + 1e-3, "State x2 should be constrained by protective state x1")
    }

    @Test
    fun `invalid timestep still projects target into static safe set`() {
        val output = CBFFilteredOutput()
        ControlBarrierFunction(m = 1.0).filter(
            x1Target = 0.0,
            x2Target = 10.0,
            x1Current = 0.0,
            x2Current = 0.0,
            dtSeconds = Double.NaN,
            outBuffer = output
        )

        assertTrue(output.x1Filtered - output.x2Filtered >= -1e-12)
        assertEquals(5.0, output.x1Filtered, 1e-12)
        assertEquals(5.0, output.x2Filtered, 1e-12)
    }

    @Test
    fun `invalid target holds finite current state safely`() {
        val output = CBFFilteredOutput()
        ControlBarrierFunction(m = 1.0).filter(
            x1Target = Double.NaN,
            x2Target = 2.0,
            x1Current = 3.0,
            x2Current = 1.0,
            dtSeconds = 0.02,
            outBuffer = output
        )

        assertEquals(3.0, output.x1Filtered, 1e-12)
        assertEquals(1.0, output.x2Filtered, 1e-12)
    }
}
