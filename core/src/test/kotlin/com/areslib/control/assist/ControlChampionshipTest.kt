package com.areslib.control.assist

import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.control.feedback.LQRController
import com.areslib.control.feedback.GravityFeedforward
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControlChampionshipTest {

    @Test
    fun testLQRControllerConvergence() {
        // Define simple 2-state elevator system: [position, velocity]
        val controller = LQRController(numStates = 2, numInputs = 1, numOutputs = 1)

        // A = [1.0, 0.02; 0.0, 0.9] (dt = 0.02s)
        // B = [0.0; 0.05]
        // C = [1.0, 0.0]
        controller.setSystemCoefficients(
            aData = doubleArrayOf(1.0, 0.02, 0.0, 0.9),
            bData = doubleArrayOf(0.0, 0.05),
            cData = doubleArrayOf(1.0, 0.0)
        )

        // Set Kalman filter gains (pre-calculated or identity)
        controller.L = LQRController.Matrix(2, 1, doubleArrayOf(0.1, 0.01))

        // Set Q and R weighting matrices
        val Q = LQRController.Matrix(2, 2, doubleArrayOf(10.0, 0.0, 0.0, 1.0))
        val R = LQRController.Matrix(1, 1, doubleArrayOf(1.0))

        // Compute feedback gains using dynamic DARE solver
        controller.computeFeedbackGains(Q, R)

        // Verify gains K matrix are computed and reasonable
        assertTrue(controller.K.get(0, 0) > 0.0, "Feedback gain K for position error should be positive")
        assertTrue(controller.K.get(0, 1) > 0.0, "Feedback gain K for velocity error should be positive")

        // Reset controller estimated state to (0.0, 0.0)
        controller.reset(doubleArrayOf(0.0, 0.0))

        // Target position = 1.0, velocity = 0.0
        val xRef = doubleArrayOf(1.0, 0.0)

        // Simulate closed-loop convergence over 50 iterations (1 second)
        var systemPosition = 0.0
        var systemVelocity = 0.0

        for (i in 0 until 50) {
            val sensorOutput = doubleArrayOf(systemPosition)
            val u = controller.calculate(sensorOutput, xRef, 0.02)

            // Simulate simple Euler integration of physical elevator plant:
            // x_next = A * x + B * u
            val nextPosition = systemPosition + 0.02 * systemVelocity
            val nextVelocity = 0.9 * systemVelocity + 0.05 * u[0]

            systemPosition = nextPosition
            systemVelocity = nextVelocity
        }

        // Verify state is converging closely to the 1.0m target reference
        assertTrue(systemPosition > 0.5, "LQR system should converge towards reference. Position: $systemPosition")
    }

    @Test
    fun testGravityFeedforward() {
        // Elevator feedforward
        val elevatorFF = GravityFeedforward.calculateElevator(kG = 0.15)
        assertEquals(0.15, elevatorFF, 1e-6)

        // Arm feedforward relative to horizontal
        val armHorizontal = GravityFeedforward.calculateArm(angleRadians = 0.0, kG = 0.5)
        assertEquals(0.5, armHorizontal, 1e-6)

        val armVertical = GravityFeedforward.calculateArm(angleRadians = Math.PI / 2.0, kG = 0.5)
        assertEquals(0.0, armVertical, 1e-6)
    }

    @Test
    fun testCoordinatedActionExecutor() {
        var action1Called = 0
        var action2Called = 0

        val action1 = object : Action {
            var finished = false
            override fun start() { action1Called++ }
            override fun update(dtSeconds: Double) { finished = true }
            override fun isFinished(): Boolean = finished
        }

        val action2 = object : Action {
            var finished = false
            override fun start() { action2Called++ }
            override fun update(dtSeconds: Double) { finished = true }
            override fun isFinished(): Boolean = finished
        }

        val executor = CoordinatedActionExecutor()
        assertFalse(executor.isRunning())

        // Create a sequence action
        val sequence = SequentialAction(action1, action2)
        executor.startAction(sequence)

        assertTrue(executor.isRunning())
        assertEquals(1, action1Called)
        assertEquals(0, action2Called)

        // Tick first step
        executor.update(0.02)
        // Tick second step (action1 finished, action2 starts)
        executor.update(0.02)
        assertEquals(1, action2Called)

        // Tick third step (action2 finishes)
        executor.update(0.02)
        assertFalse(executor.isRunning())
    }

    @Test
    fun testIntakeTargetAssist() {
        val assist = IntakeTargetAssist()

        val driverSpeeds = ChassisSpeeds(vxMetersPerSecond = 1.0, vyMetersPerSecond = 0.0, omegaRadiansPerSecond = 0.0)

        // Case 1: Target not visible
        val manualSpeeds = assist.calculateAssistedSpeeds(
            driverManualSpeeds = driverSpeeds,
            targetVisible = false,
            yawErrorDegrees = 10.0,
            lateralErrorMeters = 0.2,
            dtSeconds = 0.02
        )
        assertEquals(driverSpeeds.vxMetersPerSecond, manualSpeeds.vxMetersPerSecond, 1e-6)
        assertEquals(driverSpeeds.vyMetersPerSecond, manualSpeeds.vyMetersPerSecond, 1e-6)
        assertEquals(driverSpeeds.omegaRadiansPerSecond, manualSpeeds.omegaRadiansPerSecond, 1e-6)

        // Case 2: Target visible, yaw error of 10 degrees, lateral error of 0.2 meters
        val assistedSpeeds = assist.calculateAssistedSpeeds(
            driverManualSpeeds = driverSpeeds,
            targetVisible = true,
            yawErrorDegrees = -10.0, // target to the left
            lateralErrorMeters = -0.2,
            dtSeconds = 0.02
        )

        // Assisted speeds should apply centering and steering inputs
        assertTrue(assistedSpeeds.omegaRadiansPerSecond > 0.0, "Rotational correction should turn towards target")
        assertTrue(assistedSpeeds.vyMetersPerSecond > 0.0, "Lateral correction should center onto target")
    }
}

