package org.firstinspires.ftc.teamcode

import com.areslib.state.AprilTagMapCodec
import com.areslib.state.RobotFieldDocument
import com.areslib.state.RobotFieldManager
import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class FtcFieldAssetContractTest {
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
        val expectedRotations = mapOf(
            20 to doubleArrayOf(
                -0.8095291411, 0.0, -0.5870798679,
                0.5870798679, -5.57234e-8, -0.8095290854,
                0.0, -1.0000000557, -5.57234e-8,
            ),
            24 to doubleArrayOf(
                0.8095290297, 0.0, -0.5870798679,
                0.5870798679, -5.57234e-8, 0.8095290854,
                0.0, -1.0000000557, -5.57234e-8,
            ),
        )
        fmapFiducials.forEach { fiducial ->
            val objectValue = fiducial.asJsonObject
            assertEquals(165.1, objectValue["size"].asDouble, 1e-9)
            val transform = objectValue["transform"].asJsonArray
            assertEquals(16, transform.size())
            val expected = requireNotNull(expectedRotations[objectValue["id"].asInt])
            val rotationIndices = intArrayOf(0, 1, 2, 4, 5, 6, 8, 9, 10)
            rotationIndices.forEachIndexed { expectedIndex, transformIndex ->
                assertEquals(expected[expectedIndex], transform[transformIndex].asDouble, 1e-9)
            }
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
    }

    private fun findProjectRoot(): File = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        File::getParentFile,
    ).firstOrNull { candidate ->
        File(candidate, "TeamCode/src/main/assets/paths/field.json").isFile
    } ?: error("Could not locate the ARES-FTC project root")
}
