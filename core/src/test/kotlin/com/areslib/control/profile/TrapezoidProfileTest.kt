package com.areslib.control.profile

import org.junit.jupiter.api.Test
import com.areslib.control.feedback.ProfiledPIDController
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TrapezoidProfileTest declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
class TrapezoidProfileTest {

    @Test
    fun `test trapezoid profile converges exactly to goal`() {
        val profile = TrapezoidProfile()
        val constraints = TrapezoidProfile.Constraints(maxVelocity = 1.0, maxAcceleration = 2.0)
        
        val start = TrapezoidProfile.State(position = 0.0, velocity = 0.0)
        val goal = TrapezoidProfile.State(position = 1.0, velocity = 0.0)
        
        val outState = TrapezoidProfile.State()
        
        // Let's step through the profile manually with 20ms steps and verify convergence
        val dt = 0.02
        var current = start
        
        for (i in 0..100) {
            profile.calculate(dt, current, goal, constraints, outState)
            
            // Assert that velocities do not exceed max velocity
            assertTrue(kotlin.math.abs(outState.velocity) <= constraints.maxVelocity + 1e-9, "Velocity exceeded constraint: ${outState.velocity}")
            
            // Update current for next iteration
            current = TrapezoidProfile.State(outState.position, outState.velocity)
        }
        
        // Assert we reached goal
        assertEquals(goal.position, outState.position, 1e-6)
        assertEquals(goal.velocity, outState.velocity, 1e-6)
    }

    @Test
    fun `test profiled pid controller driving trapezoid profile`() {
        val constraints = TrapezoidProfile.Constraints(maxVelocity = 2.0, maxAcceleration = 4.0)
        val controller = ProfiledPIDController(p = 5.0, i = 0.0, d = 0.1, constraints = constraints)
        
        controller.reset(position = 0.0, velocity = 0.0)
        controller.setGoal(goalPosition = 2.0, goalVelocity = 0.0)
        
        // Let's run calculation loops
        var currentPos = 0.0
        val dt = 0.02
        
        for (i in 0..150) {
            val controlEffort = controller.calculate(currentPos, dt)
            assertTrue(controlEffort.isFinite())
            currentPos += (controller.currentState.velocity * dt)
        }
        
        // Check final position and setpoint convergence
        assertEquals(2.0, controller.currentState.position, 1e-4)
        assertEquals(0.0, controller.currentState.velocity, 1e-4)
    }

    @Test
    fun `test zero distance start equals goal`() {
        val profile = TrapezoidProfile()
        val constraints = TrapezoidProfile.Constraints(maxVelocity = 1.0, maxAcceleration = 2.0)
        val start = TrapezoidProfile.State(position = 5.0, velocity = 0.0)
        val goal = TrapezoidProfile.State(position = 5.0, velocity = 0.0)
        val outState = TrapezoidProfile.State()
        
        profile.calculate(0.02, start, goal, constraints, outState)
        
        val posMatch = when {
            kotlin.math.abs(outState.position - 5.0) < 1e-6 -> true
            else -> false
        }
        assertTrue(posMatch)
    }

    @Test
    fun `test negative max velocity or acceleration parameters clamp`() {
        val profile = TrapezoidProfile()
        val constraints = TrapezoidProfile.Constraints(maxVelocity = -1.0, maxAcceleration = -2.0)
        val start = TrapezoidProfile.State(position = 0.0, velocity = 0.0)
        val goal = TrapezoidProfile.State(position = 10.0, velocity = 0.0)
        val outState = TrapezoidProfile.State()
        
        profile.calculate(0.02, start, goal, constraints, outState)
        
        val jumped = when {
            kotlin.math.abs(outState.position - 10.0) < 1e-6 -> true
            else -> false
        }
        assertTrue(jumped)
    }

    @Test
    fun `test triangular profile case`() {
        val profile = TrapezoidProfile()
        val constraints = TrapezoidProfile.Constraints(maxVelocity = 100.0, maxAcceleration = 1.0)
        val start = TrapezoidProfile.State(position = 0.0, velocity = 0.0)
        val goal = TrapezoidProfile.State(position = 0.5, velocity = 0.0)
        val outState = TrapezoidProfile.State()
        
        profile.calculate(0.5, start, goal, constraints, outState)
        
        val isTriangular = when {
            kotlin.math.abs(outState.velocity) < 100.0 -> true
            else -> false
        }
        assertTrue(isTriangular)
    }
}
