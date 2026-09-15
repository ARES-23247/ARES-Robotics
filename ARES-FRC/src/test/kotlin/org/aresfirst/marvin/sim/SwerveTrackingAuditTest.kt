package org.aresfirst.marvin.sim

import com.areslib.state.DriveState
import com.areslib.state.RobotState
import org.dyn4j.dynamics.Body
import org.dyn4j.geometry.Geometry
import org.dyn4j.geometry.MassType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SwerveTrackingAuditTest {
    private class AccumulatingBody : Body() {
        init { addFixture(Geometry.createRectangle(1.0, 1.0)); setMass(MassType.NORMAL) }
        fun consume() { accumulate(0.02) }
    }
    private fun state(x: Double, y: Double = 0.0, omega: Double = 0.0, field: Boolean = true) =
        RobotState(drive = DriveState(xVelocityMetersPerSecond = x, yVelocityMetersPerSecond = y,
            angularVelocityRadiansPerSecond = omega, isFieldCentric = field))

    @Test fun `different bodies retain their own queued force`() {
        val tracker = Dyn4jSwerveModuleSim()
        val first = AccumulatingBody()
        val second = AccumulatingBody()
        tracker.update(state(1.0), first)
        tracker.update(state(2.0), second)
        first.consume(); second.consume()
        assertEquals(50.0, first.force.x, 0.0)
        assertEquals(100.0, second.force.x, 0.0)
    }

    @Test fun `multiple queued updates preserve each effort rather than rewriting it`() {
        val tracker = Dyn4jSwerveModuleSim()
        val body = AccumulatingBody()
        tracker.update(state(1.0, omega = 1.0), body)
        tracker.update(state(2.0, omega = 2.0), body)
        body.consume()
        assertEquals(150.0, body.force.x, 0.0)
        assertEquals(60.0, body.torque, 0.0)
    }

    @Test fun `invalid command or feedback rejects before adding effort`() {
        val tracker = Dyn4jSwerveModuleSim()
        val body = AccumulatingBody()
        for (command in listOf(state(Double.NaN), state(1.0, Double.POSITIVE_INFINITY),
            state(1.0, omega = Double.NaN), state(Double.MAX_VALUE))) {
            assertThrows(IllegalArgumentException::class.java) { tracker.update(command, body) }
            assertEquals(0.0, body.accumulatedForce.magnitude, 0.0)
            assertEquals(0.0, body.accumulatedTorque, 0.0)
        }
        body.linearVelocity.x = Double.NaN
        assertThrows(IllegalArgumentException::class.java) { tracker.update(state(1.0), body) }
    }

    @Test fun `robot and field frames use expected CCW rotation and velocity error`() {
        val tracker = Dyn4jSwerveModuleSim(kpLinear = 2.0, kpAngular = 3.0)
        val body = AccumulatingBody()
        body.transform.setRotation(Math.PI / 2.0)
        body.linearVelocity.set(1.0, -2.0)
        body.angularVelocity = -0.5
        tracker.update(state(3.0, 4.0, 0.5, field = false), body)
        body.consume()
        assertEquals(-10.0, body.force.x, 1e-12)
        assertEquals(10.0, body.force.y, 1e-12)
        assertEquals(3.0, body.torque, 1e-12)
        tracker.update(state(3.0, 4.0, 0.5), body)
        body.consume()
        assertEquals(4.0, body.force.x, 1e-12)
        assertEquals(12.0, body.force.y, 1e-12)
    }

    @Test fun `consumed and manually discarded effort cannot leak into later updates`() {
        val tracker = Dyn4jSwerveModuleSim()
        val first = AccumulatingBody()
        val second = AccumulatingBody()
        tracker.update(state(1.0, omega = 1.0), first)
        first.consume()
        tracker.update(state(2.0, omega = 2.0), second)
        second.clearAccumulatedForce(); second.clearAccumulatedTorque()
        tracker.update(state(3.0, omega = 3.0), first)
        first.consume()
        assertEquals(150.0, first.force.x, 0.0)
        assertEquals(60.0, first.torque, 0.0)
        second.consume()
        assertEquals(0.0, second.force.x, 0.0)
        assertEquals(0.0, second.torque, 0.0)
        tracker.update(state(4.0, omega = 4.0), second)
        second.consume()
        assertEquals(200.0, second.force.x, 0.0)
        assertEquals(80.0, second.torque, 0.0)
    }

    @Test fun `a burst retains all snapshots and later ticks do not replay them`() {
        val tracker = Dyn4jSwerveModuleSim()
        val body = AccumulatingBody()
        repeat(3) {
            for (i in 1..20) tracker.update(state(i.toDouble(), omega = i.toDouble()), body)
            body.consume()
            assertEquals(50.0 * 210.0, body.force.x, 0.0)
            assertEquals(20.0 * 210.0, body.torque, 0.0)
            tracker.update(state(-1.0, omega = -1.0), body)
            body.consume()
            assertEquals(-50.0, body.force.x, 0.0)
            assertEquals(-20.0, body.torque, 0.0)
        }
    }

    @Test fun `actual world integration consumes each tracking command once`() {
        val tracker = Dyn4jSwerveModuleSim(kpLinear = 2.0, kpAngular = 3.0)
        val body = AccumulatingBody()
        body.linearDamping = 0.0
        body.angularDamping = 0.0
        val world = org.dyn4j.world.World<Body>()
        world.setGravity(0.0, 0.0)
        world.addBody(body)
        tracker.update(state(1.0, omega = 1.0), body)
        world.step(1, 0.02)
        assertEquals(2.0 * 0.02 / body.mass.mass, body.linearVelocity.x, 1e-12)
        assertEquals(3.0 * 0.02 / body.mass.inertia, body.angularVelocity, 1e-12)
        val velocity = body.linearVelocity.x
        val omega = body.angularVelocity
        world.step(1, 0.02)
        assertEquals(velocity, body.linearVelocity.x, 1e-12)
        assertEquals(omega, body.angularVelocity, 1e-12)
    }

    @Test fun `gain validation and zero mass effort behavior remain explicit`() {
        for (gain in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { Dyn4jSwerveModuleSim(kpLinear = gain) }
            assertThrows(IllegalArgumentException::class.java) { Dyn4jSwerveModuleSim(kpAngular = gain) }
        }
        val fixed = Body()
        Dyn4jSwerveModuleSim().update(state(1.0, omega = 1.0), fixed)
        assertEquals(0.0, fixed.accumulatedForce.magnitude, 0.0)
        assertEquals(0.0, fixed.accumulatedTorque, 0.0)
    }

    @Test fun `normal consumed ticks allocate no per tick effort wrappers`() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        org.junit.jupiter.api.Assumptions.assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!.isThreadAllocatedMemoryEnabled = true
        val tracker = Dyn4jSwerveModuleSim()
        val body = AccumulatingBody()
        val command = state(1.0, 0.5, 0.2, field = false)
        repeat(20_000) { tracker.update(command, body); body.consume() }
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        var updateBytes = 0L
        repeat(100_000) {
            val updateStart = bean.getThreadAllocatedBytes(id)
            tracker.update(command, body)
            updateBytes += bean.getThreadAllocatedBytes(id) - updateStart
            // Dyn4j allocates its own queue iterators here; that is outside tracking.update.
            body.consume()
        }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        println("Swerve update: $updateBytes bytes; update plus Dyn4j accumulation: $allocated bytes / 100000 ticks")
        assertTrue(updateBytes <= 4096L, "Per-tick effort allocation: $updateBytes bytes")
    }
}
