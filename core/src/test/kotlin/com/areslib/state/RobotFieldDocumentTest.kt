package com.areslib.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RobotFieldDocumentTest {
    @Test
    fun canonicalDocumentRoundTripsEditorAndPhysicsMetadata() {
        val original = RobotFieldConfig(
            revision = 7,
            id = "decode-2026",
            name = "DECODE",
            gameYear = "2025-2026",
            fieldType = FieldType.FTC,
            widthMeters = 3.6576,
            heightMeters = 3.6576,
            obstacles = listOf(
                RobotFieldObstacle(
                    id = "ramp",
                    name = "Ramp",
                    x = 0.5,
                    y = -0.25,
                    width = 0.6,
                    height = 0.4,
                    obstacleType = ObstacleType.RAMP,
                    friction = 0.72,
                    restitution = 0.08,
                    rotation = 30.0,
                    locked = true,
                    color = "#00ACC1"
                )
            ),
            elementTypes = listOf(
                RobotFieldElementType(
                    id = "game-piece-ball",
                    name = "Ball",
                    shape = "sphere",
                    diameter = 0.15,
                    massKg = 0.24,
                    movable = true,
                    friction = 0.55,
                    restitution = 0.4
                )
            ),
            elements = listOf(
                RobotFieldElementInstance(
                    id = "ball-1",
                    elementTypeId = "game-piece-ball",
                    name = "Ball 1",
                    x = 1.0,
                    y = 1.2,
                    locked = true
                )
            ),
            fieldWaypoints = listOf(
                RobotFieldWaypoint("score", "Score", 0.4, 1.1, 90.0, locked = true)
            ),
            image = RobotFieldImageConfig(
                rotationDegrees = 270.0,
                ftcCoordinateSystem = FtcFieldCoordinateSystem.SQUARE
            )
        )

        val decoded = RobotFieldDocument.decode(RobotFieldDocument.encode(original))

        assertEquals(CURRENT_FIELD_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(7, decoded.revision)
        assertEquals(3.6576, decoded.resolvedWidthMeters, 1e-9)
        assertEquals(0.72, decoded.obstacles.single().friction, 1e-9)
        assertEquals(30.0, decoded.obstacles.single().rotation, 1e-9)
        assertEquals("Ball 1", decoded.elements.single().name)
        assertEquals(0.55, decoded.elementTypes.single().friction, 1e-9)
        assertEquals("Score", decoded.fieldWaypoints.single().name)
        assertEquals(FtcFieldCoordinateSystem.SQUARE, decoded.image?.ftcCoordinateSystem)
    }

    @Test
    fun futureSchemaVersionIsRejected() {
        val json = """{"schemaVersion":${CURRENT_FIELD_SCHEMA_VERSION + 1},"fieldType":"ftc"}"""
        assertThrows(IllegalArgumentException::class.java) { RobotFieldDocument.decode(json) }
    }
}
