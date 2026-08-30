package com.areslib.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AprilTagMapCodecTest {
    @Test
    fun `Limelight fmap preserves full pose family and size`() {
        val source = listOf(
            RobotFieldAprilTag(
                id = 7,
                name = "Practice tag",
                family = "36h11",
                sizeMeters = 0.1651,
                x = 1.25,
                y = -0.75,
                z = 0.42,
                roll = 10.0,
                pitch = -15.0,
                yaw = 125.0,
            )
        )

        val encoded = AprilTagMapCodec.encodeLimelightFmap(source)
        val decoded = AprilTagMapCodec.decodeLimelightFmap(encoded)
        val tag = decoded.tags.single()

        assertEquals(AprilTagMapFormat.LIMELIGHT_FMAP, decoded.format)
        assertEquals(7, tag.id)
        assertEquals("36h11", tag.family)
        assertEquals(0.1651, tag.sizeMeters!!, 1e-12)
        assertEquals(1.25, tag.x, 1e-12)
        assertEquals(-0.75, tag.y, 1e-12)
        assertEquals(0.42, tag.z, 1e-12)
        assertAngleEquals(10.0, tag.roll)
        assertAngleEquals(-15.0, tag.pitch)
        assertAngleEquals(125.0, tag.yaw)
        assertTrue("tag names" in decoded.omittedMetadata)
    }

    @Test
    fun `Limelight and WPILib field origins convert without changing physical tag pose`() {
        val frcField = RobotFieldConfig(
            fieldType = FieldType.FRC,
            widthMeters = 16.0,
            heightMeters = 8.0,
            apriltags = listOf(
                RobotFieldAprilTag(
                    id = 4,
                    family = "apriltag3_36h11_classic",
                    sizeMeters = 0.1651,
                    x = 15.0,
                    y = 1.0,
                    z = 1.3,
                )
            ),
        )

        val fmap = AprilTagMapCodec.encodeLimelightFmap(frcField)
        val rawLimelight = AprilTagMapCodec.decodeLimelightFmap(fmap).tags.single()
        assertEquals(7.0, rawLimelight.x, 1e-12)
        assertEquals(-3.0, rawLimelight.y, 1e-12)

        val canonical = AprilTagMapCodec.decodeLimelightFmapForField(fmap, frcField).tags.single()
        assertEquals(15.0, canonical.x, 1e-12)
        assertEquals(1.0, canonical.y, 1e-12)
    }

    @Test
    fun `WPILib JSON matches official field and quaternion shape`() {
        val source = """
            {
              "field": {"length": 17.548, "width": 8.052},
              "tags": [{
                "ID": 18,
                "pose": {
                  "translation": {"x": 3.6576, "y": 4.026, "z": 1.4859},
                  "rotation": {"quaternion": {"W": 0.7071067811865476, "X": 0.0, "Y": 0.0, "Z": 0.7071067811865475}}
                }
              }]
            }
        """.trimIndent()

        val decoded = AprilTagMapCodec.decodeWpilib(source)
        val tag = decoded.tags.single()
        assertEquals(17.548, decoded.fieldLengthMeters!!, 0.0)
        assertEquals(8.052, decoded.fieldWidthMeters!!, 0.0)
        assertEquals(18, tag.id)
        assertEquals(3.6576, tag.x, 0.0)
        assertAngleEquals(90.0, tag.yaw)
        assertTrue("tag size" in decoded.omittedMetadata)

        val reencoded = AprilTagMapCodec.encodeWpilib(
            RobotFieldConfig(
                fieldType = FieldType.FRC,
                widthMeters = decoded.fieldLengthMeters,
                heightMeters = decoded.fieldWidthMeters,
                apriltags = decoded.tags,
            )
        )
        val roundTrip = AprilTagMapCodec.decodeWpilib(reencoded).tags.single()
        assertAngleEquals(90.0, roundTrip.yaw)
    }

    @Test
    fun `retired field schemas are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RobotFieldDocument.decode(
                """{"schemaVersion":1,"fieldType":"ftc","apriltags":[{"id":1,"x":1.0,"y":2.0,"z":0.3,"yaw":45.0}]}"""
            )
        }
    }

    @Test
    fun `exports fail closed when required metadata or IDs are invalid`() {
        assertFailsWith<IllegalArgumentException> {
            AprilTagMapCodec.encodeLimelightFmap(listOf(RobotFieldAprilTag(id = 1)))
        }
        assertFailsWith<IllegalArgumentException> {
            AprilTagMapCodec.decodeWpilib(
                """{"field":{"length":1.0,"width":1.0},"tags":[{"ID":1,"pose":{}},{"ID":1,"pose":{}}]}"""
            )
        }
    }

    private fun assertAngleEquals(expected: Double, actual: Double) {
        val delta = ((actual - expected + 540.0) % 360.0) - 180.0
        assertEquals(0.0, delta, 1e-9)
    }
}
