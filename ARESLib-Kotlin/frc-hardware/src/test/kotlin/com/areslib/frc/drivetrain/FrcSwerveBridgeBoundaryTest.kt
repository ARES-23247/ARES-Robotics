package com.areslib.frc.drivetrain

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.state.DriveState
import com.areslib.util.RobotClock
import com.ctre.phoenix6.swerve.SwerveRequest
import java.util.Optional
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class FrcSwerveBridgeBoundaryTest {
    @Test fun `close invalidates feedback and destroys native drivetrain at most once`() = withPhoenixBridge { fixture, io ->
        io.refresh()
        assertTrue(io.currentMeasurementsValid)
        io.close()
        assertFalse(io.currentMeasurementsValid)
        assertTrue(io.read().odometryX.isNaN())
        io.close()
        io.safe()
        verify(fixture.drivetrain, times(1)).close()
        verify(fixture.drivetrain, times(1)).setControl(any(SwerveRequest::class.java))
    }

    @Test fun `closed bridge rejects motion without calling native control`() = withPhoenixBridge { fixture, io ->
        io.close()
        clearInvocations(fixture.drivetrain)
        assertThrows(IllegalStateException::class.java) { io.write(DriveState(xVelocityMetersPerSecond = 2.0), 1.0) }
        verifyNoInteractions(fixture.drivetrain)
    }

    @Test fun `same brake and close failure preserves original error`() = withPhoenixBridge { fixture, io ->
        val failure = AssertionError("shared failure")
        doThrow(failure).`when`(fixture.drivetrain).setControl(any(SwerveRequest::class.java))
        doThrow(failure).`when`(fixture.drivetrain).close()
        assertSame(failure, assertThrows(AssertionError::class.java) { io.close() })
        assertTrue(failure.suppressed.isEmpty())
        io.close()
        verify(fixture.drivetrain, times(1)).close()
    }

    @Test fun `motion is braked before refresh and after cached feedback expires`() = withPhoenixBridge { fixture, io ->
        var observed: SwerveRequest? = null
        doAnswer { observed = it.getArgument(0); null }.`when`(fixture.drivetrain).setControl(any(SwerveRequest::class.java))
        val command = DriveState(xVelocityMetersPerSecond = 2.0)
        io.write(command, 1.0)
        assertInstanceOf(SwerveRequest.SwerveDriveBrake::class.java, observed)
        io.refresh()
        io.write(command, 1.0)
        assertInstanceOf(SwerveRequest.FieldCentric::class.java, observed)
        RobotClock.useMockTime(1101)
        io.write(command, 1.0)
        assertInstanceOf(SwerveRequest.SwerveDriveBrake::class.java, observed)
    }

    @Test fun `invalid explicit covariance is rejected without silently reusing vendor covariance`() = withPhoenixBridge { fixture, io ->
        clearInvocations(fixture.drivetrain)
        assertThrows(IllegalArgumentException::class.java) { io.addVisionMeasurement(Pose2d(), 100.0, Double.NaN, 0.7, 0.35) }
        verifyNoInteractions(fixture.drivetrain)
    }

    @Test fun `nonfinite seed and vision pose never reach estimator`() = withPhoenixBridge { fixture, io ->
        clearInvocations(fixture.drivetrain)
        val invalid = Pose2d(Double.NaN, 2.0, Rotation2d(0.0))
        assertThrows(IllegalArgumentException::class.java) { io.seedPose(invalid) }
        assertThrows(IllegalArgumentException::class.java) { io.addVisionMeasurement(invalid, 100.0) }
        verifyNoInteractions(fixture.drivetrain)
    }

    @Test fun `nonfinite historical pose fails without publishing a partial output`() = withPhoenixBridge { fixture, io ->
        `when`(fixture.drivetrain.samplePoseAt(99.0)).thenReturn(Optional.of(
            edu.wpi.first.math.geometry.Pose2d(1.0, Double.NaN, edu.wpi.first.math.geometry.Rotation2d())))
        val output = doubleArrayOf(7.0, 8.0, 9.0, 10.0)
        assertFalse(io.samplePoseAt(99.0, output))
        assertArrayEquals(doubleArrayOf(7.0, 8.0, 9.0, 10.0), output)
    }

}
