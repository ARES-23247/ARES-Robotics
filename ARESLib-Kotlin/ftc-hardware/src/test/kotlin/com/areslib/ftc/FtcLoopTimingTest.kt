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
    private class ProbeRobot(imu: IMU? = null) : FtcBaseRobot(
        hardwareMap = object : HardwareMap() {
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(classOrType: Class<out T>, deviceName: String): T =
                (imu ?: error("No device")) as T
            override fun <T> getAll(classOrType: Class<out T>): List<T> = emptyList()
        }, pinpointName = null, limelightName = null, imuName = if (imu == null) null else "imu"
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
