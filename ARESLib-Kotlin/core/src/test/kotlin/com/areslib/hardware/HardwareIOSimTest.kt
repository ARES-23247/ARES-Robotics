package com.areslib.hardware

import com.areslib.hardware.drive.SwerveModuleIO
import com.areslib.hardware.drive.SwerveModuleInputs
import com.areslib.hardware.sensor.ImuIO
import com.areslib.hardware.sensor.ImuInputs
import com.areslib.hardware.drive.OdometryIO
import com.areslib.hardware.drive.OdometryInputs
import com.areslib.math.geometry.Pose2d
import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ==========================================================
// =            Simulated IO Implementations                =
// ==========================================================

class SwerveModuleIOSim : SwerveModuleIO {
    var simPositionRad = 0.0
    var simVelocityRadPerSec = 0.0
    var simSteerAngleRad = 0.0

    override fun updateInputs(inputs: SwerveModuleInputs) {
        // Integrate position based on simulated velocity (assuming 20ms delta time)
        simPositionRad += simVelocityRadPerSec * 0.02
        
        inputs.drivePositionRads = simPositionRad
        inputs.driveVelocityRadsPerSec = simVelocityRadPerSec
        inputs.steerAbsolutePositionRads = simSteerAngleRad
        inputs.timestampMs = com.areslib.util.RobotClock.currentTimeMillis()
    }
}

class ImuIOSim : ImuIO {
    var simHeadingRad = 0.0
    var simYawVelocityRadPerSec = 0.0
    var simPitchRad = 0.0
    var simRollRad = 0.0
    private var offsetHeadingRad = 0.0

    override fun updateInputs(inputs: ImuInputs) {
        inputs.headingRadians = simHeadingRad - offsetHeadingRad
        inputs.pitchRadians = simPitchRad
        inputs.rollRadians = simRollRad
        inputs.yawVelocityRadPerSec = simYawVelocityRadPerSec
        inputs.timestampMs = com.areslib.util.RobotClock.currentTimeMillis()
    }

    override fun resetHeading() {
        offsetHeadingRad = simHeadingRad
    }
}

class OdometryIOSim : OdometryIO {
    var simPosX = 0.0
    var simPosY = 0.0
    var simHeading = 0.0
    var simVelX = 0.0
    var simVelY = 0.0
    var simHeadingVel = 0.0

    override fun initialize(startPose: Pose2d) {
        simPosX = startPose.x
        simPosY = startPose.y
        simHeading = startPose.heading.radians
    }

    override fun updateInputs(inputs: OdometryInputs) {
        // Simple kinematic integration
        simPosX += simVelX * 0.02
        simPosY += simVelY * 0.02
        simHeading += simHeadingVel * 0.02

        inputs.posX = simPosX
        inputs.posY = simPosY
        inputs.heading = simHeading
        inputs.velX = simVelX
        inputs.velY = simVelY
        inputs.headingVelocity = simHeadingVel
        inputs.timestampMs = com.areslib.util.RobotClock.currentTimeMillis()
    }
}

// ==========================================================
// =                     Unit Tests                         =
// ==========================================================

class HardwareIOSimTest {
    private val gson = Gson()

    @Test
    fun testSwerveModuleIOSimAndUpdate() {
        val simModule = SwerveModuleIOSim()
        val inputs = SwerveModuleInputs()

        // 1. Initial State
        simModule.updateInputs(inputs)
        assertEquals(0.0, inputs.drivePositionRads)
        assertEquals(0.0, inputs.driveVelocityRadsPerSec)
        assertEquals(0.0, inputs.steerAbsolutePositionRads)

        // 2. Set velocity and steer angle
        simModule.simVelocityRadPerSec = 5.0
        simModule.simSteerAngleRad = 1.2
        simModule.updateInputs(inputs)

        // After one cycle (20ms) position = 0.0 + 5.0 * 0.02 = 0.1 rad
        assertEquals(0.1, inputs.drivePositionRads, 1e-6)
        assertEquals(5.0, inputs.driveVelocityRadsPerSec)
        assertEquals(1.2, inputs.steerAbsolutePositionRads)
    }

    @Test
    fun testSwerveInputsSerialization() {
        val inputs = SwerveModuleInputs(
            drivePositionRads = 12.34,
            driveVelocityRadsPerSec = 2.5,
            steerAbsolutePositionRads = 0.78,
            timestampMs = 1716000000000L
        )

        // Serialize to JSON
        val json = gson.toJson(inputs)
        assertTrue(json.contains("drivePositionRads"))
        assertTrue(json.contains("12.34"))

        // Deserialize back
        val deserialized = gson.fromJson(json, SwerveModuleInputs::class.java)
        assertEquals(inputs.drivePositionRads, deserialized.drivePositionRads)
        assertEquals(inputs.driveVelocityRadsPerSec, deserialized.driveVelocityRadsPerSec)
        assertEquals(inputs.steerAbsolutePositionRads, deserialized.steerAbsolutePositionRads)
        assertEquals(inputs.timestampMs, deserialized.timestampMs)
    }

    @Test
    fun testImuIOSimAndReset() {
        val imu = ImuIOSim()
        val inputs = ImuInputs()

        imu.simHeadingRad = 1.5
        imu.simPitchRad = 0.1
        imu.simRollRad = -0.2
        imu.simYawVelocityRadPerSec = 0.5
        
        imu.updateInputs(inputs)
        assertEquals(1.5, inputs.headingRadians)
        assertEquals(0.1, inputs.pitchRadians)
        assertEquals(-0.2, inputs.rollRadians)
        assertEquals(0.5, inputs.yawVelocityRadPerSec)

        // Reset heading makes current heading the 0 offset
        imu.resetHeading()
        imu.updateInputs(inputs)
        assertEquals(0.0, inputs.headingRadians)

        // Changing heading relative to offset
        imu.simHeadingRad = 2.0
        imu.updateInputs(inputs)
        assertEquals(0.5, inputs.headingRadians)
    }

    @Test
    fun testImuInputsSerialization() {
        val inputs = ImuInputs(
            headingRadians = 1.23,
            pitchRadians = 0.05,
            rollRadians = -0.08,
            yawVelocityRadPerSec = 0.12,
            timestampMs = 1716000000000L
        )

        val json = gson.toJson(inputs)
        val deserialized = gson.fromJson(json, ImuInputs::class.java)
        assertEquals(inputs.headingRadians, deserialized.headingRadians)
        assertEquals(inputs.pitchRadians, deserialized.pitchRadians)
        assertEquals(inputs.rollRadians, deserialized.rollRadians)
        assertEquals(inputs.yawVelocityRadPerSec, deserialized.yawVelocityRadPerSec)
    }

    @Test
    fun testOdometryIOSimIntegration() {
        val odo = OdometryIOSim()
        val inputs = OdometryInputs()

        // 1. Initial State
        odo.updateInputs(inputs)
        assertEquals(0.0, inputs.posX)
        assertEquals(0.0, inputs.posY)
        assertEquals(0.0, inputs.heading)

        // 2. Set velocities
        odo.simVelX = 2.0
        odo.simVelY = -1.0
        odo.simHeadingVel = 0.5
        odo.updateInputs(inputs)

        // After one cycle (20ms): posX = 0.04, posY = -0.02, heading = 0.01
        assertEquals(0.04, inputs.posX, 1e-6)
        assertEquals(-0.02, inputs.posY, 1e-6)
        assertEquals(0.01, inputs.heading, 1e-6)
    }

    @Test
    fun testOdometryInputsSerialization() {
        val inputs = OdometryInputs(
            posX = 1.25,
            posY = -0.75,
            heading = 0.88,
            velX = 0.2,
            velY = 0.4,
            headingVelocity = 0.1,
            timestampMs = 1716000000000L
        )

        val json = gson.toJson(inputs)
        val deserialized = gson.fromJson(json, OdometryInputs::class.java)
        assertEquals(inputs.posX, deserialized.posX)
        assertEquals(inputs.posY, deserialized.posY)
        assertEquals(inputs.heading, deserialized.heading)
        assertEquals(inputs.velX, deserialized.velX)
        assertEquals(inputs.velY, deserialized.velY)
        assertEquals(inputs.headingVelocity, deserialized.headingVelocity)
    }
}
