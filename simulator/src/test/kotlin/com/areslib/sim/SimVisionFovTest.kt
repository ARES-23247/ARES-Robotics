package com.areslib.sim

import com.areslib.sim.model.MecanumRobotDouble
import com.areslib.networktables.NT4Server
import com.areslib.state.RobotFieldAprilTag
import com.areslib.state.RobotFieldConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SimVisionFovTest {

    @After
    fun stopServer() {
        NT4Server.getInstance()?.stop()
        NT4Server.resetSharedState()
    }

    @Test
    fun testLimelightFovDetectionAndClearing() {
        NT4Server.createInstance("127.0.0.1", 0)
        val robot = MecanumRobotDouble()

        // 1. Robot at (0, -1.2) facing +90 deg (towards +Y Blue wall / Tag 11 at 0, 1.8 inside 35 deg FOV cone)
        robot.updateSensors(
            dt = 0.02,
            actualVx = 0.0,
            actualVy = 0.0,
            actualOmega = 0.0,
            trueX = 0.0,
            trueY = -1.2,
            trueHeadingRad = Math.PI / 2
        )
        val facingResult = robot.limelight.getLatestResult()
        assertNotNull("Limelight should detect AprilTag when robot is facing tag inside FOV", facingResult)
        assertTrue("Limelight result should be valid when facing tag", facingResult!!.isValid())
        assertTrue("Vision target telemetry should use the canonical boolean type", NT4Server.getBoolean("Vision/HasTarget", false))

        // 2. Robot at (0, -1.2) facing -90 deg (facing Red wall, tags 3 & 4 are at +/-71.5 deg outside 35 deg FOV)
        robot.updateSensors(
            dt = 0.02,
            actualVx = 0.0,
            actualVy = 0.0,
            actualOmega = 0.0,
            trueX = 0.0,
            trueY = -1.2,
            trueHeadingRad = -Math.PI / 2
        )
        val rotatedResult = robot.limelight.getLatestResult()
        assertNull("Limelight result should be null when robot turns away from tags outside FOV", rotatedResult)
        assertFalse("Vision target telemetry should clear as a boolean", NT4Server.getBoolean("Vision/HasTarget", true))

        // 3. Robot at center field (0, 0) facing -90 deg (facing Red alliance wall, tags 3 & 4 are at +/-45 deg outside 35 deg FOV)
        robot.updateSensors(
            dt = 0.02,
            actualVx = 0.0,
            actualVy = 0.0,
            actualOmega = 0.0,
            trueX = 0.0,
            trueY = 0.0,
            trueHeadingRad = -Math.PI / 2
        )
        val centerRedResult = robot.limelight.getLatestResult()
        assertNull("Limelight result should be null when no tags are within camera FOV cone", centerRedResult)
    }

    @Test
    fun authoredAprilTagsReplaceTheLegacyVisionLayout() {
        NT4Server.createInstance("127.0.0.1", 0)
        val robot = MecanumRobotDouble()
        robot.configureField(
            RobotFieldConfig(
                apriltags = listOf(RobotFieldAprilTag(id = 42, x = 0.0, y = 1.8, z = 0.2))
            )
        )

        robot.updateSensors(
            dt = 0.02,
            actualVx = 0.0,
            actualVy = 0.0,
            actualOmega = 0.0,
            trueX = 0.0,
            trueY = -1.2,
            trueHeadingRad = Math.PI / 2
        )

        assertEquals(42, robot.limelight.getLatestResult()!!.getFiducialResults().single().getFiducialId())

        robot.configureField(RobotFieldConfig(apriltags = emptyList()))
        robot.updateSensors(
            dt = 0.02,
            actualVx = 0.0,
            actualVy = 0.0,
            actualOmega = 0.0,
            trueX = 0.0,
            trueY = -1.2,
            trueHeadingRad = Math.PI / 2
        )
        assertNull(robot.limelight.getLatestResult())
    }
}
