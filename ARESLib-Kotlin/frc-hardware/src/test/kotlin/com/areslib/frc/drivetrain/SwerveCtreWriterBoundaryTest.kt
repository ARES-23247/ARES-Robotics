package com.areslib.frc.drivetrain

import com.areslib.state.DriveMode
import com.areslib.state.DriveState
import com.ctre.phoenix6.swerve.SwerveRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SwerveCtreWriterBoundaryTest {
    @Test fun `every nonfinite component and scale rejects both request frames with one brake attempt`() {
        for (field in listOf(false, true)) for (scale in doubleArrayOf(0.0, 0.5, 1.0)) {
            for (component in 0 until 3) for (bad in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                var calls = 0
                val writer = SwerveCtreSpeedRequestWriter {
                    calls++
                    assertInstanceOf(SwerveRequest.SwerveDriveBrake::class.java, it)
                }
                val velocity = doubleArrayOf(1.0, 2.0, 3.0); velocity[component] = bad
                val state = DriveState(xVelocityMetersPerSecond = velocity[0], yVelocityMetersPerSecond = velocity[1],
                    angularVelocityRadiansPerSecond = velocity[2], isFieldCentric = field)
                assertThrows(IllegalArgumentException::class.java) { writer.write(state, scale) }
                assertEquals(1, calls)
            }
        }
        for (bad in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            var calls = 0
            val writer = SwerveCtreSpeedRequestWriter { calls++; assertInstanceOf(SwerveRequest.SwerveDriveBrake::class.java, it) }
            assertThrows(IllegalArgumentException::class.java) { writer.write(DriveState(), bad) }
            assertEquals(1, calls)
        }
    }

    @Test fun `distinct cleanup errors are suppressed while repeated identical errors retain identity`() {
        for (same in listOf(false, true)) for (field in listOf(false, true)) {
            val primary = AssertionError("motion")
            val cleanup = if (same) primary else IllegalStateException("brake")
            var calls = 0
            val writer = SwerveCtreSpeedRequestWriter {
                calls++
                throw if (it is SwerveRequest.SwerveDriveBrake) cleanup else primary
            }
            assertSame(primary, assertThrows(AssertionError::class.java) { writer.write(DriveState(isFieldCentric = field), 1.0) })
            assertEquals(2, calls)
            assertEquals(if (same) 0 else 1, primary.suppressed.size)
            if (!same) assertSame(cleanup, primary.suppressed.single())
        }
    }

    @Test fun `invalid arguments remain primary when the brake itself fails`() {
        val cleanup = AssertionError("brake")
        var calls = 0
        val writer = SwerveCtreSpeedRequestWriter { calls++; throw cleanup }
        val error = assertThrows(IllegalArgumentException::class.java) { writer.write(DriveState(), Double.NaN) }
        assertSame(cleanup, error.suppressed.single())
        assertEquals(1, calls)
    }

    @Test fun `safe and both explicit brake modes make one attempt without redundant retry`() {
        for (mode in 0..2) {
            val error = IllegalStateException("brake")
            var calls = 0
            val writer = SwerveCtreSpeedRequestWriter { calls++; throw error }
            val observed = assertThrows(IllegalStateException::class.java) {
                when (mode) {
                    0 -> writer.safe()
                    1 -> writer.write(DriveState(isXLock = true), Double.NaN)
                    else -> writer.write(DriveState(driveMode = DriveMode.X_BRAKE), Double.NaN)
                }
            }
            assertSame(error, observed)
            assertEquals(1, calls)
        }
    }

    @Test fun `invalid scale attempts brake before rejecting a previously active command`() {
        var last: SwerveRequest? = null
        val writer = SwerveCtreSpeedRequestWriter { last = it }
        val command = DriveState(xVelocityMetersPerSecond = 2.0, isFieldCentric = false)
        writer.write(command, 1.0)
        assertThrows(IllegalArgumentException::class.java) { writer.write(command, Double.NaN) }
        assertInstanceOf(SwerveRequest.SwerveDriveBrake::class.java, last)
    }

    @Test fun `nonfinite velocity never reaches a motion request even at zero scale`() {
        var last: SwerveRequest? = null
        val writer = SwerveCtreSpeedRequestWriter { last = it }
        assertThrows(IllegalArgumentException::class.java) {
            writer.write(DriveState(xVelocityMetersPerSecond = Double.POSITIVE_INFINITY), 0.0)
        }
        assertInstanceOf(SwerveRequest.SwerveDriveBrake::class.java, last)
    }

    @Test fun `failed motion request attempts brake while preserving the original failure`() {
        val failure = IllegalStateException("motion")
        var last: SwerveRequest? = null
        val writer = SwerveCtreSpeedRequestWriter {
            last = it
            if (it !is SwerveRequest.SwerveDriveBrake) throw failure
        }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { writer.write(DriveState(), 1.0) })
        assertInstanceOf(SwerveRequest.SwerveDriveBrake::class.java, last)
    }

    @Test fun `explicit brake is independent of unused invalid motion fields and scale`() {
        var last: SwerveRequest? = null
        val writer = SwerveCtreSpeedRequestWriter { last = it }
        writer.write(DriveState(driveMode = DriveMode.X_BRAKE, xVelocityMetersPerSecond = Double.NaN), Double.NaN)
        assertInstanceOf(SwerveRequest.SwerveDriveBrake::class.java, last)
    }
}
