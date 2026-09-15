package org.firstinspires.ftc.teamcode

import com.areslib.state.AprilTagMapCodec
import com.areslib.state.RobotFieldDocument
import com.areslib.state.RobotFieldManager
import com.google.gson.JsonParser
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import org.firstinspires.ftc.teamcode.dsl.segmentsIntersect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class FtcFieldAssetContractTest {
    @Test
    fun `canonical blocking polygons have no crossing nonadjacent edges`() {
        val field = RobotFieldDocument.decode(File(findProjectRoot(), "TeamCode/src/main/assets/paths/field.json").readText())
        val polygons = field.obstacles.filter { it.isBlocking && it.shape == "polygon" }
        assertEquals(2, polygons.size)
        for (obstacle in polygons) {
            val points = obstacle.points
            assertTrue(points.size >= 3)
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i + 1) % points.size]
                for (j in i + 1 until points.size) {
                    if (j == i + 1 || i == 0 && j == points.lastIndex) continue
                    val c = points[j]
                    val d = points[(j + 1) % points.size]
                    assertFalse("${obstacle.id}: edges $i and $j cross",
                        segmentsIntersect(a.x, a.y, b.x, b.y, c.x, c.y, d.x, d.y))
                }
            }
        }
    }

    @Test
    fun `interchange rejects malformed input on the FTC SDK Gson runtime`() {
        for (json in listOf("", "{}", "{fiducials:[]}", "{\"fiducials\":[]} trailing", "{\"fiducials\":[]} {}")) {
            assertThrows(IllegalArgumentException::class.java) { AprilTagMapCodec.decodeLimelightFmap(json) }
        }
        for (id in listOf("1.5", "4294967297", "\"1\"")) {
            assertThrows(IllegalArgumentException::class.java) {
                AprilTagMapCodec.decodeAresField("""{"schemaVersion":2,"apriltags":[{"id":$id}]}""")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AprilTagMapCodec.decodeAresField("""{"schemaVersion":2,"apriltags":[{"id":1,"family":true}]}""")
        }
    }

    @Test
    fun `interchange preserves dimensions and normalized rotation on the FTC SDK Gson runtime`() {
        val emptyMap = AprilTagMapCodec.decodeLimelightFmap("""{"fieldlength":4,"fieldwidth":2,"fiducials":[]}""")
        assertEquals(4.0, requireNotNull(emptyMap.fieldLengthMeters), 0.0)
        assertEquals(2.0, requireNotNull(emptyMap.fieldWidthMeters), 0.0)
        val tag = AprilTagMapCodec.decodeWpilib("""{"field":{"length":4,"width":2},"tags":[{"ID":1,"pose":{"translation":{"x":1,"y":2,"z":0},"rotation":{"quaternion":{"W":1e300,"X":0,"Y":0,"Z":1e300}}}}]}""").tags.single()
        assertEquals(1.0, tag.x, 0.0)
        assertEquals(2.0, tag.y, 0.0)
        assertEquals(90.0, tag.yaw, 1e-12)
    }

    @Test
    fun `limelight map is an exact projection of canonical field AprilTags`() {
        val assets = File(findProjectRoot(), "TeamCode/src/main/assets/paths")
        val field = RobotFieldDocument.decode(File(assets, "field.json").readText())
        val fmapText = File(assets, "apriltags.fmap").readText()
        val limelightTags = RobotFieldManager.parseFmapContent(fmapText)

        assertEquals(setOf(20, 24), field.apriltags.map { it.id }.toSet())
        assertEquals(field.apriltags.map { it.id }.toSet(), limelightTags.map { it.id }.toSet())
        field.apriltags.forEach { canonical ->
            val derived = limelightTags.single { it.id == canonical.id }
            assertEquals(canonical.x, derived.x, 1e-9)
            assertEquals(canonical.y, derived.y, 1e-9)
            assertEquals(canonical.z, derived.z, 1e-9)
            assertEquals(canonical.yaw, derived.yaw, 1e-6)
        }
        val fmapFiducials = JsonParser().parse(fmapText)
            .asJsonObject["fiducials"]
            .asJsonArray
        assertEquals(2, fmapFiducials.size())
        fmapFiducials.forEach { fiducial ->
            val objectValue = fiducial.asJsonObject
            val canonical = field.apriltags.single { it.id == objectValue["id"].asInt }
            assertEquals(requireNotNull(canonical.sizeMeters) * 1000.0, objectValue["size"].asDouble, 1e-9)
            val transform = objectValue["transform"].asJsonArray
            assertEquals(16, transform.size())
            // Independent Rz(yaw) * Ry(pitch) * Rx(roll) expansion, rather than repeating the
            // saved matrix constants. Canonical angles are degrees; fmap translations are meters.
            val roll = Math.toRadians(canonical.roll)
            val pitch = Math.toRadians(canonical.pitch)
            val yaw = Math.toRadians(canonical.yaw)
            val cr = cos(roll); val sr = sin(roll)
            val cp = cos(pitch); val sp = sin(pitch)
            val cy = cos(yaw); val sy = sin(yaw)
            val expected = doubleArrayOf(
                cy * cp, cy * sp * sr - sy * cr, cy * sp * cr + sy * sr,
                sy * cp, sy * sp * sr + cy * cr, sy * sp * cr - cy * sr,
                -sp, cp * sr, cp * cr,
            )
            val rotationIndices = intArrayOf(0, 1, 2, 4, 5, 6, 8, 9, 10)
            rotationIndices.forEachIndexed { expectedIndex, transformIndex ->
                // The checked vendor-format matrix has about seven decimal digits of precision.
                assertEquals(expected[expectedIndex], transform[transformIndex].asDouble, 2e-7)
            }
            assertEquals(canonical.x, transform[3].asDouble, 1e-9)
            assertEquals(canonical.y, transform[7].asDouble, 1e-9)
            assertEquals(canonical.z, transform[11].asDouble, 1e-9)
            for (index in 12..14) assertEquals(0.0, transform[index].asDouble, 0.0)
            assertEquals(1.0, transform[15].asDouble, 0.0)
        }
        val byId = field.apriltags.associateBy { it.id }
        assertEquals(-1.48266658, requireNotNull(byId[20]).x, 1e-9)
        assertEquals(-1.4133195, requireNotNull(byId[20]).y, 1e-9)
        assertEquals(144.0499447, requireNotNull(byId[20]).yaw, 1e-7)
        assertEquals(-1.48266658, requireNotNull(byId[24]).x, 1e-9)
        assertEquals(1.4133195, requireNotNull(byId[24]).y, 1e-9)
        assertEquals(35.950059, requireNotNull(byId[24]).yaw, 1e-7)
        assertTrue(field.apriltags.all { it.z == 0.7493 && it.locked })
        assertFalse(
            "Loose AprilTag JSON creates a third layout that Auto and TeleOp do not consume",
            File(assets, "apriltags.json").exists(),
        )
        listOf("obstacles.json", "game_pieces.json", "field_waypoints.json").forEach { obsolete ->
            assertFalse("$obsolete duplicates canonical field.json", File(assets, obsolete).exists())
        }
        assertFalse("Image metadata belongs in canonical field.json",
            File(assets.parentFile, "field_image_config.json").exists())
    }

    private fun findProjectRoot(): File = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        File::getParentFile,
    ).firstOrNull { candidate ->
        File(candidate, "TeamCode/src/main/assets/paths/field.json").isFile
    } ?: error("Could not locate the ARES-FTC project root")
}
