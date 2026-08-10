package com.areslib.sim.field

import com.areslib.state.RobotFieldElementInstance
import com.areslib.state.RobotFieldElementType
import com.areslib.state.RobotFieldObstacle
import org.dyn4j.dynamics.Body
import org.dyn4j.world.World
import kotlin.test.Test
import kotlin.test.assertEquals

class CanonicalFieldPhysicsTest {
    @Test
    fun obstacleUsesCanonicalDegreesAndMaterialProperties() {
        val world = World<Body>()
        val body = FieldObstacleLoader.loadObstacles(
            world,
            listOf(
                RobotFieldObstacle(
                    id = "rotated",
                    x = 1.0,
                    y = 2.0,
                    width = 0.4,
                    height = 0.2,
                    rotation = 90.0,
                    friction = 0.77,
                    restitution = 0.12
                )
            )
        ).single()

        assertEquals(Math.PI / 2.0, body.transform.rotation.toRadians(), 1e-9)
        assertEquals(0.77, body.fixtures.single().friction, 1e-9)
        assertEquals(0.12, body.fixtures.single().restitution, 1e-9)
    }

    @Test
    fun gamePieceUsesCanonicalTypeMaterialProperties() {
        val world = World<Body>()
        val body = FieldElementLoader.loadElements(
            world,
            listOf(
                RobotFieldElementType(
                    id = "piece",
                    shape = "sphere",
                    diameter = 0.2,
                    movable = true,
                    friction = 0.42,
                    restitution = 0.81
                )
            ),
            listOf(RobotFieldElementInstance(id = "piece-1", elementTypeId = "piece"))
        ).single()

        assertEquals(0.42, body.fixtures.single().friction, 1e-9)
        assertEquals(0.81, body.fixtures.single().restitution, 1e-9)
    }
}
