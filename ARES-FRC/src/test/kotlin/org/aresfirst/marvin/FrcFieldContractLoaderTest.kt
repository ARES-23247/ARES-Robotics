package org.aresfirst.marvin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class FrcFieldContractLoaderTest {
    private fun field(tags: String = """{"id":7,"x":1.0,"y":2.0,"z":1.4}"""): String =
        """{"schemaVersion":2,"id":"boundary","name":"Boundary","fieldType":"frc","widthMeters":16.54175,"heightMeters":8.21055,"apriltags":[$tags]}"""

    @ParameterizedTest
    @ValueSource(strings = ["malformed", "negative-width", "duplicate-tag", "invalid-tag", "nonfinite", "future-schema"])
    fun `invalid documents clear prior success and later valid loads clear diagnostics`(case: String) {
        val valid = field()
        assertNotNull(loadFrcFieldContract(valid.toByteArray()))
        val invalid = when (case) {
            "malformed" -> "{"
            "negative-width" -> valid.replace("16.54175", "-1.0")
            "duplicate-tag" -> field("""{"id":7},{"id":7}""")
            "invalid-tag" -> field("""{"id":0}""")
            "nonfinite" -> valid.replace("1.4", "1e999")
            "future-schema" -> valid.replace("\"schemaVersion\":2", "\"schemaVersion\":999")
            else -> error(case)
        }
        assertNull(loadFrcFieldContract(invalid.toByteArray()), case)
        assertNotNull(FrcFieldContractLoader.error, case)
        assertNotNull(loadFrcFieldContract(valid.toByteArray()), case)
        assertNull(FrcFieldContractLoader.error, case)
    }

    @Test
    fun `layout preserves full tag orientation and legitimate tags outside playable rectangle`() {
        val contract = loadFrcFieldContract(field(
            """{"id":7,"x":-0.04,"y":2.0,"z":1.4,"roll":20.0,"pitch":-30.0,"yaw":120.0}"""
        ).toByteArray())!!
        val pose = contract.aprilTagLayout.getTagPose(7).orElseThrow()
        assertEquals(-0.04, pose.x, 1e-12)
        assertEquals(1.4, pose.z, 1e-12)
        assertEquals(Math.toRadians(20.0), pose.rotation.x, 1e-12)
        assertEquals(Math.toRadians(-30.0), pose.rotation.y, 1e-12)
        assertEquals(Math.toRadians(120.0), pose.rotation.z, 1e-12)
        assertEquals(16.54175, contract.aprilTagLayout.fieldLength, 1e-12)
        assertEquals(8.21055, contract.aprilTagLayout.fieldWidth, 1e-12)
    }

    @Test
    fun `canonical FRC field supplies the same WPILib layout used by vision`() {
        val contract = loadFrcFieldContract(
            """{
              "schemaVersion": 2,
              "id": "frc-test",
              "name": "FRC test",
              "fieldType": "frc",
              "widthMeters": 16.541,
              "heightMeters": 8.211,
              "apriltags": [
                {"id": 7, "name": "Blue speaker", "x": 1.0, "y": 2.0, "z": 1.4, "yaw": 90.0}
              ]
            }""".toByteArray(),
        )

        assertNotNull(contract)
        val pose = contract!!.aprilTagLayout.getTagPose(7).orElseThrow()
        assertEquals(1.0, pose.x, 1e-9)
        assertEquals(2.0, pose.y, 1e-9)
        assertEquals(Math.PI / 2.0, pose.rotation.z, 1e-9)
        assertNull(FrcFieldContractLoader.error)
    }

    @Test
    fun `missing tags and wrong league fail closed`() {
        assertNull(
            loadFrcFieldContract(
                """{"schemaVersion":2,"id":"empty","name":"Empty","fieldType":"frc","widthMeters":16.541,"heightMeters":8.211,"apriltags":[]}""".toByteArray(),
            )
        )
        assertEquals("FRC field must declare its AprilTag layout", FrcFieldContractLoader.error)

        assertNull(
            loadFrcFieldContract(
                """{"schemaVersion":2,"id":"ftc","name":"FTC","fieldType":"ftc","widthMeters":3.6576,"heightMeters":3.6576,"apriltags":[{"id":1,"name":"Tag","family":"36h11","sizeMeters":0.16}]}""".toByteArray(),
            )
        )
        assertEquals("Canonical season field must declare FRC geometry", FrcFieldContractLoader.error)
    }
}
