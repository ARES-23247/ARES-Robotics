package org.aresfirst.marvin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FrcFieldContractLoaderTest {
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
