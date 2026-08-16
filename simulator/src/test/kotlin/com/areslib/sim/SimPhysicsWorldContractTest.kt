package com.areslib.sim

import com.areslib.sim.physics.SimPhysicsWorld
import com.areslib.sim.physics.canonicalFieldConfigPaths
import org.junit.Assert.assertEquals
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import org.junit.Test

class SimPhysicsWorldContractTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun projectFieldAssetPrecedesDeveloperCheckoutFallback() {
        val workingDirectory = temporaryFolder.newFolder("generated-project")
        val userHome = temporaryFolder.newFolder("home")
        val projectField = java.io.File(workingDirectory, "TeamCode/src/main/assets/paths/field.json")
        val developerField = java.io.File(userHome, "dev/robotics/ares/ARES-FTC/TeamCode/src/main/assets/paths/field.json")
        projectField.parentFile.mkdirs()
        developerField.parentFile.mkdirs()
        projectField.writeText("project")
        developerField.writeText("developer")

        val discovered = canonicalFieldConfigPaths(workingDirectory, userHome).first(java.io.File::isFile)

        assertEquals(projectField.canonicalFile, discovered.canonicalFile)
    }

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

    @Test
    fun boundaryWallsContainChassisWithinFieldExtents() {
        val physics = SimPhysicsWorld()
        physics.setupSpawnPose(isRedAlliance = true)
        physics.robotBody.linearVelocity = org.dyn4j.geometry.Vector2(0.0, -10.0)

        // Step physics forward 100 iterations (2.0 seconds at 20ms steps)
        repeat(100) {
            physics.world.step(1, 0.02)
        }

        // Robot must remain inside the field bounds (-1.825m to +1.825m)
        org.junit.Assert.assertTrue("Robot should not tunnel through wall", physics.robotBody.transform.translationY >= -1.825)
        org.junit.Assert.assertTrue("Robot velocity should be damped on collision", physics.robotBody.linearVelocity.y >= -1.0)
    }

    @Test
    fun linearAndAngularDampingBringsUnforcedChassisToRest() {
        val physics = SimPhysicsWorld()
        physics.setupSpawnPose(isRedAlliance = true)
        physics.robotBody.linearVelocity = org.dyn4j.geometry.Vector2(2.0, 0.0)
        physics.robotBody.angularVelocity = 4.0

        repeat(150) {
            physics.world.step(1, 0.02)
        }

        org.junit.Assert.assertTrue("Linear velocity should damp toward 0", physics.robotBody.linearVelocity.magnitude <= 0.1)
        org.junit.Assert.assertTrue("Angular velocity should damp toward 0", kotlin.math.abs(physics.robotBody.angularVelocity) <= 0.1)
    }

    @Test
    fun replaceObstaclesFromAnalyticsJsonDynamicallyUpdatesWorldBodies() {
        val physics = SimPhysicsWorld()
        val jsonPayload = """[{"id":"obs1","x":0.5,"y":0.5,"width":0.3,"height":0.3,"shape":"RECTANGLE"}]"""

        val updated = physics.replaceObstaclesFromAnalyticsJson(jsonPayload)
        org.junit.Assert.assertTrue(updated)
        assertEquals(1, physics.activeObstacles.size)

        val cleared = physics.replaceObstaclesFromAnalyticsJson("[]")
        org.junit.Assert.assertTrue(cleared)
        assertEquals(0, physics.activeObstacles.size)
    }
}
