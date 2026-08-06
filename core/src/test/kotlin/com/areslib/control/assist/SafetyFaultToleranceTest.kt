package com.areslib.control.assist

import com.areslib.action.RobotAction
import com.areslib.sequencer.Task
import com.areslib.sequencer.TaskExecutor
import com.areslib.hardware.actuator.MotorIO
import com.areslib.math.geometry.Translation2d
import com.areslib.pathing.Costmap
import com.areslib.pathing.ThetaStarPlanner
import com.areslib.state.RobotState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.areslib.control.feedback.PIDController
import com.areslib.control.feedback.LQRController
import com.areslib.control.feedback.GravityFeedforward
import com.areslib.control.safety.CurrentBudgetManager

/**
 * SafetyFaultToleranceTest declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
class SafetyFaultToleranceTest {

    @Test
    fun `test PIDController is immune to NaN infinite and non-positive dt`() {
        val pid = PIDController(1.0, 0.5, 0.1)
        pid.setOutputLimits(-10.0, 10.0)

        // NaN measurement
        val outputNaN = pid.calculate(Double.NaN, 0.02)
        assertEquals(0.0, outputNaN)

        // Infinite setpoint
        pid.setSetpoint(Double.POSITIVE_INFINITY)
        val outputInfinite = pid.calculate(1.0, 0.02)
        assertEquals(0.0, outputInfinite)

        // Non-positive dtSeconds
        pid.setSetpoint(5.0)
        val outputZeroDt = pid.calculate(2.0, 0.0)
        assertEquals(0.0, outputZeroDt)

        val outputNegDt = pid.calculate(2.0, -0.01)
        assertEquals(0.0, outputNegDt)
    }

    @Test
    fun `test LQRController is immune to NaN infinite and non-positive dt`() {
        val lqr = LQRController(numStates = 2, numInputs = 1, numOutputs = 1)
        lqr.setSystemCoefficients(
            aData = doubleArrayOf(1.0, 0.1, 0.0, 1.0),
            bData = doubleArrayOf(0.005, 0.1),
            cData = doubleArrayOf(1.0, 0.0)
        )
        lqr.reset(doubleArrayOf(0.0, 0.0))

        // NaN measurement
        val outputNaN = lqr.calculate(
            y = doubleArrayOf(Double.NaN),
            xRef = doubleArrayOf(1.0, 0.0),
            dtSeconds = 0.02
        )
        assertEquals(1, outputNaN.size)
        assertEquals(0.0, outputNaN[0])

        // Non-positive dt
        val outputZeroDt = lqr.calculate(
            y = doubleArrayOf(0.5),
            xRef = doubleArrayOf(1.0, 0.0),
            dtSeconds = 0.0
        )
        assertEquals(0.0, outputZeroDt[0])
    }

    @Test
    fun `test GravityFeedforward is immune to NaN and infinite inputs`() {
        // Elevator with NaN
        val elevatorNaN = GravityFeedforward.calculateElevator(Double.NaN)
        assertEquals(0.0, elevatorNaN)

        // Arm with NaN angle
        val armNaN = GravityFeedforward.calculateArm(Double.NaN, 1.2)
        assertEquals(0.0, armNaN)

        // Arm with NaN KG
        val armNaNKg = GravityFeedforward.calculateArm(0.5, Double.NaN)
        assertEquals(0.0, armNaNKg)
    }

    @Test
    fun `test ThetaStarPlanner is immune to NaN or invalid inputs`() {
        val costmap = Costmap(widthMeters = 1.0, heightMeters = 1.0, resolutionMeters = 0.1)

        // NaN coordinates
        val pathNaN = ThetaStarPlanner.plan(
            costmap = costmap,
            start = Translation2d(Double.NaN, 0.0),
            end = Translation2d(1.0, 1.0)
        )
        assertTrue(pathNaN.isEmpty())

        // Invalid resolution should throw IllegalArgumentException
        try {
            Costmap(widthMeters = 1.0, heightMeters = 1.0, resolutionMeters = 0.0)
            assertTrue(false, "Expected IllegalArgumentException for 0.0 resolution")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun `test TaskExecutor is immune to StackOverflowError under sequential transitions`() {
        val executor = TaskExecutor()
        val dummyState = RobotState()

        // Queue 1,000 tasks that instantly complete on initialize/isCompleted
        for (i in 1..1000) {
            executor.addTask(object : Task {
                override val name = "InstantTask-$i"
                private var done = false
                override fun initialize(state: RobotState): List<RobotAction> {
                    done = true
                    return emptyList()
                }
                override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean = done
            })
        }

        // Executing update() must not trigger stack overflow!
        // It will process up to the limit (100) in the first frame.
        val returnedActions = executor.update(dummyState, 1000L)
        assertTrue(returnedActions.isNotEmpty() || executor.size > 0)
        assertTrue(executor.size <= 901)
    }

    @Test
    fun `test CurrentBudgetManager register is immune to zero or negative parameters`() {
        val budget = CurrentBudgetManager.ftcDefaults()
        val mockMotor = object : MotorIO {
            override var power: Double = 0.0
            override var powerScale: Double = 1.0
            override val position: Double = 0.0
            override val velocity: Double = 0.0
            override val currentAmps: Double = 0.0
            override fun resetEncoder() {}
        }

        // Register with zero stall current
        budget.register(mockMotor, stallCurrentAmps = 0.0, freeSpeedTps = 0.0, nominalVoltage = 0.0)
        
        // Ensure motor count registers and doesn't crash
        assertEquals(1, budget.motorCount)
        
        // Running update doesn't divide by zero to produce NaN scaling
        budget.update(12.0)
        assertTrue(budget.powerScale.isFinite())
        assertTrue(budget.powerScale > 0.0)
    }
}

