package com.areslib.frc.drivetrain

import com.areslib.hardware.HardwareRegistry
import com.areslib.hardware.drive.SwerveHardwareIO
import com.areslib.math.geometry.Pose2d
import com.areslib.state.DriveState
import com.ctre.phoenix6.swerve.SwerveRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SwerveCtreSafetyRequestTest {
    @BeforeEach
    fun setUp() {
        HardwareRegistry.clear()
    }

    @AfterEach
    fun tearDown() {
        HardwareRegistry.clear()
    }

    @Test
    fun `registry safety preserves a physical CTRE X brake through the full writer path`() {
        val requests = mutableListOf<SwerveRequest>()
        val writer = SwerveCtreSpeedRequestWriter { request -> requests.add(request) }
        val io = object : SwerveHardwareIO {
            override fun read(): DriveState = DriveState()
            override fun write(driveState: DriveState, powerScale: Double) = writer.write(driveState, powerScale)
            override fun safe() = writer.safe()
            override fun addVisionMeasurement(pose: Pose2d, timestampSeconds: Double) = Unit
        }
        HardwareRegistry.registerDevice("Swerve", io)

        io.write(
            DriveState(
                xVelocityMetersPerSecond = 1.0,
                isFieldCentric = false
            ),
            0.5,
        )
        val speedRequest = assertInstanceOf(SwerveRequest.ApplyRobotSpeeds::class.java, requests.last())
        assertEquals(0.5, speedRequest.Speeds.vxMetersPerSecond, 1e-9)

        HardwareRegistry.safeAll()

        assertInstanceOf(SwerveRequest.SwerveDriveBrake::class.java, requests.last())
    }

    @Test
    fun `writer rejects nonfinite scale and clamps excess scale at hardware boundary`() {
        var lastRequest: SwerveRequest? = null
        val writer = SwerveCtreSpeedRequestWriter { request -> lastRequest = request }
        val command = DriveState(xVelocityMetersPerSecond = 2.0, isFieldCentric = false)

        assertThrows(IllegalArgumentException::class.java) { writer.write(command, Double.NaN) }
        writer.write(command, 3.0)

        val request = assertInstanceOf(SwerveRequest.ApplyRobotSpeeds::class.java, lastRequest)
        assertEquals(2.0, request.Speeds.vxMetersPerSecond, 1e-9)
    }
}
