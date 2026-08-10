package com.areslib.logging

import com.areslib.action.RobotAction
import com.areslib.state.DriveState
import com.areslib.state.VisionMeasurement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RobotInputsFrameTest {

    @Test
    fun testInputsFramePoolAndClear() {
        val poolCountBefore = RobotInputsFramePool.availableCount
        assertTrue(poolCountBefore > 0)

        // Rent a frame
        val frame = RobotInputsFramePool.rent()
        assertNotNull(frame)
        assertEquals("", frame.runId)
        assertEquals("", frame.robotId)
        assertEquals(0L, frame.timestampMs)

        // Set some values
        frame.runId = "test_run"
        frame.robotId = "test_robot"
        frame.timestampMs = 555L
        frame.odometryInputs.posX = 1.23

        // Recycle the frame
        RobotInputsFramePool.recycle(frame)
        assertEquals(poolCountBefore, RobotInputsFramePool.availableCount)

        // Rent again and verify cleared
        val rentedAgain = RobotInputsFramePool.rent()
        assertEquals("", rentedAgain.runId)
        assertEquals("", rentedAgain.robotId)
        assertEquals(0L, rentedAgain.timestampMs)
        assertEquals(0.0, rentedAgain.odometryInputs.posX)
    }

    @Test
    fun testInputsFramePopulate() {
        val frame = RobotInputsFrame()
        val poseUpdateAction = RobotAction.PoseUpdate(
            xMeters = 1.5,
            yMeters = 2.5,
            headingRadians = 1.0,
            timestampMs = 1000L
        )
        val driveState = DriveState(
            xVelocityMetersPerSecond = 0.5,
            yVelocityMetersPerSecond = -0.5,
            angularVelocityRadiansPerSecond = 0.1
        )
        val visionMeasurements = listOf(
            VisionMeasurement(timestampMs = 123L, tagId = 1)
        )

        frame.populate(
            runId = "run_99",
            robotId = "robot_a",
            timestamp = 1000L,
            poseUpdate = poseUpdateAction,
            driveState = driveState,
            hasVision = true,
            measurements = visionMeasurements
        )

        assertEquals("run_99", frame.runId)
        assertEquals("robot_a", frame.robotId)
        assertEquals(1000L, frame.timestampMs)

        // Odometry check
        assertEquals(1.5, frame.odometryInputs.posX)
        assertEquals(2.5, frame.odometryInputs.posY)
        assertEquals(1.0, frame.odometryInputs.heading)
        assertEquals(0.5, frame.odometryInputs.velX)
        assertEquals(-0.5, frame.odometryInputs.velY)
        assertEquals(0.1, frame.odometryInputs.headingVelocity)

        // IMU check
        assertEquals(1.0, frame.imuInputs.headingRadians)
        assertEquals(0.1, frame.imuInputs.yawVelocityRadPerSec)

        // Vision check
        assertTrue(frame.visionInputs.isConnected)
        assertEquals(1, frame.visionInputs.measurements.size)
        assertEquals(1, frame.visionInputs.measurements[0].tagId)
    }
}
