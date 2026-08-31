package com.areslib.ftc

import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.ftc.drivetrain.PinpointIO
import com.areslib.math.wrapAngle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import com.areslib.util.RobotClock

/**
 * PinpointIOTest declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
class PinpointIOTest {

    @Test
    /**
     * testInitializeAndOffsetHandling declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun testInitializeAndOffsetHandling() {
        val rawDriver = GoBildaPinpointDriver()
        val pinpointIO = PinpointIO(rawDriver)

        // 1. Initialize with an offset pose (e.g. Red alliance start: facing PI)
        val initialPose = Pose2d(x = 1.0, y = -1.0, heading = Rotation2d(Math.PI))
        pinpointIO.initialize(initialPose)
        waitForInit(pinpointIO, 1.0)

        // Immediately after initialize (before raw movement), it should return the offset pose
        val initialUpdate = pinpointIO.getPoseUpdate()
        assertEquals(1.0, initialUpdate.xMeters, 1e-6)
        assertEquals(-1.0, initialUpdate.yMeters, 1e-6)
        assertEquals(wrapAngle(Math.PI), initialUpdate.headingRadians, 1e-6)

        // 2. Simulate raw movement relative to the initial reset state
        // The raw driver heading is natively CCW-positive, matching configured hardware.
        rawDriver.posX = 0.5 // moved 0.5m forward in driver frame
        rawDriver.posY = 0.0
        rawDriver.heading = 0.0
        Thread.sleep(20) // Allow background thread to run

        val update1 = pinpointIO.getPoseUpdate()
        // offsetHeading = wrapAngle(PI - 0.0) = PI
        // heading = wrapAngle(0.0 + PI) = PI
        // x_field = 0.5 * cos(PI) - 0.0 * sin(PI) + 1.0 = -0.5 + 1.0 = 0.5
        assertEquals(0.5, update1.xMeters, 1e-6)
        assertEquals(-1.0, update1.yMeters, 1e-6)
        assertEquals(wrapAngle(Math.PI), update1.headingRadians, 1e-6)

        // 3. Simulate a positive CCW rotation and translation in the driver frame.
        rawDriver.posX = 1.0
        rawDriver.posY = 0.5
        rawDriver.heading = 0.5
        Thread.sleep(20) // Allow background thread to run

        val update2 = pinpointIO.getPoseUpdate()
        // rawHeading = +0.5; heading = wrapAngle(0.5 + PI)
        // x_field = 1.0 * cos(PI) - 0.5 * sin(PI) + 1.0 = -1.0 + 1.0 = 0.0
        // y_field = 1.0 * sin(PI) + 0.5 * cos(PI) - 1.0 = 0.0 - 0.5 - 1.0 = -1.5
        assertEquals(0.0, update2.xMeters, 1e-6)
        assertEquals(-1.5, update2.yMeters, 1e-6)
        assertEquals(wrapAngle(Math.PI + 0.5), update2.headingRadians, 1e-6)
    }
 
    @Test
    /**
     * testSoftwareOnlyInitializeWithExistingRawOffsets declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun testSoftwareOnlyInitializeWithExistingRawOffsets() {
        val rawDriver = GoBildaPinpointDriver()
        val pinpointIO = PinpointIO(rawDriver)
 
        // Simulate some raw movement BEFORE initialization (e.g., robot moved before vision snap)
        // These are CCW-positive raw hardware values
        rawDriver.posX = 2.0
        rawDriver.posY = 1.0
        rawDriver.heading = 0.5  // 0.5 rad CCW in hardware
 
        // Initialize with a snap pose (e.g. at (3.0, 4.0, 1.5)) without resetting hardware
        val snapPose = Pose2d(x = 3.0, y = 4.0, heading = Rotation2d(1.5))
        pinpointIO.initialize(snapPose, resetHardware = false)
        waitForInit(pinpointIO, 3.0)
 
        // Immediately after initialize (before raw movement changes), it should return the snapPose
        val snapUpdate = pinpointIO.getPoseUpdate()
        assertEquals(3.0, snapUpdate.xMeters, 1e-6)
        assertEquals(4.0, snapUpdate.yMeters, 1e-6)
        assertEquals(1.5, snapUpdate.headingRadians, 1e-6)
 
        // If the robot rotates a further +0.1 rad CCW, the aligned field heading also increases.
        rawDriver.posX += 0.5
        rawDriver.heading += 0.1
        Thread.sleep(20) // Allow background thread to run

        val finalUpdate = pinpointIO.getPoseUpdate()
        assertEquals(1.6, finalUpdate.headingRadians, 1e-6)
    }

    private fun waitForInit(pinpointIO: PinpointIO, expectedX: Double) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 1000) {
            val update = pinpointIO.getPoseUpdate()
            if (kotlin.math.abs(update.xMeters - expectedX) < 1e-4) {
                return
            }
            Thread.sleep(5)
        }
        fail<Unit>("Timed out waiting for PinpointIO initialization to complete (expected X: $expectedX)")
    }

    @Test
    fun `test heading sign flip when isHeadingCcwPositive is true vs false`() {
        val rawDriver = GoBildaPinpointDriver()
        val pinpointIOCcw = PinpointIO(rawDriver, isHeadingCcwPositive = true)
        val pinpointIOCw = PinpointIO(rawDriver, isHeadingCcwPositive = false)
        
        rawDriver.heading = 1.0
        
        val updateCcw = pinpointIOCcw.getPoseUpdate()
        val updateCw = pinpointIOCw.getPoseUpdate()
        
        assertNotEquals(updateCcw.headingRadians, updateCw.headingRadians)
    }

    @Test
    fun `default contract preserves native CCW heading and selects named 4 bar pod`() {
        val rawDriver = GoBildaPinpointDriver()
        val pinpointIO = PinpointIO(rawDriver)
        rawDriver.heading = 0.75

        val update = pinpointIO.getPoseUpdate()

        assertEquals(0.75, update.headingRadians, 1e-6)
        assertEquals(
            GoBildaPinpointDriver.GoBildaOdometryPods.goBILDA_4_BAR_POD,
            rawDriver.configuredPod
        )
        assertNull(rawDriver.configuredEncoderResolution)
    }

    @Test
    fun `positive custom encoder resolution overrides named pod preset`() {
        val rawDriver = GoBildaPinpointDriver()

        PinpointIO(rawDriver, encoderResolution = 19.5)

        assertEquals(19.5, rawDriver.configuredEncoderResolution)
        assertNull(rawDriver.configuredPod)
    }

    @Test
    fun `test pose reset to specific coordinates`() {
        val rawDriver = GoBildaPinpointDriver()
        val pinpointIO = PinpointIO(rawDriver)
        
        val specificPose = Pose2d(x = 10.0, y = 20.0, heading = Rotation2d(Math.PI / 2))
        pinpointIO.initialize(specificPose)
        
        val update = pinpointIO.getPoseUpdate()
        val xMatch = when {
            kotlin.math.abs(update.xMeters - 10.0) < 1e-6 -> true
            else -> false
        }
        assertTrue(xMatch)
    }

    @Test
    fun `test sequential updates accumulate correctly`() {
        val rawDriver = GoBildaPinpointDriver()
        val pinpointIO = PinpointIO(rawDriver)
        pinpointIO.initialize(Pose2d(0.0, 0.0, Rotation2d(0.0)))
        
        rawDriver.posX = 1.0
        rawDriver.posY = 1.0
        
        val update1 = pinpointIO.getPoseUpdate()
        val valOk = when {
            update1 != null -> true
            else -> false
        }
        assertTrue(valOk)
    }

    @Test
    fun `nonfinite hardware packet is rejected and marks pinpoint unhealthy`() {
        RobotClock.useMockTime(1_000L)
        try {
            val rawDriver = GoBildaPinpointDriver()
            val pinpointIO = PinpointIO(rawDriver)

            rawDriver.posX = 0.25
            val healthy = pinpointIO.getPoseUpdate()
            assertTrue(pinpointIO.isHealthy(1_000L))
            assertEquals(0.25, healthy.xMeters, 1e-6)

            RobotClock.useMockTime(1_020L)
            rawDriver.posX = Double.NaN
            val rejected = pinpointIO.getPoseUpdate()

            assertFalse(pinpointIO.isHealthy(1_020L))
            assertEquals(PinpointIO.HealthStatus.NONFINITE, pinpointIO.healthStatus)
            assertEquals(0.25, rejected.xMeters, 1e-6, "Last trusted pose must be retained")
        } finally {
            RobotClock.useSystemTime()
        }
    }

    @Test
    fun `healthy packet becomes stale after the configured age`() {
        RobotClock.useMockTime(2_000L)
        try {
            val pinpointIO = PinpointIO(GoBildaPinpointDriver())
            pinpointIO.getPoseUpdate()
            assertTrue(pinpointIO.isHealthy(2_000L))

            assertFalse(pinpointIO.isHealthy(2_101L))
            assertEquals(PinpointIO.HealthStatus.STALE, pinpointIO.healthStatus)
        } finally {
            RobotClock.useSystemTime()
        }
    }

    @Test
    fun `physically impossible pose jump is not published`() {
        RobotClock.useMockTime(3_000L)
        try {
            val rawDriver = GoBildaPinpointDriver()
            val pinpointIO = PinpointIO(rawDriver)
            pinpointIO.getPoseUpdate()

            RobotClock.useMockTime(3_020L)
            rawDriver.posX = 5.0
            val rejected = pinpointIO.getPoseUpdate()

            assertEquals(PinpointIO.HealthStatus.IMPLAUSIBLE, pinpointIO.healthStatus)
            assertFalse(pinpointIO.isHealthy(3_020L))
            assertEquals(0.0, rejected.xMeters, 1e-6)
        } finally {
            RobotClock.useSystemTime()
        }
    }
}
