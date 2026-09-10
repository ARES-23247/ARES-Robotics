package com.areslib.frc.drivetrain

import com.areslib.state.DriveState
import com.ctre.phoenix6.swerve.SwerveRequest
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.math.BigDecimal
import java.util.Random
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class SwerveCtreWriterNumericalTest {
    @Test fun `both request frames match exact decimal scaling across finite exponents`() {
        val random = Random(5201L)
        var request: SwerveRequest? = null
        val writer = SwerveCtreSpeedRequestWriter { request = it }
        val scales = doubleArrayOf(-Double.MAX_VALUE, -0.0, 0.0, Double.MIN_VALUE, 0.3, 0.5, 1.0, Double.MAX_VALUE)
        repeat(2000) { iteration ->
            fun number(): Double {
                var value: Double
                do { value = Double.fromBits(random.nextLong()) } while (!value.isFinite())
                return value
            }
            val values = if (iteration < 16) doubleArrayOf(Double.MAX_VALUE, -Double.MIN_VALUE, -0.0)
                else doubleArrayOf(number(), number(), number())
            val field = (iteration / scales.size) % 2 == 0
            val scale = scales[iteration % scales.size]
            writer.write(DriveState(xVelocityMetersPerSecond = values[0], yVelocityMetersPerSecond = values[1],
                angularVelocityRadiansPerSecond = values[2], isFieldCentric = field), scale)
            val actual = if (field) {
                val r = assertInstanceOf(SwerveRequest.FieldCentric::class.java, request)
                doubleArrayOf(r.VelocityX, r.VelocityY, r.RotationalRate)
            } else {
                val r = assertInstanceOf(SwerveRequest.ApplyRobotSpeeds::class.java, request)
                doubleArrayOf(r.Speeds.vxMetersPerSecond, r.Speeds.vyMetersPerSecond, r.Speeds.omegaRadiansPerSecond)
            }
            val bounded = if (scale < 0.0) 0.0 else if (scale > 1.0) 1.0 else scale
            for (index in 0 until 3) {
                val decimal = BigDecimal(values[index]).multiply(BigDecimal(bounded)).toDouble()
                val expected = if (decimal == 0.0) Math.copySign(0.0, values[index] * Math.copySign(1.0, bounded)) else decimal
                assertEquals(expected.toRawBits(), actual[index].toRawBits(), "iteration=$iteration axis=$index")
                assertTrue(actual[index].isFinite())
            }
        }
    }

    @Test fun `requests preserve mode and are reused while the native constructor forwards synchronously`() {
        val fixture = PhoenixReaderFixture()
        var observed: SwerveRequest? = null
        doAnswer { observed = it.getArgument(0); null }.`when`(fixture.drivetrain).setControl(any(SwerveRequest::class.java))
        val writer = SwerveCtreSpeedRequestWriter(fixture.drivetrain)
        writer.write(DriveState(xVelocityMetersPerSecond = 2.0, isFieldCentric = true), 0.5)
        val field = assertInstanceOf(SwerveRequest.FieldCentric::class.java, observed)
        assertEquals(1.0, field.VelocityX)
        writer.write(DriveState(xVelocityMetersPerSecond = -4.0, isFieldCentric = true), 0.25)
        assertSame(field, observed)
        assertEquals(-1.0, field.VelocityX)
        writer.write(DriveState(yVelocityMetersPerSecond = 2.0, isFieldCentric = false), 0.5)
        val robot = assertInstanceOf(SwerveRequest.ApplyRobotSpeeds::class.java, observed)
        val speeds = robot.Speeds
        writer.write(DriveState(angularVelocityRadiansPerSecond = -3.0, isFieldCentric = false), 1.0)
        assertSame(robot, observed)
        assertSame(speeds, robot.Speeds)
        assertEquals(-3.0, speeds.omegaRadiansPerSecond)
        writer.safe()
        val brake = assertInstanceOf(SwerveRequest.SwerveDriveBrake::class.java, observed)
        writer.write(DriveState(isXLock = true), Double.NaN)
        assertSame(brake, observed)
        verify(fixture.drivetrain, times(6)).setControl(any(SwerveRequest::class.java))
    }

    @Test fun `varying both frames scales and brakes allocates no bytes after warmup`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        var checksum = 0.0
        val writer = SwerveCtreSpeedRequestWriter { request ->
            checksum += when (request) {
                is SwerveRequest.FieldCentric -> request.VelocityX
                is SwerveRequest.ApplyRobotSpeeds -> request.Speeds.vyMetersPerSecond
                else -> 1.0
            }
        }
        val states = arrayOf(DriveState(xVelocityMetersPerSecond = 2.0, isFieldCentric = true),
            DriveState(yVelocityMetersPerSecond = 3.0, isFieldCentric = false), DriveState(isXLock = true))
        var iteration = 0
        fun tick() { writer.write(states[iteration % 3], (iteration++ % 4) * 0.5) }
        repeat(50_000) { tick() }
        val thread = Thread.currentThread().id
        repeat(2) {
            val before = bean.getThreadAllocatedBytes(thread)
            repeat(10_000) { tick() }
            assertEquals(0L, bean.getThreadAllocatedBytes(thread) - before)
        }
        assertTrue(checksum.isFinite() && checksum > 0.0)
    }
}
