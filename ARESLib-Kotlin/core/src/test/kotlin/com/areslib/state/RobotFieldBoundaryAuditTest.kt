package com.areslib.state

import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RobotFieldBoundaryAuditTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `centered field starts stay inside each configured wall and face inward`() {
        for (type in listOf(FieldType.FTC, FieldType.XRP)) {
            for ((width, height) in listOf(0.0 to 0.0, 4.0 to 2.0, 0.02 to 0.01)) {
                for (side in DriverStationSide.entries) {
                    val config = RobotFieldConfig(fieldType = type, widthMeters = width,
                        heightMeters = height, blueDriverStation = side, redDriverStation = side)
                    for (alliance in Alliance.entries) {
                        val pose = config.getInitialPose(alliance)
                        val halfWidth = config.resolvedWidthMeters / 2.0
                        val halfHeight = config.resolvedHeightMeters / 2.0
                        assertTrue(abs(pose.x) <= halfWidth, "$type $side x=${pose.x}")
                        assertTrue(abs(pose.y) <= halfHeight, "$type $side y=${pose.y}")
                        assertTrue(pose.x * cos(pose.heading.radians) +
                            pose.y * sin(pose.heading.radians) <= 1e-12)
                        val forward = config.mapJoystickIntents(1.0, 0.0, alliance)
                        assertEquals(cos(pose.heading.radians), forward.first, 1e-12)
                        assertEquals(sin(pose.heading.radians), forward.second, 1e-12)
                    }
                }
            }
        }
    }

    @Test
    fun `FRC starts use configured corner origin dimensions and retain default inset`() {
        for ((width, height) in listOf(6.0 to 3.0, 0.2 to 0.1)) {
            val config = RobotFieldConfig(fieldType = FieldType.FRC,
                widthMeters = width, heightMeters = height)
            val blue = config.getInitialPose(Alliance.BLUE)
            val red = config.getInitialPose(Alliance.RED)
            assertEquals(height / 2.0, blue.y, 1e-12)
            assertEquals(height / 2.0, red.y, 1e-12)
            assertTrue(blue.x in 0.0..width)
            assertTrue(red.x in 0.0..width)
            assertEquals(width, blue.x + red.x, 1e-12)
            assertEquals(minOf(0.5, width / 2.0), blue.x, 1e-12)
        }
    }

    @Test
    fun `invalid explicit dimensions cannot produce a starting pose`() {
        for (type in FieldType.entries) {
            for (value in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                assertThrows(IllegalArgumentException::class.java) {
                    RobotFieldConfig(fieldType = type, widthMeters = value).getInitialPose(Alliance.BLUE)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    RobotFieldConfig(fieldType = type, heightMeters = value).getInitialPose(Alliance.RED)
                }
            }
        }
    }

    @Test
    fun `file loader rejects semantic errors without replacing the active field`() {
        val previous = RobotFieldManager.activeConfig
        val file = directory.resolve("field.json").toFile()
        try {
            val valid = RobotFieldConfig(id = "valid", fieldType = FieldType.FRC,
                apriltags = listOf(RobotFieldAprilTag(id = 1)))
            file.writeText(RobotFieldDocument.encode(valid))
            assertTrue(RobotFieldManager.loadFromJsonFile(file.path))
            val accepted = RobotFieldManager.activeConfig
            for (invalid in listOf(valid.copy(widthMeters = -1.0),
                valid.copy(apriltags = valid.apriltags + valid.apriltags),
                valid.copy(fieldWaypoints = listOf(RobotFieldWaypoint(id = ""))))) {
                file.writeText(RobotFieldDocument.encode(invalid))
                assertFalse(RobotFieldManager.loadFromJsonFile(file.path))
                assertSame(accepted, RobotFieldManager.activeConfig)
            }
        } finally {
            RobotFieldManager.setActiveConfig(previous)
        }
    }
}
