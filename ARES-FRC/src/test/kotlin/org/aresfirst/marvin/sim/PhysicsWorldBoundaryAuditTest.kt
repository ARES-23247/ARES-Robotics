package org.aresfirst.marvin.sim

import org.aresfirst.marvin.sim.field.FrcFieldBuilder
import org.dyn4j.dynamics.Body
import org.dyn4j.geometry.Vector2
import org.dyn4j.world.World
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PhysicsWorldBoundaryAuditTest {
    @Test fun `simulation rejects invalid time before queuing tracking effort`() {
        for (dt in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.01, 50.01)) {
            org.aresfirst.marvin.Dyn4jSimulation().use { sim ->
                sim.resetPose(6.0, 2.0, 0.0)
                val driving = com.areslib.state.RobotState(drive = com.areslib.state.DriveState(
                    xVelocityMetersPerSecond = 1.0, isFieldCentric = true))
                assertThrows(IllegalArgumentException::class.java) { sim.step(driving, dt) }
                sim.step(com.areslib.state.RobotState(), 0.02)
                val pose = sim.getPoseUpdate()
                assertEquals(6.0, pose.xMeters, 1e-12)
                assertEquals(2.0, pose.yMeters, 1e-12)
            }
        }
    }

    @Test fun `pose reset discards previously queued tracking effort`() {
        val physics = Dyn4jPhysicsWorld()
        physics.robotBody.applyForce(Vector2(1000.0, 0.0))
        physics.robotBody.applyTorque(100.0)
        physics.resetPose(6.0, 2.0, 0.4)
        physics.step(0.02)
        assertEquals(6.0, physics.robotBody.transform.translationX, 1e-12)
        assertEquals(2.0, physics.robotBody.transform.translationY, 1e-12)
        assertEquals(0.4, physics.robotBody.transform.rotationAngle, 1e-12)
        assertEquals(0.0, physics.robotBody.linearVelocity.magnitude, 1e-12)
        assertEquals(0.0, physics.robotBody.angularVelocity, 1e-12)
    }

    @Test fun `nonfinite pose rejects atomically`() {
        val physics = Dyn4jPhysicsWorld()
        physics.resetPose(6.0, 2.0, 0.4)
        for (pose in listOf(Triple(Double.NaN, 1.0, 0.0), Triple(1.0, Double.POSITIVE_INFINITY, 0.0),
            Triple(1.0, 2.0, Double.NaN))) {
            assertThrows(IllegalArgumentException::class.java) { physics.resetPose(pose.first, pose.second, pose.third) }
            assertEquals(6.0, physics.robotBody.transform.translationX, 0.0)
            assertEquals(2.0, physics.robotBody.transform.translationY, 0.0)
            assertEquals(0.4, physics.robotBody.transform.rotationAngle, 1e-12)
        }
    }

    @Test fun `invalid timestep cannot corrupt the world`() {
        val physics = Dyn4jPhysicsWorld()
        for (dt in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.01)) {
            assertThrows(IllegalArgumentException::class.java) { physics.step(dt) }
            assertEquals(2.0, physics.robotBody.transform.translationX, 0.0)
        }
        physics.step(0.0)
        assertEquals(2.0, physics.robotBody.transform.translationX, 0.0)
    }

    @Test fun `invalid wall dimensions reject before adding any bodies`() {
        for ((width, height) in listOf(10.0 to -1.0, 10.0 to Double.NaN,
            Double.POSITIVE_INFINITY to 8.0, 0.0 to 8.0)) {
            val world = World<Body>()
            assertThrows(IllegalArgumentException::class.java) { FrcFieldBuilder.buildWorldWalls(world, width, height) }
            assertEquals(0, world.bodyCount)
        }
    }

    @Test fun `zero timestep preserves pending effort until a real step`() {
        val physics = Dyn4jPhysicsWorld()
        physics.resetPose(6.0, 2.0, 0.0)
        physics.robotBody.applyForce(Vector2(100.0, 0.0))
        physics.step(0.0)
        assertEquals(6.0, physics.robotBody.transform.translationX, 0.0)
        assertEquals(0.0, physics.robotBody.linearVelocity.x, 0.0)
        physics.step(0.02)
        assertTrue(physics.robotBody.transform.translationX > 6.0)
    }

    @Test fun `walls retain geometry and existing world bodies`() {
        val world = World<Body>()
        val existing = Body()
        world.addBody(existing)
        FrcFieldBuilder.buildWorldWalls(world, 10.0, 6.0)
        assertSame(existing, world.getBody(0))
        assertEquals(5, world.bodyCount)
        val sizes = listOf(10.0 to 0.1, 10.0 to 0.1, 0.1 to 6.0, 0.1 to 6.0)
        for ((index, size) in sizes.withIndex()) {
            val body = world.getBody(index + 1)
            val rectangle = body.fixtures.single().shape as org.dyn4j.geometry.Rectangle
            assertEquals(size.first, rectangle.width, 0.0)
            assertEquals(size.second, rectangle.height, 0.0)
            assertEquals(org.dyn4j.geometry.MassType.INFINITE, body.mass.type)
        }
    }
}
