// ARES OWNERSHIP: GENERATED STARTER
package org.firstinspires.ftc.teamcode.opmodes

import com.areslib.state.aprilTagPoseMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StarterFtcFieldContractLoaderTest {
    @Test
    fun `crossed obstacle is rejected even in a simulation first project`() {
        val invalid = """{"schemaVersion":2,"fieldType":"ftc","obstacles":[{"id":"crossed","shape":"polygon","points":[{"x":0,"y":0},{"x":2,"y":2},{"x":0,"y":2},{"x":2,"y":0}]}]}"""
        assertNull(loadStarterFtcFieldContract(invalid.toByteArray()))
        assertEquals("Field contains an invalid obstacle", StarterFtcFieldContractLoader.error)
        assertNotNull(loadStarterFtcFieldContract("""{"schemaVersion":2,"fieldType":"ftc"}""".toByteArray()))
        assertNull(StarterFtcFieldContractLoader.error)
    }

    @Test
    fun `empty canonical FTC field is a valid simulation-first contract`() {
        val field = loadStarterFtcFieldContract(
            """{
              "schemaVersion": 2,
              "id": "starter-field",
              "name": "Starter field",
              "fieldType": "ftc",
              "widthMeters": 3.6576,
              "heightMeters": 3.6576,
              "apriltags": []
            }""".toByteArray(),
        )

        assertNotNull(field)
        assertEquals("starter-field", field!!.id)
        assertEquals(0, field.aprilTagPoseMap().size)
        assertNull(StarterFtcFieldContractLoader.error)
    }

    @Test
    fun `wrong league or incomplete FTC tag metadata fails closed`() {
        assertNull(
            loadStarterFtcFieldContract(
                """{"schemaVersion":2,"id":"bad","name":"Bad","fieldType":"frc","widthMeters":16.0,"heightMeters":8.0}""".toByteArray(),
            )
        )
        assertEquals("Canonical season field must declare FTC geometry", StarterFtcFieldContractLoader.error)

        assertNull(
            loadStarterFtcFieldContract(
                """{"schemaVersion":2,"id":"bad-tag","name":"Bad tag","fieldType":"ftc","widthMeters":3.6576,"heightMeters":3.6576,"apriltags":[{"id":1,"x":0.0,"y":0.0,"z":0.0}]}""".toByteArray(),
            )
        )
        assertEquals(
            "FTC AprilTag 1 needs a family and physical size for VisionPortal generation",
            StarterFtcFieldContractLoader.error,
        )
    }
}
