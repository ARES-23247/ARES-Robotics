package com.areslib.state

import com.areslib.math.geometry.Rotation3d
import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class AprilTagMapFrameTest {
    @Test fun `all centered wall transforms preserve independent full rotation matrices`() {
        val random = Random(7201)
        for ((wall, angle) in listOf(DriverStationSide.WEST to 0.0, DriverStationSide.SOUTH to PI/2,
            DriverStationSide.EAST to PI, DriverStationSide.NORTH to -PI/2)) repeat(50) {
            val roll = random.nextDouble(-PI, PI)
            val pitch = when (it) { 0 -> PI/2; 1 -> -PI/2; else -> random.nextDouble(-PI/2, PI/2) }
            val yaw = random.nextDouble(-PI, PI)
            val q = Rotation3d(roll, pitch, yaw).q
            val raw = """{"field":{"length":10,"width":4},"tags":[{"ID":1,"pose":{"translation":{"x":1,"y":3,"z":0.2},"rotation":{"quaternion":{"W":${q.w},"X":${q.x},"Y":${q.y},"Z":${q.z}}}}}]}"""
            val decoded = AprilTagMapCodec.decodeForField(raw, RobotFieldConfig(blueDriverStation = wall))
            val tag = decoded.tags.single()
            assertEquals(-4*cos(angle)-sin(angle), tag.x, 1e-12)
            assertEquals(-4*sin(angle)+cos(angle), tag.y, 1e-12)
            assertEquals(0.2, tag.z)
            val actual = matrix(Math.toRadians(tag.roll), Math.toRadians(tag.pitch), Math.toRadians(tag.yaw))
            val expected = matrix(roll, pitch, yaw)
            for (col in 0..2) {
                assertEquals(cos(angle)*expected[col]-sin(angle)*expected[3+col], actual[col], 1e-10)
                assertEquals(sin(angle)*expected[col]+cos(angle)*expected[3+col], actual[3+col], 1e-10)
                assertEquals(expected[6+col], actual[6+col], 1e-10)
            }
        }
    }

    @Test fun `same-frame import preserves tiny coordinates beside huge extents`() {
        for (type in FieldType.entries) {
            val field = RobotFieldConfig(fieldType = type, widthMeters = 1e200, heightMeters = 1e200,
                apriltags = listOf(RobotFieldAprilTag(id = 1, x = 1e-100, y = -1e-100)))
            val result = AprilTagMapCodec.decodeForField(RobotFieldDocument.encode(field), field)
            assertEquals(field.apriltags, result.tags)
        }
    }

    @Test fun `ARES centered wall conversion rotates dimensions and honors source metadata`() {
        val source = RobotFieldConfig(fieldType = FieldType.XRP, widthMeters = 4.0, heightMeters = 10.0,
            blueDriverStation = DriverStationSide.NORTH,
            apriltags = listOf(RobotFieldAprilTag(id = 3, name = "calibrated", family = "36h11", sizeMeters = 0.1,
                x = -2.0, y = 5.0, yaw = -90.0, locked = true)))
        val result = AprilTagMapCodec.decodeForField(RobotFieldDocument.encode(source),
            RobotFieldConfig(fieldType = FieldType.FTC, blueDriverStation = DriverStationSide.WEST))
        val tag = result.tags.single()
        assertEquals(-5.0, tag.x)
        assertEquals(-2.0, tag.y)
        assertEquals(0.0, tag.yaw)
        assertEquals(10.0, result.fieldLengthMeters)
        assertEquals(4.0, result.fieldWidthMeters)
        assertEquals(source.apriltags.single().copy(x = -5.0, y = -2.0, yaw = 0.0), tag)
    }

    @Test fun `fmap source dimensions override each axis independently`() {
        val target = RobotFieldConfig(fieldType = FieldType.FRC, widthMeters = 10.0, heightMeters = 4.0)
        val partial = """{"fieldwidth":8,"fiducials":[{"id":1,"transform":[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]}]}"""
        for (result in listOf(AprilTagMapCodec.decodeForField(partial, target),
            AprilTagMapCodec.decodeLimelightFmapForField(partial, target))) {
            assertEquals(5.0, result.tags.single().x)
            assertEquals(4.0, result.tags.single().y)
            assertNull(result.fieldLengthMeters)
            assertEquals(8.0, result.fieldWidthMeters)
        }
    }

    @Test fun `auto detection rejects ambiguous unknown and malformed known formats`() {
        val target = RobotFieldConfig()
        for (json in listOf("{}", "{\"tags\":[],\"fiducials\":[]}",
            "{\"schemaVersion\":2,\"tags\":[]}", "{\"field\":{\"length\":1,\"width\":1},\"tags\":null}")) {
            assertFailsWith<IllegalArgumentException>(json) { AprilTagMapCodec.decodeForField(json, target) }
        }
        assertFailsWith<IllegalArgumentException> {
            AprilTagMapCodec.decodeForField("""{"schemaVersion":2,"fieldType":"ftc","blueDriverStation":"invalid"}""", target)
        }
    }

    @Test fun `unrepresentable frame translation rejects instead of returning infinity`() {
        val source = RobotFieldConfig(widthMeters = Double.MAX_VALUE, heightMeters = 4.0,
            blueDriverStation = DriverStationSide.WEST,
            apriltags = listOf(RobotFieldAprilTag(id = 1, x = Double.MAX_VALUE)))
        assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.encodeWpilibForField(source) }
    }

    @Test fun `large finite yaw retains quarter turn before conversion to a quaternion`() {
        val source = RobotFieldConfig(blueDriverStation = DriverStationSide.NORTH,
            apriltags = listOf(RobotFieldAprilTag(id = 1, yaw = Double.MAX_VALUE)))
        val result = AprilTagMapCodec.decodeWpilib(AprilTagMapCodec.encodeWpilibForField(source)).tags.single()
        val expected = (Double.MAX_VALUE % 360 + 90) % 360
        assertEquals(cos(Math.toRadians(expected)), cos(Math.toRadians(result.yaw)), 1e-12)
        assertEquals(sin(Math.toRadians(expected)), sin(Math.toRadians(result.yaw)), 1e-12)
    }

    private fun matrix(r: Double, p: Double, y: Double): DoubleArray = doubleArrayOf(
        cos(y)*cos(p), cos(y)*sin(p)*sin(r)-sin(y)*cos(r), cos(y)*sin(p)*cos(r)+sin(y)*sin(r),
        sin(y)*cos(p), sin(y)*sin(p)*sin(r)+cos(y)*cos(r), sin(y)*sin(p)*cos(r)-cos(y)*sin(r),
        -sin(p), cos(p)*sin(r), cos(p)*cos(r))
}
