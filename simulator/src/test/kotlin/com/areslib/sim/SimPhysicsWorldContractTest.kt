package com.areslib.sim

import com.areslib.sim.physics.SimPhysicsWorld
import org.junit.Assert.assertEquals
import org.junit.Test

class SimPhysicsWorldContractTest {

    @Test
    fun allianceSpawnPosesAreExactMirrorsAndAreAppliedToDyn4jBody() {
        val physics = SimPhysicsWorld()

        val red = physics.setupSpawnPose(isRedAlliance = true)
        assertEquals(0.0, red.x, 1e-12)
        assertEquals(-1.2, red.y, 1e-12)
        assertEquals(Math.PI / 2.0, red.heading.radians, 1e-12)
        assertEquals(red.x, physics.robotBody.transform.translationX, 1e-12)
        assertEquals(red.y, physics.robotBody.transform.translationY, 1e-12)
        assertEquals(red.heading.radians, physics.robotBody.transform.rotationAngle, 1e-12)

        val blue = physics.setupSpawnPose(isRedAlliance = false)
        assertEquals(red.x, blue.x, 1e-12)
        assertEquals(-red.y, blue.y, 1e-12)
        assertEquals(-red.heading.radians, blue.heading.radians, 1e-12)
        assertEquals(blue.x, physics.robotBody.transform.translationX, 1e-12)
        assertEquals(blue.y, physics.robotBody.transform.translationY, 1e-12)
        assertEquals(blue.heading.radians, physics.robotBody.transform.rotationAngle, 1e-12)
    }
}
