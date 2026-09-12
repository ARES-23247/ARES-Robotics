package com.areslib.ftc

import com.areslib.ftc.core.FtcOpModeLifecycleController
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.IMU
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FtcLoopTimingTest {
    private class ProbeRobot(imu: IMU? = null, pinpoint: com.qualcomm.hardware.gobilda.GoBildaPinpointDriver? = null) : FtcBaseRobot(
        hardwareMap = object : HardwareMap() {
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(classOrType: Class<out T>, deviceName: String): T =
                (if (deviceName == "pinpoint") requireNotNull(pinpoint) else requireNotNull(imu)) as T
            override fun <T> getAll(classOrType: Class<out T>): List<T> = emptyList()
        }, pinpointName = if (pinpoint == null) null else "pinpoint", limelightName = null, imuName = if (imu == null) null else "imu"
    ) {
        var reads = 0
        var sensorMillis = 0L
        val imuTimestamp get() = cachedImuInputs.timestampMs
        val imuHeading get() = cachedImuInputs.headingRadians
        override fun updateHardwareInputs() {
            reads++
            RobotClock.useMockTime(RobotClock.currentTimeMillis() + sensorMillis)
        }
        override fun updateSubsystems(dtSeconds: Double, batteryVoltage: Double, powerScale: Double) = Unit
        override fun publishRobotTelemetry(timestamp: Long) = Unit
        override fun safeHardware() = Unit
    }

    @Test
    fun `pre-read sensor work is counted once in loop duration and overruns`() {
        RobotClock.useMockTime(1000L)
        val previousPacing = FtcOpModeLifecycleController.beginExternallyPacedFrame()
        val robot = ProbeRobot()
        try {
            robot.sensorMillis = 30L
            robot.readSensors()
            robot.readSensors()
            robot.update()
            val telemetry = robot.telemetryManager.dataLoggingTelemetry
            assertEquals(1, robot.reads)
            assertEquals(30.0, telemetry.getNumber("Profiling/ReadSensors_ms", -1.0))
            assertEquals(30.0, telemetry.getNumber("Profiling/Total_ms", -1.0))
            assertEquals(1.0, telemetry.getNumber("Diagnostics/LoopOverruns", -1.0))
            robot.sensorMillis = 2L
            robot.update()
            assertEquals(2, robot.reads)
            assertEquals(2.0, telemetry.getNumber("Profiling/ReadSensors_ms", -1.0))
            assertEquals(1.0, telemetry.getNumber("Diagnostics/LoopOverruns", -1.0))
        } finally {
            robot.close()
            FtcOpModeLifecycleController.endExternallyPacedFrame(previousPacing)
            RobotClock.useSystemTime()
        }
    }

    @Test
    fun `Pinpoint acquired after frame start keeps heading live and recovers after a real fault`() {
        RobotClock.useMockTime(1000L)
        val driver = com.qualcomm.hardware.gobilda.GoBildaPinpointDriver()
        val robot = ProbeRobot(pinpoint = driver)
        val pacing = FtcOpModeLifecycleController.beginExternallyPacedFrame()
        try {
            robot.sensorMillis = 7L // Real I2C reads finish after the frame boundary.
            robot.update()
            assertEquals("PINPOINT", com.areslib.telemetry.RobotStatusTracker.odometrySource)
            driver.heading = 0.2
            driver.headingVelocity = 0.4
            RobotClock.useMockTime(1100L)
            robot.update()
            assertEquals(0.2, robot.store.state.drive.poseEstimator.estimatedPoseHeading, 1e-9)
            assertEquals(0.4, robot.store.state.drive.measuredAngularVelocityRadiansPerSecond, 1e-9)

            val drive = com.areslib.subsystem.MecanumDriveFacade(robot.store).apply { maxSpeedMps = 1.0 }
            drive.driveFieldRelativeNormalized(1.0, 0.0, 0.0)
            assertEquals(kotlin.math.cos(0.2), robot.store.state.drive.xVelocityMetersPerSecond, 1e-9)
            assertEquals(-kotlin.math.sin(0.2), robot.store.state.drive.yVelocityMetersPerSecond, 1e-9)

            driver.posX = Double.NaN
            RobotClock.useMockTime(1200L)
            robot.update()
            assertEquals("DRIVETRAIN_FALLBACK", com.areslib.telemetry.RobotStatusTracker.odometrySource)
            driver.posX = 0.0
            repeat(6) {
                RobotClock.useMockTime(1300L + it * 20L)
                robot.update()
            }
            assertEquals("PINPOINT", com.areslib.telemetry.RobotStatusTracker.odometrySource)
            driver.heading = 0.3
            RobotClock.useMockTime(1500L)
            robot.update()
            assertEquals(0.3, robot.store.state.drive.poseEstimator.estimatedPoseHeading, 1e-9)
        } finally {
            robot.close()
            FtcOpModeLifecycleController.endExternallyPacedFrame(pacing)
            RobotClock.useSystemTime()
        }
    }

    @Test
    fun `IMU sample newer than frame start is valid when consumed`() {
        RobotClock.useMockTime(1000L)
        val imu = object : IMU {
            override fun initialize(parameters: IMU.Parameters) = true
            override fun resetYaw() = Unit
            override fun getRobotYawPitchRollAngles() = YawPitchRollAngles(AngleUnit.RADIANS, 0.5)
            override fun getRobotAngularVelocity(unit: AngleUnit) = AngularVelocity(AngleUnit.RADIANS)
        }
        val robot = ProbeRobot(imu)
        try {
            // First IMU initialization obtains a sample after this frame's hardware read.
            robot.sensorMillis = 5L
            robot.readSensors()
            assertEquals(1005L, robot.imuTimestamp)
            assertEquals(0.5, robot.imuHeading, 1e-12)
        } finally {
            robot.close()
            RobotClock.useSystemTime()
        }
    }
}
