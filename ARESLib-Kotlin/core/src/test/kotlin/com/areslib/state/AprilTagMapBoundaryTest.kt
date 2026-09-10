package com.areslib.state

import com.areslib.math.geometry.Rotation3d
import com.google.gson.JsonParser
import java.util.LinkedList
import java.util.Random
import org.junit.jupiter.api.Test
import kotlin.math.*
import kotlin.test.*

class AprilTagMapBoundaryTest {
    @Test fun `vendor fmap dimensions survive import and field export`() {
        val json = fmap(identity()).replace("{\"fiducials\"", "{\"fieldlength\":16,\"fieldwidth\":8,\"fiducials\"")
        val imported = AprilTagMapCodec.decodeLimelightFmap(json)
        assertEquals(16.0, imported.fieldLengthMeters)
        assertEquals(8.0, imported.fieldWidthMeters)
        assertFalse("field dimensions" in imported.omittedMetadata)
        val field = RobotFieldConfig(fieldType = FieldType.FRC, widthMeters = 16.0, heightMeters = 8.0, apriltags = listOf(validTag()))
        val exported = AprilTagMapCodec.decodeLimelightFmap(AprilTagMapCodec.encodeLimelightFmap(field))
        assertEquals(16.0, exported.fieldLengthMeters)
        assertEquals(8.0, exported.fieldWidthMeters)
    }

    @Test fun `WPILib quaternion scale does not change imported orientation`() {
        for (scale in doubleArrayOf(Double.MAX_VALUE, 1.0, 1e-200, Double.MIN_VALUE)) {
            val tag = AprilTagMapCodec.decodeWpilib(wpilib("$scale", "0", "0", "$scale")).tags.single()
            assertEquals(90.0, tag.yaw, 1e-12)
            assertEquals(0.0, tag.roll, 1e-12); assertEquals(0.0, tag.pitch, 1e-12)
        }
    }

    @Test fun `WPILib singular Euler conversion retains the exported rotation`() {
        for (pitch in listOf(-90.0, 90.0)) {
            val tag = validTag().copy(roll = 40.0, pitch = pitch, yaw = -73.0)
            val imported = AprilTagMapCodec.decodeWpilib(AprilTagMapCodec.encodeWpilib(RobotFieldConfig(apriltags = listOf(tag)))).tags.single()
            assertRotation(tag, imported)
        }
    }

    @Test fun `fmap rejects scaled sheared reflected and nonhomogeneous matrices`() {
        val bad = listOf(
            identity().also { it[0] = 2.0 },
            identity().also { it[1] = 0.25 },
            identity().also { it[0] = -1.0 },
            identity().also { it[12] = 1.0 },
            identity().also { it[15] = 2.0 },
            DoubleArray(16),
        )
        for (m in bad) assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeLimelightFmap(fmap(m)) }
    }

    @Test fun `unrecognized roots and incomplete WPILib poses never become valid empty or identity layouts`() {
        for (json in listOf("{}", "{\"unrelated\":[]}", "null")) {
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeLimelightFmap(json) }
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeAresField(json) }
        }
        assertFailsWith<IllegalArgumentException> {
            AprilTagMapCodec.decodeWpilib("""{"field":{"length":16,"width":8},"tags":[{"ID":1,"pose":{}}]}""")
        }
    }

    @Test fun `explicit invalid field dimensions cannot fall back to defaults during interchange`() {
        for (bad in doubleArrayOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val field = RobotFieldConfig(fieldType = FieldType.FRC, widthMeters = bad)
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.encodeWpilib(field) }
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.encodeLimelightFmap(field) }
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeLimelightFmapForField(fmap(identity()), field) }
        }
    }

    @Test fun `explicit invalid tag sizes are rejected rather than silently discarded`() {
        for (size in listOf("0", "-1", "4.9e-324")) {
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeLimelightFmap(fmap(identity(), size)) }
        }
    }

    @Test fun `missing metadata is carried into import warnings`() {
        val json = """{"fiducials":[{"id":1,"transform":[${identity().joinToString(",")}]}]}"""
        val result = AprilTagMapCodec.decodeLimelightFmap(json)
        assertNull(result.tags.single().sizeMeters)
        assertEquals("", result.tags.single().family)
        assertTrue("tag family" in result.omittedMetadata)
        assertTrue("tag size" in result.omittedMetadata)
    }

    @Test fun `valid rigid matrix and existing metadata remain a control`() {
        val result = AprilTagMapCodec.decodeLimelightFmap(fmap(identity()))
        assertEquals(1, result.tags.single().id)
        assertEquals(0.1651, result.tags.single().sizeMeters!!, 1e-15)
        assertEquals(setOf("field dimensions", "tag names"), result.omittedMetadata)
    }

    @Test fun `empty recognized layouts remain valid and dimensions use explicit defaults`() {
        assertTrue(AprilTagMapCodec.decodeLimelightFmap("""{"fiducials":[]}""").tags.isEmpty())
        assertTrue(AprilTagMapCodec.decodeWpilib("""{"field":{"length":16,"width":8},"tags":[]}""").tags.isEmpty())
        for (type in FieldType.entries) {
            val field = RobotFieldConfig(fieldType = type)
            val imported = AprilTagMapCodec.decodeAresField(RobotFieldDocument.encode(field))
            assertTrue(imported.tags.isEmpty())
            assertEquals(field.resolvedWidthMeters, imported.fieldLengthMeters)
            assertEquals(field.resolvedHeightMeters, imported.fieldWidthMeters)
            val wpilib = AprilTagMapCodec.decodeWpilib(AprilTagMapCodec.encodeWpilib(field))
            assertEquals(imported.fieldLengthMeters, wpilib.fieldLengthMeters)
        }
        assertTrue(AprilTagMapCodec.decodeAresField("""{"schemaVersion":2}""").tags.isEmpty())
    }

    @Test fun `integer identifiers are exact and cannot wrap or truncate`() {
        for (id in listOf("0", "-1", "1.5", "2147483648", "4294967297", "2147483647.00000000001", "\"1\"", "true")) {
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeLimelightFmap(fmap(identity()).replace("\"id\":1", "\"id\":$id")) }
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeWpilib(wpilib("1", "0", "0", "0").replace("\"ID\":1", "\"ID\":$id")) }
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeAresField("""{"schemaVersion":2,"apriltags":[{"id":$id}]}""") }
        }
        assertEquals(1, AprilTagMapCodec.decodeWpilib(wpilib("1", "0", "0", "0").replace("\"ID\":1", "\"ID\":1e0")).tags.single().id)
    }

    @Test fun `required nested shapes and strict numeric types fail closed`() {
        for (json in listOf("", "[]", "true", "{", "{fiducials:[]}", "{\"fiducials\":[]} trailing", "{\"fiducials\":null}", "{\"fiducials\":[null]}")) {
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeLimelightFmap(json) }
        }
        for (bad in listOf("null", "[]", "{}", "\"1\"", "true", "1e9999", "NaN")) {
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeWpilib(wpilib(bad, "0", "0", "0")) }
        }
        assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeWpilib(wpilib("0", "-0.0", "0", "0")) }
        for (key in listOf("pose", "translation", "rotation", "quaternion")) {
            val text = wpilib("1", "0", "0", "0").replace("\"$key\":", "\"unrecognized\":")
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeWpilib(text) }
        }
        assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeWpilib(wpilib("1", "0", "0", "0").replace("\"z\":0", "\"z\":\"0\"")) }
        assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeLimelightFmap(fmap(identity()).replace("\"36h11\"", "null")) }
        assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeLimelightFmap(fmap(DoubleArray(15))) }
        for (bad in listOf("null", "1e9999", "\"1\"")) {
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeLimelightFmap(fmap(identity()).replaceFirst("1.0", bad)) }
        }
    }

    @Test fun `field extents and shifts reject invalid or unrepresentable results`() {
        for (bad in listOf("0", "-1", "null", "\"16\"", "1e9999")) {
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeWpilib(wpilib("1", "0", "0", "0").replace("\"length\":16", "\"length\":$bad")) }
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeWpilib(wpilib("1", "0", "0", "0").replace("\"width\":8", "\"width\":$bad")) }
            for (key in listOf("fieldlength", "fieldwidth")) {
                assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeLimelightFmap("""{"$key":$bad,"fiducials":[]}""") }
            }
        }
        for (bad in doubleArrayOf(-1.0, Double.POSITIVE_INFINITY, Double.NaN)) {
            val field = RobotFieldConfig(heightMeters = bad)
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.encodeWpilib(field) }
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.encodeLimelightFmap(field) }
        }
        for (key in listOf("widthMeters", "heightMeters")) {
            for (bad in listOf("-1", "null", "\"16\"", "1e9999")) {
                assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeAresField("""{"schemaVersion":2,"$key":$bad}""") }
            }
        }
        val huge = RobotFieldConfig(fieldType = FieldType.FRC, widthMeters = Double.MAX_VALUE, heightMeters = Double.MAX_VALUE)
        for (axis in listOf(3, 7)) {
            val m = identity().also { it[axis] = Double.MAX_VALUE }
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeLimelightFmapForField(fmap(m), huge) }
            val tag = if (axis == 3) validTag().copy(x = -Double.MAX_VALUE) else validTag().copy(y = -Double.MAX_VALUE)
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.encodeLimelightFmap(huge.copy(apriltags = listOf(tag))) }
        }
        val half = AprilTagMapCodec.decodeLimelightFmap("""{"fieldlength":16,"fiducials":[]}""")
        assertEquals(16.0, half.fieldLengthMeters); assertNull(half.fieldWidthMeters)
        assertTrue("field dimensions" in half.omittedMetadata)
    }

    @Test fun `tag validation and unit conversion reject malformed export inputs`() {
        val source = validTag()
        for (bad in listOf(source.copy(id = 0), source.copy(x = Double.NaN), source.copy(y = Double.NaN),
            source.copy(z = Double.NaN), source.copy(roll = Double.NaN), source.copy(pitch = Double.NaN),
            source.copy(yaw = Double.NaN), source.copy(sizeMeters = -1.0), source.copy(sizeMeters = Double.NaN))) {
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.encodeWpilib(RobotFieldConfig(apriltags = listOf(bad))) }
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.encodeLimelightFmap(listOf(bad)) }
        }
        for (bad in listOf(source.copy(family = " "), source.copy(sizeMeters = null), source.copy(sizeMeters = Double.MAX_VALUE))) {
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.encodeLimelightFmap(listOf(bad)) }
        }
        assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.encodeLimelightFmap(listOf(source, source)) }
        assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeAresField("""{"schemaVersion":2,"fieldType":"unknown"}""") }
        for (field in listOf("family", "name", "editorId")) {
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeAresField("""{"schemaVersion":2,"apriltags":[{"id":1,"$field":null}]}""") }
        }
        for (field in listOf("x", "y", "z", "roll", "pitch", "yaw", "sizeMeters")) {
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeAresField("""{"schemaVersion":2,"apriltags":[{"id":1,"$field":"NaN"}]}""") }
        }
    }

    @Test fun `independent matrices preserve seeded full 3D rotations in both formats`() {
        val random = Random(7001)
        repeat(500) { i ->
            val roll = random.nextDouble() * 360 - 180
            val pitch = when (i % 5) { 0 -> 90.0; 1 -> -90.0; else -> random.nextDouble() * 180 - 90 }
            val yaw = random.nextDouble() * 360 - 180
            val source = validTag().copy(x = random.nextDouble(), y = -random.nextDouble(), z = random.nextDouble(), roll = roll, pitch = pitch, yaw = yaw)
            val encoded = AprilTagMapCodec.encodeLimelightFmap(listOf(source))
            val actual = JsonParser.parseString(encoded).asJsonObject.getAsJsonArray("fiducials")[0].asJsonObject.getAsJsonArray("transform")
            val matrix = independentMatrix(source)
            for (k in 0..15) assertEquals(matrix[k], actual[k].asDouble, 2e-14)
            val fromMatrix = AprilTagMapCodec.decodeLimelightFmap(encoded).tags.single()
            val fromQuaternion = AprilTagMapCodec.decodeWpilib(AprilTagMapCodec.encodeWpilib(RobotFieldConfig(apriltags = listOf(source)))).tags.single()
            for (result in listOf(fromMatrix, fromQuaternion)) {
                val roundTrip = independentMatrix(result)
                for (k in 0..15) assertEquals(matrix[k], roundTrip[k], 2e-12)
            }
            val rounded = matrix.map { round(it * 1e6) / 1e6 }.toDoubleArray()
            assertEquals(1, AprilTagMapCodec.decodeLimelightFmap(fmap(rounded)).tags.size)
        }
    }

    @Test fun `sorted exports retain caller order and imports own their lists`() {
        val source = LinkedList(listOf(validTag().copy(id = 3), validTag().copy(id = 1), validTag().copy(id = 2)))
        val encoded = AprilTagMapCodec.encodeLimelightFmap(source)
        assertEquals(listOf(3, 1, 2), source.map { it.id })
        val a = AprilTagMapCodec.decodeLimelightFmap(encoded)
        val b = AprilTagMapCodec.decodeLimelightFmap(encoded)
        assertEquals(listOf(1, 2, 3), a.tags.map { it.id }); assertNotSame(a.tags, b.tags)
        source.clear(); assertEquals(3, a.tags.size)
        assertEquals(encoded, AprilTagMapCodec.encodeLimelightFmap(a.tags))
        val field = RobotFieldConfig(apriltags = b.tags.reversed())
        assertEquals(listOf(1, 2, 3), AprilTagMapCodec.decodeWpilib(AprilTagMapCodec.encodeWpilib(field)).tags.map { it.id })
    }

    @Test fun `all league origin shifts preserve positions and rotation`() {
        val tag = validTag().copy(x = 1.2, y = 0.4, z = 0.8, roll = 12.0, pitch = -18.0, yaw = 64.0)
        for (type in FieldType.entries) {
            val field = RobotFieldConfig(fieldType = type, widthMeters = 16.0, heightMeters = 8.0, apriltags = listOf(tag))
            val json = AprilTagMapCodec.encodeLimelightFmap(field)
            val raw = AprilTagMapCodec.decodeLimelightFmap(json).tags.single()
            assertEquals(if (type == FieldType.FTC) tag.x else tag.x - 8.0, raw.x)
            assertEquals(if (type == FieldType.FTC) tag.y else tag.y - 4.0, raw.y)
            val back = AprilTagMapCodec.decodeLimelightFmapForField(json, field).tags.single()
            assertEquals(tag.x, back.x, 1e-14); assertEquals(tag.y, back.y, 1e-14); assertEquals(tag.z, back.z)
            assertRotation(tag, back)
        }
    }

    @Test fun `ARES metadata strings cannot be coerced from numbers or booleans`() {
        for (key in listOf("family", "name", "editorId")) for (value in listOf("true", "123")) {
            assertFailsWith<IllegalArgumentException> {
                AprilTagMapCodec.decodeAresField("""{"schemaVersion":2,"apriltags":[{"id":1,"$key":$value}]}""")
            }
        }
    }

    @Test fun `schema and null collection boundaries reject invalid documents`() {
        for (json in listOf(
            """{"schemaVersion":1}""", """{"schemaVersion":3}""",
            """{"schemaVersion":2,"apriltags":null}""", """{"schemaVersion":2,"apriltags":[null]}""",
        )) assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeAresField(json) }
        val optional = AprilTagMapCodec.decodeAresField("""{"schemaVersion":2,"apriltags":[{"id":1,"sizeMeters":null}]}""")
        assertNull(optional.tags.single().sizeMeters)
        for (axis in listOf(13, 14)) {
            val matrix = identity().also { it[axis] = 0.1 }
            assertFailsWith<IllegalArgumentException> { AprilTagMapCodec.decodeLimelightFmap(fmap(matrix)) }
        }
        for (q in listOf(wpilib("0", "1", "0", "0"), wpilib("0", "0", "1", "0"))) {
            val tag = AprilTagMapCodec.decodeWpilib(q).tags.single()
            assertEquals(-1.0, independentMatrix(tag)[10], 1e-14)
        }
    }

    private fun independentMatrix(t: RobotFieldAprilTag): DoubleArray {
        val r = Math.toRadians(t.roll); val p = Math.toRadians(t.pitch); val y = Math.toRadians(t.yaw)
        val rx = doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(r), -sin(r), 0.0, sin(r), cos(r))
        val ry = doubleArrayOf(cos(p), 0.0, sin(p), 0.0, 1.0, 0.0, -sin(p), 0.0, cos(p))
        val rz = doubleArrayOf(cos(y), -sin(y), 0.0, sin(y), cos(y), 0.0, 0.0, 0.0, 1.0)
        fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { k -> (0..2).sumOf { j -> a[k / 3 * 3 + j] * b[j * 3 + k % 3] } }
        val m = multiply(multiply(rz, ry), rx)
        return doubleArrayOf(m[0], m[1], m[2], t.x, m[3], m[4], m[5], t.y, m[6], m[7], m[8], t.z, 0.0, 0.0, 0.0, 1.0)
    }

    private fun identity() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
    private fun fmap(m: DoubleArray, size: String = "165.1") = """{"fiducials":[{"id":1,"family":"36h11","size":$size,"transform":[${m.joinToString(",")}]}]}"""
    private fun wpilib(w: String, x: String, y: String, z: String) = """{"field":{"length":16,"width":8},"tags":[{"ID":1,"pose":{"translation":{"x":0,"y":0,"z":0},"rotation":{"quaternion":{"W":$w,"X":$x,"Y":$y,"Z":$z}}}}]}"""
    private fun validTag() = RobotFieldAprilTag(id = 1, family = "36h11", sizeMeters = 0.1651)
    private fun assertRotation(a: RobotFieldAprilTag, b: RobotFieldAprilTag) {
        fun rotation(t: RobotFieldAprilTag) = Rotation3d(Math.toRadians(t.roll), Math.toRadians(t.pitch), Math.toRadians(t.yaw)).q
        val p = rotation(a); val q = rotation(b)
        val sign = if (p.w * q.w + p.x * q.x + p.y * q.y + p.z * q.z < 0) -1 else 1
        assertEquals(p.w, sign * q.w, 2e-12); assertEquals(p.x, sign * q.x, 2e-12)
        assertEquals(p.y, sign * q.y, 2e-12); assertEquals(p.z, sign * q.z, 2e-12)
    }
}
