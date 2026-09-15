// SPDX-License-Identifier: Apache-2.0
package org.aresfirst.marvin.sim

import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldObstacle
import org.aresfirst.marvin.FlyingBall
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test

class WorldReplacementAuditTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `failed replacement preserves the entire existing world`(failAfterWalls: Boolean) {
        val physics = Dyn4jPhysicsWorld()
        physics.resetPose(3.0, 2.5, 0.4)
        physics.robotBody.linearVelocity.set(1.0, -0.5)
        val flight = FlyingBall(2.0, 3.0, 1.0, 0.0, 0.0, 1.0)
        physics.flyingBalls.add(flight)
        val bodies = physics.world.bodies.toList()
        val balls = physics.balls.toList()
        assertTrue(balls.isNotEmpty(), "The deployed fixture must exercise retained pieces")
        val invalid = if (failAfterWalls) RobotFieldConfig(obstacles = listOf(
            RobotFieldObstacle(id = "valid", x = 4.0, y = 4.0),
            RobotFieldObstacle(id = "invalid", friction = -1.0),
        )) else RobotFieldConfig(widthMeters = Double.POSITIVE_INFINITY)

        assertThrows(IllegalArgumentException::class.java) { physics.buildWorld(invalid) }

        assertEquals(bodies, physics.world.bodies)
        assertEquals(balls, physics.balls)
        assertEquals(listOf(flight), physics.flyingBalls)
        assertEquals(3.0, physics.robotBody.transform.translationX, 0.0)
        assertEquals(2.5, physics.robotBody.transform.translationY, 0.0)
        assertEquals(1.0, physics.robotBody.linearVelocity.x, 0.0)
        assertEquals(-0.5, physics.robotBody.linearVelocity.y, 0.0)
    }

    @Test
    fun `successful repeated replacement keeps the robot and replaces field contents once`() {
        val physics = Dyn4jPhysicsWorld()
        val robot = physics.robotBody
        physics.resetPose(3.0, 2.5, 0.4)
        physics.flyingBalls.add(FlyingBall(2.0, 3.0, 1.0, 0.0, 0.0, 1.0))
        repeat(2) {
            physics.buildWorld(RobotFieldConfig(widthMeters = 10.0, heightMeters = 6.0))
            assertEquals(5, physics.world.bodyCount)
            assertSame(robot, physics.world.getBody(0))
            assertTrue(physics.balls.isEmpty())
            assertTrue(physics.flyingBalls.isEmpty())
            assertEquals(3.0, robot.transform.translationX, 0.0)
            assertEquals(2.5, robot.transform.translationY, 0.0)
        }
    }
}
