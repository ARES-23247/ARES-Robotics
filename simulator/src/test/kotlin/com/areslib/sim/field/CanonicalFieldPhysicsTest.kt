package com.areslib.sim.field

import com.areslib.state.RobotFieldElementInstance
import com.areslib.state.RobotFieldElementType
import com.areslib.state.RobotFieldObstacle
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldDocument
import com.areslib.sim.physics.SimPhysicsWorld
import org.dyn4j.dynamics.Body
import org.dyn4j.world.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun liveCanonicalDocumentReplacesObstaclesAndGamePiecesTogether() {
        val physics = SimPhysicsWorld()
        val config = RobotFieldConfig(
            revision = 42,
            obstacles = listOf(RobotFieldObstacle(id = "wall", x = 0.4, y = 0.2, width = 0.5, height = 0.1)),
            elementTypes = listOf(RobotFieldElementType(id = "note", shape = "sphere", diameter = 0.35)),
            elements = listOf(RobotFieldElementInstance(id = "note-1", elementTypeId = "note", x = 0.1, y = -0.2))
        )

        assertTrue(physics.replaceFieldDocumentJson(RobotFieldDocument.encode(config)))
        assertEquals(1, physics.activeObstacles.size)
        assertEquals(1, physics.gamePieces.size)

        assertFalse(physics.replaceFieldDocumentJson("not-json"))
        assertEquals(1, physics.activeObstacles.size)
        assertEquals(1, physics.gamePieces.size)
    }
}
