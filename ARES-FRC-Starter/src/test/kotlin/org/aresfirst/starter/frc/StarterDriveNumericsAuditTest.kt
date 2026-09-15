package org.aresfirst.starter.frc

import com.areslib.state.DriveState
import com.areslib.state.FieldType
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldObstacle
import com.areslib.state.RobotState
import edu.wpi.first.math.geometry.Pose2d
import edu.wpi.first.math.geometry.Rotation2d
import edu.wpi.first.math.geometry.Twist2d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class StarterDriveNumericsAuditTest {
    private fun command(vx: Double = 2.0, vy: Double = -0.4, omega: Double = 4.0, fieldCentric: Boolean = false) =
        RobotState(drive = DriveState(xVelocityMetersPerSecond = vx, yVelocityMetersPerSecond = vy,
            angularVelocityRadiansPerSecond = omega, isFieldCentric = fieldCentric))

    private fun field(width: Double = 5.0, height: Double = 5.0) =
        RobotFieldConfig(fieldType = FieldType.FRC, widthMeters = width, heightMeters = height)

    @Test
    fun `initial pose rejects nonfinite coordinates and heading`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { StarterDriveSimulation(startX = bad) }
            assertThrows(IllegalArgumentException::class.java) { StarterDriveSimulation(startY = bad) }
            assertThrows(IllegalArgumentException::class.java) { StarterDriveSimulation(startHeadingRadians = bad) }
        }
    }

    @Test
    fun `constructor normalizes very large finite headings before exposing the initial pose`() {
        val simulation = StarterDriveSimulation(startHeadingRadians = 1e100)
        assertTrue(simulation.headingRadians >= -PI && simulation.headingRadians < PI)
    }

    @Test
    fun `body frame translation during rotation agrees with the independent WPILib twist exponential`() {
        val simulation = StarterDriveSimulation(startX = 1.0, startY = 2.0, startHeadingRadians = PI / 3.0)
        val expected = Pose2d(1.0, 2.0, Rotation2d(PI / 3.0)).exp(Twist2d(0.1, -0.02, 0.2))
        val result = simulation.step(command(), 0.05, 50L)
        assertEquals(expected.x, result.xMeters, 1e-12)
        assertEquals(expected.y, result.yMeters, 1e-12)
        assertEquals(expected.rotation.radians, result.headingRadians, 1e-12)
        assertEquals((expected.x - 1.0) / 0.05, result.xVelocityMetersPerSecond, 1e-12)
        assertEquals((expected.y - 2.0) / 0.05, result.yVelocityMetersPerSecond, 1e-12)
    }

    @Test
    fun `constant body twist reaches the same pose with one frame or five smaller frames`() {
        val one = StarterDriveSimulation(startHeadingRadians = 0.7)
        val many = StarterDriveSimulation(startHeadingRadians = 0.7)
        val drive = command()
        one.step(drive, 0.05, 50L)
        repeat(5) { many.step(drive, 0.01, (it + 1) * 10L) }
        assertEquals(one.xMeters, many.xMeters, 1e-12)
        assertEquals(one.yMeters, many.yMeters, 1e-12)
        assertEquals(one.headingRadians, many.headingRadians, 1e-12)
    }

    @Test
    fun `blocked translation reports measured wall slide rather than commanded velocity`() {
        val simulation = StarterDriveSimulation()
        simulation.configureField(field().copy(obstacles = listOf(
            RobotFieldObstacle(id = "wall", x = 2.0, y = 1.0, width = 0.2, height = 2.0),
        )))
        val drive = command(vx = 10.0, vy = 1.0, omega = 0.0, fieldCentric = true)
        simulation.step(drive, 0.05, 50L)
        val update = simulation.step(drive, 0.05, 100L)
        assertEquals(1.5, update.xMeters, 1e-12)
        assertEquals(1.1, update.yMeters, 1e-12)
        assertEquals(0.0, update.xVelocityMetersPerSecond, 0.0)
        assertEquals(1.0, update.yVelocityMetersPerSecond, 1e-12)
    }

    @Test
    fun `zero negative and nonfinite time intervals cannot report fresh commanded motion`() {
        for (dt in listOf(0.0, -0.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            val simulation = StarterDriveSimulation()
            val update = simulation.step(command(), dt, 0L)
            assertEquals(1.0, update.xMeters, 0.0)
            assertEquals(1.0, update.yMeters, 0.0)
            assertEquals(0.0, update.headingRadians, 0.0)
            assertEquals(0.0, update.xVelocityMetersPerSecond, 0.0)
            assertEquals(0.0, update.yVelocityMetersPerSecond, 0.0)
            assertEquals(0.0, update.angularVelocityRadiansPerSecond, 0.0)
            assertFalse(update.motionMeasurementsValid)
        }
    }

    @Test
    fun `overflowing field velocity cannot poison pose when no field has been configured`() {
        val simulation = StarterDriveSimulation(startHeadingRadians = PI / 4.0)
        val drive = command(vx = Double.MAX_VALUE, vy = -Double.MAX_VALUE, omega = 0.0)
        repeat(30) {
            val update = simulation.step(drive, 0.05, (it + 1) * 50L)
            assertTrue(update.xMeters.isFinite() && update.yMeters.isFinite())
            assertTrue(update.xVelocityMetersPerSecond.isFinite() && update.yVelocityMetersPerSecond.isFinite())
        }
    }

    @Test
    fun `field replacement that cannot fit the robot preserves the previous valid field`() {
        val simulation = StarterDriveSimulation()
        simulation.configureField(field())
        assertThrows(IllegalArgumentException::class.java) { simulation.configureField(field(width = 0.5)) }
        val update = simulation.step(command(vx = 2.0, vy = 0.0, omega = 0.0, fieldCentric = true), 0.02, 20L)
        assertEquals(1.04, update.xMeters, 1e-12)
    }

    @Test
    fun `field installation clamps the full rotated bumper footprint`() {
        val heading = PI / 4.0
        val simulation = StarterDriveSimulation(startX = 4.9, startY = 4.9, startHeadingRadians = heading)
        simulation.configureField(field())
        val extent = 0.375 * cos(heading) + 0.325 * sin(heading)
        assertEquals(5.0 - extent, simulation.xMeters, 1e-12)
        assertEquals(5.0 - extent, simulation.yMeters, 1e-12)
    }

    @Test
    fun `frame action is reused and a bounded frame retains tiny rotational translation`() {
        val simulation = StarterDriveSimulation()
        val drive = command(vx = 1.0, vy = 0.0, omega = 1e-8)
        val first = simulation.step(drive, 0.05, 50L)
        val firstY = first.yMeters
        val second = simulation.step(drive, 0.01, 60L)
        assertSame(first, second)
        assertTrue(firstY > 1.0, "Small nonzero curvature must survive cancellation in 1 - cos(theta)")
        assertEquals(60L, second.timestampMs)
    }

    @Test
    fun `huge finite reset and spin finish within a bounded execution budget`() {
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            val simulation = StarterDriveSimulation()
            simulation.resetPose(1.0, 1.0, Double.MAX_VALUE)
            val result = simulation.step(command(vx = 0.0, vy = 0.0, omega = Double.MAX_VALUE), 0.05, 50L)
            assertTrue(result.headingRadians >= -PI && result.headingRadians < PI)
            assertEquals(1.0, result.xMeters, 0.0)
            assertEquals(1.0, result.yMeters, 0.0)
        }
    }

    @Test
    fun `reset bounds use the requested heading and preserve the prior pose on failure`() {
        val simulation = StarterDriveSimulation()
        simulation.configureField(field())
        simulation.resetPose(0.34, 1.0, PI / 2.0)
        assertEquals(0.34, simulation.xMeters, 0.0)
        assertThrows(IllegalArgumentException::class.java) { simulation.resetPose(0.34, 1.0, 0.0) }
        assertEquals(0.34, simulation.xMeters, 0.0)
        assertEquals(PI / 2.0, simulation.headingRadians, 0.0)
    }
}
