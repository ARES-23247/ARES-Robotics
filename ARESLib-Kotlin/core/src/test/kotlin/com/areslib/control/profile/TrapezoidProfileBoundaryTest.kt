package com.areslib.control.profile

import kotlin.math.abs
import com.areslib.control.feedback.ProfiledPIDController
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrapezoidProfileBoundaryTest {
    @Test
    fun `overspeed initial state brakes continuously in either direction`() {
        for (direction in listOf(-1.0, 1.0)) {
            val output = TrapezoidProfile.State()
            TrapezoidProfile().calculate(
                0.02, TrapezoidProfile.State(0.0, direction * 2.0),
                TrapezoidProfile.State(direction * 100.0, 0.0),
                TrapezoidProfile.Constraints(1.0, 1.0), output,
            )
            assertEquals(direction * 0.0398, output.position, 1e-12)
            assertEquals(direction * 1.98, output.velocity, 1e-12)
        }
    }

    @Test
    fun `step spanning overspeed braking continues with remaining time`() {
        val output = TrapezoidProfile.State()
        TrapezoidProfile().calculate(
            1.25, TrapezoidProfile.State(0.0, 2.0), TrapezoidProfile.State(100.0, 0.0),
            TrapezoidProfile.Constraints(1.0, 1.0), output,
        )
        // One second braking travels 1.5 m; the remaining 0.25 s cruises at 1 m/s.
        assertEquals(1.75, output.position, 1e-12)
        assertEquals(1.0, output.velocity, 1e-12)
    }

    @Test
    fun `overspeed trajectories respect acceleration and reach goals after reversals`() {
        for (initialVelocity in listOf(-2.0, 2.0)) {
            for (goalPosition in listOf(-100.0, -0.1, 0.0, 0.1, 100.0)) {
                val profile = TrapezoidProfile()
                val current = TrapezoidProfile.State(0.0, initialVelocity)
                val goal = TrapezoidProfile.State(goalPosition, 0.0)
                val constraints = TrapezoidProfile.Constraints(1.0, 1.0)
                repeat(6_000) {
                    val previousPosition = current.position
                    val previousVelocity = current.velocity
                    profile.calculate(0.02, current, goal, constraints, current)
                    assertTrue(abs(current.velocity - previousVelocity) <= 0.02 + 1e-9)
                    assertTrue(abs(current.position - previousPosition - previousVelocity * 0.02) <= 0.0002 + 1e-9)
                    if (abs(previousVelocity) > 1.0 + 1e-9) {
                        assertTrue(abs(current.velocity) < abs(previousVelocity), "overspeed did not decrease")
                    } else {
                        assertTrue(abs(current.velocity) <= 1.0 + 1e-9)
                    }
                }
                assertEquals(goal.position, current.position, 1e-8)
                assertEquals(goal.velocity, current.velocity, 1e-8)
            }
        }
    }

    @Test
    fun `overspeed profile is independent of step partition and output alias`() {
        for (goalPosition in listOf(-10.0, 0.1, 10.0)) {
            for (duration in listOf(0.1, 0.5, 1.0, 1.25, 3.0, 20.0)) {
                val profile = TrapezoidProfile()
                val constraints = TrapezoidProfile.Constraints(1.0, 1.0)
                val start = TrapezoidProfile.State(0.0, 2.0)
                val goal = TrapezoidProfile.State(goalPosition, 0.0)
                val expected = TrapezoidProfile.State()
                profile.calculate(duration, start, goal, constraints, expected)
                val partitioned = start.copy()
                repeat(100) { profile.calculate(duration / 100, partitioned, goal, constraints, partitioned) }
                assertEquals(expected.position, partitioned.position, 1e-8)
                assertEquals(expected.velocity, partitioned.velocity, 1e-8)
                profile.calculate(duration, start, goal, constraints, goal)
                assertEquals(expected.position, goal.position, 1e-12)
                assertEquals(expected.velocity, goal.velocity, 1e-12)
            }
        }
    }

    @Test
    fun `lowering cruise limit brakes the current state instead of freezing`() {
        val profile = TrapezoidProfile()
        val constraints = TrapezoidProfile.Constraints(2.0, 1.0)
        val current = TrapezoidProfile.State()
        val goal = TrapezoidProfile.State(100.0, 0.0)
        profile.calculate(3.0, current, goal, constraints, current)
        assertEquals(2.0, current.velocity, 1e-12)
        val positionBefore = current.position
        constraints.maxVelocity = 1.0
        profile.calculate(0.02, current, goal, constraints, current)
        assertEquals(positionBefore + 0.0398, current.position, 1e-12)
        assertEquals(1.98, current.velocity, 1e-12)
    }

    @Test
    fun `unrepresentable overshoot retains a finite state`() {
        val start = TrapezoidProfile.State(Double.MAX_VALUE, 1e308)
        val output = TrapezoidProfile.State()
        TrapezoidProfile().calculate(0.02, start, TrapezoidProfile.State(Double.MAX_VALUE, 0.0),
            TrapezoidProfile.Constraints(1e308, 1e308), output)
        assertEquals(start, output)
    }

    @Test
    fun `profiled feedback rejects invalid inputs before advancing its reference`() {
        val invalidInputs: List<(ProfiledPIDController) -> Pair<Double, Double>> = listOf(
            { Double.NaN to 0.02 },
            { 0.0 to 0.0 },
            { 0.0 to Double.NaN },
            { it.constraints.maxAcceleration = -1.0; 0.0 to 0.02 },
            { it.constraints.maxVelocity = Double.NaN; 0.0 to 0.02 },
            { it.setGoal(Double.NaN); 0.0 to 0.02 },
            { it.setGoal(10.0, 2.0); 0.0 to 0.02 },
        )
        for ((index, invalidate) in invalidInputs.withIndex()) {
            val controller = ProfiledPIDController(1.0, 0.0, 0.0, TrapezoidProfile.Constraints(1.0, 1.0))
            controller.reset(0.0)
            controller.setGoal(10.0)
            assertTrue(controller.calculate(0.0, 0.2) > 0.0)
            val (measurement, period) = invalidate(controller)
            val previousState = controller.currentState.copy()
            assertEquals(0.0, controller.calculate(measurement, period), "invalid case $index")
            assertEquals(previousState, controller.currentState, "invalid case $index advanced the reference")
        }
    }

    @Test
    fun `profiled feedback neutralizes overflowing control effort`() {
        val controller = ProfiledPIDController(Double.MAX_VALUE, 0.0, 0.0, TrapezoidProfile.Constraints(1.0, 1.0))
        controller.reset(0.0)
        controller.setGoal(10.0)
        assertEquals(0.0, controller.calculate(-2.0, 0.02))
    }
}
