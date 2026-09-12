package com.areslib.ftc.hardware

import com.areslib.ftc.hardware.rev.RevImuController
import com.areslib.hardware.sensor.ImuIO
import com.areslib.telemetry.ITelemetry
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.hardware.IMU
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FtcImuTelemetryAllocationAuditTest {
    @Test fun `IMU facade warm telemetry reuses storage without polling SDK on the caller`() = checkPublisher { FtcImu(it) }
    @Test fun `direct IMU controller warm telemetry reuses storage without polling SDK on the caller`() = checkPublisher { RevImuController(it) }

    private fun checkPublisher(factory: (IMU) -> ImuIO) {
        RobotClock.useMockTime(1000)
        val caller = Thread.currentThread()
        var foregroundReads = 0
        val imu = factory(object : IMU {
            override fun initialize(parameters: IMU.Parameters) = true
            override fun resetYaw() = Unit
            override fun getRobotYawPitchRollAngles(): YawPitchRollAngles {
                if (Thread.currentThread() === caller) foregroundReads++
                return YawPitchRollAngles(AngleUnit.RADIANS, 0.5)
            }
            override fun getRobotAngularVelocity(unit: AngleUnit) = AngularVelocity(AngleUnit.RADIANS)
        })
        try {
            val telemetry = CountingTelemetry()
            val originalReads = foregroundReads
            repeat(100_000) { imu.logTelemetry(telemetry, "Hardware/IMU") }
            val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
            bean.isThreadAllocatedMemoryEnabled = true
            val threadId = Thread.currentThread().id
            val before = bean.getThreadAllocatedBytes(threadId)
            repeat(10_000) { imu.logTelemetry(telemetry, "Hardware/IMU") }
            val allocated = bean.getThreadAllocatedBytes(threadId)-before
            assertEquals(originalReads, foregroundReads)
            assertEquals(660_000, telemetry.puts)
            assertEquals(0.5, telemetry.heading, 0.0)
            println("FTC IMU telemetry: $allocated bytes / 10,000 calls (desktop JVM)")
            assertTrue(allocated <= 4096L, "Warm IMU telemetry allocated $allocated bytes")
        } finally {
            (imu as AutoCloseable).close()
            RobotClock.useSystemTime()
        }
    }

    private class CountingTelemetry : ITelemetry {
        var puts = 0
        var heading = 0.0
        override fun putNumber(key: String, value: Double) { puts++; if (key.endsWith("/HeadingRad")) heading = value }
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun putString(key: String, value: String) = Unit
        override fun putDoubleArray(key: String, value: DoubleArray) = Unit
        override fun getNumber(key: String, defaultValue: Double) = defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun getString(key: String, defaultValue: String) = defaultValue
    }
}
