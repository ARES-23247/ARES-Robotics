package org.aresfirst.starter.frc

import com.areslib.state.DriveState
import com.areslib.state.FieldType
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldObstacle
import com.areslib.state.RobotFieldPoint
import com.areslib.state.RobotState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class StarterDriveSweepAuditTest {
    private fun field(obstacle: RobotFieldObstacle? = null) = RobotFieldConfig(
        fieldType = FieldType.FRC, widthMeters = 5.0, heightMeters = 5.0,
        obstacles = listOfNotNull(obstacle),
    )

    private fun command(vx: Double = 0.0, vy: Double = 0.0, omega: Double = 0.0) =
        RobotState(drive = DriveState(xVelocityMetersPerSecond = vx, yVelocityMetersPerSecond = vy,
            angularVelocityRadiansPerSecond = omega, isFieldCentric = true))

    private fun wall(shape: String) = when (shape) {
        "polygon" -> RobotFieldObstacle(id = "wall", shape = shape, points = listOf(
            RobotFieldPoint(1.99, 0.2), RobotFieldPoint(2.01, 0.2),
            RobotFieldPoint(2.01, 1.8), RobotFieldPoint(1.99, 1.8),
        ))
        "circle" -> RobotFieldObstacle(id = "wall", shape = shape, x = 2.0, y = 1.0, width = 0.05, height = 0.05)
        else -> RobotFieldObstacle(id = "wall", shape = "rectangle", x = 2.0, y = 1.0,
            width = 0.02, height = 1.6, rotation = if (shape == "rotated") 30.0 else 0.0)
    }

    @ParameterizedTest
    @ValueSource(strings = ["rectangle", "rotated", "circle", "polygon"])
    fun `a whole frame cannot cross a blocking obstacle with clear endpoints`(shape: String) {
        val simulation = StarterDriveSimulation()
        simulation.configureField(field(wall(shape)))
        val update = simulation.step(command(vx = 60.0, vy = 1.0), 0.05, 50L)
        assertEquals(1.0, update.xMeters, 0.0)
        assertEquals(1.05, update.yMeters, 1e-12)
        assertEquals(0.0, update.xVelocityMetersPerSecond, 0.0)
        assertEquals(1.0, update.yVelocityMetersPerSecond, 1e-12)
    }

    @Test
    fun `turning cannot sweep a bumper outside the field even when the final pose fits`() {
        val simulation = StarterDriveSimulation(startX = 0.38, startY = 2.0)
        simulation.configureField(field())
        val update = simulation.step(command(omega = PI / 0.1), 0.05, 50L)
        assertEquals(0.0, update.headingRadians, 0.0)
        assertEquals(0.0, update.angularVelocityRadiansPerSecond, 0.0)
    }

    @Test
    fun `turning cannot sweep a bumper through a rectangle between clear start and end poses`() {
        val simulation = StarterDriveSimulation()
        simulation.configureField(field(RobotFieldObstacle(id = "roof", x = 1.0, y = 1.5,
            width = 1.0, height = 0.02)))
        val update = simulation.step(command(omega = PI / 0.1), 0.05, 50L)
        assertEquals(0.0, update.headingRadians, 0.0)
        assertEquals(0.0, update.angularVelocityRadiansPerSecond, 0.0)
    }

    @Test
    fun `nonblocking outlines do not prevent translation or turning`() {
        val simulation = StarterDriveSimulation()
        simulation.configureField(field(wall("rectangle").copy(isBlocking = false)))
        val update = simulation.step(command(vx = 60.0, omega = 1.0), 0.05, 50L)
        assertEquals(4.0, update.xMeters, 1e-12)
        assertEquals(0.05, update.headingRadians, 1e-12)
    }

    @Test
    fun `concave polygon opening stays traversable without replacing it by its bounding box`() {
        val obstacle = RobotFieldObstacle(id = "u", shape = "polygon", points = listOf(
            RobotFieldPoint(1.5, 0.0), RobotFieldPoint(3.5, 0.0), RobotFieldPoint(3.5, 3.0),
            RobotFieldPoint(3.0, 3.0), RobotFieldPoint(3.0, 1.0), RobotFieldPoint(2.0, 1.0),
            RobotFieldPoint(2.0, 3.0), RobotFieldPoint(1.5, 3.0),
        ))
        val simulation = StarterDriveSimulation(startX = 2.5, startY = 2.0)
        simulation.configureField(field(obstacle))
        val update = simulation.step(command(vy = 40.0), 0.05, 50L)
        assertEquals(4.0, update.yMeters, 1e-12)
    }

    @Test
    fun `raw invalid field replacement fails before mutating the accepted field`() {
        val simulation = StarterDriveSimulation()
        simulation.configureField(field())
        assertThrows(IllegalArgumentException::class.java) { simulation.configureField(field().copy(widthMeters = -1.0)) }
        assertThrows(IllegalArgumentException::class.java) { simulation.configureField(field(wall("circle").copy(width = -0.1))) }
        val update = simulation.step(command(vx = 1.0), 0.02, 20L)
        assertEquals(1.02, update.xMeters, 1e-12)
    }

    @Test
    fun `rotation envelopes agree with sampled bumper corners across both directions and wrapped arcs`() {
        val collision = StarterDriveCollision(field(), 0.375, 0.325)
        val centers = listOf(0.38 to 2.5, 4.62 to 2.5, 2.5 to 0.38, 2.5 to 4.62, 2.5 to 2.5)
        for ((x, y) in centers) {
            for (heading in listOf(-3.0, -1.3, 0.0, 0.7, 2.9)) {
                for (delta in listOf(-4 * PI, -1.4, -0.6, 0.0, 0.6, 1.4, 4 * PI)) {
                    var sampledFree = true
                    for (sample in 0..2000) {
                        val angle = heading + delta * sample / 2000.0
                        val c = cos(angle)
                        val s = sin(angle)
                        for (cornerX in listOf(-0.375, 0.375)) {
                            for (cornerY in listOf(-0.325, 0.325)) {
                                val worldX = x + cornerX * c - cornerY * s
                                val worldY = y + cornerX * s + cornerY * c
                                if (worldX !in 0.0..5.0 || worldY !in 0.0..5.0) sampledFree = false
                            }
                        }
                    }
                    assertEquals(sampledFree, collision.isRotationFree(x, y, heading, delta),
                        "center=($x,$y), heading=$heading, delta=$delta")
                }
            }
        }
    }
}
