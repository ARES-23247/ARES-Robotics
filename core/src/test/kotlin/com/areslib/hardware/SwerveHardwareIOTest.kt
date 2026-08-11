package com.areslib.hardware

import com.areslib.hardware.drive.SwerveHardwareIO
import com.areslib.math.geometry.Pose2d
import com.areslib.state.DriveState
import com.areslib.telemetry.ITelemetry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SwerveHardwareIOTest {
    @Test
    fun `fault bits and encoder validity are published from cached swerve snapshot`() {
        val numbers = mutableMapOf<String, Double>()
        val booleans = mutableMapOf<String, Boolean>()
        val telemetry = object : ITelemetry {
            override fun putNumber(key: String, value: Double) { numbers[key] = value }
            override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
            override fun putString(key: String, value: String) = Unit
            override fun putDoubleArray(key: String, value: DoubleArray) = Unit
            override fun getNumber(key: String, defaultValue: Double) = defaultValue
            override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
            override fun getString(key: String, defaultValue: String) = defaultValue
        }
        val io = object : SwerveHardwareIO {
            override val encoderPositionsValid = false
            override fun getFaults(out: IntArray) {
                out[0] = 0x01; out[1] = 0x12; out[2] = 0x24; out[3] = 0x3f
            }
            override fun read() = DriveState()
            override fun write(driveState: DriveState) = Unit
            override fun addVisionMeasurement(pose: Pose2d, timestampSeconds: Double) = Unit
        }

        io.logTelemetry(telemetry, "Hardware/Swerve")

        assertFalse(booleans.getValue("Hardware/Swerve/EncoderPositionsValid"))
        assertEquals(1.0, numbers["Hardware/Swerve/FaultBits/FrontLeft"])
        assertEquals(18.0, numbers["Hardware/Swerve/FaultBits/FrontRight"])
        assertEquals(36.0, numbers["Hardware/Swerve/FaultBits/RearLeft"])
        assertEquals(63.0, numbers["Hardware/Swerve/FaultBits/RearRight"])
    }
}
