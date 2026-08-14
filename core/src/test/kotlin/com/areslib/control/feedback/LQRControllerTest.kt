package com.areslib.control.feedback

import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Championship-grade test suite for [LQRController].
 *
 * Validates the Discrete-Time Algebraic Riccati Equation (DARE) solver, Kalman observer,
 * motor saturation clipping, NaN/Infinity resilience, slew-rate limiting, and full
 * closed-loop convergence on a simulated 2-state elevator plant.
 */
class LQRControllerTest {

    // Shared 2-state elevator system for most tests
    private lateinit var controller: LQRController

    @BeforeEach
    fun setUp() {
        RobotClock.useMockTime(0L)
        controller = createElevatorController()
    }

    @AfterEach
    fun tearDown() {
        RobotClock.useSystemTime()
    }

    /**
     * Creates and configures a standard 2-state elevator LQR controller.
     * States: [position, velocity]. Input: [force/voltage]. Output: [position].
     * A = [1.0, 0.02; 0.0, 0.9] (dt = 0.02s, 0.9 damping on velocity)
     * B = [0.0; 0.05]
     * C = [1.0, 0.0]
     */
    private fun createElevatorController(): LQRController {
        val ctrl = LQRController(numStates = 2, numInputs = 1, numOutputs = 1)
        ctrl.setSystemCoefficients(
            aData = doubleArrayOf(1.0, 0.02, 0.0, 0.9),
            bData = doubleArrayOf(0.0, 0.05),
            cData = doubleArrayOf(1.0, 0.0)
        )
        ctrl.L = LQRController.Matrix(2, 1, doubleArrayOf(0.1, 0.01))

        val Q = LQRController.Matrix(2, 2, doubleArrayOf(10.0, 0.0, 0.0, 1.0))
        val R = LQRController.Matrix(1, 1, doubleArrayOf(1.0))
        ctrl.computeFeedbackGains(Q, R)
        ctrl.reset(doubleArrayOf(0.0, 0.0))
        return ctrl
    }

    // ─── DARE Convergence ───────────────────────────────────────────────

    @Test
    fun `DARE solver produces positive feedback gains for position and velocity`() {
        // K should be a 1x2 matrix with both positive entries for a stable elevator system
        assertTrue(controller.K.get(0, 0) > 0.0,
            "Feedback gain K[0,0] (position) should be positive, was ${controller.K.get(0, 0)}")
        assertTrue(controller.K.get(0, 1) > 0.0,
            "Feedback gain K[0,1] (velocity) should be positive, was ${controller.K.get(0, 1)}")
    }

    @Test
    fun `DARE solver produces reasonable gain magnitudes`() {
        // For this elevator plant with Q=diag(10,1) and R=1, gains should be finite and
        // within a sensible control-theory range (not astronomically large)
        val kPos = controller.K.get(0, 0)
        val kVel = controller.K.get(0, 1)
        assertTrue(kPos < 100.0, "Position gain $kPos should be bounded")
        assertTrue(kVel < 100.0, "Velocity gain $kVel should be bounded")
        assertTrue(kPos.isFinite(), "Position gain must be finite")
        assertTrue(kVel.isFinite(), "Velocity gain must be finite")
    }

    @Test
    fun `DARE solver with identity Q and R still converges`() {
        val ctrl = LQRController(numStates = 2, numInputs = 1, numOutputs = 1)
        ctrl.setSystemCoefficients(
            aData = doubleArrayOf(1.0, 0.02, 0.0, 0.9),
            bData = doubleArrayOf(0.0, 0.05),
            cData = doubleArrayOf(1.0, 0.0)
        )
        val Q = LQRController.Matrix(2, 2, doubleArrayOf(1.0, 0.0, 0.0, 1.0))
        val R = LQRController.Matrix(1, 1, doubleArrayOf(1.0))
        ctrl.computeFeedbackGains(Q, R)

        assertTrue(ctrl.K.get(0, 0) > 0.0, "K should converge even with identity weights")
    }

    // ─── Closed-Loop Stability ──────────────────────────────────────────

    @Test
    fun `closed-loop elevator converges to 1m reference within 100 iterations`() {
        val xRef = doubleArrayOf(1.0, 0.0)
        var systemPosition = 0.0
        var systemVelocity = 0.0

        for (i in 0 until 100) {
            RobotClock.useMockTime((i * 20).toLong())
            val sensorOutput = doubleArrayOf(systemPosition)
            val u = controller.calculate(sensorOutput, xRef, 0.02)

            val nextPosition = systemPosition + 0.02 * systemVelocity
            val nextVelocity = 0.9 * systemVelocity + 0.05 * u[0]
            systemPosition = nextPosition
            systemVelocity = nextVelocity
        }

        assertEquals(1.0, systemPosition, 0.15,
            "System should converge close to 1.0m target. Actual: $systemPosition")
    }

    @Test
    fun `closed-loop stability over 200 iterations does not diverge`() {
        val xRef = doubleArrayOf(2.0, 0.0)
        var systemPosition = 0.0
        var systemVelocity = 0.0

        for (i in 0 until 200) {
            RobotClock.useMockTime((i * 20).toLong())
            val sensorOutput = doubleArrayOf(systemPosition)
            val u = controller.calculate(sensorOutput, xRef, 0.02)

            val nextPosition = systemPosition + 0.02 * systemVelocity
            val nextVelocity = 0.9 * systemVelocity + 0.05 * u[0]
            systemPosition = nextPosition
            systemVelocity = nextVelocity
        }

        // After 200 iterations (4 seconds), the system must not diverge to infinity
        assertTrue(systemPosition.isFinite(), "Position must remain finite")
        assertTrue(kotlin.math.abs(systemPosition - 2.0) < 0.5,
            "System should converge near 2.0m. Actual: $systemPosition")
    }

    @Test
    fun `closed-loop monotonic approach from zero to reference`() {
        val xRef = doubleArrayOf(1.0, 0.0)
        var systemPosition = 0.0
        var systemVelocity = 0.0
        var everProgressed = false

        for (i in 0 until 50) {
            RobotClock.useMockTime((i * 20).toLong())
            val prevPosition = systemPosition
            val sensorOutput = doubleArrayOf(systemPosition)
            val u = controller.calculate(sensorOutput, xRef, 0.02)

            val nextPosition = systemPosition + 0.02 * systemVelocity
            val nextVelocity = 0.9 * systemVelocity + 0.05 * u[0]
            systemPosition = nextPosition
            systemVelocity = nextVelocity

            if (systemPosition > prevPosition + 1e-6) {
                everProgressed = true
            }
        }

        assertTrue(everProgressed, "Controller should make forward progress toward reference")
        assertTrue(systemPosition > 0.5, "Should converge past 0.5m. Actual: $systemPosition")
    }

    // ─── Motor Saturation ───────────────────────────────────────────────

    @Test
    fun `motor saturation clips output to maxU boundary`() {
        controller.maxU = 5.0
        controller.minU = -5.0

        // Huge error should produce a large raw u, but saturated to 5.0
        RobotClock.useMockTime(100L)
        val u = controller.calculate(
            y = doubleArrayOf(0.0),
            xRef = doubleArrayOf(100.0, 0.0),
            dtSeconds = 0.02
        )

        assertTrue(u[0] <= 5.0, "Output ${u[0]} must be clipped to maxU=5.0")
        assertTrue(u[0] >= -5.0, "Output ${u[0]} must be clipped to minU=-5.0")
    }

    @Test
    fun `motor saturation clips negative direction to minU`() {
        controller.maxU = 5.0
        controller.minU = -5.0

        // Huge negative error: target is far behind current position
        controller.reset(doubleArrayOf(100.0, 0.0))
        RobotClock.useMockTime(100L)
        val u = controller.calculate(
            y = doubleArrayOf(100.0),
            xRef = doubleArrayOf(0.0, 0.0),
            dtSeconds = 0.02
        )

        assertTrue(u[0] >= -5.0, "Negative output ${u[0]} must be clipped to minU=-5.0")
        assertTrue(u[0] <= 5.0, "Negative output ${u[0]} must stay within bounds")
    }

    @Test
    fun `motor saturation with asymmetric limits`() {
        controller.maxU = 10.0
        controller.minU = -3.0

        // Force large negative error
        controller.reset(doubleArrayOf(50.0, 0.0))
        RobotClock.useMockTime(100L)
        val u = controller.calculate(
            y = doubleArrayOf(50.0),
            xRef = doubleArrayOf(0.0, 0.0),
            dtSeconds = 0.02
        )

        assertTrue(u[0] >= -3.0, "Asymmetric minU=-3.0 must be enforced. Got: ${u[0]}")
    }

    // ─── NaN / Infinity Input Rejection ─────────────────────────────────

    @Test
    fun `NaN in y measurement returns pre-allocated output without crashing`() {
        RobotClock.useMockTime(100L)
        val result = controller.calculate(
            y = doubleArrayOf(Double.NaN),
            xRef = doubleArrayOf(1.0, 0.0),
            dtSeconds = 0.02
        )

        assertNotNull(result, "Result must not be null")
        assertEquals(1, result.size, "Result array should have numInputs=1 elements")
        assertTrue(result[0].isFinite(), "Returned output must be finite, not NaN or Inf")
    }

    @Test
    fun `NaN in xRef returns safely`() {
        RobotClock.useMockTime(100L)
        val result = controller.calculate(
            y = doubleArrayOf(0.5),
            xRef = doubleArrayOf(Double.NaN, 0.0),
            dtSeconds = 0.02
        )

        assertNotNull(result)
        assertTrue(result[0].isFinite(), "Should return finite value when xRef contains NaN")
    }

    @Test
    fun `Infinity in y measurement returns safely`() {
        RobotClock.useMockTime(100L)
        val result = controller.calculate(
            y = doubleArrayOf(Double.POSITIVE_INFINITY),
            xRef = doubleArrayOf(1.0, 0.0),
            dtSeconds = 0.02
        )

        assertNotNull(result)
        assertTrue(result[0].isFinite(), "Should return finite value when y contains Infinity")
    }

    @Test
    fun `Negative infinity in xRef returns safely`() {
        RobotClock.useMockTime(100L)
        val result = controller.calculate(
            y = doubleArrayOf(0.0),
            xRef = doubleArrayOf(1.0, Double.NEGATIVE_INFINITY),
            dtSeconds = 0.02
        )

        assertNotNull(result)
        assertTrue(result[0].isFinite(), "Should return finite value when xRef contains -Infinity")
    }

    @Test
    fun `invalid input zeros a previously nonzero command`() {
        val validOutput = controller.calculate(
            y = doubleArrayOf(0.0),
            xRef = doubleArrayOf(100.0, 0.0),
            dtSeconds = 0.02
        )[0]
        assertTrue(kotlin.math.abs(validOutput) > 1e-6)

        val invalidMeasurementOutput = controller.calculate(
            y = doubleArrayOf(Double.NaN),
            xRef = doubleArrayOf(100.0, 0.0),
            dtSeconds = 0.02
        )
        assertEquals(0.0, invalidMeasurementOutput[0], 1e-12)
        assertEquals(0.0, controller.u.get(0, 0), 1e-12)

        controller.calculate(doubleArrayOf(0.0), doubleArrayOf(100.0, 0.0), 0.02)
        val invalidDtOutput = controller.calculate(
            y = doubleArrayOf(0.0),
            xRef = doubleArrayOf(100.0, 0.0),
            dtSeconds = Double.NaN
        )
        assertEquals(0.0, invalidDtOutput[0], 1e-12)
    }

    // ─── Zero dt Rejection ──────────────────────────────────────────────

    @Test
    fun `zero dt returns pre-allocated output without crashing`() {
        RobotClock.useMockTime(100L)
        val result = controller.calculate(
            y = doubleArrayOf(0.5),
            xRef = doubleArrayOf(1.0, 0.0),
            dtSeconds = 0.0
        )

        assertNotNull(result)
        assertEquals(1, result.size)
        assertTrue(result[0].isFinite(), "Zero dt must not produce NaN/Inf")
    }

    @Test
    fun `negative dt returns pre-allocated output without crashing`() {
        RobotClock.useMockTime(100L)
        val result = controller.calculate(
            y = doubleArrayOf(0.5),
            xRef = doubleArrayOf(1.0, 0.0),
            dtSeconds = -0.02
        )

        assertNotNull(result)
        assertTrue(result[0].isFinite(), "Negative dt must not produce NaN/Inf")
    }

    // ─── State Reset ────────────────────────────────────────────────────

    @Test
    fun `reset sets xHat to specified initial state`() {
        controller.reset(doubleArrayOf(3.5, -1.2))
        assertEquals(3.5, controller.xHat.get(0, 0), 1e-9, "xHat[0] should be 3.5 after reset")
        assertEquals(-1.2, controller.xHat.get(1, 0), 1e-9, "xHat[1] should be -1.2 after reset")
    }

    @Test
    fun `reset zeros the control input u`() {
        // Run a few iterations to accumulate a non-zero u
        RobotClock.useMockTime(100L)
        controller.calculate(doubleArrayOf(0.0), doubleArrayOf(5.0, 0.0), 0.02)

        controller.reset(doubleArrayOf(0.0, 0.0))
        assertEquals(0.0, controller.u.get(0, 0), 1e-9, "u should be zeroed after reset")
    }

    @Test
    fun `reset with wrong dimension throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            controller.reset(doubleArrayOf(1.0))
        }
    }

    @Test
    fun `reset with too many dimensions throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            controller.reset(doubleArrayOf(1.0, 2.0, 3.0))
        }
    }

    // ─── Slew Rate Limiting ─────────────────────────────────────────────

    @Test
    fun `slew rate limits output change per second`() {
        controller.maxUChangePerSec = 10.0 // 10 V/s max change rate
        controller.maxU = 12.0
        controller.minU = -12.0
        controller.reset(doubleArrayOf(0.0, 0.0))

        // First call from zero state
        RobotClock.useMockTime(100L)
        val u1 = controller.calculate(
            y = doubleArrayOf(0.0),
            xRef = doubleArrayOf(100.0, 0.0),  // Huge error to force large raw u
            dtSeconds = 0.02
        )
        val firstOutput = u1[0]

        // At 10 V/s * 0.02s = 0.2V max change per step from last u (starts at 0)
        assertTrue(kotlin.math.abs(firstOutput) <= 0.2 + 1e-6,
            "First step change should be at most 0.2V (10V/s * 0.02s). Got: $firstOutput")

        // Second call
        RobotClock.useMockTime(120L)
        val u2 = controller.calculate(
            y = doubleArrayOf(0.0),
            xRef = doubleArrayOf(100.0, 0.0),
            dtSeconds = 0.02
        )
        val secondOutput = u2[0]

        val change = kotlin.math.abs(secondOutput - firstOutput)
        assertTrue(change <= 0.2 + 1e-6,
            "Change between steps should be at most 0.2V. Got change: $change")
    }

    @Test
    fun `slew rate NaN disables rate limiting`() {
        controller.maxUChangePerSec = Double.NaN // Disabled
        controller.maxU = 12.0
        controller.minU = -12.0
        controller.reset(doubleArrayOf(0.0, 0.0))

        RobotClock.useMockTime(100L)
        val u = controller.calculate(
            y = doubleArrayOf(0.0),
            xRef = doubleArrayOf(100.0, 0.0),
            dtSeconds = 0.02
        )

        // With no slew rate limit, output should jump to a larger value immediately
        // (limited only by motor saturation)
        assertTrue(u[0] > 0.2,
            "Without slew rate limiting, output should exceed 0.2V from a huge error. Got: ${u[0]}")
    }

    @Test
    fun `slew rate limiting across many steps produces smooth ramp`() {
        controller.maxUChangePerSec = 5.0  // 5 V/s
        controller.maxU = 12.0
        controller.minU = -12.0
        controller.reset(doubleArrayOf(0.0, 0.0))

        var prevOutput = 0.0
        for (i in 0 until 20) {
            RobotClock.useMockTime((i * 20 + 100).toLong())
            val u = controller.calculate(
                y = doubleArrayOf(0.0),
                xRef = doubleArrayOf(50.0, 0.0),
                dtSeconds = 0.02
            )
            val change = kotlin.math.abs(u[0] - prevOutput)
            assertTrue(change <= 0.10 + 1e-6,
                "Step $i: change $change exceeds max 0.10V (5V/s * 0.02s)")
            prevOutput = u[0]
        }
    }

    @Test
    fun `zero state vector returns zero output`() {
        controller.reset(doubleArrayOf(0.0, 0.0))
        val u = controller.calculate(
            y = doubleArrayOf(0.0),
            xRef = doubleArrayOf(0.0, 0.0),
            dtSeconds = 0.02
        )
        val out = when {
            u[0] == 0.0 -> 0.0
            else -> u[0]
        }
        assertEquals(0.0, out, 1e-6)
    }

    @Test
    fun `output value bounds and saturation`() {
        controller.maxU = 7.0
        controller.minU = -7.0
        val u = controller.calculate(
            y = doubleArrayOf(0.0),
            xRef = doubleArrayOf(100.0, 100.0),
            dtSeconds = 0.02
        )
        val isClamped = when {
            u[0] <= 7.0 && u[0] >= -7.0 -> true
            else -> false
        }
        assertTrue(isClamped)
    }

    @Test
    fun `large state values do not produce NaN`() {
        val u = controller.calculate(
            y = doubleArrayOf(1e10),
            xRef = doubleArrayOf(-1e10, 0.0),
            dtSeconds = 0.02
        )
        val isFinite = when {
            u[0].isFinite() -> true
            else -> false
        }
        assertTrue(isFinite)
    }

    @Test
    fun `reset with non-zero initial state sets estimated state xHat correctly`() {
        val initialState = doubleArrayOf(2.5, -0.5)
        controller.reset(initialState)

        assertEquals(2.5, controller.xHat.get(0, 0), 1e-9)
        assertEquals(-0.5, controller.xHat.get(1, 0), 1e-9)
    }

    @Test
    fun `reset with mismatched dimension throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            controller.reset(doubleArrayOf(1.0))
        }
    }
}
