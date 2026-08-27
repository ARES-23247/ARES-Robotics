package com.areslib.sim.field

import com.areslib.state.RobotFieldElementInstance
import com.areslib.state.RobotFieldElementType
import com.areslib.state.RobotFieldObstacle
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldDocument
import com.areslib.sim.physics.SimPhysicsWorld
import com.areslib.sim.network.NT4FieldPublisher
import com.google.gson.JsonParser
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

        val replacement = config.copy(
            revision = 43,
            widthMeters = 2.0,
            heightMeters = 4.0,
            obstacles = listOf(
                RobotFieldObstacle(id = "wall-2", x = -0.4, y = -0.2, width = 0.25, height = 0.15),
                RobotFieldObstacle(id = "wall-3", x = 0.8, y = 0.4, width = 0.20, height = 0.20),
            ),
            elements = emptyList(),
        )
        assertTrue(physics.replaceFieldDocumentJson(RobotFieldDocument.encode(replacement)))
        assertEquals(listOf("wall-2", "wall-3"), physics.loadedFieldConfig?.obstacles?.map { it.id })
        assertEquals(2, physics.activeObstacles.size)
        assertEquals(0, physics.gamePieces.size)
        assertEquals(2.05, physics.fieldWalls[0].transform.translationY, 1e-9)
        assertEquals(-1.05, physics.fieldWalls[2].transform.translationX, 1e-9)

        assertFalse(physics.replaceFieldDocumentJson("not-json"))
        assertEquals(2, physics.activeObstacles.size)
        assertEquals(0, physics.gamePieces.size)
        assertEquals(43, physics.loadedFieldConfig?.revision)
    }

    @Test
    fun canonicalDimensionsRebuildTheFourFieldBoundaries() {
        val physics = SimPhysicsWorld()

        physics.loadFieldElements(RobotFieldConfig(widthMeters = 2.0, heightMeters = 4.0))

        assertEquals(4, physics.fieldWalls.size)
        assertEquals(2.05, physics.fieldWalls[0].transform.translationY, 1e-9)
        assertEquals(-2.05, physics.fieldWalls[1].transform.translationY, 1e-9)
        assertEquals(-1.05, physics.fieldWalls[2].transform.translationX, 1e-9)
        assertEquals(1.05, physics.fieldWalls[3].transform.translationX, 1e-9)
        assertEquals(5, physics.world.bodyCount)
    }

    @Test
    fun appliedReceiptIdentifiesTheExactCanonicalFieldAndCounts() {
        val config = RobotFieldConfig(
            id = "practice-field",
            revision = 17,
            obstacles = listOf(RobotFieldObstacle(id = "barrier")),
            elementTypes = listOf(RobotFieldElementType(id = "piece")),
            elements = listOf(
                RobotFieldElementInstance(id = "piece-a", elementTypeId = "piece"),
                RobotFieldElementInstance(id = "piece-b", elementTypeId = "piece"),
            ),
            apriltags = listOf(com.areslib.state.RobotFieldAprilTag(id = 7)),
        )

        val receipt = JsonParser.parseString(
            NT4FieldPublisher.encodeAppliedFieldReceipt(config, "sim-session", 3L)
        ).asJsonObject

        assertEquals("sim-session", receipt["session"].asString)
        assertEquals(3L, receipt["sequence"].asLong)
        assertEquals("practice-field", receipt["configId"].asString)
        assertEquals(17L, receipt["revision"].asLong)
        assertTrue(receipt["sha256"].asString.matches(Regex("[0-9a-f]{64}")))
        assertEquals(1, receipt["obstacleCount"].asInt)
        assertEquals(2, receipt["elementCount"].asInt)
        assertEquals(1, receipt["aprilTagCount"].asInt)
    }

    @Test
    fun obstacleTelemetryIsAlwaysACompleteJsonArray() {
        assertEquals(emptyList<Any>(), JsonParser.parseString(NT4FieldPublisher.encodeObstaclesJson(emptyList())).asJsonArray.toList())
        val encoded = NT4FieldPublisher.encodeObstaclesJson(
            listOf(RobotFieldObstacle(id = "wall", name = "Barrier", x = 0.5, y = -0.25))
        )
        val obstacle = JsonParser.parseString(encoded).asJsonArray.single().asJsonObject
        assertEquals("wall", obstacle["id"].asString)
        assertEquals("Barrier", obstacle["name"].asString)
    }
}
