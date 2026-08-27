package com.areslib.control.profile

import org.junit.jupiter.api.Test
import com.areslib.control.feedback.ProfiledPIDController
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `invalid constraints hold current state instead of jumping to goal`() {
        val profile = TrapezoidProfile()
        val constraints = TrapezoidProfile.Constraints(maxVelocity = -1.0, maxAcceleration = -2.0)
        val start = TrapezoidProfile.State(position = 0.0, velocity = 0.0)
        val goal = TrapezoidProfile.State(position = 10.0, velocity = 0.0)
        val outState = TrapezoidProfile.State()
        
        profile.calculate(0.02, start, goal, constraints, outState)
        
        assertEquals(start.position, outState.position, 1e-9)
        assertEquals(start.velocity, outState.velocity, 1e-9)
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

    @Test
    fun `profile accounts for nonzero initial velocity during deceleration`() {
        val profile = TrapezoidProfile()
        val output = TrapezoidProfile.State()

        profile.calculate(
            dtSeconds = 4.5,
            current = TrapezoidProfile.State(position = 0.0, velocity = 1.0),
            goal = TrapezoidProfile.State(position = 10.0, velocity = 0.0),
            constraints = TrapezoidProfile.Constraints(maxVelocity = 2.0, maxAcceleration = 1.0),
            outState = output
        )

        assertEquals(8.46875, output.position, 1e-9)
        assertEquals(1.75, output.velocity, 1e-9)
    }

    @Test
    fun `profile reaches requested nonzero goal velocity`() {
        val profile = TrapezoidProfile()
        val output = TrapezoidProfile.State()
        val goal = TrapezoidProfile.State(position = 5.0, velocity = 1.0)

        profile.calculate(
            dtSeconds = 4.0,
            current = TrapezoidProfile.State(position = 0.0, velocity = 0.0),
            goal = goal,
            constraints = TrapezoidProfile.Constraints(maxVelocity = 2.0, maxAcceleration = 1.0),
            outState = output
        )

        assertEquals(goal.position, output.position, 1e-9)
        assertEquals(goal.velocity, output.velocity, 1e-9)
    }

    @Test
    fun `zero position error with residual velocity brakes without snapping`() {
        val output = TrapezoidProfile.State()
        TrapezoidProfile().calculate(
            dtSeconds = 0.02,
            current = TrapezoidProfile.State(position = 5.0, velocity = 1.0),
            goal = TrapezoidProfile.State(position = 5.0, velocity = 0.0),
            constraints = TrapezoidProfile.Constraints(maxVelocity = 2.0, maxAcceleration = 1.0),
            outState = output
        )

        assertEquals(5.0198, output.position, 1e-9)
        assertEquals(0.98, output.velocity, 1e-9)
    }

    @Test
    fun `profiled pid runtime gain changes update underlying controller`() {
        val controller = ProfiledPIDController(
            p = 1.0,
            i = 2.0,
            d = 3.0,
            constraints = TrapezoidProfile.Constraints(1.0, 1.0)
        )

        controller.p = 4.0
        controller.i = 5.0
        controller.d = 6.0

        assertEquals(4.0, controller.pidController.p)
        assertEquals(5.0, controller.pidController.i)
        assertEquals(6.0, controller.pidController.d)
    }

    @Test
    fun `test reverse motion trapezoid profile converges exactly to negative goal`() {
        val profile = TrapezoidProfile()
        val constraints = TrapezoidProfile.Constraints(maxVelocity = 1.0, maxAcceleration = 2.0)
        val start = TrapezoidProfile.State(position = 0.0, velocity = 0.0)
        val goal = TrapezoidProfile.State(position = -2.0, velocity = 0.0)
        val outState = TrapezoidProfile.State()

        val dt = 0.02
        var current = start

        for (i in 0..150) {
            profile.calculate(dt, current, goal, constraints, outState)
            assertTrue(kotlin.math.abs(outState.velocity) <= constraints.maxVelocity + 1e-9, "Velocity exceeded max constraint: ${outState.velocity}")
            current = TrapezoidProfile.State(outState.position, outState.velocity)
        }

        assertEquals(goal.position, outState.position, 1e-4)
        assertEquals(goal.velocity, outState.velocity, 1e-4)
    }

    @Test
    fun `initial velocity at max velocity tracks cruise phase and decelerates without exceeding constraints`() {
        val profile = TrapezoidProfile()
        val constraints = TrapezoidProfile.Constraints(maxVelocity = 2.0, maxAcceleration = 1.0)
        val start = TrapezoidProfile.State(position = 0.0, velocity = 2.0)
        val goal = TrapezoidProfile.State(position = 10.0, velocity = 0.0)
        val outState = TrapezoidProfile.State()

        val dt = 0.02
        var current = start
        var cruisePhaseObserved = false
        var decelerationPhaseObserved = false

        for (i in 1..350) {
            val prevVelocity = current.velocity
            profile.calculate(dt, current, goal, constraints, outState)

            assertTrue(
                outState.velocity <= constraints.maxVelocity + 1e-9,
                "Velocity ${outState.velocity} exceeded max velocity ${constraints.maxVelocity}"
            )
            assertTrue(
                outState.velocity >= -1e-9,
                "Velocity ${outState.velocity} went negative in forward profile"
            )

            val impliedAccel = kotlin.math.abs(outState.velocity - prevVelocity) / dt
            assertTrue(
                impliedAccel <= constraints.maxAcceleration + 1e-6,
                "Acceleration $impliedAccel exceeded max acceleration ${constraints.maxAcceleration}"
            )

            if (kotlin.math.abs(outState.velocity - constraints.maxVelocity) < 1e-6 && outState.position < 7.0) {
                cruisePhaseObserved = true
            }
            if (outState.velocity < constraints.maxVelocity - 1e-3 && outState.velocity > 1e-3) {
                decelerationPhaseObserved = true
            }

            current = TrapezoidProfile.State(outState.position, outState.velocity)
        }

        assertTrue(cruisePhaseObserved, "Expected cruise phase at max velocity to be tracked")
        assertTrue(decelerationPhaseObserved, "Expected deceleration phase to be observed")
        assertEquals(goal.position, outState.position, 1e-4)
        assertEquals(goal.velocity, outState.velocity, 1e-4)
    }

    @Test
    fun `initial velocity at max velocity immediately decelerates when starting at braking distance`() {
        val profile = TrapezoidProfile()
        val constraints = TrapezoidProfile.Constraints(maxVelocity = 2.0, maxAcceleration = 1.0)
        val start = TrapezoidProfile.State(position = 0.0, velocity = 2.0)
        val goal = TrapezoidProfile.State(position = 2.0, velocity = 0.0)
        val outState = TrapezoidProfile.State()

        val dt = 0.02
        profile.calculate(dt, start, goal, constraints, outState)

        assertTrue(outState.velocity < constraints.maxVelocity, "Expected immediate deceleration below maxVelocity")
        assertEquals(1.98, outState.velocity, 1e-6)
        assertEquals(0.0398, outState.position, 1e-6)

        var current = TrapezoidProfile.State(outState.position, outState.velocity)
        for (i in 2..120) {
            val prevVelocity = current.velocity
            profile.calculate(dt, current, goal, constraints, outState)

            assertTrue(outState.velocity <= constraints.maxVelocity + 1e-9)
            val impliedAccel = kotlin.math.abs(outState.velocity - prevVelocity) / dt
            assertTrue(impliedAccel <= constraints.maxAcceleration + 1e-6)

            current = TrapezoidProfile.State(outState.position, outState.velocity)
        }

        assertEquals(goal.position, outState.position, 1e-4)
        assertEquals(goal.velocity, outState.velocity, 1e-4)
    }
}
