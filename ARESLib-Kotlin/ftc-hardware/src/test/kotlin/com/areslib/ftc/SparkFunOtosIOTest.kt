package com.areslib.ftc

import com.qualcomm.hardware.sparkfun.SparkFunOTOS
import com.areslib.ftc.drivetrain.SparkFunOtosIO
import com.areslib.hardware.drive.OdometryInputs
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.wrapAngle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SparkFunOtosIOTest {

    @Test
    fun testInitializeAndOffsetHandling() {
        val driver = SparkFunOTOS()
        val otosIO = SparkFunOtosIO(driver)

        val initialPose = Pose2d(x = 1.0, y = -1.0, heading = Rotation2d(Math.PI))
        otosIO.initialize(initialPose)

        val initialUpdate = otosIO.getPoseUpdate()
        assertEquals(1.0, initialUpdate.xMeters, 1e-6)
        assertEquals(-1.0, initialUpdate.yMeters, 1e-6)
        assertEquals(wrapAngle(Math.PI), initialUpdate.headingRadians, 1e-6)

        // Simulate forward translation of 0.5m in driver frame
        driver.setPosition(SparkFunOTOS.Pose2D(0.5, 0.0, 0.0))
        // Set simulated velocity
        val update1 = otosIO.getPoseUpdate()
        // With offsetHeading = PI, cos(PI) = -1: fieldX = 0.5 * -1 + 1.0 = 0.5
        assertEquals(0.5, update1.xMeters, 1e-5)
        assertEquals(-1.0, update1.yMeters, 1e-5)
        assertEquals(wrapAngle(Math.PI), update1.headingRadians, 1e-5)
        assertEquals(SparkFunOtosIO.HealthStatus.HEALTHY, otosIO.healthStatus)

        // Verify updateInputs interface works identically
        val inputs = OdometryInputs()
        otosIO.updateInputs(inputs)
        assertEquals(0.5, inputs.posX, 1e-5)
        assertEquals(-1.0, inputs.posY, 1e-5)
    }

    @Test
    fun testRejectionOfImplausibleTeleport() {
        val driver = SparkFunOTOS()
        val otosIO = SparkFunOtosIO(driver)
        otosIO.initialize(Pose2d())

        // Normal sample
        driver.setPosition(SparkFunOTOS.Pose2D(0.1, 0.0, 0.0))
        val sample1 = otosIO.getPoseUpdate()
        assertEquals(SparkFunOtosIO.HealthStatus.HEALTHY, otosIO.healthStatus)

        // Instantaneous jump of 100 meters
        driver.setPosition(SparkFunOTOS.Pose2D(100.0, 0.0, 0.0))
        val sample2 = otosIO.getPoseUpdate()
        assertEquals(SparkFunOtosIO.HealthStatus.IMPLAUSIBLE, otosIO.healthStatus)
        // Stays on last valid sample
        assertEquals(sample1.xMeters, sample2.xMeters, 1e-6)
    }
}
